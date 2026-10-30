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

package org.smartregister.fhircore.ai

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ScreeningOutputValidatorTest {

  private val descriptor =
    ModelDescriptor(
      id = "three-class",
      version = "1",
      classes =
        listOf(
          ClassDescriptor("benign", Outcome.NEGATIVE, mapOf("en" to "Benign", "hi" to "सौम्य")),
          ClassDescriptor("opmd", Outcome.POSITIVE),
          ClassDescriptor("poor", Outcome.INCONCLUSIVE),
        ),
    )

  @Test
  fun acceptsAValidOutput() {
    ScreeningOutputValidator.validate(
      descriptor,
      ScreeningOutput(mapOf("benign" to 0.7f, "opmd" to 0.2f, "poor" to 0.1f), "benign"),
    )
  }

  @Test
  fun rejectsAMissingClassScore() {
    assertThrows(InvalidScreeningOutputException::class.java) {
      ScreeningOutputValidator.validate(
        descriptor,
        ScreeningOutput(mapOf("benign" to 0.7f, "opmd" to 0.3f), "benign"),
      )
    }
  }

  @Test
  fun rejectsLogits() {
    assertThrows(InvalidScreeningOutputException::class.java) {
      ScreeningOutputValidator.validate(
        descriptor,
        ScreeningOutput(mapOf("benign" to 3.2f, "opmd" to -1f, "poor" to 0f), "benign"),
      )
    }
  }

  @Test
  fun rejectsAnUnknownPrediction() {
    assertThrows(InvalidScreeningOutputException::class.java) {
      ScreeningOutputValidator.validate(
        descriptor,
        ScreeningOutput(mapOf("benign" to 0.7f, "opmd" to 0.2f, "poor" to 0.1f), "malignant"),
      )
    }
  }

  @Test
  fun rejectsAnInvalidComponent() {
    assertThrows(InvalidScreeningOutputException::class.java) {
      ScreeningOutputValidator.validate(
        descriptor,
        ScreeningOutput(
          scores = mapOf("benign" to 0.7f, "opmd" to 0.2f, "poor" to 0.1f),
          predictedClassId = "benign",
          components = listOf(ComponentOutput("a", mapOf("benign" to Float.NaN), "benign")),
        ),
      )
    }
  }

  @Test
  fun confidenceDefaultsToThePredictedScore() {
    val output = ScreeningOutput(mapOf("benign" to 0.7f, "opmd" to 0.2f, "poor" to 0.1f), "benign")
    assertEquals(0.7f, output.effectiveConfidence)
    assertEquals(0.9f, output.copy(confidence = 0.9f).effectiveConfidence)
  }

  @Test
  fun displayNameFallsBackFromLanguageToEnglishToId() {
    val benign = descriptor.classById("benign")!!
    assertEquals("सौम्य", benign.displayName(Locale("hi", "IN")))
    assertEquals("Benign", benign.displayName(Locale.FRENCH))
    assertEquals("opmd", descriptor.classById("opmd")!!.displayName(Locale.ENGLISH))
  }

  @Test
  fun descriptorRejectsDuplicateClasses() {
    assertThrows(IllegalArgumentException::class.java) {
      ModelDescriptor(
        "dup",
        "1",
        listOf(ClassDescriptor("a", Outcome.POSITIVE), ClassDescriptor("a", Outcome.NEGATIVE)),
      )
    }
  }
}
