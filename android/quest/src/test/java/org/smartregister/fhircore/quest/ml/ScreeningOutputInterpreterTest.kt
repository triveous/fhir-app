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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreeningOutputInterpreterTest {

  /**
   * The v38 PyTorch pipeline's probabilities and result maps for docs/test-images, as recorded in
   * scripts/ai_inference_baseline.json. Fed the same probabilities, the ONNX interpreter must
   * produce the same result map byte for byte, so nothing downstream (bundle extras, FHIR
   * extensions, analytics) can tell the runtimes apart.
   */
  @Test
  fun `result map matches the PyTorch pipeline for the recorded test images`() {
    val baseline =
      listOf(
        floatArrayOf(0.9999998807907104f, 0.9999996423721313f, 1.0f) to
          legacyMap("Suspicious", "100", "99.999985", "99.99996", "100.0"),
        floatArrayOf(1.0f, 0.9999998807907104f, 1.0f) to
          legacyMap("Suspicious", "100", "100.0", "99.999985", "100.0"),
        floatArrayOf(0.9999997615814209f, 0.9999991655349731f, 1.0f) to
          legacyMap("Suspicious", "100", "99.99998", "99.999916", "100.0"),
        floatArrayOf(8.966941823018715e-05f, 0.002959915902465582f, 3.552334237610921e-05f) to
          legacyMap("Non-Suspicious", "99.9", "99.991035", "99.70401", "99.996445"),
        floatArrayOf(0.9999885559082031f, 0.9999985694885254f, 0.9952173233032227f) to
          legacyMap("Suspicious", "99.84", "99.998856", "99.999855", "99.52173"),
      )

    baseline.forEach { (probabilities, expected) ->
      assertEquals(expected, ScreeningOutputInterpreter.interpret(probabilities).toResultMap())
    }
  }

  @Test
  fun `three outputs are named v6, v8, v82 and the ensemble needs all three suspicious`() {
    val result = ScreeningOutputInterpreter.interpret(floatArrayOf(0.9f, 0.2f, 0.8f))

    assertEquals(listOf("v6", "v8", "v82"), result.components.map { it.modelName })
    assertEquals(listOf(true, false, true), result.components.map { it.isSuspicious })
    assertEquals("ensemble", result.ensemble.modelName)
    assertFalse(result.ensemble.isSuspicious)
    assertEquals("Non-Suspicious", result.ensemble.displayPrediction)
    assertEquals((90f + 80f + 80f) / 3, result.ensemble.confidence, 1e-4f)
    assertEquals((0.9f + 0.2f + 0.8f) / 3, result.ensemble.probability, 1e-6f)
  }

  @Test
  fun `confidence is taken on the predicted side and 0_5 is not suspicious`() {
    val (atThreshold, justAbove) =
      ScreeningOutputInterpreter.interpret(floatArrayOf(0.5f, 0.6f)).components

    assertFalse(atThreshold.isSuspicious)
    assertEquals(50f, atThreshold.confidence, 1e-4f)
    assertTrue(atThreshold.lowConfidence)
    assertEquals("non_suspicious", atThreshold.prediction)

    assertTrue(justAbove.isSuspicious)
    assertEquals(60f, justAbove.confidence, 1e-4f)
    assertTrue(justAbove.lowConfidence)
    assertEquals("suspicious", justAbove.prediction)
  }

  @Test
  fun `a single output becomes the ensemble with generic keys`() {
    val result = ScreeningOutputInterpreter.interpret(floatArrayOf(0.97f))

    assertEquals("component_0", result.components.single().modelName)
    assertTrue(result.ensemble.isSuspicious)
    assertEquals(result.components.single().confidence, result.ensemble.confidence, 0f)
    assertEquals(
      setOf("camera_prediction", "camera_confidence", "component0_prediction", "component0_confidence"),
      result.toResultMap().keys,
    )
  }

  @Test
  fun `raw logits are rejected instead of being clamped into a verdict`() {
    // What the v38 models emit before the Kotlin sigmoid: a graph exported without Sigmoid.
    val error =
      assertThrows(OnnxContractException::class.java) {
        ScreeningOutputInterpreter.interpret(floatArrayOf(15.6f, 14.9f, 21.3f))
      }
    assertTrue(error.message!!.contains("Sigmoid"))
  }

  @Test
  fun `non-finite, negative and empty outputs are rejected`() {
    listOf(floatArrayOf(Float.NaN), floatArrayOf(-0.2f), floatArrayOf(Float.POSITIVE_INFINITY), floatArrayOf())
      .forEach { outputs ->
        assertThrows(OnnxContractException::class.java) {
          ScreeningOutputInterpreter.interpret(outputs)
        }
      }
  }

  @Test
  fun `float slack just outside 0 to 1 is tolerated and snapped into range`() {
    val result = ScreeningOutputInterpreter.interpret(floatArrayOf(1.00005f, -0.00005f))

    assertEquals(1f, result.components[0].probability, 0f)
    assertEquals(0f, result.components[1].probability, 0f)
  }

  private fun legacyMap(
    finalPrediction: String,
    finalConfidence: String,
    model6Confidence: String,
    model8Confidence: String,
    model82Confidence: String,
  ) =
    mapOf(
      "camera_prediction" to finalPrediction,
      "camera_confidence" to finalConfidence,
      "model6_prediction" to finalPrediction,
      "model6_confidence" to model6Confidence,
      "model8_prediction" to finalPrediction,
      "model8_confidence" to model8Confidence,
      "model82_prediction" to finalPrediction,
      "model82_confidence" to model82Confidence,
    )
}
