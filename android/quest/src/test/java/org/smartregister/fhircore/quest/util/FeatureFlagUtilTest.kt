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

import com.google.android.fhir.FhirEngine
import com.google.android.fhir.db.ResourceNotFoundException
import com.google.android.fhir.get
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.hl7.fhir.r4.model.Basic
import org.hl7.fhir.r4.model.BooleanType
import org.hl7.fhir.r4.model.Bundle
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import org.smartregister.fhircore.engine.data.remote.fhir.resource.FhirResourceDataSource
import org.smartregister.fhircore.engine.util.SharedPreferencesHelper

class FeatureFlagUtilTest {

  private val featureFlagsResourceId = SharedPreferencesHelper.FEATURE_FLAGS_RESOURCE_ID
  private val fhirEngine = mockk<FhirEngine>()
  private val fhirResourceDataSource = mockk<FhirResourceDataSource>()
  private val sharedPreferencesHelper = mockk<SharedPreferencesHelper>(relaxUnitFun = true)
  private lateinit var featureFlagUtil: FeatureFlagUtil

  @Before
  fun setUp() {
    every { sharedPreferencesHelper.getFeatureFlagsResourceId() } returns featureFlagsResourceId
    every { sharedPreferencesHelper.getLastKnownFeatureFlags(any()) } returns emptyMap()
    featureFlagUtil =
      FeatureFlagUtil(
        fhirEngine = fhirEngine,
        fhirResourceDataSource = fhirResourceDataSource,
        sharedPreferencesHelper = sharedPreferencesHelper,
      )
  }

  @Test
  fun testIsAiInferenceEnabledRereadsSyncedBasicOnEachCall() = runTest {
    coEvery { fhirEngine.get<Basic>(featureFlagsResourceId) } returnsMany
      listOf(featureFlagsBasic(false), featureFlagsBasic(true))

    Assert.assertFalse(featureFlagUtil.isAiInferenceEnabled())
    Assert.assertTrue(featureFlagUtil.isAiInferenceEnabled())

    coVerify(exactly = 2) { fhirEngine.get<Basic>(featureFlagsResourceId) }
    coVerify(exactly = 0) { fhirResourceDataSource.getResource(any()) }
  }

  @Test
  fun testReadsUseTenantPrefixedResourceIdFromPreferences() = runTest {
    val tenantResourceId = "staging-2-feature-flags"
    every { sharedPreferencesHelper.getFeatureFlagsResourceId() } returns tenantResourceId
    coEvery { fhirEngine.get<Basic>(tenantResourceId) } throws
      ResourceNotFoundException("Basic", tenantResourceId)
    coEvery {
      fhirResourceDataSource.getResource("Basic?_id=$tenantResourceId&_count=1")
    } returns Bundle().apply { addEntry().resource = featureFlagsBasic(true) }

    Assert.assertTrue(featureFlagUtil.isAiInferenceEnabled())

    coVerify { fhirEngine.get<Basic>(tenantResourceId) }
    coVerify { fhirResourceDataSource.getResource("Basic?_id=$tenantResourceId&_count=1") }
    verify { sharedPreferencesHelper.saveLastKnownFeatureFlags(tenantResourceId, any()) }
  }

  @Test
  fun testEmptyNetworkResultFallsBackToLastKnownFeatureFlags() = runTest {
    coEvery { fhirEngine.get<Basic>(featureFlagsResourceId) } throws
      ResourceNotFoundException("Basic", featureFlagsResourceId)
    coEvery {
      fhirResourceDataSource.getResource("Basic?_id=feature-flags&_count=1")
    } returns Bundle()
    every { sharedPreferencesHelper.getLastKnownFeatureFlags(featureFlagsResourceId) } returns
      mapOf(FeatureFlagUtil.AI_INFERENCE_ENABLED_URL to true)

    Assert.assertTrue(featureFlagUtil.isAiInferenceEnabled())

    verify { sharedPreferencesHelper.getLastKnownFeatureFlags(featureFlagsResourceId) }
  }

  private fun featureFlagsBasic(aiInferenceEnabled: Boolean): Basic =
    Basic().apply {
      id = featureFlagsResourceId
      addExtension().apply {
        url = FeatureFlagUtil.AI_INFERENCE_ENABLED_URL
        setValue(BooleanType(aiInferenceEnabled))
      }
    }
}
