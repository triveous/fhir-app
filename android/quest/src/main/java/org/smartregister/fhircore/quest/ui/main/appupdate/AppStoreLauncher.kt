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

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import timber.log.Timber

/**
 * Minimum gap between two Play Store launches. Both the soft card (tapping anywhere on it) and the
 * forced dialog's "Update now" button funnel through [launchAppStore], and a fast double-tap on
 * either would otherwise start the Play Store activity twice (or once per extra tap while it is
 * still resolving). This is a single shared guard rather than per-composable debounce state so it
 * covers both entry points — and any future one — with one check.
 */
private const val LAUNCH_DEBOUNCE_MS = 2_000L

/** Elapsed-realtime (monotonic, immune to wall-clock changes) timestamp of the last launch attempt. */
private var lastLaunchAtElapsedMs = 0L

/**
 * Opens this app's Google Play listing for both the forced dialog and the soft banner. The listing
 * is derived from the installed build's own package name, so it always points at the correct app
 * without any server-side configuration.
 *
 * Ignores calls that land within [LAUNCH_DEBOUNCE_MS] of the previous one, so rapid repeat taps on
 * the soft card or the forced dialog's button open the store once, not once per tap.
 *
 * Prefers the `market://` deep link — which opens the Play Store app directly — and falls back to
 * the web listing when no store app can handle it (e.g. de-Googled devices). Never throws.
 */
fun launchAppStore(context: Context) {
  val now = SystemClock.elapsedRealtime()
  if (now - lastLaunchAtElapsedMs < LAUNCH_DEBOUNCE_MS) return
  lastLaunchAtElapsedMs = now

  val marketUri = "market://details?id=${context.packageName}"
  val webUri = "https://play.google.com/store/apps/details?id=${context.packageName}"
  try {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(marketUri)))
  } catch (e: ActivityNotFoundException) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(webUri))) }
      .onFailure { Timber.e(it, "Unable to open Play Store listing for app update") }
  }
}
