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

import java.util.Locale
import org.hl7.fhir.r4.model.Extension
import org.hl7.fhir.r4.model.QuestionnaireResponse
import org.hl7.fhir.r4.model.StringType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.smartregister.fhircore.ai.ClassDescriptor
import org.smartregister.fhircore.ai.ComponentOutput
import org.smartregister.fhircore.ai.ModelDescriptor
import org.smartregister.fhircore.ai.Outcome
import org.smartregister.fhircore.ai.ScreeningOutput
import org.smartregister.fhircore.quest.ml.ImageScreeningResult
import org.smartregister.fhircore.quest.util.CONFIDENCE_PERCENTAGE_URL
import org.smartregister.fhircore.quest.util.SUSPICIOUS_NON_SUSPICIOUS_URL

class AiResultFhirMapperTest {

  private val ensemble =
    ImageScreeningResult.from(
      ModelDescriptor(
        "acme-oral",
        "1.0.0",
        listOf(
          ClassDescriptor("suspicious", Outcome.POSITIVE, mapOf("en" to "Suspicious")),
          ClassDescriptor("non_suspicious", Outcome.NEGATIVE, mapOf("en" to "Non-Suspicious")),
        ),
      ),
      ScreeningOutput(
        scores = mapOf("suspicious" to 0.9f, "non_suspicious" to 0.1f),
        predictedClassId = "suspicious",
        confidence = 0.942f,
        components =
          listOf(
            ComponentOutput("v6", mapOf("suspicious" to 0.95f, "non_suspicious" to 0.05f), "suspicious"),
            ComponentOutput("v8", mapOf("suspicious" to 0.9f, "non_suspicious" to 0.1f), "suspicious"),
            ComponentOutput("v82", mapOf("suspicious" to 0.85f, "non_suspicious" to 0.15f), "suspicious"),
          ),
        extras = mapOf("runtime" to "onnx"),
      ),
      inferenceTimeMs = 120,
      locale = Locale.ENGLISH,
    )

  @Test
  fun `writes the generic extension and the legacy ones the server reads`() {
    val byUrl = AiResultFhirMapper.extensionsFor(ensemble).associateBy { it.url }

    assertEquals("Suspicious", byUrl.string(SUSPICIOUS_NON_SUSPICIOUS_URL))
    assertEquals("94.2", byUrl.string(CONFIDENCE_PERCENTAGE_URL))
    assertEquals("Suspicious", byUrl.string(MODEL6_PREDICTION_URL))
    assertEquals("95.0", byUrl.string(MODEL6_CONFIDENCE_URL))
    assertEquals("85.0", byUrl.string(MODEL82_CONFIDENCE_URL))

    val generic = byUrl.getValue(AiResultFhirMapper.AI_SCREENING_RESULT_URL)
    assertEquals("acme-oral", generic.sub(AiResultFhirMapper.MODEL_ID))
    assertEquals("positive", generic.sub(AiResultFhirMapper.OUTCOME))
    assertEquals(2, generic.getExtensionsByUrl(AiResultFhirMapper.SCORE).size)
    assertEquals(3, generic.getExtensionsByUrl(AiResultFhirMapper.COMPONENT).size)
    assertEquals(1, generic.getExtensionsByUrl(AiResultFhirMapper.EXTRA).size)
  }

  @Test
  fun `an ensemble with other member names gets no legacy per-model extensions`() {
    val other = ensemble.copy(components = ensemble.components.map { it.copy(name = "member-${it.name}") })
    val urls = AiResultFhirMapper.extensionsFor(other).map { it.url }

    assertFalse(MODEL6_PREDICTION_URL in urls)
    assertTrue(SUSPICIOUS_NON_SUSPICIOUS_URL in urls)
  }

  @Test
  fun `reads back what it wrote`() {
    val answer = QuestionnaireResponse.QuestionnaireResponseItemAnswerComponent()
    AiResultFhirMapper.extensionsFor(ensemble).forEach { answer.addExtension(it) }

    val stored = AiResultFhirMapper.read(answer::getExtensionByUrl)!!

    assertFalse(stored.failed)
    assertEquals(Outcome.POSITIVE, stored.outcome)
    assertEquals("suspicious", stored.classId)
    assertEquals("acme-oral", stored.modelId)
    assertEquals(94.2f, stored.confidencePercent)
    assertEquals(false, stored.lowConfidence)
  }

  @Test
  fun `reads results written by the PyTorch app`() {
    val answer =
      QuestionnaireResponse.QuestionnaireResponseItemAnswerComponent().apply {
        addExtension(SUSPICIOUS_NON_SUSPICIOUS_URL, StringType("Non-Suspicious"))
        addExtension(CONFIDENCE_PERCENTAGE_URL, StringType("71.5"))
      }

    val stored = AiResultFhirMapper.read(answer::getExtensionByUrl)!!

    assertEquals(Outcome.NEGATIVE, stored.outcome)
    assertEquals(71.5f, stored.confidencePercent)
    assertNull(stored.lowConfidence)
  }

  @Test
  fun `a failed photo is recorded and read back as failed`() {
    val answer = QuestionnaireResponse.QuestionnaireResponseItemAnswerComponent()
    AiResultFhirMapper.failedExtensions("acme-oral").forEach { answer.addExtension(it) }

    val stored = AiResultFhirMapper.read(answer::getExtensionByUrl)!!

    assertTrue(stored.failed)
    assertNull(stored.outcome)
  }

  @Test
  fun `no AI extensions reads as no result`() {
    assertNull(AiResultFhirMapper.read(QuestionnaireResponse.QuestionnaireResponseItemAnswerComponent()::getExtensionByUrl))
  }

  @Test
  fun `the result survives the camera-to-form JSON hop`() {
    assertEquals(ensemble, ImageScreeningResult.fromJson(ensemble.toJson()))
  }

  @Test
  fun `the ensemble entropy is the mean of the members' binary entropies`() {
    val p = 0.95
    val v6 = -p * kotlin.math.ln(p) - (1 - p) * kotlin.math.ln(1 - p)
    assertEquals(v6, ensemble.components[0].entropy, 1e-6)
    assertEquals(ensemble.components.map { it.entropy }.average(), ensemble.entropy, 1e-9)
  }

  private fun Map<String, Extension>.string(url: String) = (getValue(url).value as StringType).value

  private fun Extension.sub(url: String) = getExtensionByUrl(url).value.primitiveValue()
}
