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

/**
 * What a [ScreeningModel] returns for one photo.
 *
 * @property scores class id → probability in [0, 1]. Must contain every class of the model. A model
 *   that only has a hard label returns 1 for that class and 0 for the others.
 * @property predictedClassId the model's own decision. The app does not re-threshold it.
 * @property confidence how sure the model is of [predictedClassId], in [0, 1]. Defaults to the
 *   predicted class's score; set it when the model defines confidence differently (an ensemble may
 *   average its members' confidences, for example).
 * @property components per-sub-model results for ensembles; saved for audit and telemetry.
 * @property extras opaque key/values saved with the result for audit. Never interpreted.
 */
data class ScreeningOutput(
  val scores: Map<String, Float>,
  val predictedClassId: String,
  val confidence: Float? = null,
  val components: List<ComponentOutput> = emptyList(),
  val extras: Map<String, String> = emptyMap(),
) {
  /** [confidence], or the predicted class's score when the model didn't set one. */
  val effectiveConfidence: Float
    get() = confidence ?: scores[predictedClassId] ?: 0f
}

/** One member of an ensemble. Same rules as [ScreeningOutput]. */
data class ComponentOutput(
  val name: String,
  val scores: Map<String, Float>,
  val predictedClassId: String,
  val confidence: Float? = null,
) {
  val effectiveConfidence: Float
    get() = confidence ?: scores[predictedClassId] ?: 0f
}
