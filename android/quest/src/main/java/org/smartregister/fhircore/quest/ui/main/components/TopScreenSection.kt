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

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.smartregister.fhircore.engine.R
import org.smartregister.fhircore.engine.domain.model.ToolBarHomeNavigation
import org.smartregister.fhircore.engine.ui.theme.DarkColors
import org.smartregister.fhircore.engine.ui.theme.WarningColor
import org.smartregister.fhircore.quest.event.ToolbarClickEvent
import org.smartregister.fhircore.quest.ui.main.AppMainEvent
import org.smartregister.fhircore.quest.ui.register.patients.GenericActivity
import org.smartregister.fhircore.quest.ui.register.patients.GenericActivityArg

const val DRAWER_MENU = "Drawer Menu"
const val PROFILE = "Profile"
const val SEARCH = "Search"
const val CLEAR = "Clear"
const val FILTER = "Filter"
const val TITLE_ROW_TEST_TAG = "titleRowTestTag"
const val TOP_ROW_ICON_TEST_TAG = "topRowIconTestTag"
const val TOP_ROW_TEXT_TEST_TAG = "topRowTextTestTag"
const val TOP_ROW_FILTER_ICON_TEST_TAG = "topRowFilterIconTestTag"
const val OUTLINED_BOX_TEST_TAG = "outlinedBoxTestTag"
const val TRAILING_ICON_TEST_TAG = "trailingIconTestTag"
const val TRAILING_ICON_BUTTON_TEST_TAG = "trailingIconButtonTestTag"
const val LEADING_ICON_TEST_TAG = "leadingIconTestTag"
const val SEARCH_FIELD_TEST_TAG = "searchFieldTestTag"
const val SYNCING_INDICATOR_TEST_TAG = "syncingIndicatorTestTag"
const val SYNC_COMPLETE_INDICATOR_TEST_TAG = "syncCompleteIndicatorTestTag"
const val SYNC_PENDING_INDICATOR_TEST_TAG = "syncPendingIndicatorTestTag"

private val SYNC_ICON_BOX_SIZE = 40.dp
private val SYNC_CLOUD_WIDTH = 30.dp
private val SYNC_CLOUD_HEIGHT = 20.dp
private val SYNC_ARROWS_SIZE = 15.dp
private val SYNC_ARROWS_Y_OFFSET = 0.5.dp
private val SYNC_CHECK_SIZE = 16.dp
private val SYNC_BADGE_SIZE = 16.dp
private val SYNC_BADGE_BORDER = 1.5.dp
private val SYNC_BADGE_H_PADDING = 3.dp
private const val SYNC_ARROWS_ROTATION_MS = 1100
private const val SYNC_ARROWS_POP_MS = 250
private const val SYNC_CLOUD_SHIMMER_MS = 1400
private const val SYNC_CLOUD_RESTING_ALPHA = 0.7f
private const val SYNC_CLOUD_SHIMMER_BAND_FRACTION = 0.6f
private const val SYNC_BADGE_MAX_COUNT = 9

@OptIn(ExperimentalMaterialApi::class)
@Composable
fun TopScreenSection(
  modifier: Modifier = Modifier,
  title: String = stringResource(id = R.string.appname),
  toolBarHomeNavigation: ToolBarHomeNavigation = ToolBarHomeNavigation.OPEN_DRAWER,
  isOnline: Boolean = true,
  isSyncing: Boolean = false,
  pendingSyncCount: Int? = null,
  onSync: (AppMainEvent) -> Unit,
  onClick: (ToolbarClickEvent) -> Unit,
) {
  Column(
    modifier = modifier
      .fillMaxWidth()
      .background(DarkColors.primary),
  ) {
    Row(
      modifier =
      Modifier
        .fillMaxWidth()
        .height(64.dp)
        .padding(start = 16.dp, end = 4.dp)
        .testTag(TITLE_ROW_TEST_TAG),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      if (toolBarHomeNavigation == ToolBarHomeNavigation.NAVIGATE_BACK) {
        Icon(
          Icons.Filled.ArrowBack,
          contentDescription = DRAWER_MENU,
          tint = Color.White,
          modifier =
            Modifier
              .clickable { onClick(ToolbarClickEvent.Navigate) }
              .testTag(TOP_ROW_ICON_TEST_TAG),
        )
      }
      Text(
        text = title,
        fontSize = 24.sp,
        color = Color.White,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
          .padding(start = 8.dp)
          .weight(1f)
          .testTag(TOP_ROW_TEXT_TEST_TAG),
      )
      val context = LocalContext.current

      if (toolBarHomeNavigation == ToolBarHomeNavigation.SYNC) {
        IconButton(
          onClick = {
            val intent = Intent(context, GenericActivity::class.java).apply {
              putExtra(GenericActivityArg.ARG_FROM, GenericActivityArg.FROM_PROFILE)
            }
            context.startActivity(intent)
          },
        ) {
          Icon(
            painter = painterResource(id = org.smartregister.fhircore.quest.R.drawable.ic_profile_actionbar),
            contentDescription = PROFILE,
            tint = Color.White,
            modifier = Modifier.testTag(TOP_ROW_FILTER_ICON_TEST_TAG),
          )
        }
        SyncActionButton(
          isOnline = isOnline,
          isSyncing = isSyncing,
          pendingSyncCount = pendingSyncCount,
          onClick = { onSync(AppMainEvent.SyncData(context)) },
        )
      }
    }
  }
}

/**
 * Single sync affordance for the app bar with three visually distinct states:
 * - **Syncing**: the circular arrows pop in at the cloud's center and rotate while a shimmer band
 *   sweeps across the cloud, so an ongoing sync is evident at a glance.
 * - **Up to date**: when nothing is pending upload, a green check badge sits at the cloud's
 *   lower-right — the "all changes synced" confirmation the product team asked for.
 * - **Pending**: with unsynced work waiting, the plain cloud invites a manual sync.
 *
 * The up-to-date/pending distinction is driven purely by [pendingSyncCount]; it is opt-in, so a
 * caller that does not track pending work passes `null` and the badge is never shown (avoiding a
 * false "synced" state on a screen that cannot actually confirm it). Syncing always wins over the
 * badge. The button stays clickable throughout — the caller guards against concurrent syncs.
 */
@Composable
private fun SyncActionButton(
  isOnline: Boolean,
  isSyncing: Boolean,
  pendingSyncCount: Int?,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val iconTint = if (isOnline || isSyncing) Color.White else Color.White.copy(alpha = 0.6f)
  val isUpToDate = pendingSyncCount != null && !isSyncing && pendingSyncCount == 0
  val isPending = pendingSyncCount != null && !isSyncing && pendingSyncCount >= 1
  val contentDescription =
    when {
      isSyncing -> stringResource(id = R.string.syncing)
      isUpToDate -> stringResource(id = R.string.sync_up_to_date)
      else -> stringResource(id = R.string.sync)
    }

  IconButton(onClick = onClick, modifier = modifier) {
    Box(modifier = Modifier.size(SYNC_ICON_BOX_SIZE), contentAlignment = Alignment.Center) {
      SyncCloud(isSyncing = isSyncing, tint = iconTint, contentDescription = contentDescription)
      AnimatedVisibility(
        visible = isSyncing,
        enter =
          fadeIn(animationSpec = tween(SYNC_ARROWS_POP_MS)) +
            scaleIn(animationSpec = tween(SYNC_ARROWS_POP_MS), initialScale = 0.4f),
        exit =
          fadeOut(animationSpec = tween(SYNC_ARROWS_POP_MS)) +
            scaleOut(animationSpec = tween(SYNC_ARROWS_POP_MS), targetScale = 0.4f),
      ) {
        RotatingSyncArrows(tint = iconTint)
      }
      AnimatedVisibility(
        visible = isUpToDate,
        modifier = Modifier.align(Alignment.BottomEnd),
        enter =
          fadeIn(animationSpec = tween(SYNC_ARROWS_POP_MS)) +
            scaleIn(animationSpec = tween(SYNC_ARROWS_POP_MS), initialScale = 0.4f),
        exit =
          fadeOut(animationSpec = tween(SYNC_ARROWS_POP_MS)) +
            scaleOut(animationSpec = tween(SYNC_ARROWS_POP_MS), targetScale = 0.4f),
      ) {
        SyncCompleteBadge()
      }
      AnimatedVisibility(
        visible = isPending,
        modifier = Modifier.align(Alignment.TopEnd),
        enter =
          fadeIn(animationSpec = tween(SYNC_ARROWS_POP_MS)) +
            scaleIn(animationSpec = tween(SYNC_ARROWS_POP_MS), initialScale = 0.4f),
        exit =
          fadeOut(animationSpec = tween(SYNC_ARROWS_POP_MS)) +
            scaleOut(animationSpec = tween(SYNC_ARROWS_POP_MS), targetScale = 0.4f),
      ) {
        SyncPendingBadge(count = pendingSyncCount ?: 0)
      }
    }
  }
}

/**
 * Amber count badge at the cloud's upper-right showing how many items ([count]) are waiting to
 * upload — cases plus images. Capped at "[SYNC_BADGE_MAX_COUNT]+" so a large backlog stays legible.
 * A thin app-bar-coloured ring separates it from the cloud glyph beneath. Amber (waiting) is the
 * deliberate mid-point between this and the green "all synced" check.
 */
@Composable
private fun SyncPendingBadge(count: Int, modifier: Modifier = Modifier) {
  val label = if (count > SYNC_BADGE_MAX_COUNT) "$SYNC_BADGE_MAX_COUNT+" else count.toString()
  Box(
    modifier =
      modifier
        .defaultMinSize(minWidth = SYNC_BADGE_SIZE, minHeight = SYNC_BADGE_SIZE)
        .background(WarningColor, CircleShape)
        .border(BorderStroke(SYNC_BADGE_BORDER, DarkColors.primary), CircleShape)
        .padding(horizontal = SYNC_BADGE_H_PADDING)
        .testTag(SYNC_PENDING_INDICATOR_TEST_TAG),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      text = label,
      color = Color.White,
      fontSize = 9.sp,
      fontWeight = FontWeight.Bold,
    )
  }
}

/**
 * Green check badge pinned to the cloud's lower-right corner while everything is synced. The glyph
 * ([R.drawable.ic_check_circled]) is already a filled green disc, so it is drawn untinted for a
 * crisp "done" mark against the dark app bar.
 */
@Composable
private fun SyncCompleteBadge(modifier: Modifier = Modifier) {
  Icon(
    painter =
      painterResource(id = org.smartregister.fhircore.quest.R.drawable.ic_check_circled),
    contentDescription = null,
    tint = Color.White,
    modifier = modifier
      .size(SYNC_CHECK_SIZE)
      .testTag(SYNC_COMPLETE_INDICATOR_TEST_TAG),
  )
}

/**
 * The cloud outline. While syncing it dims slightly and a bright band sweeps across the glyph —
 * the classic shimmer. The gradient is masked to the icon's own pixels by compositing the layer
 * offscreen and drawing the band with [BlendMode.SrcAtop], so nothing bleeds onto the app bar.
 */
@Composable
private fun SyncCloud(
  isSyncing: Boolean,
  tint: Color,
  contentDescription: String,
  modifier: Modifier = Modifier,
) {
  val sizeModifier =
    modifier
      .size(SYNC_CLOUD_WIDTH, SYNC_CLOUD_HEIGHT)
      .testTag(TOP_ROW_FILTER_ICON_TEST_TAG)
  if (isSyncing) {
    val shimmer = rememberInfiniteTransition(label = "cloudShimmer")
    val bandStart by
      shimmer.animateFloat(
        initialValue = -SYNC_CLOUD_SHIMMER_BAND_FRACTION,
        targetValue = 1f + SYNC_CLOUD_SHIMMER_BAND_FRACTION,
        animationSpec =
          infiniteRepeatable(
            animation = tween(durationMillis = SYNC_CLOUD_SHIMMER_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
          ),
        label = "cloudShimmerBand",
      )
    Icon(
      painter = painterResource(id = org.smartregister.fhircore.quest.R.drawable.ic_cases_sync),
      contentDescription = contentDescription,
      tint = tint.copy(alpha = tint.alpha * SYNC_CLOUD_RESTING_ALPHA),
      modifier =
        sizeModifier
          .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
          .drawWithContent {
            drawContent()
            val band = size.width * SYNC_CLOUD_SHIMMER_BAND_FRACTION
            val x = bandStart * size.width
            drawRect(
              brush =
                Brush.linearGradient(
                  colors = listOf(Color.Transparent, tint, Color.Transparent),
                  start = Offset(x, 0f),
                  end = Offset(x + band, size.height),
                ),
              blendMode = BlendMode.SrcAtop,
            )
          },
    )
  } else {
    Icon(
      painter = painterResource(id = org.smartregister.fhircore.quest.R.drawable.ic_cases_sync),
      contentDescription = contentDescription,
      tint = tint,
      modifier = sizeModifier,
    )
  }
}

/**
 * Circular sync arrows spinning at the cloud's center, counter-clockwise — the direction their
 * arrowheads point. Only composed while a sync runs; the caller's [AnimatedVisibility] pop
 * handles their entrance and exit, so a plain infinite transition is enough here.
 */
@Composable
private fun RotatingSyncArrows(tint: Color, modifier: Modifier = Modifier) {
  val transition = rememberInfiniteTransition(label = "syncArrows")
  val rotation by
    transition.animateFloat(
      initialValue = 0f,
      targetValue = -360f,
      animationSpec =
        infiniteRepeatable(
          animation = tween(durationMillis = SYNC_ARROWS_ROTATION_MS, easing = LinearEasing),
          repeatMode = RepeatMode.Restart,
        ),
      label = "syncArrowsRotation",
    )

  Icon(
    painter = painterResource(id = org.smartregister.fhircore.quest.R.drawable.ic_sync_arrows),
    contentDescription = null,
    tint = tint,
    modifier = modifier
      .size(SYNC_ARROWS_SIZE)
      .offset(y = SYNC_ARROWS_Y_OFFSET)
      .rotate(rotation)
      .testTag(SYNCING_INDICATOR_TEST_TAG),
  )
}

@Preview(showBackground = true)
@Composable
private fun TopScreenSectionSyncingPreview() {
  TopScreenSection(
    title = "App Name",
    toolBarHomeNavigation = ToolBarHomeNavigation.SYNC,
    isOnline = true,
    isSyncing = true,
    onSync = {},
    onClick = {},
  )
}

@Preview(showBackground = true)
@Composable
private fun TopScreenSectionPendingPreview() {
  TopScreenSection(
    title = "App Name",
    toolBarHomeNavigation = ToolBarHomeNavigation.SYNC,
    isOnline = true,
    isSyncing = false,
    pendingSyncCount = 3,
    onSync = {},
    onClick = {},
  )
}

@Preview(showBackground = true)
@Composable
private fun TopScreenSectionUpToDatePreview() {
  TopScreenSection(
    title = "App Name",
    toolBarHomeNavigation = ToolBarHomeNavigation.SYNC,
    isOnline = true,
    isSyncing = false,
    pendingSyncCount = 0,
    onSync = {},
    onClick = {},
  )
}
