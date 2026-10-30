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

package org.smartregister.fhircore.quest.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.smartregister.fhircore.ai.Outcome

class CaseAggregatorTest {

  @Test
  fun `one positive photo makes the case positive`() {
    val summary =
      CaseAggregator()
        .apply {
          addPhoto("a", "a.jpg", result(Outcome.NEGATIVE, "benign", 90f))
          addPhoto("b", "b.jpg", result(Outcome.POSITIVE, "opmd", 70f, severity = 1))
          addPhoto("c", "c.jpg", result(Outcome.POSITIVE, "malignant", 60f, severity = 2))
        }
        .summary()

    assertEquals(Outcome.POSITIVE, summary.outcome)
    assertTrue(summary.isSuspicious)
    // Most severe first.
    assertEquals(listOf("c.jpg", "b.jpg"), summary.suspiciousImages)
    assertEquals("malignant", summary.caseClassId)
    assertEquals(3, summary.imageCount)
    assertEquals(2, summary.suspiciousImageCount)
    assertEquals(1, summary.lowConfidenceImageCount) // 60 < 65
    assertEquals(73.333336f, summary.meanConfidence, 1e-4f)
  }

  @Test
  fun `all negative photos make the case negative with nothing flagged`() {
    val summary =
      CaseAggregator()
        .apply {
          addPhoto("a", "a.jpg", result(Outcome.NEGATIVE, "benign", 90f))
          addPhoto("b", "b.jpg", result(Outcome.NEGATIVE, "benign", 80f))
        }
        .summary()

    assertEquals(Outcome.NEGATIVE, summary.outcome)
    assertTrue(summary.suspiciousImages.isEmpty())
    assertEquals("benign", summary.caseClassId)
  }

  @Test
  fun `a photo AI failed on can never let the case read as negative`() {
    val summary =
      CaseAggregator()
        .apply {
          addPhoto("a", "a.jpg", result(Outcome.NEGATIVE, "benign", 90f))
          addPhoto("b", "b.jpg", StoredAiResult(failed = true, outcome = null))
        }
        .summary()

    assertEquals(Outcome.INCONCLUSIVE, summary.outcome)
    assertFalse(summary.isSuspicious)
    assertEquals(listOf("b.jpg"), summary.suspiciousImages)
    assertEquals(1, summary.failedImageCount)
    assertEquals(1, summary.imageCount)
  }

  @Test
  fun `positive still wins over a failed photo`() {
    val summary =
      CaseAggregator()
        .apply {
          addPhoto("a", "a.jpg", result(Outcome.POSITIVE, "suspicious", 90f))
          addPhoto("b", "b.jpg", StoredAiResult(failed = true, outcome = null))
        }
        .summary()

    assertEquals(Outcome.POSITIVE, summary.outcome)
  }

  @Test
  fun `an inconclusive photo makes a non-positive case inconclusive`() {
    val summary =
      CaseAggregator()
        .apply {
          addPhoto("a", "a.jpg", result(Outcome.NEGATIVE, "normal", 90f))
          addPhoto("b", "b.jpg", result(Outcome.INCONCLUSIVE, "poor_photo", 80f))
        }
        .summary()

    assertEquals(Outcome.INCONCLUSIVE, summary.outcome)
    assertEquals("poor_photo", summary.caseClassId)
    assertEquals(1, summary.inconclusiveImageCount)
  }

  @Test
  fun `no classified photo at all is inconclusive, not negative`() {
    assertEquals(Outcome.INCONCLUSIVE, CaseAggregator().summary().outcome)
  }

  @Test
  fun `a hidden-item-only result counts toward the outcome`() {
    val summary =
      CaseAggregator().apply { addHiddenResult("a", Outcome.POSITIVE, "a.jpg") }.summary()

    assertEquals(Outcome.POSITIVE, summary.outcome)
    assertEquals(listOf("a.jpg"), summary.suspiciousImages)
    assertEquals(0, summary.imageCount)
  }

  @Test
  fun `the model's own low-confidence flag wins over the legacy cut`() {
    val summary =
      CaseAggregator()
        .apply { addPhoto("a", "a.jpg", result(Outcome.NEGATIVE, "benign", 70f, lowConfidence = true)) }
        .summary()

    assertEquals(1, summary.lowConfidenceImageCount)
  }

  private fun result(
    outcome: Outcome,
    classId: String,
    confidencePercent: Float,
    severity: Int = 0,
    lowConfidence: Boolean? = null,
  ) =
    StoredAiResult(
      failed = false,
      outcome = outcome,
      classId = classId,
      modelId = "m",
      confidencePercentText = confidencePercent.toString(),
      severity = severity,
      lowConfidence = lowConfidence,
    )
}
