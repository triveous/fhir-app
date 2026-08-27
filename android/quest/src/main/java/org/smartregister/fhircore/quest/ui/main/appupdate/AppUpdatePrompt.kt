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

package org.smartregister.fhircore.quest.ui.main.appupdate

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Card
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Error
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.smartregister.fhircore.engine.ui.theme.AppTheme
import org.smartregister.fhircore.engine.ui.theme.LightGreyBackground
import org.smartregister.fhircore.engine.util.AppUpdateRequirement
import org.smartregister.fhircore.quest.R
import org.smartregister.fhircore.quest.theme.bodyMedium
import org.smartregister.fhircore.quest.theme.bodyNormal

const val APP_UPDATE_PROMPT_TAG = "appUpdatePromptTag"
const val APP_UPDATE_ACTION_TAG = "appUpdateActionTag"

/** Matches Figma's `colorError`/`red1` token used for the critical-update dialog and its button. */
private val CriticalColor = Color(0xFFFF3355)

/**
 * Blocking (forced) app-update prompt, shown as a themed modal dialog above the whole app. Renders
 * nothing unless [uiState]'s requirement is [AppUpdateRequirement.FORCED] — the non-blocking *soft*
 * card is rendered inline on the register screen by `SoftUpdateBanner`, not here.
 *
 * The dialog cannot be dismissed (back press and outside taps are ignored, and there is no "Later"
 * action), so the app stays inaccessible until the user updates. This blocking behaviour is
 * unaffected by the soft-update visual redesign — only the forced card's own layout/copy changed.
 */
@Composable
fun AppUpdatePrompt(
  uiState: AppUpdateUiState,
  onUpdate: () -> Unit,
) {
  if (uiState.requirement != AppUpdateRequirement.FORCED) return

  Dialog(
    onDismissRequest = {},
    properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
  ) {
    AppUpdateForcedCard(message = uiState.message, onUpdate = onUpdate)
  }
}

@Composable
private fun AppUpdateForcedCard(message: String?, onUpdate: () -> Unit) {
  Card(
    modifier = Modifier.fillMaxWidth().testTag(APP_UPDATE_PROMPT_TAG),
    shape = RoundedCornerShape(4.dp),
    elevation = 8.dp,
  ) {
    Column(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
      Box(
        modifier = Modifier.size(64.dp).clip(CircleShape).background(CriticalColor),
        contentAlignment = Alignment.Center,
      ) {
        Icon(
          imageVector = Icons.Filled.Error,
          contentDescription = null,
          tint = Color.White,
          modifier = Modifier.size(36.dp),
        )
      }

      Spacer(modifier = Modifier.height(20.dp))

      Text(
        text = stringResource(R.string.app_update_required_title),
        style = bodyMedium(18.sp),
        color = LightGreyBackground,
      )

      Spacer(modifier = Modifier.height(8.dp))

      Text(
        text = stringResource(R.string.app_update_forced_message),
        style = bodyNormal(16.sp),
        color = LightGreyBackground.copy(alpha = 0.67f),
      )

      if (!message.isNullOrBlank()) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = message, style = bodyNormal(16.sp), color = CriticalColor)
      }

      Spacer(modifier = Modifier.height(20.dp))

      Button(
        onClick = onUpdate,
        modifier = Modifier.fillMaxWidth().height(44.dp).testTag(APP_UPDATE_ACTION_TAG),
        shape = RoundedCornerShape(2.dp),
        elevation = ButtonDefaults.elevation(defaultElevation = 0.dp, pressedElevation = 0.dp),
        colors = ButtonDefaults.buttonColors(backgroundColor = CriticalColor, contentColor = Color.White),
      ) {
        Text(
          text = stringResource(R.string.app_update_action_now).uppercase(),
          style = bodyMedium(16.sp),
          color = Color.White,
        )
      }
    }
  }
}

@Preview(showBackground = true)
@Composable
private fun AppUpdatePromptForcedPreview() {
  AppTheme { AppUpdateForcedCard(message = "App is 6 months out of date", onUpdate = {}) }
}
