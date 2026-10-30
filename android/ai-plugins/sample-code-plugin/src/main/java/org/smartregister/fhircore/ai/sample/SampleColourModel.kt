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

import android.content.Context
import javax.inject.Inject
import kotlin.math.exp
import org.smartregister.fhircore.ai.ClassDescriptor
import org.smartregister.fhircore.ai.ModelDescriptor
import org.smartregister.fhircore.ai.Outcome
import org.smartregister.fhircore.ai.ScreeningImage
import org.smartregister.fhircore.ai.ScreeningModel
import org.smartregister.fhircore.ai.ScreeningOutput

/**
 * DEMO ONLY — NOT FOR CLINICAL USE. Shows the shape of a code plugin with a colour heuristic in
 * place of a real model: a dark photo can't be assessed, a red-dominant one is "refer", anything
 * else is "no referral". A real plugin loads its weights in [warmUp] and runs them in [classify].
 */
class SampleColourModel @Inject constructor() : ScreeningModel {

  override val descriptor =
    ModelDescriptor(
      id = MODEL_ID,
      version = "0.1.0-demo",
      classes =
        listOf(
          ClassDescriptor(
            "refer",
            Outcome.POSITIVE,
            displayName = mapOf("en" to "Refer (demo)"),
            resultTitle = mapOf("en" to "Demo model: refer"),
          ),
          ClassDescriptor(
            "no_refer",
            Outcome.NEGATIVE,
            displayName = mapOf("en" to "No referral (demo)")
          ),
          ClassDescriptor(
            "retake",
            Outcome.INCONCLUSIVE,
            displayName = mapOf("en" to "Too dark — retake (demo)"),
            resultDescription =
              mapOf("en" to "The demo model could not see the photo. Retake it in better light."),
          ),
        ),
    )

  // Called once, off the main thread, before the first photo. Load weights here.
  override suspend fun warmUp(context: Context) = Unit

  // Called once per photo, off the main thread. Your own pre-processing goes here.
  override suspend fun classify(image: ScreeningImage): ScreeningOutput {
    val rgb = image.decodeRgbBytes(width = 64, height = 64)
    var red = 0.0
    var green = 0.0
    var blue = 0.0
    for (i in rgb.indices step 3) {
      red += rgb[i].toInt() and 0xFF
      green += rgb[i + 1].toInt() and 0xFF
      blue += rgb[i + 2].toInt() and 0xFF
    }
    val pixels = rgb.size / 3.0
    val brightness = (red + green + blue) / (3 * pixels)
    val redDominance = (red - (green + blue) / 2) / pixels // roughly -255..255

    // Scores in [0, 1] for every class, summing to 1.
    val retake = sigmoid((DARK_LEVEL - brightness) / 8)
    val refer = (1 - retake) * sigmoid(redDominance / 16)
    val noRefer = 1 - retake - refer
    val scores =
      mapOf(
        "refer" to refer.toFloat(),
        "no_refer" to noRefer.toFloat(),
        "retake" to retake.toFloat()
      )
    return ScreeningOutput(
      scores = scores,
      predictedClassId = scores.maxBy { it.value }.key,
      extras = mapOf("brightness" to "%.1f".format(brightness)),
    )
  }

  private fun sigmoid(x: Double) = 1 / (1 + exp(-x))

  companion object {
    const val MODEL_ID = "sample-colour"
    private const val DARK_LEVEL = 40.0
  }
}
