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

package org.smartregister.fhircore.quest.ui.register.search

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.Card
import androidx.compose.material.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import org.smartregister.fhircore.engine.R
import org.smartregister.fhircore.engine.ui.theme.GreyTextColor
import org.smartregister.fhircore.quest.event.ToolbarClickEvent
import org.smartregister.fhircore.quest.theme.Colors

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
fun SearchBarSection(
    modifier: Modifier = Modifier,
    onSearchTextChanged: (String) -> Unit,
    onClick: (ToolbarClickEvent) -> Unit,
) {
    val searchText: MutableState<String> = remember { mutableStateOf("") }
    val interactionSource = remember { MutableInteractionSource() }

    Card(elevation = 4.dp, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier =
            modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier
                .clickable {
                    onClick(ToolbarClickEvent.Navigate)
                }
                .testTag(TOP_ROW_ICON_TEST_TAG)) {
                Image(
                    painterResource(org.smartregister.fhircore.quest.R.drawable.ic_back_arrow),
                    contentDescription = DRAWER_MENU,
                    modifier = Modifier.padding(20.dp)
                )
            }

            TextField(
                colors = getTextFieldColors(),
                value = searchText.value,
                interactionSource = interactionSource,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    onSearchTextChanged(searchText.value)
                }),
                onValueChange = {
                    searchText.value = it
                    if (it.length > 1) {
                        onSearchTextChanged(it)
                    }
                },
                maxLines = 1,
                singleLine = true,
                placeholder = {
                    Text(
                        color = GreyTextColor,
                        text = stringResource(R.string.search_hint),
                        modifier = modifier
                            .testTag(SEARCH_FIELD_TEST_TAG)
                            .fillMaxWidth(),
                    )
                },
                modifier = modifier.fillMaxWidth()
                    .background(Color.White)
                    .testTag(OUTLINED_BOX_TEST_TAG),
                trailingIcon = {
                    if (searchText.value.isNotEmpty()) {
                        Box(modifier = Modifier
                            .clickable {
                                searchText.value = ""
                                onSearchTextChanged("")
                            }) {
                            Image(
                                painterResource(id = org.smartregister.fhircore.quest.R.drawable.ic_rec_search_cancel),
                                contentDescription = TRAILING_ICON_TEST_TAG,
                                modifier = modifier.padding(18.dp),
                            )
                        }
                    }else{
                        null
                    }
                },
            )
        }
    }
}

@Composable
internal fun getTextFieldColors() = androidx.compose.material3.TextFieldDefaults.colors().copy(
    focusedContainerColor = Colors.WHITE,
    unfocusedContainerColor = Colors.WHITE,
    focusedTextColor = Colors.CRAYOLA,
    errorIndicatorColor = Color.Transparent,
    unfocusedTextColor = Color.Transparent,
    focusedIndicatorColor = Color.Transparent,
    unfocusedIndicatorColor = Color.Transparent
)
