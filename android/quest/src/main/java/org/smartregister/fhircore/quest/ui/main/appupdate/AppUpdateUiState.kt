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
 * [AppUpdateRequirement.SOFT] renders the dismissible register-screen card (see `SoftUpdateBanner`).
 * [softMessage] and [forcedMessage] are server-configurable copy, one per prompt, because the two
 * say different things: [softMessage] replaces the soft card's lead sentence (the "Click to update."
 * call-to-action is always kept), while [forcedMessage] is the highlighted reason line on the forced
 * dialog, e.g. "App is 6 months out of date". Blank/absent falls back to the built-in soft copy and
 * omits the forced dialog's reason line respectively. [latestVersionName] is carried through from the
 * config for callers that need it but is not rendered by either prompt. The "Update" action always
 * opens this app's Play Store listing (derived from the package name), so no store URL is carried
 * here.
 *
 * [softUpdateDismissed] tracks whether the user has closed the soft card during the current app
 * session (see [org.smartregister.fhircore.quest.ui.main.AppMainViewModel.dismissSoftUpdateBanner]).
 * It is session-scoped, not persisted: a fresh process start (a real app close-and-reopen, as opposed
 * to merely backgrounding) always begins with it `false` again.
 */
data class AppUpdateUiState(
  val requirement: AppUpdateRequirement = AppUpdateRequirement.NONE,
  val latestVersionName: String? = null,
  val softMessage: String? = null,
  val forcedMessage: String? = null,
  val softUpdateDismissed: Boolean = false,
)
