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

package org.smartregister.fhircore.quest.ui.questionnaire

import com.google.android.fhir.FhirEngine
import com.google.android.fhir.workflow.FhirOperator
import io.mockk.mockk
import org.hl7.fhir.r4.model.Attachment
import org.hl7.fhir.r4.model.Questionnaire
import org.hl7.fhir.r4.model.QuestionnaireResponse
import org.hl7.fhir.r4.model.StringType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.smartregister.fhircore.quest.ml.ActiveAiModel
import org.smartregister.fhircore.quest.ml.ModelCatalog
import org.smartregister.fhircore.quest.ml.ResultScreenStyle
import org.smartregister.fhircore.quest.ml.RuleSource
import org.smartregister.fhircore.quest.ml.VoteBlock
import org.smartregister.fhircore.quest.ml.Votes
import org.smartregister.fhircore.quest.ml.VotesNeeded
import org.smartregister.fhircore.engine.configuration.ConfigurationRegistry
import org.smartregister.fhircore.engine.data.local.DefaultRepository
import org.smartregister.fhircore.engine.rulesengine.ResourceDataRulesExecutor
import org.smartregister.fhircore.engine.sync.SyncBroadcaster
import org.smartregister.fhircore.engine.task.FhirCarePlanGenerator
import org.smartregister.fhircore.engine.util.DispatcherProvider
import org.smartregister.fhircore.engine.util.SecureSharedPreference
import org.smartregister.fhircore.engine.util.SharedPreferencesHelper
import org.smartregister.fhircore.engine.util.fhirpath.FhirPathDataExtractor
import org.smartregister.fhircore.engine.util.helper.TransformSupportServices
import org.smartregister.fhircore.quest.util.CASE_LEVEL_AI_MODEL_LINK_ID
import org.smartregister.fhircore.quest.util.CASE_LEVEL_AI_RULE_LINK_ID
import org.smartregister.fhircore.quest.util.CONFIDENCE_PERCENTAGE_URL
import org.smartregister.fhircore.quest.util.SUSPICIOUS_NON_SUSPICIOUS_URL

class QuestionnaireViewModelAiSummaryTest {

  private fun QuestionnaireResponse.answersOf(linkId: String) =
    item.filter { it.linkId == linkId }.map { it.answerFirstRep.value.primitiveValue() }

  @Test
  fun `the case records the model id and the rule id as answers`() {
    val model = ModelCatalog.THREE_CLASS
    val response =
      QuestionnaireResponse().apply { addItem(attachmentResponseItem("image-1", "first.jpg")) }

    response.recordAiModelAndRule(ActiveAiModel(model, model.defaultRule, RuleSource.SHIPPED))

    assertEquals(listOf("oral-3class-v1"), response.answersOf(CASE_LEVEL_AI_MODEL_LINK_ID))
    assertEquals(listOf("oral-3class-v1/m1-m2-table"), response.answersOf(CASE_LEVEL_AI_RULE_LINK_ID))
    assertEquals("the photo answers are left alone", 3, response.item.size)
  }

  @Test
  fun `recording the model and rule again replaces the earlier values`() {
    val threeClass = ModelCatalog.THREE_CLASS
    val binary = ModelCatalog.BINARY
    val response = QuestionnaireResponse()

    response.recordAiModelAndRule(ActiveAiModel(threeClass, threeClass.defaultRule, RuleSource.SHIPPED))
    response.recordAiModelAndRule(ActiveAiModel(binary, binary.defaultRule, RuleSource.SHIPPED))

    assertEquals(listOf("oral-binary-v1"), response.answersOf(CASE_LEVEL_AI_MODEL_LINK_ID))
    assertEquals(listOf("oral-binary-v1/all-agree"), response.answersOf(CASE_LEVEL_AI_RULE_LINK_ID))
  }

  @Test
  fun `summarizeAiInference returns case-level image aggregates`() {
    val viewModel = questionnaireViewModel()
    val questionnaire =
      Questionnaire().apply {
        addItem(aiQuestionnaireItem("image-1", "Suspicious", "70"))
        addItem(aiQuestionnaireItem("image-2", "Non-Suspicious", "55"))
      }
    val questionnaireResponse =
      QuestionnaireResponse().apply {
        addItem(attachmentResponseItem("image-1", "first.jpg"))
        addItem(attachmentResponseItem("image-2", "second.jpg"))
      }

    val summary = viewModel.summarizeAiInference(questionnaireResponse, questionnaire)

    assertTrue(summary.isSuspicious)
    assertEquals(listOf("first.jpg"), summary.suspiciousImages)
    assertEquals(2, summary.imageCount)
    assertEquals(1, summary.suspiciousImageCount)
    assertEquals(1, summary.nonSuspiciousImageCount)
    assertEquals(1, summary.lowConfidenceImageCount)
    assertEquals(62.5f, summary.meanConfidence)
  }

  @Test
  fun `a 2-class case keeps writing suspicious and non-suspicious`() {
    val viewModel = questionnaireViewModel()
    val questionnaire = Questionnaire().apply { addItem(aiQuestionnaireItem("image-1", "Non-Suspicious", "90")) }
    val questionnaireResponse = QuestionnaireResponse().apply { addItem(attachmentResponseItem("image-1", "a.jpg")) }

    val summary = viewModel.summarizeAiInference(questionnaireResponse, questionnaire)

    assertFalse(summary.isSuspicious)
    assertEquals("non-suspicious", summary.caseClass.caseValue)
    assertEquals(ModelCatalog.BINARY_MODEL_ID, summary.modelId)
    assertTrue(summary.suspiciousImages.isEmpty())
  }

  @Test
  fun `a low-risk photo refers the 3-class case (it used to read as non-suspicious)`() {
    val viewModel = questionnaireViewModel()
    val questionnaire =
      Questionnaire().apply {
        addItem(aiQuestionnaireItem("image-1", "non-suspicious", "91"))
        addItem(aiQuestionnaireItem("image-2", "low-risk-suspicious", "70"))
        addItem(aiQuestionnaireItem("image-3", "non-suspicious", "88"))
      }
    val questionnaireResponse =
      QuestionnaireResponse().apply {
        addItem(attachmentResponseItem("image-1", "first.jpg"))
        addItem(attachmentResponseItem("image-2", "second.jpg"))
        addItem(attachmentResponseItem("image-3", "third.jpg"))
      }

    val summary = viewModel.summarizeAiInference(questionnaireResponse, questionnaire, threeClass)

    assertTrue(summary.isSuspicious)
    assertEquals(ModelCatalog.LOW_RISK_SUSPICIOUS, summary.caseClass.id)
    assertEquals("low-risk-suspicious", summary.caseClass.caseValue)
    assertEquals(ResultScreenStyle.ORANGE, summary.caseClass.screen)
    assertEquals(listOf("second.jpg"), summary.suspiciousImages)
    assertEquals(1, summary.suspiciousImageCount)
    assertEquals(2, summary.nonSuspiciousImageCount)
    assertEquals(
      mapOf(ModelCatalog.NON_SUSPICIOUS to 2, ModelCatalog.LOW_RISK_SUSPICIOUS to 1),
      summary.classImageCounts,
    )
  }

  @Test
  fun `the most serious photo decides a 3-class case and leads the flagged photos`() {
    val viewModel = questionnaireViewModel()
    val questionnaire =
      Questionnaire().apply {
        addItem(aiQuestionnaireItem("image-1", "low-risk-suspicious", "70"))
        addItem(aiQuestionnaireItem("image-2", "high-risk-suspicious", "80"))
      }
    val questionnaireResponse =
      QuestionnaireResponse().apply {
        addItem(attachmentResponseItem("image-1", "low.jpg"))
        addItem(attachmentResponseItem("image-2", "high.jpg"))
      }

    val summary = viewModel.summarizeAiInference(questionnaireResponse, questionnaire, threeClass)

    assertEquals(ModelCatalog.HIGH_RISK_SUSPICIOUS, summary.caseClass.id)
    assertEquals(ResultScreenStyle.RED, summary.caseClass.screen)
    assertEquals(listOf("high.jpg", "low.jpg"), summary.suspiciousImages)
  }

  @Test
  fun `a site case rule can need low-risk on two photos`() {
    val viewModel = questionnaireViewModel()
    val twoPhotos =
      threeClass.copy(
        rule =
          threeClass.rule.copy(
            case =
              VoteBlock(
                VotesNeeded.PerClass(
                  mapOf(
                    ModelCatalog.HIGH_RISK_SUSPICIOUS to Votes.AnyOne,
                    ModelCatalog.LOW_RISK_SUSPICIOUS to Votes.AtLeast(2),
                  ),
                ),
              ),
          ),
      )
    val questionnaire =
      Questionnaire().apply {
        addItem(aiQuestionnaireItem("image-1", "low-risk-suspicious", "70"))
        addItem(aiQuestionnaireItem("image-2", "non-suspicious", "90"))
      }
    val questionnaireResponse =
      QuestionnaireResponse().apply {
        addItem(attachmentResponseItem("image-1", "first.jpg"))
        addItem(attachmentResponseItem("image-2", "second.jpg"))
      }

    val summary = viewModel.summarizeAiInference(questionnaireResponse, questionnaire, twoPhotos)

    assertEquals(ModelCatalog.NON_SUSPICIOUS, summary.caseClass.id)
    assertFalse(summary.isSuspicious)
  }

  @Test
  fun `a 3-class result kept only in the hidden ai-result item still decides the case`() {
    val viewModel = questionnaireViewModel()
    val questionnaireResponse =
      QuestionnaireResponse().apply {
        addItem(attachmentResponseItem("image-1", "only.jpg"))
        addItem(
          QuestionnaireResponse.QuestionnaireResponseItemComponent().apply {
            linkId = "image-1-ai-result"
            addAnswer().value = StringType("high-risk-suspicious|80")
          },
        )
      }

    val summary = viewModel.summarizeAiInference(questionnaireResponse, null, threeClass)

    assertEquals(ModelCatalog.HIGH_RISK_SUSPICIOUS, summary.caseClass.id)
    assertEquals(listOf("only.jpg"), summary.suspiciousImages)
    // Not counted as a classified photo: its own answer carried no result.
    assertEquals(0, summary.imageCount)
  }

  private val threeClass =
    ActiveAiModel(ModelCatalog.THREE_CLASS, ModelCatalog.THREE_CLASS.defaultRule, RuleSource.SHIPPED)

  private fun aiQuestionnaireItem(
    linkId: String,
    prediction: String,
    confidence: String,
  ): Questionnaire.QuestionnaireItemComponent =
    Questionnaire.QuestionnaireItemComponent().apply {
      this.linkId = linkId
      addExtension(SUSPICIOUS_NON_SUSPICIOUS_URL, StringType(prediction))
      addExtension(CONFIDENCE_PERCENTAGE_URL, StringType(confidence))
    }

  private fun attachmentResponseItem(
    linkId: String,
    title: String,
  ): QuestionnaireResponse.QuestionnaireResponseItemComponent =
    QuestionnaireResponse.QuestionnaireResponseItemComponent().apply {
      this.linkId = linkId
      addAnswer(
        QuestionnaireResponse.QuestionnaireResponseItemAnswerComponent().apply {
          value = Attachment().apply { this.title = title }
        },
      )
    }

  private fun questionnaireViewModel(): QuestionnaireViewModel =
    QuestionnaireViewModel(
      defaultRepository = mockk<DefaultRepository>(relaxed = true),
      dispatcherProvider = mockk<DispatcherProvider>(relaxed = true),
      fhirCarePlanGenerator = mockk<FhirCarePlanGenerator>(relaxed = true),
      resourceDataRulesExecutor = mockk<ResourceDataRulesExecutor>(relaxed = true),
      transformSupportServices = mockk<TransformSupportServices>(relaxed = true),
      sharedPreferencesHelper = mockk<SharedPreferencesHelper>(relaxed = true),
      secureSharedPreference = mockk<SecureSharedPreference>(relaxed = true),
      fhirOperator = mockk<FhirOperator>(relaxed = true),
      fhirPathDataExtractor = mockk<FhirPathDataExtractor>(relaxed = true),
      configurationRegistry = mockk<ConfigurationRegistry>(relaxed = true),
      syncBroadcaster = mockk<SyncBroadcaster>(relaxed = true),
      fhirEngine = mockk<FhirEngine>(relaxed = true),
      uploadedDocumentReferenceLedger = mockk(relaxed = true),
    )
}
