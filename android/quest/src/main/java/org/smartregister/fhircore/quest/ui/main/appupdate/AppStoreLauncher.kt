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
import timber.log.Timber

/**
 * Opens this app's Google Play listing for both the forced dialog and the soft banner. The listing
 * is derived from the installed build's own package name, so it always points at the correct app
 * without any server-side configuration.
 *
 * Prefers the `market://` deep link — which opens the Play Store app directly — and falls back to
 * the web listing when no store app can handle it (e.g. de-Googled devices). Never throws.
 */
fun launchAppStore(context: Context) {
  val marketUri = "market://details?id=${context.packageName}"
  val webUri = "https://play.google.com/store/apps/details?id=${context.packageName}"
  try {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(marketUri)))
  } catch (e: ActivityNotFoundException) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(webUri))) }
      .onFailure { Timber.e(it, "Unable to open Play Store listing for app update") }
  }
}
