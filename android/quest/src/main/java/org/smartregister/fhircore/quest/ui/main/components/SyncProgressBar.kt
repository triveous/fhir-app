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

package org.smartregister.fhircore.quest.ui.main.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.smartregister.fhircore.engine.ui.theme.LightColors
import org.smartregister.fhircore.engine.ui.theme.SubtitleTextColor
import org.smartregister.fhircore.quest.R
import org.smartregister.fhircore.quest.theme.body12Medium
import org.smartregister.fhircore.quest.theme.body14Medium
import org.smartregister.fhircore.quest.ui.main.SyncProgressUiState

const val SYNC_PROGRESS_BAR_TAG = "syncProgressBarTag"

/**
 * Non-blocking, floating sync progress bar rendered just above the bottom navigation bar. Unlike the
 * previous blocking [org.smartregister.fhircore.engine.ui.components.register.LoaderDialog], this
 * lets the user keep using the app while data continues downloading in the background, and stays
 * visible until the sync reaches a terminal state (see [SyncProgressUiState]).
 */
@Composable
fun SyncProgressBar(
  syncProgressUiState: SyncProgressUiState,
  modifier: Modifier = Modifier,
) {
  AnimatedVisibility(
    visible = syncProgressUiState.isSyncing,
    enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
    exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
  ) {
    val label =
      when {
        syncProgressUiState.isUploadSync -> stringResource(R.string.sync_bar_uploading)
        syncProgressUiState.isFirstTimeSync -> stringResource(R.string.sync_bar_first_time)
        else -> stringResource(R.string.sync_bar_downloading)
      }

    Surface(
      modifier =
        modifier
          .testTag(SYNC_PROGRESS_BAR_TAG)
          .fillMaxWidth()
          .padding(horizontal = 12.dp, vertical = 8.dp),
      shape = RoundedCornerShape(12.dp),
      color = Color.White,
      elevation = 6.dp,
    ) {
      Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            CircularProgressIndicator(
              color = LightColors.primary,
              strokeWidth = 2.dp,
              modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.size(10.dp))
            Text(
              text = label,
              style = body14Medium(),
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
          // Only show a percentage once we have a real, determinate value. While the total is
          // unknown the bar is indeterminate, so a "0%" label would be misleading.
          if (syncProgressUiState.progressPercentage > 0) {
            Text(
              text = "${syncProgressUiState.progressPercentage}%",
              style = body14Medium().copy(color = LightColors.primary),
            )
          }
        }

        Spacer(modifier = Modifier.size(8.dp))

        val animatedProgress by
          animateFloatAsState(
            targetValue = syncProgressUiState.progressPercentage.coerceIn(0, 100) / 100f,
            animationSpec = tween(durationMillis = 300),
            label = "syncProgress",
          )
        if (syncProgressUiState.progressPercentage <= 0) {
          // Sync just started and the SDK has not reported counts yet — show an indeterminate bar.
          LinearProgressIndicator(
            modifier =
              Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
            color = LightColors.primary,
            backgroundColor = LightColors.primary.copy(alpha = 0.15f),
          )
        } else {
          LinearProgressIndicator(
            progress = animatedProgress,
            modifier =
              Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
            color = LightColors.primary,
            backgroundColor = LightColors.primary.copy(alpha = 0.15f),
          )
        }

        Spacer(modifier = Modifier.size(6.dp))

        Text(
          text = stringResource(R.string.sync_bar_in_background),
          style = body12Medium().copy(color = SubtitleTextColor),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
  }
}

@Preview(showBackground = true)
@Composable
private fun SyncProgressBarPreview() {
  SyncProgressBar(
    syncProgressUiState =
      SyncProgressUiState(
        isSyncing = true,
        progressPercentage = 42,
        isUploadSync = false,
        isFirstTimeSync = true,
      ),
  )
}
