package org.smartregister.fhircore.quest.ui.shared.components

import android.app.Application
import android.os.Build
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.smartregister.fhircore.engine.ui.theme.AppTheme

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [Build.VERSION_CODES.Q])
class AiEnableFailedDialogTest {

  @get:Rule val composeRule = createComposeRule()

  @Test
  fun onlineShowsTheFailureAndRetryWithoutTheInternetHint() {
    composeRule.setContent { AppTheme { AiEnableFailedCard(isOnline = true, onRetry = {}) } }

    composeRule.onNodeWithText("Failed to enable AI").assertIsDisplayed()
    composeRule
      .onNodeWithText("AI could not be enabled due to a technical issue. Please try again.")
      .assertIsDisplayed()
    composeRule.onNodeWithText("RETRY").assertIsDisplayed()
    composeRule.onNodeWithTag(AI_ENABLE_OFFLINE_MESSAGE_TAG).assertDoesNotExist()
  }

  @Test
  fun offlineAddsTheInternetHint() {
    composeRule.setContent { AppTheme { AiEnableFailedCard(isOnline = false, onRetry = {}) } }

    composeRule
      .onNodeWithText("Enabling AI requires internet. Check your connection and try again.")
      .assertIsDisplayed()
  }

  @Test
  fun retryReportsEachTap() {
    var retries = 0
    composeRule.setContent { AppTheme { AiEnableFailedCard(isOnline = true, onRetry = { retries++ }) } }

    composeRule.onNodeWithTag(AI_ENABLE_RETRY_TAG).performClick()

    assertEquals(1, retries)
  }
}
