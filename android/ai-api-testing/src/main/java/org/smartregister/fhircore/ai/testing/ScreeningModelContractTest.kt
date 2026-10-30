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

package org.smartregister.fhircore.ai.testing

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.smartregister.fhircore.ai.AI_API_VERSION
import org.smartregister.fhircore.ai.Outcome
import org.smartregister.fhircore.ai.ScreeningImage
import org.smartregister.fhircore.ai.ScreeningModel
import org.smartregister.fhircore.ai.ScreeningOutput
import org.smartregister.fhircore.ai.ScreeningOutputValidator

/**
 * Checks a [ScreeningModel] against every rule the app relies on. Extend it in your plugin's tests:
 * ```
 * @RunWith(RobolectricTestRunner::class) // or run it as an instrumented test
 * class MyModelContractTest : ScreeningModelContractTest() {
 *   override fun createModel() = MyModel()
 *   override fun sampleImages() = listOf(File("src/test/resources/sample1.jpg"))
 * }
 * ```
 */
abstract class ScreeningModelContractTest {

  /** A fresh instance of the model under test. */
  abstract fun createModel(): ScreeningModel

  /** Real photos (JPEG) the model should classify without error. At least one. */
  abstract fun sampleImages(): List<File>

  /** The context passed to [ScreeningModel.warmUp]. Robolectric or instrumentation provide one. */
  open fun context(): Context = ApplicationProvider.getApplicationContext()

  /** The app gives up on a photo after this long; keep the same budget here. */
  open val classifyTimeoutMs: Long = 15_000

  /** Same image, same scores: allowed difference between two runs. */
  open val determinismTolerance: Float = 1e-5f

  private lateinit var model: ScreeningModel

  @Before
  fun setUpModel() {
    model = createModel()
    runBlocking { withTimeout(WARM_UP_TIMEOUT_MS) { model.warmUp(context()) } }
  }

  @After
  fun closeModel() {
    if (::model.isInitialized) model.close()
  }

  @Test
  fun descriptorIsUsable() {
    val descriptor = model.descriptor
    assertTrue("Model id must not be blank", descriptor.id.isNotBlank())
    assertTrue("Model version must not be blank", descriptor.version.isNotBlank())
    assertTrue(
      "Model needs app API ${descriptor.minAppApiVersion} but this app provides $AI_API_VERSION",
      descriptor.minAppApiVersion <= AI_API_VERSION,
    )
    val outcomes = descriptor.classes.map { it.outcome }.toSet()
    assertTrue("At least one class must be Outcome.POSITIVE (refer)", Outcome.POSITIVE in outcomes)
    assertTrue(
      "At least one class must be Outcome.NEGATIVE or INCONCLUSIVE",
      Outcome.NEGATIVE in outcomes || Outcome.INCONCLUSIVE in outcomes,
    )
  }

  @Test
  fun everySampleIsClassifiedWithinTheContract() {
    val samples = sampleImages()
    assertFalse("sampleImages() must return at least one photo", samples.isEmpty())
    samples.forEach { file ->
      val output = classify(file)
      ScreeningOutputValidator.validate(model.descriptor, output)
    }
  }

  @Test
  fun theSamePhotoGivesTheSameResult() {
    val file = sampleImages().first()
    assertSameOutput(classify(file), classify(file))
  }

  @Test
  fun concurrentCallsGiveTheSameResultAsSequentialOnes() {
    val file = sampleImages().first()
    val sequential = classify(file)
    val concurrent = runBlocking {
      withTimeout(classifyTimeoutMs * CONCURRENT_CALLS) {
        List(CONCURRENT_CALLS) {
            async(Dispatchers.Default) { model.classify(ScreeningImage(file)) }
          }
          .awaitAll()
      }
    }
    concurrent.forEach { assertSameOutput(sequential, it) }
  }

  @Test
  fun anUnreadablePhotoThrowsInsteadOfReturningAResult() {
    val garbage = File.createTempFile("not-a-photo", ".jpg").apply { writeText("not an image") }
    try {
      val output =
        try {
          classify(garbage)
        } catch (expected: Exception) {
          return
        }
      fail("classify() returned $output for a file that is not an image; it must throw instead")
    } finally {
      garbage.delete()
    }
  }

  private fun classify(file: File): ScreeningOutput = runBlocking {
    withTimeout(classifyTimeoutMs) { model.classify(ScreeningImage(file)) }
  }

  private fun assertSameOutput(expected: ScreeningOutput, actual: ScreeningOutput) {
    assertEquals(expected.predictedClassId, actual.predictedClassId)
    assertEquals(expected.scores.keys, actual.scores.keys)
    expected.scores.forEach { (classId, score) ->
      val other = actual.scores.getValue(classId)
      assertTrue(
        "Score for '$classId' changed between runs: $score vs $other",
        abs(score - other) <= determinismTolerance,
      )
    }
  }

  private companion object {
    const val WARM_UP_TIMEOUT_MS = 60_000L
    const val CONCURRENT_CALLS = 3
  }
}
