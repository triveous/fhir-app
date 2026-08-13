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

package org.smartregister.fhircore.quest.integration.util.dailog

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.pressBack
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.smartregister.fhircore.quest.R
import org.smartregister.fhircore.quest.util.dailog.FOREGROUND_SYNC_DIALOG_CONTENT_TAG
import org.smartregister.fhircore.quest.util.dailog.FOREGROUND_SYNC_DIALOG_ERROR_TAG
import org.smartregister.fhircore.quest.util.dailog.FOREGROUND_SYNC_DIALOG_LOADING_TAG
import org.smartregister.fhircore.quest.util.dailog.ForegroundSyncDialog
import org.smartregister.fhircore.quest.util.dailog.ForegroundSyncDialogState

class ForegroundSyncDialogTest {

    @get:Rule(order = 1) val composeRule = createEmptyComposeRule()

    private lateinit var scenario: ActivityScenario<ComponentActivity>
    private lateinit var activity: ComponentActivity

    @Before
    fun setUp() {
        scenario = ActivityScenario.launch(ComponentActivity::class.java)
    }

    @After
    fun tearDown() {
        scenario.close()
    }

    @Test
    fun loadingStateHidesCountsAndActionsAndCannotBeDismissed() {
        var dismissCount = 0
        initComposable(
            state = ForegroundSyncDialogState.Loading,
            onDismiss = { dismissCount++ },
        )

        composeRule.onNodeWithTag(FOREGROUND_SYNC_DIALOG_LOADING_TAG).assertExists()
        composeRule.onNodeWithText(activity.getString(R.string.image_left, "0")).assertDoesNotExist()
        composeRule
            .onNodeWithText(activity.getString(R.string.patients_left, "0"))
            .assertDoesNotExist()
        composeRule.onNodeWithText(activity.getString(R.string.okay)).assertDoesNotExist()
        composeRule.onNodeWithText(activity.getString(R.string.sync_now)).assertDoesNotExist()

        pressBack()

        composeRule.onNodeWithTag(FOREGROUND_SYNC_DIALOG_LOADING_TAG).assertExists()
        assertEquals(0, dismissCount)
    }

    @Test
    fun loadedStateShowsFreshCountsAndActions() {
        initComposable(state = ForegroundSyncDialogState.Loaded(imageCount = 4, patientsCount = 2))

        composeRule.onNodeWithTag(FOREGROUND_SYNC_DIALOG_CONTENT_TAG).assertExists()
        composeRule.onNodeWithText(activity.getString(R.string.image_left, "4"), substring = true)
            .assertExists()
        composeRule.onNodeWithText(activity.getString(R.string.patients_left, "2"), substring = true)
            .assertExists()
        composeRule.onNodeWithText(activity.getString(R.string.okay)).assertExists()
        composeRule.onNodeWithText(activity.getString(R.string.sync_now)).assertExists()
    }

    @Test
    fun failedStateShowsRetryInsteadOfCounts() {
        var retryCount = 0
        initComposable(
            state = ForegroundSyncDialogState.Failed,
            onRetry = { retryCount++ },
        )

        composeRule.onNodeWithTag(FOREGROUND_SYNC_DIALOG_ERROR_TAG).assertExists()
        composeRule.onNodeWithTag(FOREGROUND_SYNC_DIALOG_CONTENT_TAG).assertDoesNotExist()
        composeRule
            .onNodeWithText(
                activity.getString(org.smartregister.fhircore.engine.R.string.try_again),
            ).performClick()
        assertEquals(1, retryCount)
    }

    private fun initComposable(
        state: ForegroundSyncDialogState,
        onDismiss: () -> Unit = {},
        onRetry: () -> Unit = {},
    ) {
        scenario.onActivity { currentActivity ->
            activity = currentActivity
            currentActivity.setContent {
                ForegroundSyncDialog(
                    showDialog = true,
                    title = currentActivity.getString(R.string.sync_status),
                    state = state,
                    dismissButtonText = currentActivity.getString(R.string.okay),
                    confirmButtonText = currentActivity.getString(R.string.sync_now),
                    onDismiss = onDismiss,
                    onConfirm = {},
                    onRetry = onRetry,
                )
            }
        }
    }
}
