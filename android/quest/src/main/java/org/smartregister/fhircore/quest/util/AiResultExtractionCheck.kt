package org.smartregister.fhircore.quest.util

import com.google.android.fhir.FhirEngine
import com.google.android.fhir.datacapture.extensions.targetStructureMap
import org.hl7.fhir.r4.model.Questionnaire
import org.hl7.fhir.r4.model.StructureMap
import org.smartregister.fhircore.engine.util.FeatureFlagUtil
import org.smartregister.fhircore.engine.util.extension.loadResource

/** Where AI stands for new cases on this device. Users only ever see "AI", never the StructureMap. */
enum class AiReadiness {
  /** The site runs without AI (feature flag off). Cases are registered normally; nothing to fix. */
  DISABLED,

  /** Flag on and the StructureMap extracts the AI result: AI is enabled. */
  READY,

  /**
   * Flag on but the StructureMap cannot extract the AI result (a sync missed the current map), so a
   * case registered now would silently lose its AI result. The only state that blocks adding a case.
   */
  UPDATE_NEEDED,
}

/**
 * Both halves of AI on this device, read independently, for the profile screen's diagnostic label.
 * Unlike [aiReadiness] the extraction is checked even with the flag off, so a support person can tell
 * a wrong flag from a missing extraction.
 */
data class AiStatus(val flagEnabled: Boolean, val extractionEnabled: Boolean)

suspend fun FhirEngine.aiStatus(featureFlagUtil: FeatureFlagUtil, questionnaireId: String): AiStatus =
  AiStatus(
    flagEnabled = featureFlagUtil.isAiInferenceEnabled(),
    extractionEnabled = hasAiResultExtraction(questionnaireId),
  )

/**
 * [AiReadiness] for cases registered with [questionnaireId]. The flag is read first: on a site with AI
 * off the StructureMap is irrelevant, so it is not even loaded.
 */
suspend fun FhirEngine.aiReadiness(featureFlagUtil: FeatureFlagUtil, questionnaireId: String): AiReadiness =
  when {
    !featureFlagUtil.isAiInferenceEnabled() -> AiReadiness.DISABLED
    hasAiResultExtraction(questionnaireId) -> AiReadiness.READY
    else -> AiReadiness.UPDATE_NEEDED
  }

/**
 * Whether a case registered with [questionnaireId] right now would get its case-level AI result
 * extracted.
 *
 * The AI result reaches the Encounter only through the registration StructureMap rule that reads the
 * [CASE_LEVEL_AI_RESULT_LINK_ID] answer (server StructureMap v3, 2026-09-03). The map is a synced
 * server resource, so a device whose sync failed keeps the older map and registers cases without the
 * AI result — silently, because the rest of the extraction still succeeds. This follows the same path
 * as extraction (Questionnaire → targetStructureMap → local StructureMap), so a missing questionnaire
 * or map counts as "not available" too: both are fixed by the same sync.
 */
suspend fun FhirEngine.hasAiResultExtraction(questionnaireId: String): Boolean {
  val questionnaire = loadResource<Questionnaire>(questionnaireId) ?: return false
  val structureMapId = questionnaire.targetStructureMap?.substringAfterLast("/") ?: return false
  val structureMap = loadResource<StructureMap>(structureMapId) ?: return false
  return structureMap.hasCaseLevelAiResultRule()
}

/**
 * Looks at the rules themselves rather than the narrative or rule names: a rule whose source selects
 * the [CASE_LEVEL_AI_RESULT_LINK_ID] item is what copies the AI result, whatever it is called.
 */
fun StructureMap.hasCaseLevelAiResultRule(): Boolean =
  group.any { group -> group.rule.anyRule { rule -> rule.selectsCaseLevelAiResult() } }

private fun List<StructureMap.StructureMapGroupRuleComponent>.anyRule(
  predicate: (StructureMap.StructureMapGroupRuleComponent) -> Boolean,
): Boolean = any { predicate(it) || it.rule.anyRule(predicate) }

private fun StructureMap.StructureMapGroupRuleComponent.selectsCaseLevelAiResult() =
  source.any { it.condition?.contains(CASE_LEVEL_AI_RESULT_LINK_ID) == true }
