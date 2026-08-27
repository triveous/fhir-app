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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.InstallMobile
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.smartregister.fhircore.engine.ui.theme.AppTheme
import org.smartregister.fhircore.engine.ui.theme.LightGreyBackground
import org.smartregister.fhircore.engine.ui.theme.PrimaryColor
import org.smartregister.fhircore.engine.util.AppUpdateRequirement
import org.smartregister.fhircore.quest.R
import org.smartregister.fhircore.quest.theme.bodyMedium
import org.smartregister.fhircore.quest.theme.bodyNormal

const val SOFT_UPDATE_BANNER_TAG = "softUpdateBannerTag"
const val SOFT_UPDATE_DISMISS_TAG = "softUpdateDismissTag"

private val TitleColor = LightGreyBackground
private val BodyColor = LightGreyBackground.copy(alpha = 0.67f)

/**
 * Non-blocking soft-update card shown above the "Add New Case" button on the register's empty/home
 * state (see `NoRegisterDataView`). Renders nothing unless [uiState]'s requirement is
 * [AppUpdateRequirement.SOFT], [uiState.softUpdateDismissed] is false, and [isOnline] is true — the
 * card is deliberately hidden offline since its only action (opening the Play Store) cannot succeed
 * without a connection.
 *
 * Tapping anywhere on the card — other than the close (X) button — opens the Play Store via
 * [onUpdate]. The close button calls [onDismiss] (wired to
 * [org.smartregister.fhircore.quest.ui.main.AppMainViewModel.dismissSoftUpdateBanner]) instead of
 * updating, so the card stays hidden for the rest of this app session; it reappears the next time the
 * app is opened fresh. [AppUpdateUiState.message] supplies server-configured copy that replaces the
 * whole description; when absent, the built-in copy is shown with its trailing call-to-action styled
 * as a link for visual affordance (the whole card is still the tap target either way).
 */
@Composable
fun SoftUpdateBanner(
  uiState: AppUpdateUiState,
  onUpdate: () -> Unit,
  onDismiss: () -> Unit,
  isOnline: Boolean,
  modifier: Modifier = Modifier,
) {
  if (uiState.requirement != AppUpdateRequirement.SOFT) return
  if (uiState.softUpdateDismissed) return
  if (!isOnline) return

  val customMessage = uiState.message?.takeIf { it.isNotBlank() }

  Box(
    modifier = modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(4.dp))
      .background(Color.White)
      .border(1.dp, PrimaryColor, RoundedCornerShape(4.dp))
      .clickable(onClick = onUpdate)
      .testTag(SOFT_UPDATE_BANNER_TAG),
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(12.dp),
      verticalAlignment = Alignment.Top,
    ) {
      Icon(
        imageVector = Icons.Outlined.InstallMobile,
        contentDescription = null,
        tint = TitleColor,
        modifier = Modifier.size(24.dp),
      )

      Spacer(modifier = Modifier.width(8.dp))

      Column(modifier = Modifier.weight(1f).padding(end = 24.dp)) {
        Text(
          text = stringResource(R.string.app_update_available_title),
          style = bodyMedium(16.sp),
          color = TitleColor,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
          text = customMessage?.let { buildAnnotatedString { append(it) } }
            ?: buildAnnotatedString {
              append(stringResource(R.string.app_update_soft_message_lead))
              withStyle(SpanStyle(color = PrimaryColor)) {
                append(stringResource(R.string.app_update_soft_message_cta))
              }
            },
          style = bodyNormal(14.sp),
          color = BodyColor,
        )
      }
    }

    IconButton(
      onClick = onDismiss,
      modifier = Modifier
        .align(Alignment.TopEnd)
        .size(28.dp)
        .testTag(SOFT_UPDATE_DISMISS_TAG),
    ) {
      Icon(
        imageVector = Icons.Filled.Close,
        contentDescription = stringResource(R.string.app_update_dismiss),
        tint = BodyColor,
        modifier = Modifier.size(20.dp),
      )
    }
  }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun SoftUpdateBannerPreview() {
  AppTheme {
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
      SoftUpdateBanner(
        uiState = AppUpdateUiState(requirement = AppUpdateRequirement.SOFT),
        onUpdate = {},
        onDismiss = {},
        isOnline = true,
      )
      Spacer(modifier = Modifier.height(12.dp))
      SoftUpdateBanner(
        uiState =
          AppUpdateUiState(
            requirement = AppUpdateRequirement.SOFT,
            message = "New in this release: faster case sync and a fix for lost photos. Please update.",
          ),
        onUpdate = {},
        onDismiss = {},
        isOnline = true,
      )
    }
  }
}
