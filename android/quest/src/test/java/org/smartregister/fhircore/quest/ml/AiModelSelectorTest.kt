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

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.smartregister.fhircore.engine.util.AiModelConfig
import org.smartregister.fhircore.engine.util.FeatureFlagUtil

class AiModelSelectorTest {

  private val featureFlagUtil = mockk<FeatureFlagUtil>()
  private val onnxEngine = mockk<OnnxInferenceEngine>()
  private lateinit var selector: AiModelSelector

  @Before
  fun setUp() {
    every { onnxEngine.isBundled(any()) } returns false
    selector = AiModelSelector(featureFlagUtil, onnxEngine)
  }

  private fun ready(selection: AiModelSelection): ActiveAiModel {
    assertTrue("expected Ready, got $selection", selection is AiModelSelection.Ready)
    return (selection as AiModelSelection.Ready).active
  }

  @Test
  fun `no model set runs today's 2-class model with its shipped rule`() {
    val active = ready(selector.select(AiModelConfig.NONE))

    assertSame(ModelCatalog.BINARY, active.model)
    assertSame(ModelCatalog.BINARY.defaultRule, active.rule)
    assertEquals(RuleSource.SHIPPED, active.ruleSource)
  }

  @Test
  fun `the 2-class model runs without its onnx files (PyTorch fallback)`() {
    ready(selector.select(AiModelConfig(ModelCatalog.BINARY_MODEL_ID)))
  }

  @Test
  fun `an unknown model id is unavailable, never a silent fallback`() {
    val selection = selector.select(AiModelConfig("oral-4class-v1"))

    assertEquals(
      AiModelSelection.Unavailable("oral-4class-v1", AiModelSelection.Reason.UNKNOWN_MODEL),
      selection,
    )
  }

  @Test
  fun `the 3-class model is unavailable until its onnx files are bundled`() {
    assertEquals(
      AiModelSelection.Unavailable(ModelCatalog.THREE_CLASS_MODEL_ID, AiModelSelection.Reason.NOT_BUNDLED),
      selector.select(AiModelConfig(ModelCatalog.THREE_CLASS_MODEL_ID)),
    )

    every { onnxEngine.isBundled(ModelCatalog.THREE_CLASS) } returns true
    assertSame(ModelCatalog.THREE_CLASS, ready(selector.select(AiModelConfig(ModelCatalog.THREE_CLASS_MODEL_ID))).model)
  }

  @Test
  fun `a valid remote rule replaces the shipped rule`() {
    every { onnxEngine.isBundled(ModelCatalog.THREE_CLASS) } returns true
    val rule =
      """{"schema":1,"id":"site/any","version":4,"modelId":"oral-3class-v1","photo":{"votesNeeded":"any"}}"""

    val active = ready(selector.select(AiModelConfig(ModelCatalog.THREE_CLASS_MODEL_ID, rule)))

    assertEquals(RuleSource.REMOTE, active.ruleSource)
    assertEquals("site/any", active.rule.id)
    assertEquals(4, active.rule.version)
  }

  @Test
  fun `an invalid remote rule falls back to the shipped rule`() {
    every { onnxEngine.isBundled(ModelCatalog.THREE_CLASS) } returns true
    val forBinary =
      """{"schema":1,"id":"x","version":1,"modelId":"oral-binary-v1","photo":{"votesNeeded":"any"}}"""

    val active = ready(selector.select(AiModelConfig(ModelCatalog.THREE_CLASS_MODEL_ID, forBinary)))

    assertEquals(RuleSource.SHIPPED, active.ruleSource)
    assertSame(ModelCatalog.THREE_CLASS.defaultRule, active.rule)
  }

  @Test
  fun `a screening keeps its selection even if the site's settings change mid-screening`() = runTest {
    every { onnxEngine.isBundled(ModelCatalog.THREE_CLASS) } returns true
    coEvery { featureFlagUtil.getAiModelConfig() } returnsMany
      listOf(AiModelConfig.NONE, AiModelConfig(ModelCatalog.THREE_CLASS_MODEL_ID))

    val first = selector.forScreening("screening-1")
    val again = selector.forScreening("screening-1")

    assertSame(first, again)
    assertSame(ModelCatalog.BINARY, ready(again).model)
    coVerify(exactly = 1) { featureFlagUtil.getAiModelConfig() }

    // A new screening picks up the new settings.
    assertSame(ModelCatalog.THREE_CLASS, ready(selector.forScreening("screening-2")).model)
  }

  @Test
  fun `the add-case check reads only the settings stored on the device`() = runTest {
    coEvery { featureFlagUtil.getAiModelConfigLocally() } returns AiModelConfig("oral-4class-v1")

    val selection = selector.currentLocally()

    assertTrue(selection is AiModelSelection.Unavailable)
    coVerify(exactly = 0) { featureFlagUtil.getAiModelConfig() }
  }

  @Test
  fun `saved values map to classes across models`() {
    assertEquals(ModelCatalog.SUSPICIOUS, ModelCatalog.classForSavedValue("Suspicious")?.id)
    assertEquals(ModelCatalog.SUSPICIOUS, ModelCatalog.classForSavedValue("suspicious|94.2")?.id)
    assertEquals(ModelCatalog.NON_SUSPICIOUS, ModelCatalog.classForSavedValue("Non-Suspicious")?.id)
    assertEquals(ModelCatalog.LOW_RISK_SUSPICIOUS, ModelCatalog.classForSavedValue("low-risk-suspicious")?.id)
    assertTrue(ModelCatalog.classForSavedValue("low-risk-suspicious")!!.isRefer)
    assertNull(ModelCatalog.classForSavedValue("Error"))
    assertNull(ModelCatalog.classForSavedValue(""))
  }

  @Test
  fun `a 2-class value read under the 3-class model never downgrades a referral`() {
    val mapped = ModelCatalog.classInModel("Suspicious", ModelCatalog.THREE_CLASS)

    assertEquals(ModelCatalog.HIGH_RISK_SUSPICIOUS, mapped?.id)
    assertEquals(
      ModelCatalog.NON_SUSPICIOUS,
      ModelCatalog.classInModel("Non-Suspicious", ModelCatalog.THREE_CLASS)?.id,
    )
    // And a 3-class value read under the 2-class model keeps its outcome.
    assertEquals(
      ModelCatalog.SUSPICIOUS,
      ModelCatalog.classInModel("low-risk-suspicious", ModelCatalog.BINARY)?.id,
    )
  }

  @Test
  fun `the 2-class model saves exactly today's values`() {
    val binary = ModelCatalog.BINARY
    assertEquals("Suspicious", binary.classById(ModelCatalog.SUSPICIOUS)?.savedValue)
    assertEquals("suspicious", binary.classById(ModelCatalog.SUSPICIOUS)?.caseValue)
    assertEquals("Non-Suspicious", binary.classById(ModelCatalog.NON_SUSPICIOUS)?.savedValue)
    assertEquals("non-suspicious", binary.classById(ModelCatalog.NON_SUSPICIOUS)?.caseValue)
    assertEquals(listOf("model6", "model8", "model82"), binary.members.map { it.resultKeyPrefix })
    // The two 3-class members reuse two of the same per-model FHIR fields.
    assertEquals(listOf("model6", "model8"), ModelCatalog.THREE_CLASS.members.map { it.resultKeyPrefix })
  }

  @Test
  fun `a site can run one model of the 3-class pair or both`() {
    every { onnxEngine.isBundled(ModelCatalog.THREE_CLASS) } returns true
    val model = ModelCatalog.THREE_CLASS
    fun membersFor(setting: String?) =
      ready(selector.select(AiModelConfig(model.id, members = setting))).let { active ->
        active.model.activeMembers(active.rule).map { it.id }
      }

    assertEquals(listOf("m1", "m2"), membersFor(null))
    assertEquals(listOf("m1", "m2"), membersFor("m1,m2"))
    assertEquals(listOf("m1"), membersFor("m1"))
    assertEquals(listOf("m2"), membersFor(" m2 "))
    // Members run in model order whatever order the site lists them in.
    assertEquals(listOf("m1", "m2"), membersFor("m2, m1"))
  }

  @Test
  fun `a selection says whether the site set its rule and why a setting was ignored`() {
    every { onnxEngine.isBundled(ModelCatalog.THREE_CLASS) } returns true
    val threeClass = ModelCatalog.THREE_CLASS_MODEL_ID
    val goodRule =
      """{"schema":1,"id":"site/any","version":4,"modelId":"oral-3class-v1","photo":{"votesNeeded":"any"}}"""
    fun active(config: AiModelConfig) = ready(selector.select(config))

    active(AiModelConfig(threeClass)).let {
      assertEquals(false, it.ruleSetBySite)
      assertNull(it.ignoredSetting)
    }
    assertEquals(true, active(AiModelConfig(threeClass, goodRule)).ruleSetBySite)
    assertEquals(true, active(AiModelConfig(threeClass, members = "m1")).ruleSetBySite)
    active(AiModelConfig(threeClass, rule = "not json")).let {
      assertEquals(false, it.ruleSetBySite)
      assertEquals("rule: rule is not a JSON object", it.ignoredSetting)
    }
    active(AiModelConfig(threeClass, members = "m9")).let {
      assertEquals(false, it.ruleSetBySite)
      assertEquals("members names m9, which model oral-3class-v1 does not have", it.ignoredSetting)
    }
  }

  @Test
  fun `the QA feature-flag files give the results their README lists`() {
    every { onnxEngine.isBundled(ModelCatalog.THREE_CLASS) } returns true
    val dir =
      listOf(File("../docs/qa/ai-3class-remote"), File("docs/qa/ai-3class-remote")).firstOrNull { it.isDirectory }
    assumeTrue("QA folder not found from ${File(".").absolutePath}", dir != null)

    fun config(file: String): AiModelConfig {
      val extensions = Json.parseToJsonElement(File(dir, file).readText()).jsonObject["extension"]!!.jsonArray
      val aiModel =
        extensions.map { it.jsonObject }.firstOrNull { it["url"]!!.jsonPrimitive.content.endsWith("/ai-model") }
          ?: return AiModelConfig.NONE
      val values =
        aiModel["extension"]!!.jsonArray.associate {
          it.jsonObject["url"]!!.jsonPrimitive.content to it.jsonObject["valueString"]!!.jsonPrimitive.content
        }
      return AiModelConfig(values["id"], values["rule"], values["members"])
    }
    // model | models running | rule | rule set by the flag | ignored setting
    fun shown(file: String): String =
      when (val selection = selector.select(config(file))) {
        is AiModelSelection.Ready ->
          selection.active.run {
            val members = model.activeMembers(rule).joinToString(",") { it.id }
            "${model.id} | $members | ${rule.id} | $ruleSetBySite | $ignoredSetting"
          }
        is AiModelSelection.Unavailable -> "${selection.modelId} | ${selection.reason.telemetryName}"
      }

    assertEquals("oral-binary-v1 | v6,v8,v82 | oral-binary-v1/all-agree | false | null", shown("01-2class-default.json"))
    assertEquals("oral-3class-v1 | m1,m2 | oral-3class-v1/m1-m2-table | false | null", shown("02-3class-both-models.json"))
    assertEquals("oral-3class-v1 | m1 | oral-3class-v1/m1-only | true | null", shown("03-3class-m1-only.json"))
    assertEquals("oral-3class-v1 | m2 | oral-3class-v1/m2-only | true | null", shown("04-3class-m2-only.json"))
    assertEquals("oral-3class-v1 | m1,m2 | oral-3class-v1/m1-m2-any | true | null", shown("05-3class-rule-from-flag.json"))
    assertEquals(
      "oral-3class-v1 | m1,m2 | oral-3class-v1/m1-m2-table | false | rule: schema 2 is newer than this app supports",
      shown("06-3class-invalid-rule.json"),
    )
    assertEquals("oral-4class-v1 | unknown_model", shown("07-unknown-model.json"))
    assertEquals("oral-3class-v1 | m1,m2 | oral-3class-v1/m1-m2-table | false | null", shown("08-ai-off.json"))
  }

  @Test
  fun `the rule id says whether the table or one member decided`() {
    every { onnxEngine.isBundled(ModelCatalog.THREE_CLASS) } returns true
    fun ruleId(config: AiModelConfig) = ready(selector.select(config)).rule.id
    val threeClass = ModelCatalog.THREE_CLASS_MODEL_ID

    assertEquals("oral-3class-v1/m1-m2-table", ruleId(AiModelConfig(threeClass)))
    assertEquals("oral-3class-v1/m1-m2-table", ruleId(AiModelConfig(threeClass, members = "m1,m2")))
    assertEquals("oral-3class-v1/m1-only", ruleId(AiModelConfig(threeClass, members = "m1")))
    assertEquals("oral-3class-v1/m2-only", ruleId(AiModelConfig(threeClass, members = "m2")))
    assertEquals("oral-binary-v1/all-agree", ruleId(AiModelConfig.NONE))
    assertEquals("oral-binary-v1/v8-only", ruleId(AiModelConfig(null, members = "v8")))
  }

  @Test
  fun `one member running alone gives its own answer and keeps the rule's case block and source`() {
    every { onnxEngine.isBundled(ModelCatalog.THREE_CLASS) } returns true
    val model = ModelCatalog.THREE_CLASS

    val active = ready(selector.select(AiModelConfig(model.id, members = "m2")))

    assertEquals(listOf("m2"), active.rule.members)
    assertEquals(model.defaultRule.case, active.rule.case)
    assertEquals(RuleSource.SHIPPED, active.ruleSource)
    model.classes.forEach { answer ->
      assertEquals(
        answer,
        CombineRuleEvaluator.decidePhoto(model.classes, listOf(answer.id), active.rule.photo),
      )
    }
  }

  @Test
  fun `a members setting that fails the checks is ignored, so every member runs`() {
    every { onnxEngine.isBundled(ModelCatalog.THREE_CLASS) } returns true

    listOf("m3", "m1,m1", " , ", "m1;m2").forEach { setting ->
      val active = ready(selector.select(AiModelConfig(ModelCatalog.THREE_CLASS_MODEL_ID, members = setting)))

      assertSame("for '$setting'", ModelCatalog.THREE_CLASS.defaultRule, active.rule)
    }
  }

  @Test
  fun `the members setting wins over a remote rule's own members and applies to the 2-class model`() {
    val rule =
      """{"schema":1,"id":"site/two","version":1,"modelId":"oral-binary-v1","members":["v6","v8"],"photo":{"votesNeeded":"all"}}"""

    val active = ready(selector.select(AiModelConfig(ModelCatalog.BINARY_MODEL_ID, rule, members = "v82")))

    assertEquals(RuleSource.REMOTE, active.ruleSource)
    assertEquals(listOf("v82"), active.model.activeMembers(active.rule).map { it.id })
  }
}
