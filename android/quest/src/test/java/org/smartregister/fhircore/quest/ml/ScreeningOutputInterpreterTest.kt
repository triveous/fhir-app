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

import kotlin.math.exp
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
  fun `3-class members emit logits and are combined by the shipped table`() {
    // m1 says high-risk, m2 says non-suspicious: the table gives low-risk.
    val result = interpretThreeClass(floatArrayOf(-1f, 0f, 3f), floatArrayOf(4f, 1f, -2f))

    assertEquals(listOf("m1", "m2"), result.members.map { it.member.id })
    assertEquals(
      listOf(ModelCatalog.HIGH_RISK_SUSPICIOUS, ModelCatalog.NON_SUSPICIOUS),
      result.members.map { it.predictedClass.id },
    )
    assertEquals(ModelCatalog.LOW_RISK_SUSPICIOUS, result.finalClass.id)
    assertTrue(result.finalClass.isRefer)

    // Softmax is applied by the app: exp(3) / (exp(-1) + exp(0) + exp(3)).
    val m1 = softmax(-1f, 0f, 3f)
    val m2 = softmax(4f, 1f, -2f)
    assertEquals(m1[2], result.members[0].probability, 1e-6f)
    assertEquals(1f, result.members[0].classProbabilities.values.sum(), 1e-5f)
    // Confidence = mean probability the members give the final class.
    assertEquals((m1[1] + m2[1]) / 2 * 100, result.confidence, 1e-3f)
    assertTrue(result.lowConfidence)

    val map = result.toResultMap()
    assertEquals("low-risk-suspicious", map["camera_prediction"])
    assertEquals("high-risk-suspicious", map["model6_prediction"])
    assertEquals("non-suspicious", map["model8_prediction"])
    assertFalse(map.containsKey("model82_prediction"))
    assertEquals((m1[2] * 100).toString(), map["model6_confidence"])
    assertEquals(ModelCatalog.THREE_CLASS_MODEL_ID, map["model_id"])
    assertEquals("low-risk-suspicious", map["ai_class"])
    assertEquals("m1,m2", map["ai_members"])
  }

  @Test
  fun `3-class members that agree give their class`() {
    val high = floatArrayOf(-2f, 0f, 5f)
    val none = floatArrayOf(6f, 0f, -3f)

    assertEquals(ModelCatalog.HIGH_RISK_SUSPICIOUS, interpretThreeClass(high, high).finalClass.id)
    assertEquals(ModelCatalog.NON_SUSPICIOUS, interpretThreeClass(none, none).finalClass.id)
  }

  @Test
  fun `a site running one 3-class member gets that member's answer`() {
    val onlyM2 = threeClass.defaultRule.copy(members = listOf("m2"))

    val result = interpretThreeClass(floatArrayOf(-2f, 0f, 5f), rule = onlyM2)

    assertEquals(listOf("m2"), result.members.map { it.member.id })
    assertEquals(ModelCatalog.HIGH_RISK_SUSPICIOUS, result.finalClass.id)
    val map = result.toResultMap()
    assertEquals("high-risk-suspicious", map["model8_prediction"])
    assertFalse(map.containsKey("model6_prediction"))
    assertEquals("m2", map["ai_members"])
    assertEquals(softmax(-2f, 0f, 5f)[2] * 100, result.confidence, 1e-3f)
  }

  @Test
  fun `a tie goes to the more serious class`() {
    val tie = floatArrayOf(0f, 2f, 2f)

    val member = interpretThreeClass(tie, tie).members.first()

    assertEquals(ModelCatalog.HIGH_RISK_SUSPICIOUS, member.predictedClass.id)
  }

  @Test
  fun `non-finite logits are rejected`() {
    assertThrows(OnnxContractException::class.java) {
      interpretThreeClass(floatArrayOf(Float.NaN, 0f, 1f), floatArrayOf(0f, 0f, 1f))
    }
  }

  @Test
  fun `a model with Softmax in its graph must emit values that sum to 1`() {
    val inGraph =
      threeClass.copy(
        outputLayout = (threeClass.outputLayout as OutputLayout.Softmax).copy(appliedInGraph = true),
      )
    val interpret = { m1: FloatArray, m2: FloatArray ->
      ScreeningOutputInterpreter.interpret(inGraph, inGraph.defaultRule, RuleSource.SHIPPED, listOf(m1, m2))
    }

    assertEquals(
      ModelCatalog.HIGH_RISK_SUSPICIOUS,
      interpret(floatArrayOf(0.1f, 0.2f, 0.7f), floatArrayOf(0.2f, 0.6f, 0.2f)).finalClass.id,
    )
    val error =
      assertThrows(OnnxContractException::class.java) {
        interpret(floatArrayOf(0.9f, 0.9f, 0.9f), floatArrayOf(0.1f, 0.1f, 0.8f))
      }
    assertTrue(error.message!!.contains("Softmax"))
  }

  @Test
  fun `wrong output sizes and member counts are rejected`() {
    assertThrows(OnnxContractException::class.java) {
      interpretThreeClass(floatArrayOf(0.5f, 0.5f), floatArrayOf(0.5f, 0.5f))
    }
    assertThrows(OnnxContractException::class.java) { interpretThreeClass(floatArrayOf(0f, 0f, 1f)) }
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

  private fun softmax(vararg logits: Float): List<Float> {
    val exponentials = logits.map { exp(it.toDouble()) }
    return exponentials.map { (it / exponentials.sum()).toFloat() }
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
