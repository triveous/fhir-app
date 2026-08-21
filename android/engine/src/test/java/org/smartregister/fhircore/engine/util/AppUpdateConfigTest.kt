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

import org.junit.Assert
import org.junit.Test

class AppUpdateConfigTest {

  // The scenario from the field: a bug in code 48 fixed in 50, with 55 the latest build.
  private val mixed = AppUpdateConfig(minSupportedVersionCode = 50, latestVersionCode = 55)

  @Test
  fun belowSupportedFloorIsForced() {
    Assert.assertEquals(AppUpdateRequirement.FORCED, mixed.requirementFor(48))
    Assert.assertEquals(AppUpdateRequirement.FORCED, mixed.requirementFor(49))
  }

  @Test
  fun betweenFloorAndLatestIsSoft() {
    Assert.assertEquals(AppUpdateRequirement.SOFT, mixed.requirementFor(50))
    Assert.assertEquals(AppUpdateRequirement.SOFT, mixed.requirementFor(54))
  }

  @Test
  fun atOrAboveLatestIsNone() {
    Assert.assertEquals(AppUpdateRequirement.NONE, mixed.requirementFor(55))
    Assert.assertEquals(AppUpdateRequirement.NONE, mixed.requirementFor(56))
  }

  @Test
  fun onlyLatestSetIsPureSoftNudge() {
    val soft = AppUpdateConfig(latestVersionCode = 55)
    Assert.assertEquals(AppUpdateRequirement.SOFT, soft.requirementFor(48))
    Assert.assertEquals(AppUpdateRequirement.SOFT, soft.requirementFor(54))
    Assert.assertEquals(AppUpdateRequirement.NONE, soft.requirementFor(55))
  }

  @Test
  fun onlyFloorSetIsPureForcedFloor() {
    val forced = AppUpdateConfig(minSupportedVersionCode = 50)
    Assert.assertEquals(AppUpdateRequirement.FORCED, forced.requirementFor(49))
    Assert.assertEquals(AppUpdateRequirement.NONE, forced.requirementFor(50))
    Assert.assertEquals(AppUpdateRequirement.NONE, forced.requirementFor(60))
  }

  @Test
  fun forcedFloorWinsWhenLatestMisconfiguredBelowFloor() {
    val misconfigured = AppUpdateConfig(minSupportedVersionCode = 50, latestVersionCode = 40)
    Assert.assertEquals(AppUpdateRequirement.FORCED, misconfigured.requirementFor(48))
    Assert.assertEquals(AppUpdateRequirement.NONE, misconfigured.requirementFor(50))
  }

  @Test
  fun unconfiguredNeverPrompts() {
    Assert.assertEquals(AppUpdateRequirement.NONE, AppUpdateConfig.NONE.requirementFor(1))
  }
}
