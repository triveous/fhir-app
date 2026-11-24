/*
 * Copyright 2026 Indian Institute of Science (IISc)
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

package org.smartregister.fhircore.quest.ui.register.profile

import android.app.Application
import android.os.Build
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.smartregister.fhircore.quest.ml.ActiveAiModel
import org.smartregister.fhircore.quest.ml.AiModelSelection
import org.smartregister.fhircore.quest.ml.ModelCatalog
import org.smartregister.fhircore.quest.ml.RuleSource
import org.smartregister.fhircore.quest.util.AiStatus

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [Build.VERSION_CODES.Q])
class DeveloperInfoDialogTest {

  @get:Rule val composeRule = createComposeRule()

  private val threeClass = ModelCatalog.THREE_CLASS

  private fun show(status: AiStatus?, selection: AiModelSelection?) {
    composeRule.setContent { DeveloperInfoDialog(status, selection, onDismissDialog = {}) }
    composeRule.onNodeWithTag(DEVELOPER_INFO_DIALOG_TEST_TAG).assertExists()
    composeRule.onNodeWithText("Developers Only").assertExists()
  }

  private fun assertShown(vararg lines: String) =
    lines.forEach { composeRule.onNodeWithText(it).assertExists() }

  private fun ready(active: ActiveAiModel) = AiModelSelection.Ready(active)

  @Test
  fun showsTheFlagTheExtractionAndTheAppsOwnRule() {
    show(
      AiStatus(flagEnabled = true, extractionEnabled = false),
      ready(ActiveAiModel(threeClass, threeClass.defaultRule, RuleSource.SHIPPED)),
    )

    assertShown(
      "AI Enabled",
      "Extraction Disabled",
      "AI model: oral-3class-v1",
      "Models running: m1, m2",
      "Rule: oral-3class-v1/m1-m2-table",
      "Rule set by: App default",
    )
    composeRule.onNodeWithText("Feature flag setting ignored", substring = true).assertDoesNotExist()
  }

  @Test
  fun aRuleFromTheFeatureFlagIsShownAsSetByTheFlag() {
    val remote = threeClass.defaultRule.copy(id = "oral-3class-v1/m1-m2-table-v2")

    show(
      AiStatus(flagEnabled = false, extractionEnabled = true),
      ready(ActiveAiModel(threeClass, remote, RuleSource.REMOTE)),
    )

    assertShown(
      "AI Disabled",
      "Extraction Enabled",
      "Rule: oral-3class-v1/m1-m2-table-v2",
      "Rule set by: Feature flag",
    )
  }

  @Test
  fun oneModelChosenByTheFlagIsShownAsSetByTheFlag() {
    val oneModel =
      threeClass.defaultRule.copy(id = "oral-3class-v1/m1-only", members = listOf("m1"))

    show(null, ready(ActiveAiModel(threeClass, oneModel, RuleSource.SHIPPED)))

    assertShown("Models running: m1", "Rule: oral-3class-v1/m1-only", "Rule set by: Feature flag")
  }

  @Test
  fun aFlagSettingThatFailedTheChecksIsShownWithItsReason() {
    show(
      null,
      ready(
        ActiveAiModel(
          threeClass,
          threeClass.defaultRule,
          RuleSource.SHIPPED,
          ignoredSetting = "rule: schema 2 is newer than this app supports",
        ),
      ),
    )

    assertShown(
      "Rule set by: App default",
      "Feature flag setting ignored: rule: schema 2 is newer than this app supports",
    )
  }

  @Test
  fun aModelThisAppCannotRunIsShownAsUnavailable() {
    show(
      null,
      AiModelSelection.Unavailable("oral-4class-v1", AiModelSelection.Reason.UNKNOWN_MODEL),
    )

    assertShown("AI model: oral-4class-v1", "Not available in this app version (unknown_model)")
  }

  @Test
  fun nothingReadableShowsNotAvailable() {
    show(null, null)

    assertShown("Not available")
  }
}
