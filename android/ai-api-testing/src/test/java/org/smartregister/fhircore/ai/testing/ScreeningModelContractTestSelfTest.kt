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
import android.content.ContextWrapper
import java.io.File
import java.io.IOException
import org.smartregister.fhircore.ai.ClassDescriptor
import org.smartregister.fhircore.ai.ModelDescriptor
import org.smartregister.fhircore.ai.Outcome
import org.smartregister.fhircore.ai.ScreeningImage
import org.smartregister.fhircore.ai.ScreeningModel
import org.smartregister.fhircore.ai.ScreeningOutput

/** Runs the kit against a well-behaved fake model, so the kit itself is known to pass. */
class ScreeningModelContractTestSelfTest : ScreeningModelContractTest() {

  override fun createModel(): ScreeningModel = JpegMagicModel()

  override fun sampleImages(): List<File> =
    listOf(
      File.createTempFile("sample", ".jpg").apply {
        writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x42))
        deleteOnExit()
      },
    )

  override fun context(): Context = ContextWrapper(null)

  /** "Classifies" by reading the first bytes; throws for anything that isn't a JPEG. */
  private class JpegMagicModel : ScreeningModel {
    override val descriptor =
      ModelDescriptor(
        id = "jpeg-magic",
        version = "1",
        classes =
          listOf(
            ClassDescriptor("refer", Outcome.POSITIVE),
            ClassDescriptor("fine", Outcome.NEGATIVE),
          ),
      )

    override suspend fun warmUp(context: Context) = Unit

    override suspend fun classify(image: ScreeningImage): ScreeningOutput {
      val bytes = image.file.readBytes()
      if (bytes.size < 3 || bytes[0] != 0xFF.toByte() || bytes[1] != 0xD8.toByte()) {
        throw IOException("Not a JPEG")
      }
      val refer = (bytes.last().toInt() and 0xFF) / 255f
      return ScreeningOutput(
        scores = mapOf("refer" to refer, "fine" to 1 - refer),
        predictedClassId = if (refer > 0.5f) "refer" else "fine",
      )
    }
  }
}
