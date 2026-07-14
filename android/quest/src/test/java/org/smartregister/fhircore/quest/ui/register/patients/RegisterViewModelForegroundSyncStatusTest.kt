/*
 * Copyright 2021-2026 Ona Systems, Inc
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

package org.smartregister.fhircore.quest.ui.register.patients

import android.app.Application
import android.os.Build
import com.google.android.fhir.FhirEngine
import com.google.android.fhir.LocalChange
import com.google.android.fhir.LocalChangeToken
import com.google.android.fhir.SearchResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.hl7.fhir.r4.model.DocumentReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.smartregister.fhircore.engine.configuration.ConfigurationRegistry
import org.smartregister.fhircore.engine.data.local.register.RegisterRepository
import org.smartregister.fhircore.engine.rulesengine.ResourceDataRulesExecutor
import org.smartregister.fhircore.engine.util.DispatcherProvider
import org.smartregister.fhircore.engine.util.FeatureFlagUtil
import org.smartregister.fhircore.engine.util.SecureSharedPreference
import org.smartregister.fhircore.engine.util.SharedPreferencesHelper
import org.smartregister.fhircore.quest.util.dailog.ForegroundSyncDialogState

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE, sdk = [Build.VERSION_CODES.Q])
class RegisterViewModelForegroundSyncStatusTest {

    private val testDispatcher = StandardTestDispatcher()
    private val fhirEngine = mockk<FhirEngine>()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `opening dialog always hides cached zero until fresh counts are loaded`() =
        runTest(testDispatcher) {
            coEvery { fhirEngine.getUnsyncedLocalChanges() } returns
                emptyList() andThen listOf(patientLocalChange("patient-1"))
            coEvery { fhirEngine.search<DocumentReference>(any()) } returns
                emptyList() andThen
                listOf(
                    documentReference(DocumentReferenceCaseType.SUBMITTED.name),
                    documentReference(DocumentReferenceCaseType.DRAFT.name),
                    documentReference(DocumentReferenceCaseType.SUBMITTED.name),
                )
            val viewModel = createViewModel()

            viewModel.setShowDialog(true)

            assertTrue(viewModel.showDialog.value)
            assertSame(ForegroundSyncDialogState.Loading, viewModel.foregroundSyncDialogState.value)
            advanceUntilIdle()
            assertEquals(
                ForegroundSyncDialogState.Loaded(imageCount = 0, patientsCount = 0),
                viewModel.foregroundSyncDialogState.value,
            )

            viewModel.setShowDialog(false)
            viewModel.setShowDialog(true)

            // The previous zero must disappear synchronously, before the new database read runs.
            assertSame(ForegroundSyncDialogState.Loading, viewModel.foregroundSyncDialogState.value)
            advanceUntilIdle()
            assertEquals(
                ForegroundSyncDialogState.Loaded(imageCount = 2, patientsCount = 1),
                viewModel.foregroundSyncDialogState.value,
            )
            assertEquals(2, viewModel.allUnSyncedImages.value)
            assertEquals(1, viewModel.allUnSyncedStateFlow.value.size)
            coVerify(exactly = 2) { fhirEngine.getUnsyncedLocalChanges() }
            coVerify(exactly = 2) { fhirEngine.search<DocumentReference>(any()) }
        }

    @Test
    fun `failed refresh shows failure instead of a cached count`() =
        runTest(testDispatcher) {
            coEvery { fhirEngine.getUnsyncedLocalChanges() } throws
                IllegalStateException("database unavailable")
            val viewModel = createViewModel()

            viewModel.setShowDialog(true)

            assertSame(ForegroundSyncDialogState.Loading, viewModel.foregroundSyncDialogState.value)
            advanceUntilIdle()
            assertSame(ForegroundSyncDialogState.Failed, viewModel.foregroundSyncDialogState.value)
            assertTrue(viewModel.showDialog.value)
        }

    private fun createViewModel() =
        RegisterViewModel(
            registerRepository = mockk<RegisterRepository>(relaxed = true),
            configurationRegistry = mockk<ConfigurationRegistry>(relaxed = true),
            sharedPreferencesHelper = mockk<SharedPreferencesHelper>(relaxed = true),
            dispatcherProvider =
                object : DispatcherProvider {
                    override fun io() = testDispatcher
                },
            secureSharedPreference = mockk<SecureSharedPreference>(relaxed = true),
            resourceDataRulesExecutor = mockk<ResourceDataRulesExecutor>(relaxed = true),
            fhirEngine = fhirEngine,
            featureFlagUtil = mockk<FeatureFlagUtil>(relaxed = true),
        )

    private fun patientLocalChange(id: String) =
        LocalChange(
            resourceType = "Patient",
            resourceId = id,
            timestamp = Instant.EPOCH,
            type = LocalChange.Type.INSERT,
            payload =
                """{"resourceType":"Patient","id":"$id","meta":{"lastUpdated":"2026-07-14T00:00:00Z"},"name":[{"given":["Test"]}],"gender":"female","telecom":[{"value":"123"}]}""",
            token = LocalChangeToken(listOf(1L)),
        )

    private fun documentReference(description: String) =
        SearchResult(
            resource = DocumentReference().apply { this.description = description },
            revIncluded = null,
            included = null,
        )
}
