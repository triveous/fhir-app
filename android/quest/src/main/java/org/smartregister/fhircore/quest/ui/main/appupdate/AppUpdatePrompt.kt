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

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Card
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import org.smartregister.fhircore.engine.ui.theme.AppTheme
import org.smartregister.fhircore.engine.util.AppUpdateRequirement
import org.smartregister.fhircore.quest.R
import org.smartregister.fhircore.quest.theme.Colors.CRAYOLA
import org.smartregister.fhircore.quest.theme.Colors.CRAYOLA_LIGHT
import org.smartregister.fhircore.quest.theme.Colors.SIZZLING_RED
import org.smartregister.fhircore.quest.theme.Colors.WHITE
import org.smartregister.fhircore.quest.theme.bodyMedium
import org.smartregister.fhircore.quest.theme.bodyNormal

const val APP_UPDATE_PROMPT_TAG = "appUpdatePromptTag"
const val APP_UPDATE_ACTION_TAG = "appUpdateActionTag"
const val APP_UPDATE_MESSAGE_TAG = "appUpdateMessageTag"

/** The design's scrim (`711:2636`) is lighter than the platform's 0.6 default. */
private const val SCRIM_DIM_AMOUNT = 0.32f

/**
 * Blocking (forced) app-update prompt, shown as a themed modal dialog above the whole app. Renders
 * nothing unless [uiState]'s requirement is [AppUpdateRequirement.FORCED] — the non-blocking *soft*
 * card is rendered inline on the register screen by `SoftUpdateBanner`, not here.
 *
 * The dialog cannot be dismissed (back press and outside taps are ignored, and there is no "Later"
 * action), so the app stays inaccessible until the user updates.
 *
 * Laid out to the Figma frame `711:2609`. `usePlatformDefaultWidth = false` is required because the
 * design pins the card to 16dp side margins rather than the platform's narrower dialog width.
 */
@Composable
fun AppUpdatePrompt(
  uiState: AppUpdateUiState,
  onUpdate: () -> Unit,
) {
  if (uiState.requirement != AppUpdateRequirement.FORCED) return

  Dialog(
    onDismissRequest = {},
    properties =
      DialogProperties(
        dismissOnBackPress = false,
        dismissOnClickOutside = false,
        usePlatformDefaultWidth = false,
      ),
  ) {
    (LocalView.current.parent as? DialogWindowProvider)?.window?.let { window ->
      SideEffect { window.setDimAmount(SCRIM_DIM_AMOUNT) }
    }
    AppUpdateForcedCard(message = uiState.forcedMessage, onUpdate = onUpdate)
  }
}

@Composable
private fun AppUpdateForcedCard(message: String?, onUpdate: () -> Unit) {
  Card(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag(APP_UPDATE_PROMPT_TAG),
    shape = RoundedCornerShape(4.dp),
    backgroundColor = WHITE,
    elevation = 8.dp,
  ) {
    Column(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
      // The mark is inset inside a 76dp frame in the design; keeping the frame preserves the
      // 8dp of breathing room above the disc and the 24dp gap measured below it.
      Box(modifier = Modifier.size(76.dp), contentAlignment = Alignment.Center) {
        Image(
          painter = painterResource(id = R.drawable.ic_app_update_critical),
          contentDescription = null,
          modifier = Modifier.size(60.dp),
        )
      }

      Spacer(modifier = Modifier.height(24.dp))

      Text(
        text = stringResource(R.string.app_update_required_title),
        style = bodyMedium(18.sp).copy(lineHeight = 24.sp, letterSpacing = 0.15.sp),
        color = CRAYOLA,
      )

      Spacer(modifier = Modifier.height(12.dp))

      Text(
        text = stringResource(R.string.app_update_forced_message),
        style = bodyNormal(16.sp).copy(lineHeight = 24.sp, letterSpacing = 0.5.sp),
        color = CRAYOLA_LIGHT,
      )

      // Server-configured copy (the `forcedMessage` sub-extension on the feature-flags Basic, which
      // is configured separately from the soft card's). It is the reason the update is being forced —
      // e.g. "App is 6 months out of date" — so it is rendered in colorError beneath the fixed
      // explanation whenever the server supplies one.
      if (!message.isNullOrBlank()) {
        Spacer(modifier = Modifier.height(12.dp))
        Text(
          text = message,
          style = bodyNormal(16.sp).copy(lineHeight = 24.sp, letterSpacing = 0.5.sp),
          color = SIZZLING_RED,
          modifier = Modifier.testTag(APP_UPDATE_MESSAGE_TAG),
        )
      }

      Spacer(modifier = Modifier.height(24.dp))

      Button(
        onClick = onUpdate,
        modifier = Modifier.fillMaxWidth().height(44.dp).testTag(APP_UPDATE_ACTION_TAG),
        shape = RoundedCornerShape(2.dp),
        elevation = ButtonDefaults.elevation(defaultElevation = 2.dp, pressedElevation = 4.dp),
        colors = ButtonDefaults.buttonColors(backgroundColor = SIZZLING_RED, contentColor = WHITE),
      ) {
        Text(
          text = stringResource(R.string.app_update_action_now).uppercase(),
          style = bodyMedium(16.sp).copy(lineHeight = 20.sp, letterSpacing = 1.25.sp),
          color = WHITE,
        )
      }
    }
  }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun AppUpdatePromptForcedPreview() {
  AppTheme { AppUpdateForcedCard(message = "App is 6 months out of date", onUpdate = {}) }
}
