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

package org.smartregister.fhircore.engine.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import com.google.android.fhir.FhirEngine
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import io.mockk.mockk
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.smartregister.fhircore.engine.app.fakes.Faker
import org.smartregister.fhircore.engine.robolectric.RobolectricTest
import org.smartregister.fhircore.engine.rule.CoroutineTestRule
import org.smartregister.fhircore.engine.util.DispatcherProvider
import org.smartregister.fhircore.engine.util.SecureSharedPreference
import org.smartregister.fhircore.engine.util.SharedPreferencesHelper

/**
 * Whether a one-time sync is worth enqueuing.
 *
 * The SDK builds the one-time request with no constraints, so an offline device runs the whole
 * worker and fails every call in it. The periodic request does carry a connectivity constraint, so
 * skipping costs nothing — hence the guard, and hence these tests.
 */
@ExperimentalCoroutinesApi
@HiltAndroidTest
class SyncBroadcasterOfflineGuardTest : RobolectricTest() {

  @get:Rule(order = 0) val hiltAndroidRule = HiltAndroidRule(this)

  @get:Rule(order = 1) val coroutineTestRule = CoroutineTestRule()

  @Inject lateinit var sharedPreferencesHelper: SharedPreferencesHelper

  @Inject lateinit var secureSharedPreference: SecureSharedPreference

  @Inject lateinit var configService: org.smartregister.fhircore.engine.configuration.app.ConfigService

  @Inject lateinit var dispatcherProvider: DispatcherProvider

  private val context = ApplicationProvider.getApplicationContext<HiltTestApplication>()
  private lateinit var syncBroadcaster: SyncBroadcaster

  @Before
  fun setUp() {
    hiltAndroidRule.inject()
    val configurationRegistry = Faker.buildTestConfigurationRegistry(sharedPreferencesHelper)
    syncBroadcaster =
      SyncBroadcaster(
        configurationRegistry = configurationRegistry,
        fhirEngine = mockk<FhirEngine>(),
        dispatcherProvider = dispatcherProvider,
        syncListenerManager =
          SyncListenerManager(
            configService = configService,
            sharedPreferencesHelper = sharedPreferencesHelper,
            configurationRegistry = configurationRegistry,
            secureSharedPreference = secureSharedPreference,
          ),
        context = context,
      )
  }

  @After
  fun tearDown() {
    // The mutex is a companion-object singleton; a test that leaves it locked would break the next.
    if (AppSyncWorker.mutex.isLocked) AppSyncWorker.mutex.unlock()
  }

  private fun setDeviceOnline(online: Boolean) {
    val connectivityManager =
      context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val shadowConnectivityManager = shadowOf(connectivityManager)
    if (online) {
      val capabilities =
        ShadowNetworkCapabilities.newInstance().also {
          shadowOf(it).addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        }
      shadowConnectivityManager.setNetworkCapabilities(
        connectivityManager.activeNetwork,
        capabilities,
      )
    } else {
      shadowConnectivityManager.setDefaultNetworkActive(false)
      shadowConnectivityManager.setNetworkCapabilities(connectivityManager.activeNetwork, null)
    }
  }

  @Test
  fun `an offline device does not start a one time sync`() {
    setDeviceOnline(false)

    assertFalse(syncBroadcaster.shouldStartOneTimeSync("test request"))
  }

  @Test
  fun `an online device starts a one time sync`() {
    setDeviceOnline(true)

    assertTrue(syncBroadcaster.shouldStartOneTimeSync("test request"))
  }

  @Test
  fun `a sync already in flight is not started again even when online`() = runBlocking {
    setDeviceOnline(true)
    AppSyncWorker.mutex.lock()

    assertFalse(syncBroadcaster.shouldStartOneTimeSync("test request"))
  }

  @Test
  fun `runOneTimeSync enqueues nothing while offline`() = runBlocking {
    setDeviceOnline(false)

    // Would otherwise reach WorkManager, which is not initialised in this test — so completing
    // without throwing is itself evidence that the request was dropped before being enqueued.
    syncBroadcaster.runOneTimeSync()
  }
}
