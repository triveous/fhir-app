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
import org.smartregister.fhircore.quest.ui.main.appupdate.SOFT_UPDATE_BANNER_TAG
import org.smartregister.fhircore.quest.ui.main.appupdate.SOFT_UPDATE_DISMISS_TAG
import org.smartregister.fhircore.quest.ui.main.appupdate.AppUpdateUiState
import org.smartregister.fhircore.quest.ui.main.appupdate.SoftUpdateBanner

@HiltAndroidTest
class SoftUpdateBannerTest {

  @get:Rule val hiltRule = HiltAndroidRule(this)

  @get:Rule val composeTestRule = createAndroidComposeRule<HiltActivityForTest>()

  @Before
  fun setUp() {
    hiltRule.inject()
  }

  @Test
  fun softBannerShowsBuiltInCopyWhenServerConfiguresNoMessage() {
    composeTestRule.setContent {
      SoftUpdateBanner(
        uiState = AppUpdateUiState(requirement = AppUpdateRequirement.SOFT),
        onUpdate = {},
        onDismiss = {},
        isOnline = true,
      )
    }

    composeTestRule.onNodeWithTag(SOFT_UPDATE_BANNER_TAG, useUnmergedTree = true).assertExists()
    composeTestRule.onNodeWithText("Update app").assertIsDisplayed()
    composeTestRule
      .onNodeWithText("A new version is available with latest improvements. Click to update.")
      .assertIsDisplayed()
  }

  /**
   * The server message replaces the lead sentence only. "Click to update." is the card's tap
   * affordance and stays put, so a configured message can never leave the card looking inert.
   */
  @Test
  fun softBannerKeepsCallToActionAlongsideServerConfiguredMessage() {
    composeTestRule.setContent {
      SoftUpdateBanner(
        uiState =
          AppUpdateUiState(
            requirement = AppUpdateRequirement.SOFT,
            softMessage = "A new version is available. Please update.",
          ),
        onUpdate = {},
        onDismiss = {},
        isOnline = true,
      )
    }

    composeTestRule
      .onNodeWithText("A new version is available. Please update. Click to update.")
      .assertIsDisplayed()
  }

  /**
   * Operators tend to configure the whole sentence, call-to-action included. The card must not then
   * read "… Click to update. Click to update."
   */
  @Test
  fun softBannerDoesNotDuplicateACallToActionAlreadyInTheMessage() {
    composeTestRule.setContent {
      SoftUpdateBanner(
        uiState =
          AppUpdateUiState(
            requirement = AppUpdateRequirement.SOFT,
            softMessage = "A new version is available with latest improvements. Click to update.",
          ),
        onUpdate = {},
        onDismiss = {},
        isOnline = true,
      )
    }

    composeTestRule
      .onNodeWithText("A new version is available with latest improvements. Click to update.")
      .assertIsDisplayed()
  }

  @Test
  fun tappingTheCardOpensTheStoreAndTheCloseButtonDismisses() {
    var updateClicked = false
    var dismissed = false
    composeTestRule.setContent {
      SoftUpdateBanner(
        uiState = AppUpdateUiState(requirement = AppUpdateRequirement.SOFT),
        onUpdate = { updateClicked = true },
        onDismiss = { dismissed = true },
        isOnline = true,
      )
    }

    composeTestRule.onNodeWithTag(SOFT_UPDATE_DISMISS_TAG, useUnmergedTree = true).performClick()
    Assert.assertTrue(dismissed)
    Assert.assertFalse(updateClicked)

    composeTestRule.onNodeWithTag(SOFT_UPDATE_BANNER_TAG, useUnmergedTree = true).performClick()
    Assert.assertTrue(updateClicked)
  }

  @Test
  fun softBannerHiddenWhenDismissedOfflineOrNotRequired() {
    composeTestRule.setContent {
      SoftUpdateBanner(
        uiState =
          AppUpdateUiState(requirement = AppUpdateRequirement.SOFT, softUpdateDismissed = true),
        onUpdate = {},
        onDismiss = {},
        isOnline = true,
      )
      SoftUpdateBanner(
        uiState = AppUpdateUiState(requirement = AppUpdateRequirement.SOFT),
        onUpdate = {},
        onDismiss = {},
        isOnline = false,
      )
      SoftUpdateBanner(
        uiState = AppUpdateUiState(requirement = AppUpdateRequirement.FORCED),
        onUpdate = {},
        onDismiss = {},
        isOnline = true,
      )
    }

    composeTestRule
      .onNodeWithTag(SOFT_UPDATE_BANNER_TAG, useUnmergedTree = true)
      .assertDoesNotExist()
  }
}
