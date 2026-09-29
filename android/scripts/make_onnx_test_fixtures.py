#!/usr/bin/env python3
"""
Builds the tiny ONNX contract fixtures used by the JVM unit tests of the ONNX
screening path (quest/src/test/.../ml/OnnxModelRunnerTest.kt).

The real screening model is still being ported from PyTorch by the ML team.
These fixtures stand in for it: each one follows the contract the ML team gave
us (raw BGR uint8 image in, Sigmoid inside the graph, probabilities out), and
computes something simple enough to assert on exactly:

    output[c] = sigmoid((mean of channel c over the image - 128) / 32)

Because each output depends on exactly one input channel, a test that feeds a
uniform B=10 / G=128 / R=250 image catches a swapped channel order (outputs 0
and 2 trade places) and a missing HWC -> CHW permute (all three outputs collapse
to the same value), not just a crash.

Fixtures written to --out (default quest/src/test/resources/onnx/):

    probs_uint8_nchw.onnx          uint8   [1,3,256,256] -> float [1,3]   (the stated contract)
    probs_float32_nhwc.onnx        float32 [1,256,256,3] -> float [1,3]   (other dtype + layout)
    probs_uint8_nchw_split.onnx    uint8   [1,3,256,256] -> 3 outputs of [1,1]
    probs_uint8_nchw_dynamic.onnx  uint8   [N,3,H,W]     -> float [N,3]   (dynamic dims)
    logits_uint8_nchw.onnx         uint8   [1,3,256,256] -> float [1,3]   (NO sigmoid: contract violation)

Requires: torch, onnx (dev-only; the app does not depend on either).
    pip install torch onnx
    python3 scripts/make_onnx_test_fixtures.py
"""

from __future__ import annotations

import argparse
from pathlib import Path

import torch

REPO_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_OUT = REPO_ROOT / "quest" / "src" / "test" / "resources" / "onnx"

# col2im in the real MobileViTV2 models needs opset 18; match it so the
# fixtures exercise the same ORT opset range as the model we are waiting for.
OPSET = 18
SIZE = 256


class ChannelMean(torch.nn.Module):
    """sigmoid((per-channel mean - 128) / 32), for NCHW or NHWC input of any dtype."""

    def __init__(self, channel_axis: int, apply_sigmoid: bool = True, split: bool = False):
        super().__init__()
        self.spatial = (2, 3) if channel_axis == 1 else (1, 2)
        self.apply_sigmoid = apply_sigmoid
        self.split = split

    def forward(self, image):
        z = (image.to(torch.float32).mean(dim=self.spatial) - 128.0) / 32.0
        out = torch.sigmoid(z) if self.apply_sigmoid else z
        if self.split:
            return out[:, 0:1], out[:, 1:2], out[:, 2:3]
        return out


def export(module, example, path: Path, output_names, dynamic_axes=None):
    torch.onnx.export(
        module,
        (example,),
        str(path),
        input_names=["image"],
        output_names=list(output_names),
        dynamic_axes=dynamic_axes,
        opset_version=OPSET,
        dynamo=False,
    )
    print(f"wrote {path.relative_to(REPO_ROOT)} ({path.stat().st_size} bytes)")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT)
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)

    nchw_u8 = torch.zeros(1, 3, SIZE, SIZE, dtype=torch.uint8)
    nhwc_f32 = torch.zeros(1, SIZE, SIZE, 3, dtype=torch.float32)

    export(ChannelMean(1), nchw_u8, args.out / "probs_uint8_nchw.onnx", ["probabilities"])
    export(ChannelMean(3), nhwc_f32, args.out / "probs_float32_nhwc.onnx", ["probabilities"])
    export(ChannelMean(1, split=True), nchw_u8, args.out / "probs_uint8_nchw_split.onnx", ["p0", "p1", "p2"])
    export(
        ChannelMean(1),
        nchw_u8,
        args.out / "probs_uint8_nchw_dynamic.onnx",
        ["probabilities"],
        dynamic_axes={"image": {0: "batch", 2: "height", 3: "width"}, "probabilities": {0: "batch"}},
    )
    export(ChannelMean(1, apply_sigmoid=False), nchw_u8, args.out / "logits_uint8_nchw.onnx", ["logits"])


if __name__ == "__main__":
    main()
