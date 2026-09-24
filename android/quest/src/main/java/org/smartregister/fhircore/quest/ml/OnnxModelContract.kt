/*
 * Copyright 2021-2024 Ona Systems, Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.smartregister.fhircore.quest.ml

/**
 * The agreement between the app and the ONNX screening model, kept in one place so that dropping
 * in a new model only touches this file (see docs/onnx_implementation_plan.md §8).
 *
 * Confirmed by the ML team:
 * - Input is the raw `cv2.imread` output — BGR, uint8 — resized to the model's input size. The app
 *   applies no grey-world, clipping, rounding, /255 or mean/std normalisation.
 * - Sigmoid runs inside the graph, so every output value is already a probability in [0, 1].
 *
 * Still open until the model is delivered: [ASSET_NAMES], [MODEL_VERSION] and the output ordering
 * behind [COMPONENTS].
 */
object OnnxModelContract {

  /**
   * Model files under `quest/src/main/assets/`, in output order. The ONNX path switches on only
   * when every file listed here is bundled; until then the app keeps using the PyTorch models.
   *
   * One file covers the "1 model → 3 values" and "1 model → 1 value" layouts of slide 3; listing
   * three files covers "3 models → 3 values" (their outputs are concatenated in list order).
   */
  val ASSET_NAMES = listOf("screening_model.onnx")

  /**
   * Reported as `model_version` on `model_inference_completed`. Must differ from the PyTorch
   * pipeline's "v38" so field telemetry from the two runtimes stays separable.
   */
  const val MODEL_VERSION = "onnx-v1"

  /** Height and width used when the model declares its spatial input dims as dynamic. */
  const val DEFAULT_INPUT_SIZE = 256

  /**
   * Identity of each output value when the model emits exactly this many. Output `i` is reported
   * as `COMPONENTS[i]`, which keeps today's `model6_*`/`model8_*`/`model82_*` result keys (and
   * therefore the `v38-model-v*` FHIR extensions) unchanged. Any other output count falls back to
   * `component_<i>` names.
   */
  val COMPONENTS =
    listOf(
      ModelComponent(modelName = "v6", resultKeyPrefix = "model6"),
      ModelComponent(modelName = "v8", resultKeyPrefix = "model8"),
      ModelComponent(modelName = "v82", resultKeyPrefix = "model82"),
    )

  /** A component is "Suspicious" when its probability is strictly above this. */
  const val DECISION_THRESHOLD = 0.5f

  /** Confidence (%) below which a prediction is flagged low-confidence; the V1 launch cut. */
  const val LOW_CONFIDENCE_THRESHOLD = 65f

  /**
   * Float slack allowed around [0, 1]. Anything further out means the graph is not emitting
   * probabilities (most likely Sigmoid is missing) and the inference fails instead of clamping.
   */
  const val PROBABILITY_TOLERANCE = 1e-4f

  /**
   * Execution provider the session is created with. CPU until the per-device benchmark in the
   * plan's §3.3 justifies an accelerator; see [ExecutionProvider].
   */
  val EXECUTION_PROVIDER = ExecutionProvider.CPU
}

data class ModelComponent(val modelName: String, val resultKeyPrefix: String)

/** ONNX Runtime execution providers the engine knows how to configure. */
enum class ExecutionProvider(val telemetryName: String) {
  CPU("cpu"),
  XNNPACK("xnnpack"),

  /** Deprecated by Android 15; only worth enabling where a benchmark shows a real gain. */
  NNAPI("nnapi"),
}
