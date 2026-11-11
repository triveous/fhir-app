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

import java.text.DecimalFormat
import kotlin.math.ln
import org.smartregister.fhircore.quest.camerax.CameraxLauncherFragment
import org.smartregister.fhircore.quest.camerax.classes

/** Thrown when the model's output breaks [OnnxModelContract] (e.g. logits instead of probabilities). */
class OnnxContractException(message: String) : IllegalStateException(message)

/** One model's (or the ensemble's) verdict for an image. */
data class ModelPrediction(
  val modelName: String,
  val probability: Float,
  val isSuspicious: Boolean,
  val confidence: Float,
  val entropy: Double,
  val lowConfidence: Boolean,
) {
  /** Telemetry form: "suspicious" / "non_suspicious". */
  val prediction: String
    get() = if (isSuspicious) "suspicious" else "non_suspicious"

  /** UI / FHIR form: "Suspicious" / "Non-Suspicious". */
  val displayPrediction: String
    get() = classes.diseases[if (isSuspicious) 1 else 0]
}

data class ScreeningPrediction(
  val components: List<ModelPrediction>,
  val componentResultKeyPrefixes: List<String>,
  val ensemble: ModelPrediction,
) {
  /**
   * The result map the camera fragment hands to its ViewModel. For three outputs the keys are the
   * PyTorch pipeline's, byte for byte: `camera_prediction`, `camera_confidence`,
   * `model6_prediction`, `model6_confidence`, … `model82_confidence`.
   */
  fun toResultMap(): Map<String, Any> = buildMap {
    put(CameraxLauncherFragment.CAMERA_PREDICTION_KEY, ensemble.displayPrediction)
    put(CameraxLauncherFragment.CAMERA_CONFIDENCE_KEY, DecimalFormat("#.##").format(ensemble.confidence))
    components.forEachIndexed { index, component ->
      val prefix = componentResultKeyPrefixes[index]
      put("${prefix}_prediction", component.displayPrediction)
      put("${prefix}_confidence", component.confidence.toString())
    }
  }
}

/**
 * Turns the model's output probabilities into per-component and ensemble predictions, with the
 * same arithmetic as the PyTorch pipeline's `runInference` / `processImage` minus the Kotlin
 * sigmoid, which now lives inside the graph.
 *
 * The output count N is taken from the model, never assumed: N = 3 maps to v6/v8/v82 (see
 * [OnnxModelContract.COMPONENTS]); any other N gets `component_<i>` names; N = 1 makes the single
 * output the ensemble.
 */
object ScreeningOutputInterpreter {

  fun interpret(probabilities: FloatArray): ScreeningPrediction {
    validate(probabilities)
    val named = OnnxModelContract.COMPONENTS.takeIf { it.size == probabilities.size }
    val components =
      probabilities.mapIndexed { index, value ->
        predict(named?.get(index)?.modelName ?: "component_$index", value.coerceIn(0f, 1f))
      }
    val prefixes =
      probabilities.indices.map { index -> named?.get(index)?.resultKeyPrefix ?: "component$index" }
    return ScreeningPrediction(components, prefixes, combine(components))
  }

  private fun validate(probabilities: FloatArray) {
    if (probabilities.isEmpty()) throw OnnxContractException("Model produced no output values")
    val tolerance = OnnxModelContract.PROBABILITY_TOLERANCE
    val outOfRange = probabilities.filter { !it.isFinite() || it < -tolerance || it > 1f + tolerance }
    if (outOfRange.isNotEmpty()) {
      throw OnnxContractException(
        "Model outputs ${probabilities.contentToString()} are not probabilities in [0, 1] " +
          "(offending: $outOfRange). Is Sigmoid missing from the exported graph?",
      )
    }
  }

  private fun predict(modelName: String, probability: Float): ModelPrediction {
    val isSuspicious = probability > OnnxModelContract.DECISION_THRESHOLD
    val confidence = if (isSuspicious) probability * 100 else (1 - probability) * 100
    return ModelPrediction(
      modelName = modelName,
      probability = probability,
      isSuspicious = isSuspicious,
      confidence = confidence,
      entropy = binaryEntropy(probability),
      lowConfidence = confidence < OnnxModelContract.LOW_CONFIDENCE_THRESHOLD,
    )
  }

  /** Suspicious only when every component agrees; the rest are plain means. */
  private fun combine(components: List<ModelPrediction>): ModelPrediction {
    val confidence = components.map { it.confidence }.average().toFloat()
    return ModelPrediction(
      modelName = ENSEMBLE_MODEL_NAME,
      probability = components.map { it.probability }.average().toFloat(),
      isSuspicious = components.all { it.isSuspicious },
      confidence = confidence,
      entropy = components.map { it.entropy }.average(),
      lowConfidence = confidence < OnnxModelContract.LOW_CONFIDENCE_THRESHOLD,
    )
  }

  private fun binaryEntropy(probability: Float): Double {
    val p = probability.coerceIn(0.000001f, 0.999999f).toDouble()
    return -p * ln(p) - (1 - p) * ln(1 - p)
  }

  const val ENSEMBLE_MODEL_NAME = "ensemble"
}
