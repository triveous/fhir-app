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
import com.posthog.PostHogInterface
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.After
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import org.smartregister.fhircore.quest.robolectric.RobolectricTest
import timber.log.Timber
import java.net.UnknownHostException

class ReleaseTreeTest : RobolectricTest() {

  private lateinit var postHog: PostHogInterface

  @Before
  fun setUp() {
    Timber.uprootAll()
    postHog = mockk(relaxed = true)
    every { postHog.capture(any(), any(), any(), any(), any(), any(), any()) } returns Unit
    every { postHog.captureException(any(), any()) } returns Unit
    PostHog.overrideSharedInstance(postHog)
    Timber.plant(ReleaseTree())
  }

  @After
  override fun tearDown() {
    Timber.uprootAll()
    PostHog.resetSharedInstance()
    super.tearDown()
  }

  @Test
  fun warnLogWithoutThrowableShouldNotCaptureException() {
    val message = "RegisterFragment registered to receive sync state events"

    Timber.w(message)

    verify(exactly = 0) { postHog.captureException(any(), any()) }
    verify(exactly = 1) {
      postHog.capture(
        event = "warning_log",
        distinctId = null,
        properties = match { it.get("message") == message && it["priority"] == Log.WARN },
        userProperties = null,
        userPropertiesSetOnce = null,
        groups = null,
        timestamp = null,
      )
    }
  }

  @Test
  fun errorLogWithoutThrowableShouldNotCaptureException() {
    val message = "AppSyncWorker failed"

    Timber.e(message)

    verify(exactly = 0) { postHog.captureException(any(), any()) }
    verify(exactly = 1) {
      postHog.capture(
        event = "error_log",
        distinctId = null,
        properties = match { it.get("message") == message && it["priority"] == Log.ERROR },
        userProperties = null,
        userPropertiesSetOnce = null,
        groups = null,
        timestamp = null,
      )
    }
  }

  @Test
  fun infoLogShouldBeCapturedForSyncAnalytics() {
    val message = "Download summary (Questionnaire, 0), (StructureMap, 0), (Patient, 0)"

    Timber.i(message)

    verify(exactly = 0) { postHog.captureException(any(), any()) }
    verify(exactly = 1) {
      postHog.capture(
        event = "info_log",
        distinctId = null,
        properties = match { it["message"] == message && it["priority"] == Log.INFO },
        userProperties = null,
        userPropertiesSetOnce = null,
        groups = null,
        timestamp = null,
      )
    }
  }

  @Test
  fun expectedNetworkErrorShouldNotCaptureException() {
    Timber.e(
      UnknownHostException(
        "Unable to resolve host \"mt.aarogya-aarohan.tanuh.ai\": No address associated with hostname",
      ),
    )

    verify(exactly = 0) { postHog.captureException(any(), any()) }
    verify(exactly = 0) {
      postHog.capture(any(), any(), any(), any(), any(), any(), any())
    }
  }

  @Test
  fun placeholderHostErrorShouldStillCaptureException() {
    val throwable =
      UnknownHostException(
        "Unable to resolve host \"placeholder.invalid\": No address associated with hostname",
      )

    Timber.e(throwable)

    verify(exactly = 1) { postHog.captureException(throwable = throwable, properties = any()) }
  }

  @Test
  fun errorLogWithThrowableShouldCaptureException() {
    val message = "AppSyncWorker failed"
    val throwable = IllegalStateException(message)
    val throwableSlot = slot<Throwable>()

    Timber.e(throwable, message)

    verify(exactly = 1) {
      postHog.captureException(
        throwable = capture(throwableSlot),
        properties =
          match {
            (it.get("message") as String).startsWith(message) && it["priority"] == Log.ERROR
          },
      )
    }
    Assert.assertEquals(message, throwableSlot.captured.message)
  }
}
