#!/usr/bin/env python3
"""
Automated tests for the bundled ONNX screening models, driven by the images in
docs/test-images/ (or any folder you point it at).

This sits next to run_ai_inference.py (the PyTorch .pt harness, left untouched)
and reuses the pipeline mirror in run_onnx_inference.py, so it exercises exactly
what the app does: cv2.imread (BGR) -> INTER_CUBIC resize -> raw uint8 into the
three models -> probabilities -> AND-ensemble.

What it checks, per model file and per image:
  * contract   - one uint8 image input, float output produced by Sigmoid, weights
                 inlined (the app loads models from bytes, so no .onnx.data sidecar)
  * range      - every output is a probability in [0, 1]
  * legacy     - same class as the v38 .pt baseline (ai_inference_baseline.json)
  * baseline   - same class/confidence as ai_inference_baseline_onnx.json
  * stability  - two runs give identical outputs

Usage (no pytest needed; plain unittest):
    python3 scripts/test_onnx_models.py
    python3 scripts/test_onnx_models.py -v
    ONNX_TEST_IMAGES=/path/to/folder python3 scripts/test_onnx_models.py       # other images
    ONNX_TEST_MODELS=a.onnx,b.onnx,c.onnx python3 scripts/test_onnx_models.py   # other models
    python3 -m unittest scripts.test_onnx_models                               # from the repo root

Images that are not in a baseline file are still contract- and range-checked;
only the comparison against that baseline is skipped for them.
Requires: onnxruntime, opencv-python, numpy (onnx optional; enables the Sigmoid/weights checks).
"""

from __future__ import annotations

import json
import os
import sys
import unittest
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import run_onnx_inference as harness  # noqa: E402

IMAGE_SUFFIXES = (".jpg", ".jpeg", ".png")


def _model_paths() -> list[Path]:
    override = os.environ.get("ONNX_TEST_MODELS")
    if override:
        return [Path(p).resolve() for p in override.split(",") if p]
    return [harness.ASSETS_DIR / name for name in harness.ASSET_NAMES]


def _image_paths() -> list[Path]:
    folder = Path(os.environ.get("ONNX_TEST_IMAGES", harness.IMAGES_DIR))
    return sorted(p for p in folder.iterdir() if p.suffix.lower() in IMAGE_SUFFIXES)


def _load_json(path: Path) -> dict:
    """image name -> recorded run, or {} when the file is missing."""
    return {r["image"]: r for r in json.loads(path.read_text())} if path.exists() else {}


class OnnxModelTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.model_paths = _model_paths()
        missing = [str(p) for p in cls.model_paths if not p.exists()]
        if missing:
            raise unittest.SkipTest(f"model file(s) not found: {missing}")
        cls.image_paths = _image_paths()
        if not cls.image_paths:
            raise unittest.SkipTest("no images found")
        cls.sessions = harness.load_sessions(cls.model_paths)
        cls.spec = cls.sessions[0][1]
        cls.runs = {p.name: harness.run_image(cls.sessions, p) for p in cls.image_paths}
        cls.legacy = {
            r["image"]: r
            for r in json.loads(harness.LEGACY_BASELINE_PATH.read_text())
            if r.get("preprocess") == "opencv"
        } if harness.LEGACY_BASELINE_PATH.exists() else {}
        cls.onnx_baseline = _load_json(harness.BASELINE_PATH)

    # ── contract ────────────────────────────────────────────────────────────

    def test_models_take_one_uint8_bgr_image(self):
        for path, (_, spec) in zip(self.model_paths, self.sessions):
            with self.subTest(model=path.name):
                self.assertIs(spec["dtype"], np.uint8)
                self.assertEqual((spec["height"], spec["width"]), (256, 256))
                self.assertEqual(spec["layout"], "NHWC")

    def test_models_are_self_contained(self):
        """The app loads models from bytes; a .onnx.data sidecar cannot be resolved there."""
        try:
            import onnx
        except ImportError:
            self.skipTest("pip install onnx to check for external weights")
        for path in self.model_paths:
            with self.subTest(model=path.name):
                graph = onnx.load(str(path), load_external_data=False).graph
                external = [t.name for t in graph.initializer if t.data_location == onnx.TensorProto.EXTERNAL]
                self.assertEqual(external, [], "run scripts/embed_onnx_weights.py")

    def test_outputs_come_from_sigmoid(self):
        producers = [harness._producing_ops(p) for p in self.model_paths]
        if producers[0] is None:
            self.skipTest("pip install onnx to check the graph")
        for path, found in zip(self.model_paths, producers):
            for output, ops in found.items():
                with self.subTest(model=path.name, output=output):
                    self.assertIn("Sigmoid", ops)

    def test_output_count_matches_the_app_components(self):
        for name, run in self.runs.items():
            with self.subTest(image=name):
                self.assertEqual(len(run["raw_outputs"]), len(harness.COMPONENTS))

    # ── per-image behaviour ─────────────────────────────────────────────────

    def test_outputs_are_probabilities(self):
        for name, run in self.runs.items():
            with self.subTest(image=name):
                for value in run["raw_outputs"]:
                    self.assertTrue(np.isfinite(value))
                    self.assertGreaterEqual(value, -float(harness.PROBABILITY_TOLERANCE))
                    self.assertLessEqual(value, 1 + float(harness.PROBABILITY_TOLERANCE))

    def test_matches_v38_pytorch_classification(self):
        checked = 0
        for name, run in self.runs.items():
            expected = self.legacy.get(name)
            if expected is None:
                continue
            checked += 1
            with self.subTest(image=name):
                self.assertEqual(len(run["models"]), len(expected["models"]))
                for actual, wanted in zip(run["models"] + [run["ensemble"]], expected["models"] + [expected["ensemble"]]):
                    self.assertEqual(actual["prediction"], wanted["prediction"], wanted["model_name"])
                    self.assertAlmostEqual(
                        actual["probability"], wanted["probability"],
                        delta=harness.LEGACY_PROBABILITY_TOLERANCE, msg=wanted["model_name"],
                    )
        if not checked:
            self.skipTest("none of these images are in scripts/ai_inference_baseline.json")

    def test_matches_recorded_onnx_baseline(self):
        checked = 0
        for name, run in self.runs.items():
            expected = self.onnx_baseline.get(name)
            if expected is None:
                continue
            checked += 1
            with self.subTest(image=name):
                failures = harness.compare_to_baseline([run], [expected])
                self.assertEqual(failures, [])
        if not checked:
            self.skipTest("none of these images are in scripts/ai_inference_baseline_onnx.json (--write-baseline)")

    def test_result_map_keys_match_the_fhir_contract(self):
        keys = {"camera_prediction", "camera_confidence"}
        for _, prefix in harness.COMPONENTS:
            keys |= {f"{prefix}_prediction", f"{prefix}_confidence"}
        for name, run in self.runs.items():
            with self.subTest(image=name):
                self.assertEqual(set(run["result_map"]), keys)
                self.assertIn(run["result_map"]["camera_prediction"], harness.DISEASES)

    def test_ensemble_is_and_of_components(self):
        for name, run in self.runs.items():
            with self.subTest(image=name):
                all_suspicious = all(m["is_suspicious"] for m in run["models"])
                self.assertEqual(run["ensemble"]["is_suspicious"], all_suspicious)

    def test_inference_is_deterministic(self):
        for path in self.image_paths[:2]:
            with self.subTest(image=path.name):
                again = harness.run_image(self.sessions, path)
                self.assertEqual(again["raw_outputs"], self.runs[path.name]["raw_outputs"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
