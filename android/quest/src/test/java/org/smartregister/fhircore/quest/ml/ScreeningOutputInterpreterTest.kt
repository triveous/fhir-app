/*
 * Copyright 2026 Indian Institute of Science (IISc)
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

  private val binary = ModelCatalog.BINARY
  private val threeClass = ModelCatalog.THREE_CLASS

  private fun interpretBinary(vararg probabilities: Float, rule: CombineRule = binary.defaultRule) =
    ScreeningOutputInterpreter.interpret(
      binary,
      rule,
      RuleSource.SHIPPED,
      probabilities.map { floatArrayOf(it) },
    )

  private fun interpretThreeClass(vararg members: FloatArray, rule: CombineRule = threeClass.defaultRule) =
    ScreeningOutputInterpreter.interpret(threeClass, rule, RuleSource.SHIPPED, members.toList())

  /**
   * The v38 PyTorch pipeline's probabilities and result maps for docs/test-images, as recorded in
   * scripts/ai_inference_baseline.json. Fed the same probabilities, the interpreter must produce the
   * same FHIR-bound result keys byte for byte, so 2-class sites see no change.
   */
  @Test
  fun `2-class result map matches the PyTorch pipeline for the recorded test images`() {
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
      val resultMap = interpretBinary(*probabilities).toResultMap()
      assertEquals(expected, resultMap.filterKeys { it in expected.keys })
    }
  }

  @Test
  fun `2-class members are v6, v8, v82 and the shipped rule needs all three suspicious`() {
    val result = interpretBinary(0.9f, 0.2f, 0.8f)

    assertEquals(listOf("v6", "v8", "v82"), result.members.map { it.member.id })
    assertEquals(listOf(true, false, true), result.members.map { it.predictedClass.isRefer })
    assertFalse(result.finalClass.isRefer)
    assertEquals("Non-Suspicious", result.finalClass.savedValue)
    assertEquals((90f + 80f + 80f) / 3, result.confidence, 1e-4f)
    assertEquals((0.9f + 0.2f + 0.8f) / 3, result.probability, 1e-6f)
  }

  @Test
  fun `confidence is taken on the predicted side and 0_5 is not suspicious`() {
    val (atThreshold, justAbove) = interpretBinary(0.5f, 0.6f, 0.1f).members

    assertFalse(atThreshold.predictedClass.isRefer)
    assertEquals(50f, atThreshold.confidence, 1e-4f)
    assertTrue(atThreshold.lowConfidence)
    assertEquals("non_suspicious", atThreshold.predictedClass.telemetryName)

    assertTrue(justAbove.predictedClass.isRefer)
    assertEquals(60f, justAbove.confidence, 1e-4f)
    assertTrue(justAbove.lowConfidence)
    assertEquals("suspicious", justAbove.predictedClass.telemetryName)
  }

  @Test
  fun `a remote majority rule changes the 2-class photo result`() {
    val majority =
      binary.defaultRule.copy(
        id = "binary/majority",
        photo = VoteBlock(VotesNeeded.PerClass(mapOf(ModelCatalog.SUSPICIOUS to Votes.Majority))),
      )

    assertEquals("Suspicious", interpretBinary(0.9f, 0.2f, 0.8f, rule = majority).finalClass.savedValue)
  }

  @Test
  fun `a rule that drops a member is fed one output per remaining member`() {
    val twoMembers = binary.defaultRule.copy(members = listOf("v6", "v82"))

    val result = interpretBinary(0.9f, 0.8f, rule = twoMembers)

    assertEquals(listOf("v6", "v82"), result.members.map { it.member.id })
    assertEquals(setOf("model6_prediction", "model82_prediction"), result.toResultMap().keys.filter { it.startsWith("model") && it.endsWith("_prediction") }.toSet())
    assertTrue(result.finalClass.isRefer)
  }

  @Test
  fun `3-class softmax members are combined by the shipped rule`() {
    // The plan's worked example: m1 high-risk, m2 low-risk, m3 non-suspicious.
    val result =
      interpretThreeClass(
        floatArrayOf(0.1f, 0.2f, 0.7f),
        floatArrayOf(0.2f, 0.6f, 0.2f),
        floatArrayOf(0.8f, 0.1f, 0.1f),
      )

    assertEquals(
      listOf(ModelCatalog.HIGH_RISK_SUSPICIOUS, ModelCatalog.LOW_RISK_SUSPICIOUS, ModelCatalog.NON_SUSPICIOUS),
      result.members.map { it.predictedClass.id },
    )
    // High-risk needs all 3 (has 1); at-least-low-risk needs a majority (has 2).
    assertEquals(ModelCatalog.LOW_RISK_SUSPICIOUS, result.finalClass.id)
    assertTrue(result.finalClass.isRefer)
    // Confidence = mean probability the members give the final class.
    assertEquals((0.2f + 0.6f + 0.1f) / 3 * 100, result.confidence, 1e-3f)

    val map = result.toResultMap()
    assertEquals("low-risk-suspicious", map["camera_prediction"])
    assertEquals("high-risk-suspicious", map["model6_prediction"])
    assertEquals("low-risk-suspicious", map["model8_prediction"])
    assertEquals("non-suspicious", map["model82_prediction"])
    assertEquals("70.0", map["model6_confidence"])
    assertEquals(ModelCatalog.THREE_CLASS_MODEL_ID, map["model_id"])
    assertEquals("low-risk-suspicious", map["ai_class"])
  }

  @Test
  fun `3-class all high-risk is high-risk and all agreeing non-suspicious is non-suspicious`() {
    val high = floatArrayOf(0.05f, 0.15f, 0.8f)
    val none = floatArrayOf(0.9f, 0.05f, 0.05f)

    assertEquals(ModelCatalog.HIGH_RISK_SUSPICIOUS, interpretThreeClass(high, high, high).finalClass.id)
    assertEquals(ModelCatalog.NON_SUSPICIOUS, interpretThreeClass(none, none, none).finalClass.id)
  }

  @Test
  fun `a softmax tie goes to the more serious class`() {
    val tie = floatArrayOf(0.2f, 0.4f, 0.4f)

    val member = interpretThreeClass(tie, tie, tie).members.first()

    assertEquals(ModelCatalog.HIGH_RISK_SUSPICIOUS, member.predictedClass.id)
  }

  @Test
  fun `softmax outputs that do not sum to 1 are rejected as missing Softmax`() {
    val error =
      assertThrows(OnnxContractException::class.java) {
        interpretThreeClass(
          floatArrayOf(0.9f, 0.9f, 0.9f),
          floatArrayOf(0.1f, 0.1f, 0.8f),
          floatArrayOf(0.1f, 0.1f, 0.8f),
        )
      }
    assertTrue(error.message!!.contains("Softmax"))
  }

  @Test
  fun `wrong output sizes and member counts are rejected`() {
    assertThrows(OnnxContractException::class.java) {
      interpretThreeClass(floatArrayOf(0.5f, 0.5f), floatArrayOf(0.5f, 0.5f), floatArrayOf(0.5f, 0.5f))
    }
    assertThrows(OnnxContractException::class.java) { interpretBinary(0.9f, 0.9f) }
  }

  @Test
  fun `raw logits are rejected instead of being clamped into a verdict`() {
    // What the v38 models emit before the Kotlin sigmoid: a graph exported without Sigmoid.
    val error = assertThrows(OnnxContractException::class.java) { interpretBinary(15.6f, 14.9f, 21.3f) }
    assertTrue(error.message!!.contains("Sigmoid"))
  }

  @Test
  fun `non-finite, negative and empty outputs are rejected`() {
    listOf(Float.NaN, -0.2f, Float.POSITIVE_INFINITY).forEach { bad ->
      assertThrows(OnnxContractException::class.java) { interpretBinary(bad, 0.5f, 0.5f) }
    }
    assertThrows(OnnxContractException::class.java) {
      ScreeningOutputInterpreter.interpret(
        binary,
        binary.defaultRule,
        RuleSource.SHIPPED,
        listOf(floatArrayOf(), floatArrayOf(0.5f), floatArrayOf(0.5f)),
      )
    }
  }

  @Test
  fun `float slack just outside 0 to 1 is tolerated and snapped into range`() {
    val result = interpretBinary(1.00005f, -0.00005f, 0.5f)

    assertEquals(1f, result.members[0].probability, 0f)
    assertEquals(0f, result.members[1].probability, 0f)
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
