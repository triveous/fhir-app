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
class LogoutPendingSyncDialogTest {

  @get:Rule val composeRule = createComposeRule()

  @Test
  fun showsThePendingCasesAndBothChoices() {
    composeRule.setContent {
      AppTheme { LogoutPendingSyncCard(caseCount = 3, onLogout = {}, onSyncNow = {}) }
    }

    composeRule.onNodeWithText("3 cases pending to sync").assertIsDisplayed()
    composeRule
      .onNodeWithText("Sync your device before logging out to prevent data loss")
      .assertIsDisplayed()
    composeRule.onNodeWithText("LOG OUT").assertIsDisplayed()
    composeRule.onNodeWithText("SYNC NOW").assertIsDisplayed()
  }

  @Test
  fun oneCaseUsesTheSingular() {
    composeRule.setContent {
      AppTheme { LogoutPendingSyncCard(caseCount = 1, onLogout = {}, onSyncNow = {}) }
    }

    composeRule.onNodeWithText("1 case pending to sync").assertIsDisplayed()
  }

  @Test
  fun pendingDataWithoutACaseDropsTheCount() {
    composeRule.setContent {
      AppTheme { LogoutPendingSyncCard(caseCount = 0, onLogout = {}, onSyncNow = {}) }
    }

    composeRule.onNodeWithText("Data pending to sync").assertIsDisplayed()
  }

  @Test
  fun eachButtonReportsItsChoice() {
    var logouts = 0
    var syncs = 0
    composeRule.setContent {
      AppTheme {
        LogoutPendingSyncCard(caseCount = 3, onLogout = { logouts++ }, onSyncNow = { syncs++ })
      }
    }

    composeRule.onNodeWithTag(LOGOUT_PENDING_SYNC_LOGOUT_TAG).performClick()
    composeRule.onNodeWithTag(LOGOUT_PENDING_SYNC_SYNC_NOW_TAG).performClick()

    assertEquals(1, logouts)
    assertEquals(1, syncs)
  }
}
