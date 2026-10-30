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

import android.content.Context
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.runs
import io.mockk.unmockkAll
import io.mockk.verify
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.smartregister.fhircore.ai.ClassDescriptor
import org.smartregister.fhircore.ai.ModelDescriptor
import org.smartregister.fhircore.ai.Outcome
import org.smartregister.fhircore.ai.ScreeningImage
import org.smartregister.fhircore.ai.ScreeningModel
import org.smartregister.fhircore.ai.ScreeningOutput
import org.smartregister.fhircore.engine.util.FeatureFlagUtil
import org.smartregister.fhircore.quest.util.PostHogAnalytics
import org.smartregister.fhircore.quest.util.ScreeningTimer

class ScreeningEngineTest {

  private val featureFlagUtil = mockk<FeatureFlagUtil>()
  private val photo = File("photo.jpg")

  @Before
  fun setUp() {
    mockkObject(PostHogAnalytics, ScreeningTimer)
    every { PostHogAnalytics.capture(any(), any()) } just runs
    every { PostHogAnalytics.captureError(any(), any(), any()) } just runs
    every { ScreeningTimer.markStep(any(), any()) } just runs
    coEvery { featureFlagUtil.isAiInferenceEnabled() } returns true
    coEvery { featureFlagUtil.getAiModelId() } returns null
  }

  @After
  fun tearDown() {
    unmockkAll()
  }

  @Test
  fun `with no model installed AI is off even when the tenant flag is on`() = runBlocking {
    val engine = engine()

    assertEquals(AiModelState.NOT_INSTALLED, engine.status().state)
    assertFalse(engine.isAiActive())
    assertNull(engine.screen(photo, "s1"))
  }

  @Test
  fun `the only installed model is used, warmed up once, and its result resolved`() = runBlocking {
    val model = FakeModel("acme")
    val engine = engine(model)

    assertTrue(engine.isAiActive())
    assertEquals(AiModelState.NOT_LOADED, engine.status().state)
    val result = engine.screen(photo, "s1")!!
    engine.screen(photo, "s1")

    assertEquals(1, model.warmUps)
    assertEquals(AiModelState.READY, engine.status().state)
    assertEquals("acme", result.modelId)
    assertEquals(Outcome.POSITIVE, result.outcome)
    assertEquals("Refer", result.displayName)
    assertEquals(0.8f, result.confidence)
    verify { PostHogAnalytics.capture(PostHogAnalytics.Events.MODEL_INFERENCE_COMPLETED, any()) }
  }

  @Test
  fun `AI flag off means AI is not active`() = runBlocking {
    coEvery { featureFlagUtil.isAiInferenceEnabled() } returns false
    assertFalse(engine(FakeModel("acme")).isAiActive())
  }

  @Test
  fun `several models need a choice - tenant flag first, then the build default`() = runBlocking {
    val engine = engine(FakeModel("a"), FakeModel("b"))
    assertEquals(AiModelState.AMBIGUOUS, engine.status().state)

    engine.defaultModelId = "b"
    assertEquals("b", engine.status().modelId)

    coEvery { featureFlagUtil.getAiModelId() } returns "a"
    assertEquals("a", engine.status().modelId)
    assertEquals("a", engine.screen(photo, null)!!.modelId)
  }

  @Test
  fun `a tenant asking for a model the app lacks gets no AI and a reason`() = runBlocking {
    coEvery { featureFlagUtil.getAiModelId() } returns "missing"
    val engine = engine(FakeModel("acme"))

    val status = engine.status()
    assertEquals(AiModelState.NOT_INSTALLED, status.state)
    assertTrue(status.problem!!.contains("acme"))
    assertFalse(engine.isAiActive())
  }

  @Test
  fun `a model that fails to load is reported and retried, and stays selected`() = runBlocking {
    val model = FakeModel("acme", failLoads = 1)
    val engine = engine(model)

    assertNull(engine.screen(photo, null))
    assertEquals(AiModelState.LOAD_FAILED, engine.status().state)
    // Still selected: its photos are recorded as "AI failed" rather than skipping AI silently.
    assertTrue(engine.isAiActive())

    assertNotNull(engine.screen(photo, null))
    assertEquals(AiModelState.READY, engine.status().state)
    assertEquals(2, model.warmUps)
  }

  @Test
  fun `output that breaks the contract becomes no result`() = runBlocking {
    val engine = engine(FakeModel("acme", output = ScreeningOutput(mapOf("refer" to 3f, "fine" to 0f), "refer")))

    assertNull(engine.screen(photo, null))
    verify { PostHogAnalytics.captureError("ScreeningEngine", any(), any()) }
  }

  @Test
  fun `a model that throws becomes no result`() = runBlocking {
    assertNull(engine(FakeModel("acme", throwOnClassify = true)).screen(photo, null))
  }

  @Test
  fun `a model slower than the timeout becomes no result`() = runBlocking {
    val engine = engine(FakeModel("acme", delayMs = 2_000))
    engine.classifyTimeoutMs = 50

    assertNull(engine.screen(photo, null))
  }

  @Test
  fun `a plugin registered under another id is unusable`() = runBlocking {
    val engine = engine(pluginMap = mapOf("other" to FakeModel("acme")))

    assertEquals(AiModelState.LOAD_FAILED, engine.status().state)
    assertNull(engine.screen(photo, null))
  }


  private fun engine(vararg models: FakeModel, pluginMap: Map<String, ScreeningModel>? = null) =
    ScreeningEngine(
      context = mockk<Context>(relaxed = true),
      pluginModels = pluginMap ?: models.associateBy { it.descriptor.id },
      featureFlagUtil = featureFlagUtil,
    ).apply { defaultModelId = "" }

  private class FakeModel(
    id: String,
    private val output: ScreeningOutput = ScreeningOutput(mapOf("refer" to 0.8f, "fine" to 0.2f), "refer"),
    private var failLoads: Int = 0,
    private val throwOnClassify: Boolean = false,
    private val delayMs: Long = 0,
  ) : ScreeningModel {
    var warmUps = 0

    override val descriptor =
      ModelDescriptor(
        id,
        "1",
        listOf(
          ClassDescriptor("refer", Outcome.POSITIVE, mapOf("en" to "Refer")),
          ClassDescriptor("fine", Outcome.NEGATIVE, mapOf("en" to "Fine")),
        ),
      )

    override suspend fun warmUp(context: Context) {
      warmUps++
      if (failLoads > 0) {
        failLoads--
        throw IllegalStateException("weights missing")
      }
    }

    override suspend fun classify(image: ScreeningImage): ScreeningOutput {
      if (delayMs > 0) delay(delayMs)
      if (throwOnClassify) throw IllegalArgumentException("cannot decode")
      return output
    }
  }
}
