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

package org.smartregister.fhircore.quest.ui.main.appupdate

import org.smartregister.fhircore.engine.util.AppUpdateRequirement

/**
 * UI-facing state for the app-update prompt, derived from the feature-flag [AppUpdateConfig] and the
 * installed build's version code. [requirement] drives whether — and how — the prompt is shown:
 * [AppUpdateRequirement.FORCED] renders the blocking dialog (see `AppUpdatePrompt`) and
 * [AppUpdateRequirement.SOFT] renders the dismissible register-screen nudge (see `SoftUpdateBanner`).
 * [latestVersionName] feeds the prompt content; [message] is the server-configurable soft-banner
 * copy (falls back to built-in text when null). The "Update" action always opens this app's Play
 * Store listing (derived from the package name), so no store URL is carried here.
 */
data class AppUpdateUiState(
  val requirement: AppUpdateRequirement = AppUpdateRequirement.NONE,
  val latestVersionName: String? = null,
  val message: String? = null,
)
