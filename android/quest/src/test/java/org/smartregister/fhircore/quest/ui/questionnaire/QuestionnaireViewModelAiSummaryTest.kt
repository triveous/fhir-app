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
import org.junit.Assert.assertTrue
import org.junit.Test
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
import org.smartregister.fhircore.quest.util.CONFIDENCE_PERCENTAGE_URL
import org.smartregister.fhircore.quest.util.SUSPICIOUS_NON_SUSPICIOUS_URL

class QuestionnaireViewModelAiSummaryTest {

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
    )
}
