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

package org.smartregister.fhircore.engine.util

/** What the currently-installed build must do, once the config is resolved against its version. */
enum class AppUpdateRequirement {
  NONE,
  SOFT,
  FORCED,
}

/**
 * App-update configuration read from the `app-update` nested extension on the feature-flags
 * [org.hl7.fhir.r4.model.Basic] resource.
 *
 * Rather than a per-version "soft or forced" flag, the policy is expressed as **two thresholds** on
 * the Android-canonical, monotonic version code (see `BuildConfigs.versionCode`), and the kind of
 * prompt is *derived* from which threshold the installed build falls below:
 *
 * - [minSupportedVersionCode] — the hard floor. Installs **below** it are unsupported and get a
 *   **forced** (blocking) prompt.
 * - [latestVersionCode] — the newest available build. Installs at/above the floor but **below** it
 *   get a **soft** (dismissible) prompt.
 * - Installs at/above [latestVersionCode] see nothing.
 *
 * This makes mixed rollouts trivial: e.g. a critical bug in code 48 that is fixed in 50, with 55 as
 * the latest, is `minSupportedVersionCode = 50, latestVersionCode = 55` — codes < 50 are forced,
 * 50–54 are softly nudged, 55+ are left alone. Either threshold may be omitted (0) to get a
 * pure forced-floor or pure soft-nudge policy.
 *
 * [latestVersionName], [softMessage] and [forcedMessage] are display-only. The two messages are
 * configured independently because the prompts say different things: [softMessage] replaces the soft
 * card's lead sentence (see `SoftUpdateBanner`) and reads like a release note — "Faster case sync
 * and a fix for lost photos" — while [forcedMessage] is the highlighted reason line on the blocking
 * dialog (see `AppUpdatePrompt`) and reads like a justification — "App is 6 months out of date".
 *
 * Both fall back to the older single `message` sub-extension when their own is absent, so configs
 * written before the split keep working and show the same text on either prompt. (That fallback is
 * applied when parsing — see `FeatureFlagUtil` for the server shape and `SharedPreferencesHelper`
 * for the persisted one. It deliberately is *not* a Gson `alternate`: two fields cannot share one
 * JSON name, and Gson fails to build the adapter at all if they try.) The "Update" action
 * always opens this app's Play Store listing (derived from the package name), so no store URL is
 * configured here.
 */
data class AppUpdateConfig(
  val minSupportedVersionCode: Int = 0,
  val latestVersionCode: Int = 0,
  val latestVersionName: String? = null,
  val softMessage: String? = null,
  val forcedMessage: String? = null,
) {

  /**
   * Resolves what the build installed at [currentVersionCode] must do. The forced floor is checked
   * first, so a build below [minSupportedVersionCode] is always forced even if [latestVersionCode]
   * is misconfigured lower. A threshold of 0 (unset) disables that half of the policy.
   */
  fun requirementFor(currentVersionCode: Int): AppUpdateRequirement =
    when {
      minSupportedVersionCode > 0 && currentVersionCode < minSupportedVersionCode ->
        AppUpdateRequirement.FORCED
      latestVersionCode > 0 && currentVersionCode < latestVersionCode -> AppUpdateRequirement.SOFT
      else -> AppUpdateRequirement.NONE
    }

  companion object {
    val NONE = AppUpdateConfig()
  }
}
