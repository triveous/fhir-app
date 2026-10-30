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

package org.smartregister.fhircore.quest.ai

import java.math.BigDecimal
import org.hl7.fhir.r4.model.BooleanType
import org.hl7.fhir.r4.model.CodeType
import org.hl7.fhir.r4.model.DecimalType
import org.hl7.fhir.r4.model.Extension
import org.hl7.fhir.r4.model.IntegerType
import org.hl7.fhir.r4.model.StringType
import org.smartregister.fhircore.ai.Outcome
import org.smartregister.fhircore.quest.ml.ImageScreeningResult
import org.smartregister.fhircore.quest.util.CONFIDENCE_PERCENTAGE_URL
import org.smartregister.fhircore.quest.util.SUSPICIOUS_NON_SUSPICIOUS_URL

/**
 * What a photo's stored AI extensions say, whichever form they were written in.
 *
 * @property failed AI was active for the photo but produced no result.
 * @property confidencePercentText the confidence as written in the legacy extension, e.g. "94.2".
 */
data class StoredAiResult(
  val failed: Boolean,
  val outcome: Outcome?,
  val classId: String? = null,
  val modelId: String? = null,
  val legacyLabel: String? = null,
  val confidencePercentText: String? = null,
  val severity: Int = 0,
  /** As the model judged it; null for results stored before models declared their own threshold. */
  val lowConfidence: Boolean? = null,
) {
  val confidencePercent: Float?
    get() = confidencePercentText?.replace("%", "")?.trim()?.toFloatOrNull()
}

/**
 * Writes a photo's AI result to FHIR and reads it back. Pure, so the format is unit tested.
 *
 * Each photo gets one generic complex extension, [AI_SCREENING_RESULT_URL], that works for any
 * model: model id/version, class, outcome, confidence, every class score, ensemble members and the
 * model's extras. The legacy extensions are written next to it — the Suspicious/Non-Suspicious
 * label, the confidence, and the per-model `v38-model-v6/v8/v82` values when the members carry those
 * names — because the server StructureMaps, dashboards and older app versions read them.
 */
object AiResultFhirMapper {

  const val AI_SCREENING_RESULT_URL =
    "http://smartregister.org/fhir/StructureDefinition/ai-screening-result"

  // Sub-extension urls of AI_SCREENING_RESULT_URL.
  const val STATUS = "status"
  const val MODEL_ID = "modelId"
  const val MODEL_VERSION = "modelVersion"
  const val CLASS_ID = "classId"
  const val OUTCOME = "outcome"
  const val DISPLAY_NAME = "displayName"
  const val SEVERITY = "severity"
  const val CONFIDENCE = "confidence"
  const val LOW_CONFIDENCE = "lowConfidence"
  const val INFERENCE_TIME_MS = "inferenceTimeMs"
  const val SCORE = "score"
  const val SCORE_CLASS = "class"
  const val SCORE_VALUE = "value"
  const val COMPONENT = "component"
  const val COMPONENT_NAME = "name"
  const val EXTRA = "extra"
  const val EXTRA_KEY = "key"
  const val EXTRA_VALUE = "value"

  const val STATUS_COMPLETED = "completed"
  const val STATUS_FAILED = "failed"

  /** Legacy per-model extensions, written for ensemble members named v6, v8 and v82. */
  private val LEGACY_COMPONENT_URLS =
    mapOf(
      "v6" to (MODEL6_PREDICTION_URL to MODEL6_CONFIDENCE_URL),
      "v8" to (MODEL8_PREDICTION_URL to MODEL8_CONFIDENCE_URL),
      "v82" to (MODEL82_PREDICTION_URL to MODEL82_CONFIDENCE_URL),
    )

  /** Every url this mapper writes on an element; remove them all before writing a new result. */
  val ALL_URLS: List<String> =
    listOf(AI_SCREENING_RESULT_URL, SUSPICIOUS_NON_SUSPICIOUS_URL, CONFIDENCE_PERCENTAGE_URL) +
      LEGACY_COMPONENT_URLS.values.flatMap { listOf(it.first, it.second) }

  /** Extensions for a photo the model classified. */
  fun extensionsFor(result: ImageScreeningResult): List<Extension> {
    val generic =
      Extension(AI_SCREENING_RESULT_URL).apply {
        addExtension(STATUS, CodeType(STATUS_COMPLETED))
        addExtension(MODEL_ID, StringType(result.modelId))
        addExtension(MODEL_VERSION, StringType(result.modelVersion))
        addExtension(CLASS_ID, StringType(result.classId))
        addExtension(OUTCOME, CodeType(result.outcome.code))
        addExtension(DISPLAY_NAME, StringType(result.displayName))
        addExtension(SEVERITY, IntegerType(result.severity))
        addExtension(CONFIDENCE, decimal(result.confidence))
        addExtension(LOW_CONFIDENCE, BooleanType(result.lowConfidence))
        addExtension(INFERENCE_TIME_MS, IntegerType(result.inferenceTimeMs.toInt()))
        result.scores.forEach { (classId, score) -> addExtension(scoreExtension(classId, score)) }
        result.components.forEach { component ->
          addExtension(
            Extension(COMPONENT).apply {
              addExtension(COMPONENT_NAME, StringType(component.name))
              addExtension(CLASS_ID, StringType(component.classId))
              addExtension(OUTCOME, CodeType(component.outcome.code))
              addExtension(CONFIDENCE, decimal(component.confidence))
              component.scores.forEach { (classId, score) -> addExtension(scoreExtension(classId, score)) }
            },
          )
        }
        result.extras.forEach { (key, value) ->
          addExtension(
            Extension(EXTRA).apply {
              addExtension(EXTRA_KEY, StringType(key))
              addExtension(EXTRA_VALUE, StringType(value))
            },
          )
        }
      }

    val legacy =
      mutableListOf(
        Extension(SUSPICIOUS_NON_SUSPICIOUS_URL, StringType(result.legacyLabel)),
        Extension(CONFIDENCE_PERCENTAGE_URL, StringType(result.legacyConfidence)),
      )
    result.components.forEach { component ->
      LEGACY_COMPONENT_URLS[component.name]?.let { (predictionUrl, confidenceUrl) ->
        legacy += Extension(predictionUrl, StringType(component.legacyLabel))
        legacy += Extension(confidenceUrl, StringType(component.confidencePercent.toString()))
      }
    }
    return listOf(generic) + legacy
  }

  /** Extensions for a photo AI was meant to classify but couldn't. */
  fun failedExtensions(modelId: String? = null): List<Extension> =
    listOf(
      Extension(AI_SCREENING_RESULT_URL).apply {
        addExtension(STATUS, CodeType(STATUS_FAILED))
        modelId?.let { addExtension(MODEL_ID, StringType(it)) }
      },
    )

  /**
   * Reads a stored result through [extensionByUrl] (e.g. `element::getExtensionByUrl`). Prefers the
   * generic extension and falls back to the legacy ones, so drafts saved by older app versions
   * still summarise. Null when the element carries no AI result.
   */
  fun read(extensionByUrl: (String) -> Extension?): StoredAiResult? {
    val legacyLabel = extensionByUrl(SUSPICIOUS_NON_SUSPICIOUS_URL)?.value?.primitiveValue()?.takeIf { it.isNotBlank() }
    val legacyConfidence = extensionByUrl(CONFIDENCE_PERCENTAGE_URL)?.value?.primitiveValue()?.takeIf { it.isNotBlank() }
    val generic = extensionByUrl(AI_SCREENING_RESULT_URL)
    if (generic != null) {
      fun sub(url: String) = generic.getExtensionByUrl(url)?.value?.primitiveValue()?.takeIf { it.isNotBlank() }
      if (sub(STATUS) == STATUS_FAILED) {
        return StoredAiResult(failed = true, outcome = null, modelId = sub(MODEL_ID))
      }
      val outcome = outcomeOfCode(sub(OUTCOME)) ?: ImageScreeningResult.outcomeOfLegacyLabel(legacyLabel)
      return StoredAiResult(
        failed = false,
        outcome = outcome,
        classId = sub(CLASS_ID),
        modelId = sub(MODEL_ID),
        legacyLabel = legacyLabel ?: outcome?.let(ImageScreeningResult::legacyLabelOf),
        confidencePercentText =
          legacyConfidence
            ?: sub(CONFIDENCE)?.toFloatOrNull()?.let { ImageScreeningResult.formatPercent(it * 100f) },
        severity = sub(SEVERITY)?.toIntOrNull() ?: 0,
        lowConfidence = sub(LOW_CONFIDENCE)?.toBooleanStrictOrNull(),
      )
    }
    if (legacyLabel == null) return null
    return StoredAiResult(
      failed = false,
      outcome = ImageScreeningResult.outcomeOfLegacyLabel(legacyLabel),
      legacyLabel = legacyLabel,
      confidencePercentText = legacyConfidence,
    )
  }

  private fun scoreExtension(classId: String, score: Float) =
    Extension(SCORE).apply {
      addExtension(SCORE_CLASS, StringType(classId))
      addExtension(SCORE_VALUE, decimal(score))
    }

  private fun decimal(value: Float) = DecimalType(BigDecimal(value.toString()))

  /** FHIR code of an outcome: positive, negative, inconclusive. */
  val Outcome.code: String
    get() = name.lowercase()

  fun outcomeOfCode(code: String?): Outcome? = Outcome.values().firstOrNull { it.code == code }
}

internal const val MODEL6_PREDICTION_URL =
  "http://smartregister.org/ai-model-result/v38-model-v6-prediction"

internal const val MODEL6_CONFIDENCE_URL =
  "http://smartregister.org/ai-model-result/v38-model-v6-confidence"

internal const val MODEL8_PREDICTION_URL =
  "http://smartregister.org/ai-model-result/v38-model-v8-prediction"

internal const val MODEL8_CONFIDENCE_URL =
  "http://smartregister.org/ai-model-result/v38-model-v8-confidence"

internal const val MODEL82_PREDICTION_URL =
  "http://smartregister.org/ai-model-result/v38-model-v82-prediction"

internal const val MODEL82_CONFIDENCE_URL =
  "http://smartregister.org/ai-model-result/v38-model82-confidence"
