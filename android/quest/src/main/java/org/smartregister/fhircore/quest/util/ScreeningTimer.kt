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

import android.os.SystemClock
import java.util.UUID

object ScreeningTimer {
  private const val MAX_ACTIVE_SCREENINGS = 16

  private data class State(
    val startMs: Long,
    var lastStepMs: Long,
    var retakeCount: Int = 0,
    var photoCount: Int = 0,
    val batteryStartPct: Int? = null,
  )

  interface Clock {
    fun nowMs(): Long
  }

  private val activeScreenings =
    object : LinkedHashMap<String, State>(MAX_ACTIVE_SCREENINGS, 0.75f, true) {
      override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, State>?): Boolean =
        size > MAX_ACTIVE_SCREENINGS
    }

  private var clock: Clock =
    object : Clock {
      override fun nowMs(): Long = SystemClock.elapsedRealtime()
    }

  private var eventSink: (String, Map<String, Any?>) -> Unit = { event, props ->
    PostHogAnalytics.capture(event, props)
  }

  @Synchronized
  fun start(batteryStartPct: Int? = null): String {
    val now = clock.nowMs()
    val screeningId = UUID.randomUUID().toString()
    activeScreenings[screeningId] = State(now, now, batteryStartPct = batteryStartPct)
    return screeningId
  }

  @Synchronized
  fun markStep(screeningId: String?, name: String) {
    if (screeningId.isNullOrBlank()) return
    val state = activeScreenings[screeningId] ?: return
    val now = clock.nowMs()
    val stepDuration = now - state.lastStepMs
    val cumulativeDuration = now - state.startMs
    state.lastStepMs = now

    capture(
      PostHogAnalytics.Events.SCREENING_STEP_COMPLETED,
      mapOf(
        PostHogAnalytics.Props.SCREENING_ID to screeningId,
        PostHogAnalytics.Props.STEP_NAME to name,
        PostHogAnalytics.Props.STEP_DURATION_MS to stepDuration,
        PostHogAnalytics.Props.CUMULATIVE_DURATION_MS to cumulativeDuration,
      ),
    )
  }

  @Synchronized
  fun incrementRetake(screeningId: String?): Int {
    if (screeningId.isNullOrBlank()) return 0
    val state = activeScreenings[screeningId] ?: return 0
    state.retakeCount += 1
    capture(
      PostHogAnalytics.Events.PHOTO_RETAKEN,
      mapOf(
        PostHogAnalytics.Props.SCREENING_ID to screeningId,
        PostHogAnalytics.Props.RETAKE_INDEX to state.retakeCount,
      ),
    )
    return state.retakeCount
  }

  @Synchronized
  fun incrementPhoto(screeningId: String?): Int {
    if (screeningId.isNullOrBlank()) return 0
    val state = activeScreenings[screeningId] ?: return 0
    state.photoCount += 1
    return state.photoCount
  }

  @Synchronized
  fun end(screeningId: String?, outcome: String, extraProps: Map<String, Any?>? = null) {
    if (screeningId.isNullOrBlank()) return
    val state = activeScreenings.remove(screeningId) ?: return
    val now = clock.nowMs()
    val props =
      mutableMapOf<String, Any?>(
        PostHogAnalytics.Props.SCREENING_ID to screeningId,
        PostHogAnalytics.Props.TOTAL_DURATION_MS to now - state.startMs,
        PostHogAnalytics.Props.OUTCOME to outcome,
        PostHogAnalytics.Props.PHOTO_COUNT to state.photoCount,
        PostHogAnalytics.Props.RETAKE_COUNT to state.retakeCount,
      )
    extraProps?.let(props::putAll)
    state.batteryStartPct?.let { startPct ->
      (props[PostHogAnalytics.Props.BATTERY_PCT] as? Int)?.let { endPct ->
        props[PostHogAnalytics.Props.BATTERY_DELTA_PCT] = endPct - startPct
      }
    }

    capture(PostHogAnalytics.Events.SCREENING_COMPLETED, props)
    if (outcome == "abandoned") {
      capture(PostHogAnalytics.Events.SCREENING_ABANDONED, props)
    }
  }

  private fun capture(event: String, props: Map<String, Any?>) {
    eventSink(event, props)
  }

  @Synchronized
  fun resetForTesting(
    testClock: Clock,
    testEventSink: (String, Map<String, Any?>) -> Unit = { event, props ->
      PostHogAnalytics.capture(event, props)
    },
  ) {
    activeScreenings.clear()
    clock = testClock
    eventSink = testEventSink
  }

  @Synchronized
  fun clearForTesting() {
    activeScreenings.clear()
    clock =
      object : Clock {
        override fun nowMs(): Long = SystemClock.elapsedRealtime()
      }
    eventSink = { event, props -> PostHogAnalytics.capture(event, props) }
  }
}
