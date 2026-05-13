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

import android.app.ActivityManager
import android.content.Context
import android.os.BatteryManager
import android.os.Build

object DeviceMetrics {
  fun snapshot(context: Context): Map<String, Any> {
    val runtime = Runtime.getRuntime()
    val usedMemoryMb = (runtime.totalMemory() - runtime.freeMemory()).bytesToMib()
    val memoryInfo = ActivityManager.MemoryInfo()
    (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)
      ?.getMemoryInfo(memoryInfo)

    return mapOf(
      PostHogAnalytics.Props.BATTERY_PCT to (batteryPct(context) ?: -1),
      PostHogAnalytics.Props.BATTERY_CHARGING to batteryCharging(context),
      PostHogAnalytics.Props.MEMORY_USED_MB to usedMemoryMb,
      PostHogAnalytics.Props.MEMORY_AVAILABLE_MB to memoryInfo.availMem.bytesToMib(),
    )
  }

  fun batteryPct(context: Context): Int? =
    (context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager)
      ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
      ?.takeIf { it >= 0 }

  private fun batteryCharging(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      (context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager)?.isCharging == true
    } else {
      false
    }

  private fun Long.bytesToMib(): Long = this / (1024L * 1024L)
}
