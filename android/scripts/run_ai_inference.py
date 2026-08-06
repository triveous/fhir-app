#!/usr/bin/env python3
"""
Regression harness for the on-device AI screening pipeline.

This is a *characterization test*, not a reimplementation: it runs the real
TorchScript models from `quest/src/main/assets/` over real images, driving them
through a Python mirror of the exact preprocessing the app performs. It exists
because the inference path is PyTorch Mobile + OpenCV native code that cannot
run in a JVM unit test, so there is no way to assert on it from `src/test`.

Nothing in `quest/src/main` is modified or imported. The only substitution is
the *source of the image*: files from `docs/test-images/` instead of a CameraX
capture. Everything downstream of `absolutePath` is replicated faithfully.

Kotlin sources mirrored (keep in sync if these change):

  quest/src/main/java/org/smartregister/fhircore/quest/util/OpenCVUtils.kt
      scaleImageMat()                  -> preprocess_opencv()
  quest/src/main/java/org/smartregister/fhircore/quest/camerax/CameraxLauncherFragment.kt
      processImage()      (L768-845)   -> build_input_tensor() + run_image()
      convertRGBtoBGR()   (L742-766)   -> _convert_rgb_to_bgr()
      runInference()      (L847-867)   -> run_model()
      sigmoid()           (L886)       -> _sigmoid()
      binaryEntropy()     (L888-891)   -> _binary_entropy()

Usage:
    python3 scripts/run_ai_inference.py                      # run + compare to baseline
    python3 scripts/run_ai_inference.py --write-baseline     # record a new baseline
    python3 scripts/run_ai_inference.py --preprocess both    # also run the no-normalization control

Exit codes:
    0 - all images matched the baseline (or baseline was just written)
    1 - drift detected against the baseline, or a hard failure
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
import torch

# ─────────────────────────────────────────────────────────────────────────────
# Constants — mirrored from the Kotlin sources. Values must match exactly.
# ─────────────────────────────────────────────────────────────────────────────

REPO_ROOT = Path(__file__).resolve().parent.parent

ASSETS_DIR = REPO_ROOT / "quest" / "src" / "main" / "assets"
IMAGES_DIR = REPO_ROOT / "docs" / "test-images"
BASELINE_PATH = REPO_ROOT / "scripts" / "ai_inference_baseline.json"

# CameraxLauncherFragment.kt L417-419 — load order and model->name mapping.
MODELS = (("v6", "model6.pt"), ("v8", "model8.pt"), ("v82", "model82.pt"))

# CameraxLauncherFragment.kt L988-989
MODEL_VERSION = "v38"
LOW_CONFIDENCE_THRESHOLD = np.float32(65.0)

# classes.java L4 — index 0 = negative, index 1 = positive.
DISEASES = ("Non-Suspicious", "Suspicious")

# OpenCVUtils.kt L28-46
TARGET_WIDTH = 256
TARGET_HEIGHT = 256
TARGET_CHANNEL_MEAN = 128.0
PIXEL_MIN = 0.0
PIXEL_MAX = 255.0
ONE_DECIMAL_SHIFT = 10.0


# ─────────────────────────────────────────────────────────────────────────────
# Preprocessing — mirrors OpenCVUtils.scaleImageMat()
# ─────────────────────────────────────────────────────────────────────────────


def preprocess_opencv(file_path: Path) -> np.ndarray:
    """
    Mirror of OpenCVUtils.scaleImageMat (OpenCVUtils.kt L89-143).

    Returns a 256x256x3 uint8 BGR array — the Python equivalent of the
    CV_8UC3 Mat the Kotlin function hands back.
    """
    # 1. Decode. imread defaults to IMREAD_COLOR, which drops any alpha channel
    #    and yields 3-channel BGR — matching the `channels() == 3` require at
    #    OpenCVUtils.kt L106. Two of the test PNGs are RGBA on disk.
    original = cv2.imread(str(file_path), cv2.IMREAD_COLOR)
    if original is None or original.size == 0:
        raise IOError(f"Failed to decode image file: {file_path}")
    if original.shape[2] != 3:
        raise ValueError(
            f"Input image must have exactly 3 channels (BGR), found {original.shape[2]}"
        )

    # 2. Resize to model input dims with bicubic interpolation (L150-161).
    resized = cv2.resize(
        original, (TARGET_WIDTH, TARGET_HEIGHT), interpolation=cv2.INTER_CUBIC
    )

    # 3. Promote to 32-bit float (L116). uint8 -> float32 is exact.
    as_float = resized.astype(np.float32)

    # 4. Per-channel mean normalization (L178-200). Each B/G/R channel is scaled
    #    so its mean lands on 128.0; a fully-black channel is left alone to
    #    avoid divide-by-zero.
    channels = cv2.split(as_float)
    scaled_channels = []
    for channel in channels:
        # Core.mean returns a double; compute the factor in float64 as Kotlin does.
        mean = float(np.mean(channel.astype(np.float64)))
        factor = (TARGET_CHANNEL_MEAN / mean) if mean != 0.0 else 1.0
        # Core.multiply against a constant-filled Mat == element-wise scale.
        # The Mat operand is CV_32F, so the factor is truncated to float32 first.
        scaled_channels.append(channel * np.float32(factor))
    normalized = cv2.merge(scaled_channels)

    # 5. Clip back into the valid pixel range (L206-211).
    clipped = np.minimum(np.maximum(normalized, np.float32(PIXEL_MIN)), np.float32(PIXEL_MAX))

    # 6. Round each channel to one decimal (L247-281). The Kotlin trick is
    #    multiply by 10 -> convertTo(CV_32S) -> back to CV_32F -> divide by 10.
    #    convertTo uses saturate_cast, i.e. cvRound = round-half-to-even, which
    #    is exactly np.rint.
    shifted = clipped * np.float32(ONE_DECIMAL_SHIFT)
    as_int = np.rint(shifted).astype(np.int32)
    rounded = as_int.astype(np.float32) / np.float32(ONE_DECIMAL_SHIFT)

    # 7. Back to CV_8UC3 (L131-132). convertTo float->uchar is saturate_cast<uchar>,
    #    which rounds to nearest (NOT truncation) and clamps to [0, 255].
    return np.clip(np.rint(rounded), 0, 255).astype(np.uint8)


def preprocess_simple(file_path: Path) -> np.ndarray:
    """
    Control pipeline: bicubic resize only, no mean normalization or rounding.

    Not what the app does — this exists so `--preprocess both` shows how much
    the per-channel normalization in step 4 actually moves the model output.
    """
    original = cv2.imread(str(file_path), cv2.IMREAD_COLOR)
    if original is None or original.size == 0:
        raise IOError(f"Failed to decode image file: {file_path}")
    return cv2.resize(
        original, (TARGET_WIDTH, TARGET_HEIGHT), interpolation=cv2.INTER_CUBIC
    )


# ─────────────────────────────────────────────────────────────────────────────
# Tensor construction — mirrors processImage() L780-789
# ─────────────────────────────────────────────────────────────────────────────


def _convert_rgb_to_bgr(tensor: torch.Tensor) -> torch.Tensor:
    """
    Mirror of CameraxLauncherFragment.convertRGBtoBGR (L742-766).

    Operates on a [1, 3, H, W] tensor, swapping planes 0 and 2 while leaving
    plane 1 in place. Implemented as an explicit plane copy rather than a slice
    reversal so it stays line-for-line comparable with the Kotlin loop.
    """
    shape = tensor.shape
    if len(shape) != 4 or shape[1] != 3:
        raise ValueError("Input tensor must have shape [1, 3, H, W]")

    out = torch.empty_like(tensor)
    out[0, 0] = tensor[0, 2]  # B <- plane 2
    out[0, 1] = tensor[0, 1]  # G <- plane 1
    out[0, 2] = tensor[0, 0]  # R <- plane 0
    return out


def build_input_tensor(bgr_mat: np.ndarray) -> torch.Tensor:
    """
    Mirror of processImage L780-789.

    The app's route is: BGR Mat -> cvtColor(BGR2RGB) -> ARGB_8888 Bitmap ->
    TensorImageUtils.bitmapToFloat32Tensor(mean=[0,0,0], std=[1,1,1]) ->
    convertRGBtoBGR. Each step is reproduced below.
    """
    # Imgproc.cvtColor(sourceMat, rgbMat, COLOR_BGR2RGB) — L780.
    rgb_mat = cv2.cvtColor(bgr_mat, cv2.COLOR_BGR2RGB)

    # TensorImageUtils.bitmapToFloat32Tensor — L787. It reads ARGB_8888 pixels,
    # divides each 8-bit channel by 255.0f, then applies (v - mean) / std. With
    # mean=[0,0,0] and std=[1,1,1] the normalization is a no-op, so the result
    # is simply the RGB bytes scaled into [0, 1], laid out NCHW.
    chw = np.transpose(rgb_mat, (2, 0, 1)).astype(np.float32) / np.float32(255.0)
    rgb_tensor = torch.from_numpy(np.ascontiguousarray(chw)).unsqueeze(0)

    # convertRGBtoBGR — L788. Net effect of L780+L787+L788 is that the model
    # sees the original BGR channel order in [0, 1].
    return _convert_rgb_to_bgr(rgb_tensor)


# ─────────────────────────────────────────────────────────────────────────────
# Inference — mirrors runInference() L847-867
# ─────────────────────────────────────────────────────────────────────────────


def _sigmoid(value: np.float32) -> np.float32:
    """CameraxLauncherFragment.sigmoid (L886). Kotlin evaluates this in Float."""
    return np.float32(1.0) / (np.float32(1.0) + np.exp(-np.float32(value), dtype=np.float32))


def _binary_entropy(probability: np.float32) -> float:
    """CameraxLauncherFragment.binaryEntropy (L888-891). Coerce in Float, math in Double."""
    p = float(np.clip(np.float32(probability), np.float32(0.000001), np.float32(0.999999)))
    return -p * math.log(p) - (1.0 - p) * math.log(1.0 - p)


def _decimal_format_two(value: float) -> str:
    """
    Java DecimalFormat("#.##") — used at L804 for the displayed confidence.

    Default RoundingMode is HALF_EVEN, and trailing zeros are dropped ("83.5",
    not "83.50"). Python's format() also rounds half-to-even, so formatting
    then stripping matches.
    """
    text = f"{value:.2f}"
    if "." in text:
        text = text.rstrip("0").rstrip(".")
    return text or "0"


def _kotlin_float_to_string(value: np.float32) -> str:
    """
    Kotlin's Float.toString() — used at L828/L832 to stringify each model's
    confidence into the result map.

    It emits the shortest decimal that round-trips the float32. numpy's str()
    on a float32 scalar does the same (repr() would wrap it in 'np.float32(..)').
    Kotlin switches to scientific notation outside [1e-3, 1e7); confidence is
    always in [50, 100], so that branch is unreachable here.
    """
    return str(np.float32(value))


def run_model(module: torch.jit.ScriptModule, input_tensor: torch.Tensor, model_name: str) -> dict:
    """Mirror of runInference (L847-867)."""
    started = time.monotonic()
    with torch.no_grad():
        output = module.forward(input_tensor)
    inference_time_ms = int((time.monotonic() - started) * 1000)

    # outputTensor.dataAsFloatArray[0] — L852. Flatten so a [1] or [1,1] shaped
    # head both resolve to the same first element the Kotlin indexing takes.
    raw_logit = np.float32(output.reshape(-1)[0].item())

    probability = _sigmoid(raw_logit)
    prediction = 1 if probability > np.float32(0.5) else 0
    confidence = (
        probability * np.float32(100.0)
        if prediction == 1
        else (np.float32(1.0) - probability) * np.float32(100.0)
    )

    return {
        "model_name": model_name,
        "model_version": MODEL_VERSION,
        "raw_logit": float(raw_logit),
        "prediction": "suspicious" if prediction == 1 else "non_suspicious",
        "display_prediction": DISEASES[prediction],
        "confidence": float(confidence),
        "probability": float(probability),
        "entropy": _binary_entropy(probability),
        "low_confidence": bool(confidence < LOW_CONFIDENCE_THRESHOLD),
        "inference_time_ms": inference_time_ms,
        "is_suspicious": prediction == 1,
    }


def run_image(modules: dict, image_path: Path, preprocess: str) -> dict:
    """Mirror of processImage L791-836, including the ensemble combine."""
    bgr_mat = (
        preprocess_opencv(image_path) if preprocess == "opencv" else preprocess_simple(image_path)
    )
    input_tensor = build_input_tensor(bgr_mat)

    results = [run_model(modules[name], input_tensor, name) for name, _ in MODELS]

    # L799-817 — the ensemble is unanimous-vote: suspicious only if ALL agree.
    combined_ms = sum(r["inference_time_ms"] for r in results)
    all_suspicious = all(r["is_suspicious"] for r in results)
    final_prediction = 1 if all_suspicious else 0

    # Kotlin averages Float values in Double, then narrows back to Float (L803).
    final_confidence = np.float32(float(np.mean([r["confidence"] for r in results])))

    ensemble = {
        "model_name": "ensemble",
        "model_version": MODEL_VERSION,
        "prediction": "suspicious" if all_suspicious else "non_suspicious",
        "display_prediction": DISEASES[final_prediction],
        "confidence": float(final_confidence),
        "probability": float(np.float32(float(np.mean([r["probability"] for r in results])))),
        "entropy": float(np.mean([r["entropy"] for r in results])),
        "low_confidence": bool(final_confidence < LOW_CONFIDENCE_THRESHOLD),
        "inference_time_ms": combined_ms,
        "combined_inference_time_ms": combined_ms,
        "is_suspicious": all_suspicious,
    }

    # L822-836 — the map handed back to the questionnaire.
    result_map = {
        "camera_prediction": DISEASES[final_prediction],
        "camera_confidence": _decimal_format_two(float(final_confidence)),
    }
    for result, key in zip(results, ("model6", "model8", "model82")):
        result_map[f"{key}_prediction"] = result["display_prediction"]
        result_map[f"{key}_confidence"] = _kotlin_float_to_string(result["confidence"])

    return {
        "image": image_path.name,
        "preprocess": preprocess,
        "models": results,
        "ensemble": ensemble,
        "result_map": result_map,
    }


# ─────────────────────────────────────────────────────────────────────────────
# Baseline comparison — the actual "unit test" assertion
# ─────────────────────────────────────────────────────────────────────────────

# Predictions must match exactly. Numeric fields get a tolerance because BLAS
# kernels differ between this desktop x86/ARM build and Android's PyTorch Mobile;
# a genuine model or preprocessing change moves them far more than this.
CONFIDENCE_TOLERANCE = 0.01

# Only the mode that mirrors the shipping pipeline is a regression target. The
# 'simple' control exists to show what the normalization contributes, so it is
# reported but never asserted on.
SHIPPING_MODE = "opencv"


def compare_to_baseline(current: list, baseline: list) -> list[str]:
    """Returns a list of human-readable drift messages; empty means pass."""
    failures: list[str] = []
    baseline_by_key = {(r["image"], r["preprocess"]): r for r in baseline}

    for run in current:
        key = (run["image"], run["preprocess"])
        if run["preprocess"] != SHIPPING_MODE:
            continue  # diagnostic control, not under test
        expected = baseline_by_key.get(key)
        if expected is None:
            failures.append(f"{key[0]} [{key[1]}]: no baseline entry (run --write-baseline)")
            continue

        for actual_model, expected_model in zip(run["models"] + [run["ensemble"]],
                                                expected["models"] + [expected["ensemble"]]):
            name = actual_model["model_name"]
            if actual_model["prediction"] != expected_model["prediction"]:
                failures.append(
                    f"{key[0]} [{key[1]}] {name}: prediction changed "
                    f"{expected_model['prediction']} -> {actual_model['prediction']}"
                )
            delta = abs(actual_model["confidence"] - expected_model["confidence"])
            if delta > CONFIDENCE_TOLERANCE:
                failures.append(
                    f"{key[0]} [{key[1]}] {name}: confidence drifted "
                    f"{expected_model['confidence']:.4f} -> {actual_model['confidence']:.4f} "
                    f"(delta {delta:.4f} > {CONFIDENCE_TOLERANCE})"
                )
            if actual_model["low_confidence"] != expected_model["low_confidence"]:
                failures.append(
                    f"{key[0]} [{key[1]}] {name}: low_confidence flag changed "
                    f"{expected_model['low_confidence']} -> {actual_model['low_confidence']}"
                )

    return failures


# ─────────────────────────────────────────────────────────────────────────────
# CLI
# ─────────────────────────────────────────────────────────────────────────────


def load_modules() -> dict:
    modules = {}
    for name, filename in MODELS:
        path = ASSETS_DIR / filename
        if not path.exists():
            raise FileNotFoundError(f"Model asset missing: {path}")
        module = torch.jit.load(str(path), map_location="cpu")
        module.eval()
        modules[name] = module
    return modules


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument(
        "--preprocess",
        choices=("opencv", "simple", "both"),
        default="opencv",
        help="'opencv' mirrors the shipping pipeline (default); 'simple' is the "
        "resize-only control; 'both' runs each for comparison.",
    )
    parser.add_argument("--images-dir", type=Path, default=IMAGES_DIR)
    parser.add_argument("--baseline", type=Path, default=BASELINE_PATH)
    parser.add_argument(
        "--write-baseline",
        action="store_true",
        help="Record the current output as the baseline instead of comparing against it.",
    )
    parser.add_argument("--json", action="store_true", help="Emit full JSON results to stdout.")
    args = parser.parse_args()

    image_paths = sorted(
        p for p in args.images_dir.iterdir()
        if p.suffix.lower() in (".jpg", ".jpeg", ".png")
    )
    if not image_paths:
        print(f"No images found in {args.images_dir}", file=sys.stderr)
        return 1

    torch.manual_seed(0)
    torch.set_num_threads(1)  # determinism over speed

    print(f"Loading models from {ASSETS_DIR.relative_to(REPO_ROOT)} ...")
    modules = load_modules()
    print(f"Loaded: {', '.join(name for name, _ in MODELS)}  (MODEL_VERSION={MODEL_VERSION})\n")

    modes = ("opencv", "simple") if args.preprocess == "both" else (args.preprocess,)
    results = []

    for mode in modes:
        label = "shipping pipeline (OpenCVUtils)" if mode == "opencv" else "control (resize only)"
        print(f"── preprocess: {mode} — {label} " + "─" * 24)
        header = f"{'image':<12} {'v6':>22} {'v8':>22} {'v82':>22} {'ENSEMBLE':>24}"
        print(header)
        print("-" * len(header))

        for image_path in image_paths:
            run = run_image(modules, image_path, mode)
            results.append(run)

            cells = []
            for result in run["models"] + [run["ensemble"]]:
                flag = "!" if result["low_confidence"] else " "
                short = "SUSP" if result["is_suspicious"] else "non-susp"
                cells.append(f"{short} {result['confidence']:6.2f}%{flag}")
            print(
                f"{run['image']:<12} {cells[0]:>22} {cells[1]:>22} {cells[2]:>22} {cells[3]:>24}"
            )
        print()

    print("  '!' marks confidence below the LOW_CONFIDENCE_THRESHOLD of "
          f"{float(LOW_CONFIDENCE_THRESHOLD):.0f}%\n")

    if args.json:
        print(json.dumps(results, indent=2))

    # ── baseline handling ────────────────────────────────────────────────
    if args.write_baseline:
        args.baseline.write_text(json.dumps(results, indent=2) + "\n")
        print(f"Baseline written: {args.baseline.relative_to(REPO_ROOT)} "
              f"({len(results)} runs)")
        return 0

    if not args.baseline.exists():
        print(f"No baseline at {args.baseline.relative_to(REPO_ROOT)}.", file=sys.stderr)
        print("Run with --write-baseline to record one.", file=sys.stderr)
        return 2

    baseline = json.loads(args.baseline.read_text())
    failures = compare_to_baseline(results, baseline)
    if failures:
        print(f"DRIFT DETECTED — {len(failures)} mismatch(es) vs baseline:\n", file=sys.stderr)
        for failure in failures:
            print(f"  ✗ {failure}", file=sys.stderr)
        return 1

    asserted = [r for r in results if r["preprocess"] == SHIPPING_MODE]
    checked = len(asserted) * (len(MODELS) + 1)
    print(f"PASS — {checked} model outputs across {len(asserted)} image runs match the baseline.")
    if len(asserted) != len(results):
        print(f"       ({len(results) - len(asserted)} '{'simple'}' control runs shown above "
              "are diagnostic and not asserted on.)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
