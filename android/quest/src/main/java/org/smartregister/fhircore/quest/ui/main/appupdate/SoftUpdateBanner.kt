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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.smartregister.fhircore.engine.ui.theme.AppTheme
import org.smartregister.fhircore.engine.util.AppUpdateRequirement
import org.smartregister.fhircore.quest.R
import org.smartregister.fhircore.quest.theme.Colors.BRANDEIS_BLUE
import org.smartregister.fhircore.quest.theme.Colors.CRAYOLA
import org.smartregister.fhircore.quest.theme.Colors.CRAYOLA_LIGHT
import org.smartregister.fhircore.quest.theme.Colors.WHITE
import org.smartregister.fhircore.quest.theme.bodyMedium
import org.smartregister.fhircore.quest.theme.bodyNormal

const val SOFT_UPDATE_BANNER_TAG = "softUpdateBannerTag"
const val SOFT_UPDATE_DISMISS_TAG = "softUpdateDismissTag"

/**
 * Non-blocking soft-update card, pinned at the top of the register above the "Add New Case" button
 * (see `RegisterScreen`). Renders nothing unless [uiState]'s requirement is
 * [AppUpdateRequirement.SOFT], [AppUpdateUiState.softUpdateDismissed] is false, and [isOnline] is
 * true — the card is deliberately hidden offline since its only action (opening the Play Store)
 * cannot succeed without a connection.
 *
 * Laid out to the Figma frame `711:2370`: a white 4dp card with a 1dp blue1 outline, 12dp padding, a
 * 24dp `install_mobile` mark, a "Update app" title and a two-tone description whose trailing "Click
 * to update." is blue1 with a dashed underline. Tapping anywhere on the card — other than the close
 * (X) button — opens the Play Store via [onUpdate]. The close button calls [onDismiss] (wired to
 * [org.smartregister.fhircore.quest.ui.main.AppMainViewModel.dismissSoftUpdateBanner]) instead of
 * updating, so the card stays hidden for the rest of this app session; it reappears the next time
 * the app is opened fresh.
 *
 * [AppUpdateUiState.softMessage] is the server-configured release note for this card (the forced
 * dialog has its own, separate copy). It substitutes for the built-in lead sentence — the "Click to
 * update." call-to-action is part of the pattern and is always present, so the card never loses its
 * tap affordance however the message is worded. A message that already ends with the call-to-action
 * is rendered as-is rather than gaining a second one.
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

  Box(
    modifier = modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(4.dp))
      .background(WHITE)
      .border(1.dp, BRANDEIS_BLUE, RoundedCornerShape(4.dp))
      .clickable(onClick = onUpdate)
      .testTag(SOFT_UPDATE_BANNER_TAG),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(12.dp),
      verticalAlignment = Alignment.Top,
    ) {
      Icon(
        painter = painterResource(id = R.drawable.ic_install_mobile),
        contentDescription = null,
        tint = CRAYOLA,
        modifier = Modifier.size(24.dp),
      )

      Spacer(modifier = Modifier.width(8.dp))

      Column(modifier = Modifier.weight(1f)) {
        Text(
          // Reserve the top-right corner for the close button so a long title never runs under it.
          modifier = Modifier.padding(end = 28.dp),
          text = stringResource(R.string.app_update_available_title),
          style = bodyMedium(16.sp).copy(lineHeight = 24.sp, letterSpacing = 0.2.sp),
          color = CRAYOLA,
        )
        Spacer(modifier = Modifier.height(4.dp))
        SoftUpdateDescription(message = uiState.softMessage?.takeIf { it.isNotBlank() })
      }
    }

    IconButton(
      onClick = onDismiss,
      modifier = Modifier.align(Alignment.TopEnd).size(40.dp).testTag(SOFT_UPDATE_DISMISS_TAG),
    ) {
      Icon(
        imageVector = Icons.Filled.Close,
        contentDescription = stringResource(R.string.app_update_dismiss),
        tint = CRAYOLA,
        modifier = Modifier.size(20.dp),
      )
    }
  }
}

/**
 * The card's description: a grey lead sentence — [message] when the server configures one, the
 * built-in copy otherwise — followed by the blue1 "Click to update." call-to-action.
 *
 * Compose has no dashed [androidx.compose.ui.text.style.TextDecoration], so the design's 2/2 dashed
 * rule under the call-to-action is drawn from the laid-out text, line by line, which keeps the rule
 * correct however the paragraph happens to wrap.
 */
@Composable
private fun SoftUpdateDescription(message: String?) {
  val cta = stringResource(R.string.app_update_soft_message_cta)
  val configured = (message ?: stringResource(R.string.app_update_soft_message_lead)).trimEnd()
  // Operators naturally write the whole sentence they want to see, call-to-action included. Strip a
  // trailing copy rather than appending a second one, so "… improvements. Click to update." renders
  // once with its link styling intact instead of ending "Click to update. Click to update.".
  val lead =
    if (configured.endsWith(cta, ignoreCase = true)) {
      configured.dropLast(cta.length).trimEnd()
    } else {
      configured
    }
  val separator = if (lead.isEmpty()) "" else " "
  val ctaStart = lead.length + separator.length
  val ctaEnd = ctaStart + cta.length
  val text = remember(lead, separator, cta) {
    buildAnnotatedString {
      if (lead.isNotEmpty()) withStyle(SpanStyle(color = CRAYOLA_LIGHT)) { append(lead + separator) }
      withStyle(SpanStyle(color = BRANDEIS_BLUE)) { append(cta) }
    }
  }

  var layout by remember { mutableStateOf<TextLayoutResult?>(null) }

  Text(
    text = text,
    style = bodyNormal(14.sp).copy(lineHeight = 20.sp, letterSpacing = 0.2.sp),
    color = CRAYOLA_LIGHT,
    onTextLayout = { layout = it },
    modifier = Modifier.drawBehind {
      val result = layout ?: return@drawBehind
      if (ctaEnd > result.layoutInput.text.length) return@drawBehind
      val dash = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 2.dp.toPx()))
      val firstLine = result.getLineForOffset(ctaStart)
      val lastLine = result.getLineForOffset(ctaEnd - 1)
      for (line in firstLine..lastLine) {
        val start = maxOf(ctaStart, result.getLineStart(line))
        val end = minOf(ctaEnd, result.getLineEnd(line, visibleEnd = true))
        if (end <= start) continue
        val y = result.getLineBottom(line) - 2.dp.toPx()
        drawLine(
          color = BRANDEIS_BLUE,
          start = Offset(result.getHorizontalPosition(start, usePrimaryDirection = true), y),
          end = Offset(result.getHorizontalPosition(end, usePrimaryDirection = true), y),
          strokeWidth = 1.dp.toPx(),
          pathEffect = dash,
        )
      }
    },
  )
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
            softMessage = "New in this release: faster case sync and a fix for lost photos.",
          ),
        onUpdate = {},
        onDismiss = {},
        isOnline = true,
      )
    }
  }
}
