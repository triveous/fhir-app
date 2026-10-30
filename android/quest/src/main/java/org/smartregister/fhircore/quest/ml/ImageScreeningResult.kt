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
import java.util.Locale
import kotlin.math.ln
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.smartregister.fhircore.ai.ModelDescriptor
import org.smartregister.fhircore.ai.Outcome
import org.smartregister.fhircore.ai.ScreeningOutput

/**
 * One photo's AI result, as the rest of the app sees it: the model's output resolved against its
 * descriptor (outcome, label, confidence, low-confidence flag). Travels from the camera to the form
 * as JSON and is written to FHIR by `AiResultFhirMapper`.
 *
 * Confidences are fractions in [0, 1]; [confidencePercent] gives the 0–100 form the legacy FHIR
 * extensions and analytics use.
 */
@Serializable
data class ImageScreeningResult(
  val modelId: String,
  val modelVersion: String,
  val classId: String,
  val outcome: Outcome,
  val displayName: String,
  /** The class's severity within its outcome; picks the class that describes a whole case. */
  val severity: Int = 0,
  val confidence: Float,
  val lowConfidence: Boolean,
  val entropy: Double,
  val scores: Map<String, Float>,
  val components: List<ComponentResult> = emptyList(),
  val extras: Map<String, String> = emptyMap(),
  val inferenceTimeMs: Long = 0,
) {

  val confidencePercent: Float
    get() = confidence * 100f

  /** "Suspicious" / "Non-Suspicious" / "Inconclusive": the value of the legacy result extension. */
  val legacyLabel: String
    get() = legacyLabelOf(outcome)

  /** The legacy confidence string, e.g. "94.2" (two decimals at most), as the PyTorch app wrote. */
  val legacyConfidence: String
    get() = formatPercent(confidencePercent)

  fun toJson(): String = json.encodeToString(serializer(), this)

  @Serializable
  data class ComponentResult(
    val name: String,
    val classId: String,
    val outcome: Outcome,
    val confidence: Float,
    val lowConfidence: Boolean,
    val entropy: Double,
    val scores: Map<String, Float>,
  ) {
    val confidencePercent: Float
      get() = confidence * 100f

    val legacyLabel: String
      get() = legacyLabelOf(outcome)
  }

  companion object {
    private val json = Json { ignoreUnknownKeys = true }

    fun fromJson(text: String): ImageScreeningResult = json.decodeFromString(serializer(), text)

    /**
     * Resolves [output] against [descriptor]. [output] must already have passed
     * `ScreeningOutputValidator`.
     */
    fun from(
      descriptor: ModelDescriptor,
      output: ScreeningOutput,
      inferenceTimeMs: Long,
      locale: Locale = Locale.getDefault(),
    ): ImageScreeningResult {
      val predicted = descriptor.classById(output.predictedClassId)!!
      val components =
        output.components.map { component ->
          ComponentResult(
            name = component.name,
            classId = component.predictedClassId,
            outcome = descriptor.classById(component.predictedClassId)!!.outcome,
            confidence = component.effectiveConfidence,
            lowConfidence = component.effectiveConfidence < descriptor.lowConfidenceThreshold,
            entropy = entropy(component.scores.values),
            scores = component.scores,
          )
        }
      val confidence = output.effectiveConfidence
      return ImageScreeningResult(
        modelId = descriptor.id,
        modelVersion = descriptor.version,
        classId = predicted.id,
        outcome = predicted.outcome,
        displayName = predicted.displayName(locale),
        severity = predicted.severity,
        confidence = confidence,
        lowConfidence = confidence < descriptor.lowConfidenceThreshold,
        // An ensemble reports the mean of its members' entropies, as the original pipeline did.
        entropy =
          components.takeIf { it.isNotEmpty() }?.map { it.entropy }?.average()
            ?: entropy(output.scores.values),
        scores = output.scores,
        components = components,
        extras = output.extras,
        inferenceTimeMs = inferenceTimeMs,
      )
    }

    fun legacyLabelOf(outcome: Outcome): String =
      when (outcome) {
        Outcome.POSITIVE -> LEGACY_POSITIVE
        Outcome.NEGATIVE -> LEGACY_NEGATIVE
        Outcome.INCONCLUSIVE -> LEGACY_INCONCLUSIVE
      }

    /** Reads a legacy result label back; null when it isn't one. */
    fun outcomeOfLegacyLabel(label: String?): Outcome? =
      when (label?.substringBefore("|")?.trim()?.lowercase()) {
        LEGACY_POSITIVE.lowercase() -> Outcome.POSITIVE
        LEGACY_NEGATIVE.lowercase(),
        "non_suspicious",
        "non suspicious",
        "nonsuspicious", -> Outcome.NEGATIVE
        LEGACY_INCONCLUSIVE.lowercase() -> Outcome.INCONCLUSIVE
        else -> null
      }

    /** Two decimals at most, as the original pipeline formatted confidences. */
    fun formatPercent(percent: Float): String = DecimalFormat("#.##").format(percent)

    const val LEGACY_POSITIVE = "Suspicious"
    const val LEGACY_NEGATIVE = "Non-Suspicious"
    const val LEGACY_INCONCLUSIVE = "Inconclusive"

    /**
     * Shannon entropy (natural log) of a score distribution. For two classes it equals the binary
     * entropy the original pipeline reported, including its clamp to avoid ln(0).
     */
    private fun entropy(scores: Collection<Float>): Double =
      -scores.sumOf { score ->
        val p = score.coerceIn(0.000001f, 0.999999f).toDouble()
        p * ln(p)
      }
  }
}
