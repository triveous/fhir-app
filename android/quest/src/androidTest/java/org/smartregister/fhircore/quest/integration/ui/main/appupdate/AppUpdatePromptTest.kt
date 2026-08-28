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

package org.smartregister.fhircore.quest.integration.ui.main.appupdate

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Assert
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.smartregister.fhircore.engine.util.AppUpdateRequirement
import org.smartregister.fhircore.engine.util.test.HiltActivityForTest
import org.smartregister.fhircore.quest.ui.main.appupdate.APP_UPDATE_ACTION_TAG
import org.smartregister.fhircore.quest.ui.main.appupdate.APP_UPDATE_MESSAGE_TAG
import org.smartregister.fhircore.quest.ui.main.appupdate.APP_UPDATE_PROMPT_TAG
import org.smartregister.fhircore.quest.ui.main.appupdate.AppUpdatePrompt
import org.smartregister.fhircore.quest.ui.main.appupdate.AppUpdateUiState

/**
 * Covers the forced (blocking) prompt. `forcedMessage` is the server-configured reason the update is
 * being forced, so it must reach the dialog verbatim — that is the regression these tests guard. It
 * is configured separately from the soft card's copy; see [SoftUpdateBannerTest].
 */
@HiltAndroidTest
class AppUpdatePromptTest {

  @get:Rule val hiltRule = HiltAndroidRule(this)

  @get:Rule val composeTestRule = createAndroidComposeRule<HiltActivityForTest>()

  @Before
  fun setUp() {
    hiltRule.inject()
  }

  @Test
  fun forcedPromptRendersServerConfiguredMessage() {
    composeTestRule.setContent {
      AppUpdatePrompt(
        uiState =
          AppUpdateUiState(
            requirement = AppUpdateRequirement.FORCED,
            forcedMessage = "App is 6 months out of date",
          ),
        onUpdate = {},
      )
    }

    composeTestRule.onNodeWithTag(APP_UPDATE_PROMPT_TAG, useUnmergedTree = true).assertExists()
    composeTestRule
      .onNodeWithTag(APP_UPDATE_MESSAGE_TAG, useUnmergedTree = true)
      .assertIsDisplayed()
    composeTestRule.onNodeWithText("App is 6 months out of date").assertIsDisplayed()
    composeTestRule.onNodeWithText("Critical update required").assertIsDisplayed()
  }

  @Test
  fun forcedPromptOmitsMessageLineWhenServerConfiguresNone() {
    composeTestRule.setContent {
      AppUpdatePrompt(
        uiState = AppUpdateUiState(requirement = AppUpdateRequirement.FORCED, forcedMessage = null),
        onUpdate = {},
      )
    }

    composeTestRule.onNodeWithTag(APP_UPDATE_PROMPT_TAG, useUnmergedTree = true).assertExists()
    composeTestRule.onNodeWithTag(APP_UPDATE_MESSAGE_TAG, useUnmergedTree = true).assertDoesNotExist()
  }

  @Test
  fun forcedPromptUpdateActionOpensStore() {
    var updateClicked = false
    composeTestRule.setContent {
      AppUpdatePrompt(
        uiState = AppUpdateUiState(requirement = AppUpdateRequirement.FORCED),
        onUpdate = { updateClicked = true },
      )
    }

    composeTestRule.onNodeWithTag(APP_UPDATE_ACTION_TAG, useUnmergedTree = true).performClick()
    Assert.assertTrue(updateClicked)
  }

  @Test
  fun softRequirementDoesNotRenderTheBlockingDialog() {
    composeTestRule.setContent {
      AppUpdatePrompt(
        uiState =
          AppUpdateUiState(requirement = AppUpdateRequirement.SOFT, softMessage = "Update available"),
        onUpdate = {},
      )
    }

    composeTestRule.onNodeWithTag(APP_UPDATE_PROMPT_TAG, useUnmergedTree = true).assertDoesNotExist()
  }

  @Test
  fun noRequirementRendersNothing() {
    composeTestRule.setContent {
      AppUpdatePrompt(uiState = AppUpdateUiState(), onUpdate = {})
    }

    composeTestRule.onNodeWithTag(APP_UPDATE_PROMPT_TAG, useUnmergedTree = true).assertDoesNotExist()
  }
}
