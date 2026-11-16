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
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
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
    // The 3-class members reuse the same three per-model FHIR fields.
    assertEquals(
      binary.members.map { it.resultKeyPrefix },
      ModelCatalog.THREE_CLASS.members.map { it.resultKeyPrefix },
    )
  }
}
