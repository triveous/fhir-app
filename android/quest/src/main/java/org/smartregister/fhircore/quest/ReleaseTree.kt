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

package org.smartregister.fhircore.quest

import android.util.Log
import com.posthog.PostHog
import timber.log.Timber

class ReleaseTree : Timber.Tree() {

  override fun isLoggable(tag: String?, priority: Int): Boolean {
    return priority >= Log.INFO
  }

  override fun log(priority: Int, tag: String?, message: String, throwable: Throwable?) {
    val properties =
      mutableMapOf<String, Any>().apply {
        tag?.let { put("tag", it) }
        put("priority", priority)
        put("message", message)
      }

    try {
      if (priority >= Log.WARN) {
        if (throwable != null) {
          PostHog.captureException(throwable, properties = properties)
        } else {
          PostHog.captureException(Exception(message), properties = properties)
        }
      } else if (priority == Log.INFO) {
        PostHog.capture("info_log", properties = properties)
      }
    } catch (e: Exception) {
      Log.e("ReleaseTree", "Error sending log to PostHog: ${e.message}", e)
    }
  }
}
