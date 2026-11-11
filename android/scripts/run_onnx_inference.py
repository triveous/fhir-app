#!/usr/bin/env python3
"""
Contract check and regression harness for the ONNX screening pipeline.

It is the ONNX counterpart of run_ai_inference.py (which covers the PyTorch
.pt pipeline): a line-for-line Python mirror of what the app does on device,
driven by the real .onnx model over the real images in docs/test-images/.

Kotlin sources mirrored (keep in sync if these change):

  quest/src/main/java/org/smartregister/fhircore/quest/ml/
      OnnxModelContract.kt           -> constants below
      ModelInputSpec.kt              -> input_spec(), pack()
      OnnxModelRunner.kt             -> run_models()
      ScreeningOutputInterpreter.kt  -> interpret()
      OnnxScreeningPipeline.kt       -> read_resized()
  quest/src/main/java/org/smartregister/fhircore/quest/util/OpenCVUtils.kt
      readBgr() + resizeBicubic()    -> read_resized()

Pipeline (as agreed with the ML team): cv2.imread (BGR, uint8) -> cv2.resize
(INTER_CUBIC) to the model's input size -> HWC->CHW if the model is NCHW -> run.
No normalisation of any kind; Sigmoid runs inside the graph.

Usage:
    python3 scripts/run_onnx_inference.py --inspect           # the §8 contract check, run first
    python3 scripts/run_onnx_inference.py --compare-legacy    # port fidelity vs the v38 .pt baseline
    python3 scripts/run_onnx_inference.py --write-baseline    # record the ONNX baseline
    python3 scripts/run_onnx_inference.py                     # run + compare to the ONNX baseline
    python3 scripts/run_onnx_inference.py --model a.onnx --model b.onnx   # 3-file layout, in order

Requires: onnxruntime, opencv-python, numpy. `onnx` is optional (--inspect uses
it to look for the Sigmoid node).

Exit codes:
    0 - pass (or baseline just written / inspect found no contract problems)
    1 - drift, a contract violation, or a legacy classification disagreement
    2 - no baseline on disk (run --write-baseline first)
"""

from __future__ import annotations

import argparse
import json
import math
import sys
import time
from pathlib import Path

import cv2
import numpy as np
import onnxruntime as ort

REPO_ROOT = Path(__file__).resolve().parent.parent
ASSETS_DIR = REPO_ROOT / "quest" / "src" / "main" / "assets"
IMAGES_DIR = REPO_ROOT / "docs" / "test-images"
BASELINE_PATH = REPO_ROOT / "scripts" / "ai_inference_baseline_onnx.json"
LEGACY_BASELINE_PATH = REPO_ROOT / "scripts" / "ai_inference_baseline.json"

# ── OnnxModelContract.kt ────────────────────────────────────────────────────
ASSET_NAMES = ("mvit2_fold1_6_latest.onnx", "mvit2_fold1_8.onnx", "mvit2_fold2_8_latest.onnx")
MODEL_VERSION = "onnx-v1"
DEFAULT_INPUT_SIZE = 256
COMPONENTS = (("v6", "model6"), ("v8", "model8"), ("v82", "model82"))
DECISION_THRESHOLD = np.float32(0.5)
LOW_CONFIDENCE_THRESHOLD = np.float32(65.0)
PROBABILITY_TOLERANCE = np.float32(1e-4)

# classes.java — index 0 = negative, index 1 = positive.
DISEASES = ("Non-Suspicious", "Suspicious")

# Same tolerance as run_ai_inference.py for re-runs against our own baseline.
CONFIDENCE_TOLERANCE = 0.01

# Largest per-component probability gap still accepted as "the same model" when
# comparing the ONNX port to the PyTorch v38 baseline. Classifications must
# match exactly regardless.
LEGACY_PROBABILITY_TOLERANCE = 0.02


class ContractViolation(Exception):
    pass


# ─────────────────────────────────────────────────────────────────────────────
# Input — mirrors ModelInputSpec.fromDeclaredShape / packUint8 / packFloat32
# ─────────────────────────────────────────────────────────────────────────────


def _dim(value) -> int:
    """ORT reports dynamic dims as strings/None; Kotlin sees them as negative."""
    return value if isinstance(value, int) and value > 0 else -1


def input_spec(session: ort.InferenceSession) -> dict:
    inputs = session.get_inputs()
    if len(inputs) != 1:
        raise ContractViolation(f"Expected a single image input, model declares {[i.name for i in inputs]}")
    node = inputs[0]
    dtypes = {"tensor(uint8)": np.uint8, "tensor(float)": np.float32}
    if node.type not in dtypes:
        raise ContractViolation(f"Model input '{node.name}' is {node.type}; only uint8 and float32 images are supported")
    shape = [_dim(d) for d in node.shape]
    if len(shape) != 4:
        raise ContractViolation(f"Model input '{node.name}' must be rank 4 (one BGR image), found {node.shape}")
    if not (shape[0] == 1 or shape[0] < 0):
        raise ContractViolation(f"Model input '{node.name}' must take a batch of 1, found {node.shape}")
    if shape[1] == 3:
        layout, height, width = "NCHW", shape[2], shape[3]
    elif shape[3] == 3:
        layout, height, width = "NHWC", shape[1], shape[2]
    elif shape[1] < 0 and shape[3] < 0:
        layout, height, width = "NCHW", shape[2], shape[3]
    else:
        raise ContractViolation(f"Model input '{node.name}' has no 3-channel axis: {node.shape}")
    return {
        "name": node.name,
        "layout": layout,
        "dtype": dtypes[node.type],
        "height": height if height > 0 else DEFAULT_INPUT_SIZE,
        "width": width if width > 0 else DEFAULT_INPUT_SIZE,
        "declared_shape": node.shape,
        "declared_type": node.type,
    }


def read_resized(image_path: Path, width: int, height: int) -> np.ndarray:
    """OpenCVUtils.readBgr + resizeBicubic: raw BGR uint8, HxWx3."""
    original = cv2.imread(str(image_path), cv2.IMREAD_COLOR)
    if original is None or original.size == 0:
        raise IOError(f"Failed to decode image file: {image_path}")
    return cv2.resize(original, (width, height), interpolation=cv2.INTER_CUBIC)


def pack(bgr_hwc: np.ndarray, spec: dict) -> np.ndarray:
    """No channel swap, no scaling: only the layout and the element type change."""
    image = bgr_hwc.transpose(2, 0, 1) if spec["layout"] == "NCHW" else bgr_hwc
    return np.ascontiguousarray(image[np.newaxis]).astype(spec["dtype"])


# ─────────────────────────────────────────────────────────────────────────────
# Run — mirrors OnnxModelRunner.run
# ─────────────────────────────────────────────────────────────────────────────


def load_sessions(model_paths: list[Path]) -> list[tuple[ort.InferenceSession, dict]]:
    options = ort.SessionOptions()
    options.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
    options.intra_op_num_threads = 1  # determinism over speed
    loaded = []
    for path in model_paths:
        session = ort.InferenceSession(str(path), options, providers=["CPUExecutionProvider"])
        loaded.append((session, input_spec(session)))
    sizes = {(spec["height"], spec["width"]) for _, spec in loaded}
    if len(sizes) != 1:
        raise ContractViolation(f"All models must take the same input size, found {sorted(sizes)}")
    return loaded


def run_models(sessions, bgr_hwc: np.ndarray) -> np.ndarray:
    """Every output of every model, flattened in model order then declared output order."""
    values = []
    for session, spec in sessions:
        outputs = session.run(None, {spec["name"]: pack(bgr_hwc, spec)})
        for output in outputs:
            values.extend(np.asarray(output, dtype=np.float32).reshape(-1).tolist())
    return np.asarray(values, dtype=np.float32)


# ─────────────────────────────────────────────────────────────────────────────
# Interpret — mirrors ScreeningOutputInterpreter (Float math, like Kotlin)
# ─────────────────────────────────────────────────────────────────────────────


def _binary_entropy(probability: np.float32) -> float:
    p = float(np.clip(np.float32(probability), np.float32(0.000001), np.float32(0.999999)))
    return -p * math.log(p) - (1.0 - p) * math.log(1.0 - p)


def _decimal_format_two(value: float) -> str:
    """Java DecimalFormat("#.##"): HALF_EVEN, trailing zeros dropped."""
    text = f"{value:.2f}"
    if "." in text:
        text = text.rstrip("0").rstrip(".")
    return text or "0"


def _kotlin_float_to_string(value: np.float32) -> str:
    """Kotlin Float.toString(): shortest round-tripping decimal (confidence is always 50-100)."""
    return str(np.float32(value))


def _prediction(model_name: str, probability: np.float32, inference_ms: int, combined_ms=None) -> dict:
    is_suspicious = bool(probability > DECISION_THRESHOLD)
    confidence = (
        probability * np.float32(100.0) if is_suspicious else (np.float32(1.0) - probability) * np.float32(100.0)
    )
    return {
        "model_name": model_name,
        "model_version": MODEL_VERSION,
        "prediction": "suspicious" if is_suspicious else "non_suspicious",
        "display_prediction": DISEASES[1 if is_suspicious else 0],
        "confidence": float(confidence),
        "probability": float(probability),
        "entropy": _binary_entropy(probability),
        "low_confidence": bool(confidence < LOW_CONFIDENCE_THRESHOLD),
        "inference_time_ms": inference_ms,
        "combined_inference_time_ms": combined_ms,
        "is_suspicious": is_suspicious,
    }


def interpret(values: np.ndarray, inference_ms: int) -> tuple[list, dict, dict]:
    if values.size == 0:
        raise ContractViolation("Model produced no output values")
    bad = [float(v) for v in values if not np.isfinite(v) or v < -PROBABILITY_TOLERANCE or v > 1 + PROBABILITY_TOLERANCE]
    if bad:
        raise ContractViolation(
            f"Model outputs {values.tolist()} are not probabilities in [0, 1] (offending: {bad}). "
            "Is Sigmoid missing from the exported graph?"
        )
    named = COMPONENTS if len(COMPONENTS) == values.size else None
    components, prefixes = [], []
    for index, value in enumerate(values):
        name, prefix = named[index] if named else (f"component_{index}", f"component{index}")
        components.append(_prediction(name, np.float32(np.clip(value, 0, 1)), inference_ms))
        prefixes.append(prefix)

    # Kotlin averages Float values in Double, then narrows back to Float.
    confidence = np.float32(float(np.mean([c["confidence"] for c in components])))
    all_suspicious = all(c["is_suspicious"] for c in components)
    ensemble = {
        "model_name": "ensemble",
        "model_version": MODEL_VERSION,
        "prediction": "suspicious" if all_suspicious else "non_suspicious",
        "display_prediction": DISEASES[1 if all_suspicious else 0],
        "confidence": float(confidence),
        "probability": float(np.float32(float(np.mean([c["probability"] for c in components])))),
        "entropy": float(np.mean([c["entropy"] for c in components])),
        "low_confidence": bool(confidence < LOW_CONFIDENCE_THRESHOLD),
        "inference_time_ms": inference_ms,
        "combined_inference_time_ms": inference_ms,
        "is_suspicious": all_suspicious,
    }
    result_map = {
        "camera_prediction": ensemble["display_prediction"],
        "camera_confidence": _decimal_format_two(float(confidence)),
    }
    for component, prefix in zip(components, prefixes):
        result_map[f"{prefix}_prediction"] = component["display_prediction"]
        result_map[f"{prefix}_confidence"] = _kotlin_float_to_string(component["confidence"])
    return components, ensemble, result_map


def run_image(sessions, image_path: Path) -> dict:
    spec = sessions[0][1]
    bgr = read_resized(image_path, spec["width"], spec["height"])
    started = time.monotonic()
    values = run_models(sessions, bgr)
    inference_ms = int((time.monotonic() - started) * 1000)
    components, ensemble, result_map = interpret(values, inference_ms)
    return {
        "image": image_path.name,
        "raw_outputs": [float(v) for v in values],
        "models": components,
        "ensemble": ensemble,
        "result_map": result_map,
    }


# ─────────────────────────────────────────────────────────────────────────────
# --inspect — the plan's §8 contract check, automated
# ─────────────────────────────────────────────────────────────────────────────

# Ops that only move or reshape values; walked through when looking for the
# operation that actually produces an output.
_PASS_THROUGH_OPS = {
    "Identity", "Reshape", "Flatten", "Squeeze", "Unsqueeze", "Concat", "Slice",
    "Gather", "Cast", "Transpose", "Split",
}


def _producing_ops(model_path: Path) -> dict | None:
    """Output name -> set of value-producing op types feeding it, or None without `onnx`."""
    try:
        import onnx  # noqa: PLC0415 - optional dependency
    except ImportError:
        return None
    graph = onnx.load(str(model_path), load_external_data=False).graph
    producer = {out: node for node in graph.node for out in node.output}
    found = {}
    for output in graph.output:
        ops, stack, seen = set(), [output.name], set()
        while stack:
            name = stack.pop()
            if name in seen or name not in producer:
                continue
            seen.add(name)
            node = producer[name]
            if node.op_type in _PASS_THROUGH_OPS:
                stack.extend(node.input)
            else:
                ops.add(node.op_type)
        found[output.name] = ops
    return found


def inspect(model_paths: list[Path], image_paths: list[Path]) -> int:
    problems = []
    sessions = load_sessions(model_paths)
    for path, (session, spec) in zip(model_paths, sessions):
        meta = session.get_modelmeta()
        print(f"── {path.relative_to(REPO_ROOT) if path.is_relative_to(REPO_ROOT) else path}")
        version = meta.version if meta.version != 2**63 - 1 else "-"  # ORT's "not set" sentinel
        print(f"   size {path.stat().st_size / 1e6:.1f} MB · producer {meta.producer_name or '-'} · "
              f"graph {meta.graph_name or '-'} · version {version}")
        if meta.custom_metadata_map:
            print(f"   metadata {meta.custom_metadata_map}")
        print(f"   input  {spec['name']}: {spec['declared_type']} {spec['declared_shape']}"
              f"  -> app sends {spec['layout']} {np.dtype(spec['dtype']).name} "
              f"{spec['height']}x{spec['width']}")
        if spec["dtype"] is not np.uint8:
            print("   note   input is float32; the app will send raw 0-255 values as floats")
        if (spec["height"], spec["width"]) != (DEFAULT_INPUT_SIZE, DEFAULT_INPUT_SIZE):
            print(f"   note   input size differs from {DEFAULT_INPUT_SIZE}x{DEFAULT_INPUT_SIZE}")
        producers = _producing_ops(path)
        for output in session.get_outputs():
            ops = "" if producers is None else f"  produced by {sorted(producers.get(output.name, set())) or '?'}"
            print(f"   output {output.name}: {output.type} {output.shape}{ops}")
            if producers is not None and "Sigmoid" not in producers.get(output.name, set()):
                problems.append(f"{path.name}: output '{output.name}' is not produced by Sigmoid")
            if output.type not in ("tensor(float)", "tensor(double)"):
                problems.append(f"{path.name}: output '{output.name}' is {output.type}, expected float")
        if producers is None:
            print("   (pip install onnx to check which op produces each output)")

    print("\n── outputs on docs/test-images")
    for image_path in image_paths:
        spec = sessions[0][1]
        values = run_models(sessions, read_resized(image_path, spec["width"], spec["height"]))
        in_range = bool(np.all(np.isfinite(values)) and values.min() >= -PROBABILITY_TOLERANCE
                        and values.max() <= 1 + PROBABILITY_TOLERANCE)
        print(f"   {image_path.name:<12} N={values.size}  {np.array2string(values, precision=6)}"
              f"  {'probabilities' if in_range else 'NOT IN [0,1]'}")
        if not in_range:
            problems.append(f"{image_path.name}: outputs {values.tolist()} are not probabilities")
        if values.size != len(COMPONENTS):
            problems.append(
                f"{image_path.name}: {values.size} outputs; the app names them component_<i> and the "
                f"v6/v8/v82 result keys + FHIR extensions stay empty (plan §5.6)"
            )

    print()
    if problems:
        print("CONTRACT PROBLEMS:")
        for problem in dict.fromkeys(problems):
            print(f"  ✗ {problem}")
        return 1
    print("Contract OK: single uint8/float32 image input, float outputs in [0, 1]"
          f"{', Sigmoid-terminated' if _producing_ops(model_paths[0]) is not None else ''}, "
          f"N={len(COMPONENTS)}.")
    return 0


# ─────────────────────────────────────────────────────────────────────────────
# Baselines
# ─────────────────────────────────────────────────────────────────────────────


def compare_to_baseline(current: list, baseline: list) -> list[str]:
    failures = []
    expected_by_image = {r["image"]: r for r in baseline}
    for run in current:
        expected = expected_by_image.get(run["image"])
        if expected is None:
            failures.append(f"{run['image']}: no baseline entry (run --write-baseline)")
            continue
        if len(run["models"]) != len(expected["models"]):
            failures.append(f"{run['image']}: output count {len(expected['models'])} -> {len(run['models'])}")
            continue
        for actual, wanted in zip(run["models"] + [run["ensemble"]], expected["models"] + [expected["ensemble"]]):
            name = actual["model_name"]
            if actual["prediction"] != wanted["prediction"]:
                failures.append(f"{run['image']} {name}: prediction {wanted['prediction']} -> {actual['prediction']}")
            delta = abs(actual["confidence"] - wanted["confidence"])
            if delta > CONFIDENCE_TOLERANCE:
                failures.append(f"{run['image']} {name}: confidence {wanted['confidence']:.4f} -> "
                                f"{actual['confidence']:.4f} (delta {delta:.4f})")
            if actual["low_confidence"] != wanted["low_confidence"]:
                failures.append(f"{run['image']} {name}: low_confidence {wanted['low_confidence']} -> "
                                f"{actual['low_confidence']}")
    return failures


def compare_to_legacy(current: list) -> int:
    """
    The ONNX model is a port of the v38 PyTorch ensemble, so on the same images
    it should agree with the recorded .pt baseline (which used the app's
    grey-world preprocessing). A class flip means the port or the input contract
    (channel order, grey-world moved into the graph or not) is wrong.
    """
    legacy = {r["image"]: r for r in json.loads(LEGACY_BASELINE_PATH.read_text()) if r["preprocess"] == "opencv"}
    disagreements, drifted = [], []
    print(f"{'image':<12} {'model':<6} {'v38 .pt':>12} {'onnx':>12} {'delta':>10}  class")
    for run in current:
        expected = legacy.get(run["image"])
        if expected is None:
            continue
        if len(run["models"]) != len(expected["models"]):
            disagreements.append(f"{run['image']}: {len(run['models'])} outputs vs {len(expected['models'])} legacy models")
            continue
        for actual, wanted in zip(run["models"] + [run["ensemble"]], expected["models"] + [expected["ensemble"]]):
            delta = actual["probability"] - wanted["probability"]
            same = actual["prediction"] == wanted["prediction"]
            print(f"{run['image']:<12} {wanted['model_name']:<6} {wanted['probability']:>12.6f} "
                  f"{actual['probability']:>12.6f} {delta:>+10.6f}  {'same' if same else 'FLIPPED'}")
            if not same:
                disagreements.append(f"{run['image']} {wanted['model_name']}: {wanted['prediction']} -> {actual['prediction']}")
            elif abs(delta) > LEGACY_PROBABILITY_TOLERANCE:
                drifted.append(f"{run['image']} {wanted['model_name']}: probability moved {delta:+.4f}")
    print()
    for message in drifted:
        print(f"  ~ {message}")
    if disagreements:
        print("CLASSIFICATION DISAGREEMENTS vs v38:", file=sys.stderr)
        for message in disagreements:
            print(f"  ✗ {message}", file=sys.stderr)
        return 1
    print(f"Every classification matches the v38 PyTorch baseline"
          f"{f' ({len(drifted)} probabilities moved > {LEGACY_PROBABILITY_TOLERANCE})' if drifted else ''}.")
    return 0


# ─────────────────────────────────────────────────────────────────────────────
# CLI
# ─────────────────────────────────────────────────────────────────────────────


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--model", type=Path, action="append",
                        help="Model file; repeat for the 3-file layout, in output order. "
                             f"Default: {', '.join(ASSET_NAMES)} under quest/src/main/assets/.")
    parser.add_argument("--images-dir", type=Path, default=IMAGES_DIR)
    parser.add_argument("--baseline", type=Path, default=BASELINE_PATH)
    parser.add_argument("--inspect", action="store_true", help="Print the model's contract and check it.")
    parser.add_argument("--compare-legacy", action="store_true",
                        help="Compare against the v38 PyTorch baseline (scripts/ai_inference_baseline.json).")
    parser.add_argument("--write-baseline", action="store_true", help="Record the ONNX baseline.")
    parser.add_argument("--json", action="store_true", help="Emit full JSON results to stdout.")
    args = parser.parse_args()

    model_paths = [p.resolve() for p in (args.model or [ASSETS_DIR / name for name in ASSET_NAMES])]
    missing = [str(p) for p in model_paths if not p.exists()]
    if missing:
        print(f"Model file(s) not found: {', '.join(missing)}", file=sys.stderr)
        return 1
    image_paths = sorted(p for p in args.images_dir.iterdir() if p.suffix.lower() in (".jpg", ".jpeg", ".png"))
    if not image_paths:
        print(f"No images found in {args.images_dir}", file=sys.stderr)
        return 1

    try:
        if args.inspect:
            return inspect(model_paths, image_paths)

        sessions = load_sessions(model_paths)
        spec = sessions[0][1]
        print(f"Model: {', '.join(p.name for p in model_paths)}  input {spec['layout']} "
              f"{np.dtype(spec['dtype']).name} {spec['height']}x{spec['width']}  (MODEL_VERSION={MODEL_VERSION})\n")
        results = [run_image(sessions, image_path) for image_path in image_paths]
    except ContractViolation as e:
        print(f"CONTRACT VIOLATION: {e}", file=sys.stderr)
        return 1

    names = [m["model_name"] for m in results[0]["models"]]
    header = f"{'image':<12} " + " ".join(f"{n:>22}" for n in names) + f" {'ENSEMBLE':>24}"
    print(header)
    print("-" * len(header))
    for run in results:
        cells = []
        for result in run["models"] + [run["ensemble"]]:
            flag = "!" if result["low_confidence"] else " "
            cells.append(f"{'SUSP' if result['is_suspicious'] else 'non-susp'} {result['confidence']:6.2f}%{flag}")
        print(f"{run['image']:<12} " + " ".join(f"{c:>22}" for c in cells[:-1]) + f" {cells[-1]:>24}")
    print(f"\n  '!' marks confidence below the LOW_CONFIDENCE_THRESHOLD of {float(LOW_CONFIDENCE_THRESHOLD):.0f}%\n")

    if args.json:
        print(json.dumps(results, indent=2))

    if args.compare_legacy:
        return compare_to_legacy(results)

    if args.write_baseline:
        args.baseline.write_text(json.dumps(results, indent=2) + "\n")
        print(f"Baseline written: {args.baseline} ({len(results)} images)")
        return 0

    if not args.baseline.exists():
        print(f"No baseline at {args.baseline}. Run with --write-baseline to record one.", file=sys.stderr)
        return 2
    failures = compare_to_baseline(results, json.loads(args.baseline.read_text()))
    if failures:
        print(f"DRIFT DETECTED — {len(failures)} mismatch(es) vs baseline:\n", file=sys.stderr)
        for failure in failures:
            print(f"  ✗ {failure}", file=sys.stderr)
        return 1
    print(f"PASS — {len(results)} images match the ONNX baseline.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
