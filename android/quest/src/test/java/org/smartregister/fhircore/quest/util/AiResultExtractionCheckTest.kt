package org.smartregister.fhircore.quest.util

import ca.uhn.fhir.context.FhirContext
import com.google.android.fhir.FhirEngine
import com.google.android.fhir.db.ResourceNotFoundException
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.hl7.fhir.r4.model.CanonicalType
import org.hl7.fhir.r4.model.Questionnaire
import org.hl7.fhir.r4.model.ResourceType
import org.hl7.fhir.r4.model.StructureMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.smartregister.fhircore.engine.util.FeatureFlagUtil

class AiResultExtractionCheckTest {

  private val fhirEngine = mockk<FhirEngine>()
  private val featureFlagUtil = mockk<FeatureFlagUtil>()

  // The fixtures are the registration StructureMap as deployed on staging before (v2) and after (v3)
  // the case-level AI result rule was added, so they pin the check to the real server change.
  private fun structureMapFixture(name: String): StructureMap =
    FhirContext.forR4Cached()
      .newJsonParser()
      .parseResource(
        StructureMap::class.java,
        javaClass.classLoader!!.getResource("structuremap/$name.json")!!.readText(),
      )

  private val mapWithAiResult = structureMapFixture("oral_cancer_registration_v3_with_ai_result")
  private val mapWithoutAiResult = structureMapFixture("oral_cancer_registration_v2_without_ai_result")

  private fun registrationQuestionnaire(targetStructureMap: String? = STRUCTURE_MAP_URL) =
    Questionnaire().apply {
      id = QUESTIONNAIRE_ID
      targetStructureMap?.let {
        addExtension(TARGET_STRUCTURE_MAP_EXTENSION, CanonicalType(it))
      }
    }

  private fun givenLocal(questionnaire: Questionnaire?, structureMap: StructureMap?) {
    if (questionnaire == null) {
      coEvery { fhirEngine.get(ResourceType.Questionnaire, QUESTIONNAIRE_ID) } throws
        ResourceNotFoundException("Questionnaire", QUESTIONNAIRE_ID)
    } else {
      coEvery { fhirEngine.get(ResourceType.Questionnaire, QUESTIONNAIRE_ID) } returns questionnaire
    }
    if (structureMap == null) {
      coEvery { fhirEngine.get(ResourceType.StructureMap, STRUCTURE_MAP_ID) } throws
        ResourceNotFoundException("StructureMap", STRUCTURE_MAP_ID)
    } else {
      coEvery { fhirEngine.get(ResourceType.StructureMap, STRUCTURE_MAP_ID) } returns structureMap
    }
  }

  @Test
  fun deployedMapWithCaseLevelAiResultRuleIsDetected() {
    assertTrue(mapWithAiResult.hasCaseLevelAiResultRule())
  }

  @Test
  fun deployedMapFromBeforeTheAiResultRuleIsNotDetected() {
    // v2 already copies the per-image "-ai-result" answers onto Media; those must not count
    assertFalse(mapWithoutAiResult.hasCaseLevelAiResultRule())
  }

  @Test
  fun ruleNestedAtAnyDepthIsDetected() {
    val map =
      StructureMap().apply {
        addGroup().addRule().addRule().addRule().addSource().apply {
          context = "src"
          element = "item"
          condition = "(linkId = '$CASE_LEVEL_AI_RESULT_LINK_ID')"
        }
      }
    assertTrue(map.hasCaseLevelAiResultRule())
  }

  @Test
  fun extractionIsAvailableWhenTheLocalMapHasTheRule() = runTest {
    givenLocal(registrationQuestionnaire(), mapWithAiResult)
    assertTrue(fhirEngine.hasAiResultExtraction(QUESTIONNAIRE_ID))
  }

  @Test
  fun extractionIsUnavailableWhenTheLocalMapIsStale() = runTest {
    givenLocal(registrationQuestionnaire(), mapWithoutAiResult)
    assertFalse(fhirEngine.hasAiResultExtraction(QUESTIONNAIRE_ID))
  }

  @Test
  fun extractionIsUnavailableWhenTheMapHasNotSynced() = runTest {
    givenLocal(registrationQuestionnaire(), structureMap = null)
    assertFalse(fhirEngine.hasAiResultExtraction(QUESTIONNAIRE_ID))
  }

  @Test
  fun extractionIsUnavailableWhenTheQuestionnaireHasNotSynced() = runTest {
    givenLocal(questionnaire = null, structureMap = mapWithAiResult)
    assertFalse(fhirEngine.hasAiResultExtraction(QUESTIONNAIRE_ID))
  }

  @Test
  fun extractionIsUnavailableWhenTheQuestionnaireHasNoTargetStructureMap() = runTest {
    givenLocal(registrationQuestionnaire(targetStructureMap = null), mapWithAiResult)
    assertFalse(fhirEngine.hasAiResultExtraction(QUESTIONNAIRE_ID))
  }

  @Test
  fun readinessIsDisabledWhenTheFlagIsOffWhateverTheMap() = runTest {
    coEvery { featureFlagUtil.isAiInferenceEnabled() } returns false
    givenLocal(registrationQuestionnaire(), mapWithoutAiResult)
    assertEquals(AiReadiness.DISABLED, fhirEngine.aiReadiness(featureFlagUtil, QUESTIONNAIRE_ID))
  }

  @Test
  fun readinessIsReadyWhenTheFlagIsOnAndTheMapExtracts() = runTest {
    coEvery { featureFlagUtil.isAiInferenceEnabled() } returns true
    givenLocal(registrationQuestionnaire(), mapWithAiResult)
    assertEquals(AiReadiness.READY, fhirEngine.aiReadiness(featureFlagUtil, QUESTIONNAIRE_ID))
  }

  @Test
  fun readinessNeedsAnUpdateWhenTheFlagIsOnAndTheMapIsStale() = runTest {
    coEvery { featureFlagUtil.isAiInferenceEnabled() } returns true
    givenLocal(registrationQuestionnaire(), mapWithoutAiResult)
    assertEquals(AiReadiness.UPDATE_NEEDED, fhirEngine.aiReadiness(featureFlagUtil, QUESTIONNAIRE_ID))
  }

  @Test
  fun statusReportsTheExtractionEvenWhenTheFlagIsOff() = runTest {
    coEvery { featureFlagUtil.isAiInferenceEnabled() } returns false
    givenLocal(registrationQuestionnaire(), mapWithAiResult)
    assertEquals(
      AiStatus(flagEnabled = false, extractionEnabled = true),
      fhirEngine.aiStatus(featureFlagUtil, QUESTIONNAIRE_ID),
    )
  }

  @Test
  fun statusReportsAMissingExtractionWithTheFlagOn() = runTest {
    coEvery { featureFlagUtil.isAiInferenceEnabled() } returns true
    givenLocal(registrationQuestionnaire(), mapWithoutAiResult)
    assertEquals(
      AiStatus(flagEnabled = true, extractionEnabled = false),
      fhirEngine.aiStatus(featureFlagUtil, QUESTIONNAIRE_ID),
    )
  }

  private companion object {
    const val QUESTIONNAIRE_ID = "OralCancerPatientRegistration"
    const val STRUCTURE_MAP_ID = "OralCancerPatientRegistration"
    const val STRUCTURE_MAP_URL = "https://midas.iisc.ac.in/fhir/StructureMap/$STRUCTURE_MAP_ID"
    const val TARGET_STRUCTURE_MAP_EXTENSION =
      "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-targetStructureMap"
  }
}
