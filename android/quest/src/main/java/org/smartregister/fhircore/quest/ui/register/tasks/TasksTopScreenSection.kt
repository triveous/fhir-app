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

package org.smartregister.fhircore.quest.ui.register.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.smartregister.fhircore.engine.R
import org.smartregister.fhircore.engine.domain.model.ToolBarHomeNavigation
import org.smartregister.fhircore.engine.ui.theme.DarkColors
import org.smartregister.fhircore.engine.util.annotation.PreviewWithBackgroundExcludeGenerated
import org.smartregister.fhircore.quest.event.ToolbarClickEvent

const val DRAWER_MENU = "Drawer Menu"
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

@Composable
fun TasksTopScreenSection(
  modifier: Modifier = Modifier,
  title: String = stringResource(id = R.string.appname),
  toolBarHomeNavigation: ToolBarHomeNavigation = ToolBarHomeNavigation.OPEN_DRAWER,
  onClick: (ToolbarClickEvent) -> Unit,
) {
  Column(
    modifier = modifier
      .fillMaxWidth()
      .background(DarkColors.primary),
  ) {
    Row(
      modifier =
      modifier
        .fillMaxWidth()
        .height(64.dp)
        .padding(horizontal = 16.dp, vertical = 16.dp)
        .testTag(
          TITLE_ROW_TEST_TAG,
        ),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      if (toolBarHomeNavigation == ToolBarHomeNavigation.NAVIGATE_BACK) {
        Icon(
          Icons.Filled.ArrowBack,
          contentDescription = DRAWER_MENU,
          tint = Color.White,
          modifier =
          modifier
            .clickable { onClick(ToolbarClickEvent.Navigate) }
            .testTag(TOP_ROW_ICON_TEST_TAG),
        )
      }
        Text(
          text = title,
          fontSize = 18.sp,
          color = Color.White,
          fontWeight = FontWeight.Bold,
          modifier = modifier
            .padding(start = 8.dp)
            .weight(1f)
            .testTag(TOP_ROW_TEXT_TEST_TAG),
        )
      val context = LocalContext.current

      if (toolBarHomeNavigation == ToolBarHomeNavigation.SYNC) {

      }
    }
  }
}

@PreviewWithBackgroundExcludeGenerated
@Composable
fun TopScreenSectionWithFilterItemOverNinetyNinePreview() {
  TasksTopScreenSection(
    title = "All Clients",
    toolBarHomeNavigation = ToolBarHomeNavigation.NAVIGATE_BACK,
    onClick = {},
  )
}

@PreviewWithBackgroundExcludeGenerated
@Composable
fun TopScreenSectionWithFilterCountNinetyNinePreview() {
  TasksTopScreenSection(
    title = "All Clients",
    toolBarHomeNavigation = ToolBarHomeNavigation.NAVIGATE_BACK,
    onClick = {},
  )
}

@PreviewWithBackgroundExcludeGenerated
@Composable
fun TopScreenSectionNoFilterIconPreview() {
  TasksTopScreenSection(
    title = "All Clients",
    toolBarHomeNavigation = ToolBarHomeNavigation.NAVIGATE_BACK,
    onClick = {},
  )
}
