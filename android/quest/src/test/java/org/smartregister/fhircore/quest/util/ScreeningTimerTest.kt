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

package org.smartregister.fhircore.quest.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreeningTimerTest {

  @After
  fun tearDown() {
    ScreeningTimer.clearForTesting()
  }

  @Test
  fun `start step retake photo and end emit expected screening analytics`() {
    val clock = FakeClock(1_000)
    val events = mutableListOf<Pair<String, Map<String, Any?>>>()
    ScreeningTimer.resetForTesting(clock) { event, props -> events.add(event to props) }

    val screeningId = ScreeningTimer.start(batteryStartPct = 80)
    clock.now = 1_250
    ScreeningTimer.markStep(screeningId, "questionnaire_loaded")
    clock.now = 1_500
    ScreeningTimer.incrementRetake(screeningId)
    ScreeningTimer.incrementPhoto(screeningId)
    clock.now = 2_000
    ScreeningTimer.end(
      screeningId,
      outcome = "submitted",
      extraProps = mapOf(PostHogAnalytics.Props.BATTERY_PCT to 78),
    )

    val step = events.first { it.first == PostHogAnalytics.Events.SCREENING_STEP_COMPLETED }.second
    assertEquals(screeningId, step[PostHogAnalytics.Props.SCREENING_ID])
    assertEquals("questionnaire_loaded", step[PostHogAnalytics.Props.STEP_NAME])
    assertEquals(250L, step[PostHogAnalytics.Props.STEP_DURATION_MS])
    assertEquals(250L, step[PostHogAnalytics.Props.CUMULATIVE_DURATION_MS])

    val retake = events.first { it.first == PostHogAnalytics.Events.PHOTO_RETAKEN }.second
    assertEquals(1, retake[PostHogAnalytics.Props.RETAKE_INDEX])

    val completed = events.first { it.first == PostHogAnalytics.Events.SCREENING_COMPLETED }.second
    assertEquals(1_000L, completed[PostHogAnalytics.Props.TOTAL_DURATION_MS])
    assertEquals("submitted", completed[PostHogAnalytics.Props.OUTCOME])
    assertEquals(1, completed[PostHogAnalytics.Props.PHOTO_COUNT])
    assertEquals(1, completed[PostHogAnalytics.Props.RETAKE_COUNT])
    assertEquals(-2, completed[PostHogAnalytics.Props.BATTERY_DELTA_PCT])
    assertTrue(events.none { it.first == PostHogAnalytics.Events.SCREENING_ABANDONED })
  }

  private class FakeClock(var now: Long) : ScreeningTimer.Clock {
    override fun nowMs(): Long = now
  }
}
