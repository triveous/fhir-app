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

import com.google.android.fhir.FhirEngine
import com.google.android.fhir.db.ResourceNotFoundException
import com.google.android.fhir.get
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.hl7.fhir.r4.model.Basic
import org.hl7.fhir.r4.model.BooleanType
import org.hl7.fhir.r4.model.IntegerType
import org.hl7.fhir.r4.model.StringType
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import org.smartregister.fhircore.engine.data.remote.fhir.resource.FhirResourceDataSource

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
    every { sharedPreferencesHelper.getLastKnownAppUpdateConfig(any()) } returns AppUpdateConfig.NONE
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
    coVerify(exactly = 0) { fhirResourceDataSource.getBasic(any()) }
  }


  @Test
  fun testReadsUseTenantPrefixedResourceIdFromPreferences() = runTest {
    val tenantResourceId = "staging-2-feature-flags"
    every { sharedPreferencesHelper.getFeatureFlagsResourceId() } returns tenantResourceId
    coEvery { fhirEngine.get<Basic>(tenantResourceId) } throws
      ResourceNotFoundException("Basic", tenantResourceId)
    coEvery { fhirResourceDataSource.getBasic(tenantResourceId) } returns
      featureFlagsBasic(true)
    coEvery { fhirEngine.create(any<Basic>(), isLocalOnly = true) } returns
      listOf(tenantResourceId)

    Assert.assertTrue(featureFlagUtil.isAiInferenceEnabled())

    coVerify { fhirEngine.get<Basic>(tenantResourceId) }
    coVerify { fhirResourceDataSource.getBasic(tenantResourceId) }
    verify { sharedPreferencesHelper.saveLastKnownFeatureFlags(tenantResourceId, any()) }
  }

  @Test
  fun testFailedNetworkReadFallsBackToLastKnownFeatureFlags() = runTest {
    coEvery { fhirEngine.get<Basic>(featureFlagsResourceId) } throws
      ResourceNotFoundException("Basic", featureFlagsResourceId)
    coEvery { fhirResourceDataSource.getBasic(featureFlagsResourceId) } throws
      RuntimeException("HTTP 404")
    every { sharedPreferencesHelper.getLastKnownFeatureFlags(featureFlagsResourceId) } returns
      mapOf(FeatureFlagUtil.AI_INFERENCE_ENABLED_URL to true)

    Assert.assertTrue(featureFlagUtil.isAiInferenceEnabled())

    verify { sharedPreferencesHelper.getLastKnownFeatureFlags(featureFlagsResourceId) }
  }

  @Test
  fun testNetworkFallbackWritesThroughToEngine() = runTest {
    coEvery { fhirEngine.get<Basic>(featureFlagsResourceId) } throws
      ResourceNotFoundException("Basic", featureFlagsResourceId)
    coEvery { fhirResourceDataSource.getBasic(featureFlagsResourceId) } returns
      featureFlagsBasic(true)
    val created = slot<Basic>()
    coEvery { fhirEngine.create(capture(created), isLocalOnly = true) } returns
      listOf(featureFlagsResourceId)

    Assert.assertTrue(featureFlagUtil.isAiInferenceEnabled())

    Assert.assertEquals(featureFlagsResourceId, created.captured.idElement.idPart)
    verify {
      sharedPreferencesHelper.saveLastKnownFeatureFlags(
        featureFlagsResourceId,
        mapOf(FeatureFlagUtil.AI_INFERENCE_ENABLED_URL to true),
      )
    }
  }

  @Test
  fun testRefreshFromServerFetchesDirectlyAndStoresLocally() = runTest {
    coEvery { fhirResourceDataSource.getBasic(featureFlagsResourceId) } returns
      featureFlagsBasic(true)
    coEvery { fhirEngine.create(any<Basic>(), isLocalOnly = true) } returns
      listOf(featureFlagsResourceId)

    featureFlagUtil.refreshFromServer()

    coVerify { fhirResourceDataSource.getBasic(featureFlagsResourceId) }
    coVerify { fhirEngine.create(any<Basic>(), isLocalOnly = true) }
    verify {
      sharedPreferencesHelper.saveLastKnownFeatureFlags(
        featureFlagsResourceId,
        mapOf(FeatureFlagUtil.AI_INFERENCE_ENABLED_URL to true),
      )
    }
  }

  @Test
  fun testRefreshFromServerKeepsLastKnownValuesOnNetworkFailure() = runTest {
    coEvery { fhirResourceDataSource.getBasic(featureFlagsResourceId) } throws
      RuntimeException("timeout")

    featureFlagUtil.refreshFromServer()

    coVerify(exactly = 0) { fhirEngine.create(any<Basic>(), isLocalOnly = any()) }
    verify(exactly = 0) { sharedPreferencesHelper.saveLastKnownFeatureFlags(any(), any()) }
  }

  @Test
  fun testGetAppUpdateConfigParsesNestedExtensionFromEngineBasic() = runTest {
    coEvery { fhirEngine.get<Basic>(featureFlagsResourceId) } returns
      appUpdateBasic(minSupported = 50, latest = 55, versionName = "AA_v1.7.8")

    val config = featureFlagUtil.getAppUpdateConfig()

    Assert.assertEquals(50, config.minSupportedVersionCode)
    Assert.assertEquals(55, config.latestVersionCode)
    Assert.assertEquals("AA_v1.7.8", config.latestVersionName)
    verify {
      sharedPreferencesHelper.saveLastKnownAppUpdateConfig(featureFlagsResourceId, config)
    }
  }

  @Test
  fun testGetAppUpdateConfigParsesConfigurableSoftMessage() = runTest {
    coEvery { fhirEngine.get<Basic>(featureFlagsResourceId) } returns
      appUpdateBasic(
        minSupported = 50,
        latest = 55,
        versionName = "AA_v1.7.8",
        message = "New in this release: faster case sync. Please update.",
      )

    val config = featureFlagUtil.getAppUpdateConfig()

    Assert.assertEquals("New in this release: faster case sync. Please update.", config.message)
  }

  @Test
  fun testGetAppUpdateConfigMessageDefaultsToNullWhenAbsent() = runTest {
    coEvery { fhirEngine.get<Basic>(featureFlagsResourceId) } returns
      appUpdateBasic(minSupported = 50, latest = 55, versionName = "AA_v1.7.8")

    val config = featureFlagUtil.getAppUpdateConfig()

    Assert.assertNull(config.message)
  }

  @Test
  fun testGetAppUpdateConfigDefaultsToNoneWhenNoUpdateExtension() = runTest {
    coEvery { fhirEngine.get<Basic>(featureFlagsResourceId) } returns featureFlagsBasic(true)

    val config = featureFlagUtil.getAppUpdateConfig()

    Assert.assertEquals(AppUpdateConfig.NONE, config)
  }

  @Test
  fun testGetAppUpdateConfigFallsBackToLastKnownWhenUnavailable() = runTest {
    val lastKnown = AppUpdateConfig(50, 55, "AA_v1.8.0", "Please update to the latest version.")
    coEvery { fhirEngine.get<Basic>(featureFlagsResourceId) } throws
      ResourceNotFoundException("Basic", featureFlagsResourceId)
    coEvery { fhirResourceDataSource.getBasic(featureFlagsResourceId) } throws
      RuntimeException("offline")
    every { sharedPreferencesHelper.getLastKnownAppUpdateConfig(featureFlagsResourceId) } returns
      lastKnown

    val config = featureFlagUtil.getAppUpdateConfig()

    Assert.assertEquals(lastKnown, config)
  }

  private fun featureFlagsBasic(aiInferenceEnabled: Boolean): Basic =
    Basic().apply {
      id = featureFlagsResourceId
      addExtension().apply {
        url = FeatureFlagUtil.AI_INFERENCE_ENABLED_URL
        setValue(BooleanType(aiInferenceEnabled))
      }
    }

  private fun appUpdateBasic(
    minSupported: Int,
    latest: Int,
    versionName: String,
    message: String? = null,
  ): Basic =
    Basic().apply {
      id = featureFlagsResourceId
      addExtension().apply {
        url = FeatureFlagUtil.APP_UPDATE_URL
        addExtension().apply {
          url = FeatureFlagUtil.APP_UPDATE_MIN_SUPPORTED_VERSION_CODE
          setValue(IntegerType(minSupported))
        }
        addExtension().apply {
          url = FeatureFlagUtil.APP_UPDATE_LATEST_VERSION_CODE
          setValue(IntegerType(latest))
        }
        addExtension().apply {
          url = FeatureFlagUtil.APP_UPDATE_LATEST_VERSION_NAME
          setValue(StringType(versionName))
        }
        message?.let {
          addExtension().apply {
            url = FeatureFlagUtil.APP_UPDATE_MESSAGE
            setValue(StringType(it))
          }
        }
      }
    }
}
