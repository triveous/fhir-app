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

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.smartregister.fhircore.engine.ui.theme.AppTheme
import org.smartregister.fhircore.engine.ui.theme.PrimaryColor
import org.smartregister.fhircore.engine.ui.theme.SubtitleTextColor
import org.smartregister.fhircore.engine.util.AppUpdateRequirement
import org.smartregister.fhircore.quest.R
import org.smartregister.fhircore.quest.theme.bodyBold
import org.smartregister.fhircore.quest.theme.bodyNormal

const val SOFT_UPDATE_BANNER_TAG = "softUpdateBannerTag"
const val SOFT_UPDATE_ACTION_TAG = "softUpdateActionTag"

private val AccentColor = PrimaryColor
private val TitleColor = Color(0xFF1D2733)

/**
 * Non-blocking soft-update nudge shown as a horizontal badge directly above the register's
 * "Add New Case" button (see `NoRegisterDataView`). Renders nothing unless [uiState]'s requirement
 * is [AppUpdateRequirement.SOFT].
 *
 * The nudge is **persistent**: it is not dismissible and stays visible on every launch for as long
 * as a soft update is available, until the user updates (which raises the installed version code at
 * or above the configured latest, resolving the requirement to `NONE`). It never blocks the app —
 * tapping **Update** opens the store. [AppUpdateUiState.message] supplies the server-configured copy;
 * when absent the built-in [R.string.app_update_soft_message] is used.
 */
@Composable
fun SoftUpdateBanner(
  uiState: AppUpdateUiState,
  onUpdate: () -> Unit,
  modifier: Modifier = Modifier,
) {
  if (uiState.requirement != AppUpdateRequirement.SOFT) return

  val message = uiState.message?.takeIf { it.isNotBlank() }
    ?: stringResource(R.string.app_update_soft_message)

  Surface(
    modifier = modifier.fillMaxWidth().testTag(SOFT_UPDATE_BANNER_TAG),
    shape = RoundedCornerShape(14.dp),
    color = AccentColor.copy(alpha = 0.06f),
    border = BorderStroke(1.dp, AccentColor.copy(alpha = 0.20f)),
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(start = 12.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Box(
        modifier = Modifier.size(40.dp).clip(CircleShape).background(AccentColor.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
      ) {
        Icon(
          imageVector = Icons.Rounded.SystemUpdate,
          contentDescription = null,
          tint = AccentColor,
          modifier = Modifier.size(22.dp),
        )
      }

      Spacer(modifier = Modifier.width(12.dp))

      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = stringResource(R.string.app_update_available_title),
          style = bodyBold(14.sp),
          color = TitleColor,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
          text = message,
          style = bodyNormal(12.sp),
          color = SubtitleTextColor,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
      }

      Spacer(modifier = Modifier.width(12.dp))

      Button(
        onClick = onUpdate,
        modifier = Modifier.height(38.dp).testTag(SOFT_UPDATE_ACTION_TAG),
        shape = RoundedCornerShape(10.dp),
        contentPadding = PaddingValues(horizontal = 18.dp),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp, pressedElevation = 0.dp),
        colors = ButtonDefaults.buttonColors(containerColor = AccentColor, contentColor = Color.White),
      ) {
        Text(
          text = stringResource(R.string.app_update_action),
          style = bodyBold(13.sp),
          color = Color.White,
        )
      }
    }
  }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun SoftUpdateBannerPreview() {
  AppTheme {
    Column(
      modifier = Modifier.fillMaxWidth().padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      SoftUpdateBanner(
        uiState = AppUpdateUiState(requirement = AppUpdateRequirement.SOFT, latestVersionName = "AA_v1.7.8"),
        onUpdate = {},
      )
      SoftUpdateBanner(
        uiState =
          AppUpdateUiState(
            requirement = AppUpdateRequirement.SOFT,
            message = "New in this release: faster case sync and a fix for lost photos. Please update.",
          ),
        onUpdate = {},
      )
    }
  }
}
