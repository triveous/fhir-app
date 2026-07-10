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

package org.smartregister.fhircore.quest.ui.main

/**
 * UI state that drives the non-blocking floating sync progress bar shown above the bottom
 * navigation bar.
 *
 * Visibility ([isSyncing]) is intentionally tied to the sync lifecycle (running until a terminal
 * Succeeded/Failed status) rather than to the transient raw progress percentage. The FHIR SDK emits
 * progress per resource type/batch, so the view model normalizes [progressPercentage] to avoid
 * visible resets or backwards movement during a single sync; the bar must remain visible until the
 * sync truly finishes.
 */
data class SyncProgressUiState(
  val isSyncing: Boolean = false,
  val progressPercentage: Int = 0,
  val isUploadSync: Boolean = false,
  val isFirstTimeSync: Boolean = false,
)
