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

package org.smartregister.fhircore.quest.ui.register.profile

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.AlertDialog
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.smartregister.fhircore.engine.ui.theme.LightColors
import org.smartregister.fhircore.engine.ui.theme.SearchHeaderColor
import org.smartregister.fhircore.quest.ui.main.AppMainViewModel
import org.smartregister.fhircore.quest.ui.main.components.TopScreenSection
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.ModalBottomSheetValue
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HouseSiding
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.PhonelinkLock
import androidx.compose.material.rememberModalBottomSheetState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import org.smartregister.fhircore.engine.domain.model.ToolBarHomeNavigation
import org.smartregister.fhircore.quest.R
import org.smartregister.fhircore.quest.ui.login.PASSWORD_FORGOT_DIALOG
import org.smartregister.fhircore.quest.ui.main.components.DRAWER_MENU
import org.smartregister.fhircore.quest.ui.main.components.TOP_ROW_ICON_TEST_TAG
import org.smartregister.fhircore.quest.ui.register.patients.RegisterEvent
import org.smartregister.fhircore.quest.ui.register.patients.RegisterUiState
import org.smartregister.fhircore.quest.ui.register.patients.RegisterViewModel
import org.smartregister.fhircore.quest.ui.register.patients.TOP_REGISTER_SCREEN_TEST_TAG
import androidx.compose.ui.text.font.FontWeight.Companion as FontWeight1


@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterialApi::class)
@Composable
fun ProfileSectionScreen(
  modifier: Modifier = Modifier,
  viewModel: RegisterViewModel,
  appMainViewModel: AppMainViewModel,
  onEvent: (RegisterEvent) -> Unit,
  registerUiState: RegisterUiState,
  searchText: MutableState<String>,
  userName: String = "",
  onBackPressed: () -> Unit
) {
  val lazyListState: LazyListState = rememberLazyListState()
  val coroutineScope = rememberCoroutineScope()
  val bottomSheetState = rememberModalBottomSheetState(initialValue = ModalBottomSheetValue.Hidden)
  var selectedTask by remember { mutableStateOf<RegisterViewModel.TaskItem?>(null) }
  val searchResultTasks by viewModel.searchedTasksStateFlow.collectAsState()
  val userNameText = viewModel.getUserName()
  var showForgotPasswordDialog by remember { mutableStateOf(false) }

  Scaffold(
    modifier = modifier
      .background(SearchHeaderColor)
      .fillMaxSize(),
    topBar = {
      Column(modifier = modifier
        .background(Color.White)
        .fillMaxWidth()) {

        TopScreenSection(
          modifier = modifier.testTag(TOP_REGISTER_SCREEN_TEST_TAG),
          title = stringResource(id = org.smartregister.fhircore.engine.R.string.profile),
          searchText = searchText.value,
          filteredRecordsCount = registerUiState.filteredRecordsCount,
          searchPlaceholder = registerUiState.registerConfiguration?.searchBar?.display,
          onSync = appMainViewModel::onEvent,
          toolBarHomeNavigation = ToolBarHomeNavigation.NAVIGATE_BACK,
          onSearchTextChanged = { searchText ->
            onEvent(RegisterEvent.SearchRegister(searchText = searchText))
          },
          isFilterIconEnabled = false,
        ) { event ->
            onBackPressed()
        }
      }
    },

    ) { innerPadding ->

    if (showForgotPasswordDialog) {
      ForgotPasswordDialog(
        onDismissDialog = { showForgotPasswordDialog = false },
      )
    }

    Box(modifier = modifier
      .padding(innerPadding)
      .fillMaxSize()
      .background(SearchHeaderColor)) {

      Box(
        modifier = modifier
          .background(SearchHeaderColor)
          .padding(horizontal = 16.dp)
          .fillMaxSize()
      )
      {
        Column(
          modifier = modifier
            .fillMaxHeight()
            .background(SearchHeaderColor)
            .fillMaxWidth()
        ){

          Spacer(modifier = Modifier.height(16.dp))

          Column(
            modifier = modifier
              .fillMaxHeight()
              .background(SearchHeaderColor)
              .fillMaxWidth()
          ) {

            Column(
              modifier = Modifier
                .fillMaxSize()
                .background(SearchHeaderColor)
            ) {
              //Spacer(modifier = Modifier.height(48.dp))
              Column(modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Image(
                  painter = painterResource(R.drawable.ic_patient_male), // Replace with your profile picture
                  modifier = Modifier
                    .size(84.dp)
                    .clip(CircleShape)
                    .padding(all = 4.dp),
                  contentDescription = null,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                  text = "$userNameText",
                  fontWeight = FontWeight1.Bold,
                  style = TextStyle(
                    fontSize = 18.sp,
                    letterSpacing = 0.15.sp,
                  )
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = "Username: $userNameText", style = TextStyle(
                  fontSize = 18.sp,
                  fontWeight = FontWeight.Normal,
                  letterSpacing = 0.15.sp,

                ))
                Spacer(modifier = Modifier.height(8.dp))
                Row() {
                  Icon(
                    Icons.Filled.HouseSiding,
                    contentDescription = DRAWER_MENU,
                    tint = LightColors.primary,
                    modifier = modifier
                      .testTag(TOP_ROW_ICON_TEST_TAG),
                  )
                  Spacer(modifier = Modifier.width(4.dp))
                  Text(text = "Krishnagiri", style = TextStyle(
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Normal,
                    letterSpacing = 0.15.sp,
                  ))
                }
                Spacer(modifier = Modifier.height(20.dp))
              }

              Column(
                modifier = Modifier
                  .fillMaxWidth()
                  .background(SearchHeaderColor)
                  .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
              ) {
                Row(modifier = Modifier
                  .fillMaxWidth()
                  .padding(horizontal = 16.dp, vertical = 4.dp)
                  .background(Color.White)) {
                  Row(modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 16.dp)
                    .clickable {
                      viewModel.logout()
                    }
                    .background(Color.White)) {
                    Icon(
                      Icons.Filled.PhonelinkLock,
                      contentDescription = DRAWER_MENU,
                      tint = Color.Gray,
                      modifier =
                      modifier
                        .clickable { }
                        .testTag(TOP_ROW_ICON_TEST_TAG),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "Change PIN")
                  }
                }
                Row(modifier = Modifier
                  .fillMaxWidth()
                  .padding(horizontal = 16.dp, vertical = 4.dp)
                  .background(Color.White)) {
                  Row(modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                      showForgotPasswordDialog = true
                    }
                    .padding(horizontal = 16.dp, vertical = 16.dp)
                    .background(Color.White)) {
                    Icon(
                      Icons.Filled.Lock,
                      contentDescription = DRAWER_MENU,
                      tint = Color.Gray,
                      modifier =
                      modifier
                        .clickable { }
                        .testTag(TOP_ROW_ICON_TEST_TAG),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "Change Password")
                  }
                }

                Row(modifier = Modifier
                  .fillMaxWidth()
                  .padding(horizontal = 16.dp, vertical = 4.dp)
                  .background(Color.White)) {
                  Row(modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 16.dp)
                    .clickable {
                      viewModel.logout()
                    }
                    .background(Color.White)) {
                    Icon(
                      Icons.Filled.Logout,
                      contentDescription = DRAWER_MENU,
                      tint = Color.Gray,
                      modifier =
                      modifier
                        .clickable {

                        }
                        .testTag(TOP_ROW_ICON_TEST_TAG),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "Logout", color = Color.Red)
                  }
                }
              }
            }
          }
        }
      }
    }
  }
}

@Composable
fun ForgotPasswordDialog(
  onDismissDialog: () -> Unit,
  modifier: Modifier = Modifier,
) {
  AlertDialog(
    onDismissRequest = onDismissDialog,
    title = {
      androidx.compose.material.Text(
        text = stringResource(org.smartregister.fhircore.engine.R.string.forgot_password_title),
        fontWeight = FontWeight.Bold,
        fontSize = 18.sp,
      )
    },
    text = {
      androidx.compose.material.Text(text = stringResource(org.smartregister.fhircore.engine.R.string.call_supervisor), fontSize = 16.sp)
    },
    buttons = {
      Row(
        modifier = modifier
          .fillMaxWidth()
          .padding(vertical = 20.dp),
        horizontalArrangement = Arrangement.End,
      ) {
        androidx.compose.material.Text(
          text = stringResource(org.smartregister.fhircore.engine.R.string.cancel),
          modifier = modifier
            .padding(horizontal = 10.dp)
            .clickable { onDismissDialog() },
        )
        androidx.compose.material.Text(
          color = MaterialTheme.colors.primary,
          text = stringResource(org.smartregister.fhircore.engine.R.string.ok),
          modifier =
          modifier
            .padding(horizontal = 10.dp)
            .clickable {
              onDismissDialog()
              //forgotPassword()
            },
        )
      }
    },
    modifier = Modifier.testTag(PASSWORD_FORGOT_DIALOG),
  )
}