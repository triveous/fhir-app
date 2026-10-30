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

/**
 * What a class means to the app. Everything after the model (the per-photo label, the case result,
 * the AI result screen, FHIR) works on these three values, never on class names, which is what lets
 * a 2-, 3- or N-class model plug in without app changes.
 */
enum class Outcome(
  /** Higher is more severe: a case takes the most severe outcome among its photos. */
  val severityRank: Int,
) {
  /** Needs referral. */
  POSITIVE(2),

  /** No referral needed. */
  NEGATIVE(0),

  /** The model can't tell (poor photo, unsure). The health worker decides. */
  INCONCLUSIVE(1),
}

/**
 * Identity and output classes of a [ScreeningModel].
 *
 * @property id stable id, e.g. `acme-oral-3class`. Also the key the model is registered under.
 * @property version saved with every result so each referral can be traced to the exact model.
 * @property classes every class the model can output, in any order.
 * @property lowConfidenceThreshold a result whose confidence (0–1) is below this is flagged
 *   low-confidence. The flag is informational; the app does not change the predicted class.
 * @property minAppApiVersion the [AI_API_VERSION] this model needs.
 */
data class ModelDescriptor(
  val id: String,
  val version: String,
  val classes: List<ClassDescriptor>,
  val lowConfidenceThreshold: Float = DEFAULT_LOW_CONFIDENCE_THRESHOLD,
  val minAppApiVersion: Int = AI_API_VERSION,
) {
  init {
    require(id.isNotBlank()) { "Model id must not be blank" }
    require(classes.isNotEmpty()) { "Model '$id' must declare at least one class" }
    val duplicates = classes.groupBy { it.id }.filterValues { it.size > 1 }.keys
    require(duplicates.isEmpty()) { "Model '$id' declares duplicate class ids $duplicates" }
    require(lowConfidenceThreshold in 0f..1f) {
      "Model '$id' lowConfidenceThreshold must be in [0, 1], was $lowConfidenceThreshold"
    }
  }

  fun classById(classId: String): ClassDescriptor? = classes.firstOrNull { it.id == classId }

  companion object {
    const val DEFAULT_LOW_CONFIDENCE_THRESHOLD = 0.65f
  }
}

/**
 * One output class.
 *
 * Text maps are keyed by language tag (`en`, `hi`, …). The device language is used when present,
 * then `en`, then any entry.
 *
 * @property severity orders classes that share an [outcome]; higher is worse. Used to pick which
 *   class describes a case when several photos land in different classes of the same outcome.
 * @property resultTitle optional text for the AI result screen title, e.g. "Refer to a
 *   dermatologist". Falls back to the app's text for the [outcome].
 * @property resultDescription optional text for the AI result screen's explanation line.
 */
data class ClassDescriptor(
  val id: String,
  val outcome: Outcome,
  val displayName: Map<String, String> = emptyMap(),
  val severity: Int = 0,
  val resultTitle: Map<String, String> = emptyMap(),
  val resultDescription: Map<String, String> = emptyMap(),
) {
  init {
    require(id.isNotBlank()) { "Class id must not be blank" }
  }

  /** The label health workers see, in [locale]; the class id when no name is given. */
  fun displayName(locale: Locale = Locale.getDefault()): String =
    displayName.localized(locale) ?: id
}

/** Picks the entry for [locale]'s language, then English, then any entry; null when empty. */
fun Map<String, String>.localized(locale: Locale = Locale.getDefault()): String? =
  this[locale.toLanguageTag()]?.takeIf { it.isNotBlank() }
    ?: this[locale.language]?.takeIf { it.isNotBlank() }
    ?: this["en"]?.takeIf { it.isNotBlank() }
    ?: values.firstOrNull { it.isNotBlank() }
