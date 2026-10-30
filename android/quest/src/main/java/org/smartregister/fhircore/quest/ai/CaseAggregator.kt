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

import org.smartregister.fhircore.ai.Outcome

/**
 * The case-level AI result, built from every photo's result.
 *
 * @property outcome the case's outcome (see [CaseAggregator]).
 * @property suspiciousImages titles of the photos that explain [outcome] — the flagged photos shown
 *   on the AI result screen. Kept under its original name; for an inconclusive case these are the
 *   inconclusive and unscreened photos.
 * @property caseClassId the class that best describes the case: the most severe class among the
 *   photos with the case's outcome. Null when no photo was classified.
 * @property modelId the model that classified the photos, when known.
 */
data class AiInferenceSummary(
  val outcome: Outcome,
  val suspiciousImages: List<String>,
  val imageCount: Int = 0,
  val suspiciousImageCount: Int = 0,
  val nonSuspiciousImageCount: Int = 0,
  val inconclusiveImageCount: Int = 0,
  val failedImageCount: Int = 0,
  val lowConfidenceImageCount: Int = 0,
  val meanConfidence: Float = 0f,
  val caseClassId: String? = null,
  val modelId: String? = null,
) {
  val isSuspicious: Boolean
    get() = outcome == Outcome.POSITIVE
}

/**
 * Combines per-photo AI results into the case result. The rules, safest first:
 * 1. Any POSITIVE photo makes the case POSITIVE (refer). For a binary model this is the original
 *    "one suspicious photo makes the case suspicious".
 * 2. Otherwise the case is INCONCLUSIVE when any photo is inconclusive, when AI failed on any photo,
 *    or when no photo was classified at all — an unscreened photo could have been positive, so the
 *    app must not say "no referral required".
 * 3. Otherwise NEGATIVE.
 */
class CaseAggregator(private val lowConfidencePercent: Float = DEFAULT_LOW_CONFIDENCE_PERCENT) {

  private data class Photo(val title: String?, val result: StoredAiResult)

  private val photos = linkedMapOf<String, Photo>()
  private val hiddenOnly = linkedMapOf<String, Pair<Outcome, String?>>()

  /** A photo answer (keyed by its item linkId) and its stored AI result. */
  fun addPhoto(linkId: String, title: String?, result: StoredAiResult) {
    photos[linkId] = Photo(title, result)
  }

  /**
   * A result known only from the hidden `<linkId>-ai-result` item (its answer's extensions were
   * stripped). Counts toward the outcome and the flagged photos, not toward the photo counts.
   */
  fun addHiddenResult(linkId: String, outcome: Outcome, title: String?) {
    hiddenOnly[linkId] = outcome to title
  }

  fun summary(): AiInferenceSummary {
    val classified = photos.values.filter { !it.result.failed && it.result.outcome != null }
    val failed = photos.values.filter { it.result.failed }
    val hidden = hiddenOnly.filterKeys { it !in photos }.values
    val outcomes = classified.map { it.result.outcome!! } + hidden.map { it.first }

    val outcome =
      when {
        Outcome.POSITIVE in outcomes -> Outcome.POSITIVE
        Outcome.INCONCLUSIVE in outcomes || failed.isNotEmpty() || outcomes.isEmpty() ->
          Outcome.INCONCLUSIVE
        else -> Outcome.NEGATIVE
      }

    val flagged = linkedSetOf<String>()
    if (outcome != Outcome.NEGATIVE) {
      classified
        .filter { it.result.outcome == outcome }
        .sortedByDescending { it.result.severity }
        .mapNotNullTo(flagged) { it.title }
      hidden.filter { it.first == outcome }.mapNotNullTo(flagged) { it.second }
      if (outcome == Outcome.INCONCLUSIVE) failed.mapNotNullTo(flagged) { it.title }
    }

    val confidences = classified.mapNotNull { it.result.confidencePercent }
    return AiInferenceSummary(
      outcome = outcome,
      suspiciousImages = flagged.toList(),
      imageCount = classified.size,
      suspiciousImageCount = classified.count { it.result.outcome == Outcome.POSITIVE },
      nonSuspiciousImageCount = classified.count { it.result.outcome == Outcome.NEGATIVE },
      inconclusiveImageCount = classified.count { it.result.outcome == Outcome.INCONCLUSIVE },
      failedImageCount = failed.size,
      lowConfidenceImageCount =
        classified.count { photo ->
          photo.result.lowConfidence
            ?: photo.result.confidencePercent?.let { it < lowConfidencePercent }
            ?: false
        },
      meanConfidence = confidences.takeIf { it.isNotEmpty() }?.average()?.toFloat() ?: 0f,
      caseClassId =
        classified
          .filter { it.result.outcome == outcome && it.result.classId != null }
          .maxWithOrNull(compareBy({ it.result.severity }, { it.result.confidencePercent ?: 0f }))
          ?.result
          ?.classId,
      modelId = (classified + failed).firstNotNullOfOrNull { it.result.modelId },
    )
  }

  companion object {
    /** The original pipeline's low-confidence cut, for results stored without their own flag. */
    const val DEFAULT_LOW_CONFIDENCE_PERCENT = 65f
  }
}
