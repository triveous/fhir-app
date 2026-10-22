package org.smartregister.fhircore.quest.ui.register.profile

import android.app.Application
import android.os.Build
import androidx.compose.material.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.smartregister.fhircore.quest.util.AiStatus

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [Build.VERSION_CODES.Q])
class AiStatusLabelTest {

  @get:Rule val composeRule = createComposeRule()

  private fun assertLabel(status: AiStatus, expected: String) {
    composeRule.setContent { Text(aiStatusLabel(status)) }
    composeRule.onNodeWithText(expected).assertExists()
  }

  @Test
  fun flagOnExtractionPresent() =
    assertLabel(AiStatus(flagEnabled = true, extractionEnabled = true), "AI Enabled + Extraction Enabled")

  @Test
  fun flagOnExtractionMissing() =
    assertLabel(AiStatus(flagEnabled = true, extractionEnabled = false), "AI Enabled + Extraction Disabled")

  @Test
  fun flagOffExtractionPresent() =
    assertLabel(AiStatus(flagEnabled = false, extractionEnabled = true), "AI Disabled + Extraction Enabled")

  @Test
  fun flagOffExtractionMissing() =
    assertLabel(AiStatus(flagEnabled = false, extractionEnabled = false), "AI Disabled + Extraction Disabled")
}
