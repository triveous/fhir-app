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
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import ca.uhn.fhir.context.FhirContext
import com.google.android.fhir.FhirEngine
import com.google.android.fhir.db.ResourceNotFoundException
import com.google.gson.Gson
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.hl7.fhir.r4.model.Bundle
import org.hl7.fhir.r4.model.QuestionnaireResponse
import org.hl7.fhir.r4.model.QuestionnaireResponse.QuestionnaireResponseStatus
import org.hl7.fhir.r4.model.ResourceType
import org.junit.After
import org.junit.Assert.assertEquals
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
import org.smartregister.fhircore.engine.util.SharedPreferenceKey
import org.smartregister.fhircore.engine.util.SharedPreferencesHelper
import org.smartregister.fhircore.quest.util.DraftsUtils.getAllDraftsJsonFromSharedPreferences
import org.smartregister.fhircore.quest.util.DraftsUtils.parseDraftResponses
import org.smartregister.fhircore.quest.util.DraftsUtils.saveBundleToSharedPreferences

/**
 * A draft whose case has been submitted must disappear from the register immediately. QA could
 * submit a draft, press back fast enough to land on a not-yet-refreshed home screen, reopen the same
 * draft from the stale card and submit it a second time — registering a duplicate case whose
 * screening images were missing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE, sdk = [Build.VERSION_CODES.Q])
class RegisterViewModelSubmittedDraftTest {

    private val testDispatcher = StandardTestDispatcher()
    private val fhirEngine = mockk<FhirEngine>()
    private val registerRepository = mockk<RegisterRepository>(relaxed = true)
    private val viewModels = mutableListOf<RegisterViewModel>()
    private lateinit var sharedPreferencesHelper: SharedPreferencesHelper

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        sharedPreferencesHelper =
            SharedPreferencesHelper(ApplicationProvider.getApplicationContext(), Gson())
        sharedPreferencesHelper.prefs.edit().clear().commit()
        // The opened-draft marker is process-wide; start every test as a fresh process would.
        createViewModel().forgetOpenedDraft()
        coEvery { fhirEngine.search<QuestionnaireResponse>(any()) } returns emptyList()
        // Default: the draft only ever lived in shared preferences.
        coEvery { fhirEngine.get(any(), any()) } throws
            ResourceNotFoundException("QuestionnaireResponse", "draft-1")
    }

    /** Puts a copy of the draft in the engine at [status]. */
    private fun engineHolds(status: QuestionnaireResponseStatus) {
        coEvery { fhirEngine.get(ResourceType.QuestionnaireResponse, "draft-1") } returns
            QuestionnaireResponse().apply {
                id = "draft-1"
                this.status = status
            }
    }

    @After
    fun tearDown() {
        viewModels.forEach { it.viewModelScope.cancel() }
        viewModels.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun `submitting a draft drops it from the rendered list before any storage work runs`() =
        runTest(testDispatcher) {
            val draftId = storeDrafts("draft-1").single()
            val viewModel = createViewModel()
            viewModel.getAllDraftResponses()
            advanceUntilIdle()
            assertEquals(1, viewModel.allSavedDraftResponse.value.size)

            // Opening the draft is what takes it out of shared preferences.
            viewModel.deleteIfNotOldDraft(draftId)
            advanceUntilIdle()

            viewModel.purgeSubmittedDraft()

            // Synchronous: getAllDraftResponses() is async, and that gap is the window QA tapped.
            assertTrue(
                "Submitted draft was still on screen; it could be reopened and resubmitted",
                viewModel.allSavedDraftResponse.value.isEmpty(),
            )

            advanceUntilIdle()
            assertTrue(viewModel.allSavedDraftResponse.value.isEmpty())
        }

    @Test
    fun `submitting a draft deletes the engine-backed copy so a refresh cannot resurrect it`() =
        runTest(testDispatcher) {
            engineHolds(QuestionnaireResponseStatus.INPROGRESS)
            val draftId = storeDrafts("draft-1").single()
            val viewModel = createViewModel()
            viewModel.deleteIfNotOldDraft(draftId)
            advanceUntilIdle()

            viewModel.purgeSubmittedDraft()
            advanceUntilIdle()

            coVerify {
                registerRepository.delete(
                    resourceType = ResourceType.QuestionnaireResponse,
                    resourceId = "draft-1",
                    softDelete = false,
                )
            }
        }

    /**
     * The draft and the case submitted from it are one resource: the draft's id survives the round
     * trip through QuestionnaireFragment, so the submitted response is stored under it and flips to
     * COMPLETED. Deleting by id here wiped every answer and screening image of a freshly registered
     * case — the Patient, Encounter and Observations remained, so the case opened blank rather than
     * missing.
     */
    @Test
    fun `the submitted case's response is never deleted, only a still-open draft is`() =
        runTest(testDispatcher) {
            engineHolds(QuestionnaireResponseStatus.COMPLETED)
            val draftId = storeDrafts("draft-1").single()
            val viewModel = createViewModel()
            viewModel.deleteIfNotOldDraft(draftId)
            advanceUntilIdle()

            viewModel.purgeSubmittedDraft()
            advanceUntilIdle()

            coVerify(exactly = 0) { registerRepository.delete(any(), any(), any()) }
        }

    /** The delete button on a draft card shares the same guard. */
    @Test
    fun `deleting a draft card cannot take a submitted case's response with it`() =
        runTest(testDispatcher) {
            engineHolds(QuestionnaireResponseStatus.COMPLETED)
            val viewModel = createViewModel()

            // Not in shared preferences — the branch that reaches for the engine copy.
            viewModel.softDeleteDraft("QuestionnaireResponse/draft-1")
            advanceUntilIdle()

            coVerify(exactly = 0) { registerRepository.delete(any(), any(), any()) }
        }

    /**
     * The marker survives an abandoned edit, so a later unrelated submission must not take the
     * abandoned draft down with it — that would delete work the user still expects to find.
     */
    @Test
    fun `a draft that was abandoned rather than submitted is left alone`() =
        runTest(testDispatcher) {
            val viewModel = createViewModel()
            viewModel.deleteIfNotOldDraft("QuestionnaireResponse/draft-1")
            advanceUntilIdle()
            // Backing out re-saves the draft, so it is in shared preferences again by submit time.
            storeDrafts("draft-1")

            viewModel.purgeSubmittedDraft()
            advanceUntilIdle()

            coVerify(exactly = 0) {
                registerRepository.delete(any(), any(), any())
            }
            assertEquals(
                "The abandoned draft must come back after the optimistic trim",
                1,
                viewModel.allSavedDraftResponse.value.size,
            )
        }

    @Test
    fun `submitting a fresh case with no draft open deletes nothing`() =
        runTest(testDispatcher) {
            storeDrafts("draft-1")
            val viewModel = createViewModel()
            viewModel.getAllDraftResponses()
            advanceUntilIdle()

            viewModel.purgeSubmittedDraft()
            advanceUntilIdle()

            coVerify(exactly = 0) { registerRepository.delete(any(), any(), any()) }
            assertEquals(1, viewModel.allSavedDraftResponse.value.size)
        }

    /**
     * Writes drafts exactly the way the app does, so the read side is symmetric by construction, and
     * returns the ids in the form the register actually hands around — HAPI qualifies a parsed
     * resource's id as "QuestionnaireResponse/<uuid>".
     */
    private fun storeDrafts(vararg ids: String): List<String> {
        val parser = FhirContext.forR4Cached().newJsonParser()
        val bundle =
            Bundle().apply {
                ids.forEach { id ->
                    addEntry(
                        Bundle.BundleEntryComponent().apply {
                            resource = QuestionnaireResponse().apply { this.id = id }
                        },
                    )
                }
            }
        saveBundleToSharedPreferences(sharedPreferencesHelper, parser, bundle)

        // Fail here rather than in an assertion further down if the round trip ever breaks.
        val readBack =
            parseDraftResponses(parser, getAllDraftsJsonFromSharedPreferences(sharedPreferencesHelper))
        assertEquals(
            "drafts did not round-trip through shared preferences",
            ids.size,
            readBack?.entry?.size ?: 0,
        )
        return readBack?.entry?.map { it.resource.id }.orEmpty()
    }

    private fun createViewModel() =
        RegisterViewModel(
            registerRepository = registerRepository,
            configurationRegistry = mockk<ConfigurationRegistry>(relaxed = true),
            sharedPreferencesHelper = sharedPreferencesHelper,
            dispatcherProvider =
                object : DispatcherProvider {
                    override fun io() = testDispatcher
                },
            secureSharedPreference = mockk<SecureSharedPreference>(relaxed = true),
            resourceDataRulesExecutor = mockk<ResourceDataRulesExecutor>(relaxed = true),
            fhirEngine = fhirEngine,
            featureFlagUtil = mockk<FeatureFlagUtil>(relaxed = true),
        )
            .also { viewModels.add(it) }
}
