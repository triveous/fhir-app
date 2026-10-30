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

package org.smartregister.fhircore.ai.sample

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import org.smartregister.fhircore.ai.ScreeningImage
import org.smartregister.fhircore.ai.ScreeningModel
import org.smartregister.fhircore.ai.testing.ScreeningModelContractTest

/** Every rule of the contract, from the kit, plus what this demo model should say. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // Real JPEG decoding, so the colours are the photo's.
class SampleColourModelContractTest : ScreeningModelContractTest() {

  override fun createModel(): ScreeningModel = SampleColourModel()

  override fun sampleImages(): List<File> =
    listOf("reddish.jpg", "pale.jpg", "dark.jpg").map(::resource)

  @Test
  fun `the demo heuristic calls the sample photos as expected`() = runBlocking {
    val model = SampleColourModel()
    assertEquals("refer", model.classify(ScreeningImage(resource("reddish.jpg"))).predictedClassId)
    assertEquals("no_refer", model.classify(ScreeningImage(resource("pale.jpg"))).predictedClassId)
    assertEquals("retake", model.classify(ScreeningImage(resource("dark.jpg"))).predictedClassId)
  }

  private fun resource(name: String) = File(checkNotNull(javaClass.getResource("/$name")).toURI())
}
