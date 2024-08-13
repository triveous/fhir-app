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

package org.smartregister.fhircore.quest.ui.register.dashboard

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import kotlinx.coroutines.launch
import org.smartregister.fhircore.engine.ui.theme.LightColors
import org.smartregister.fhircore.engine.ui.theme.SearchHeaderColor
import org.smartregister.fhircore.quest.ui.main.AppMainViewModel
import org.smartregister.fhircore.quest.ui.main.components.TopScreenSection
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.ModalBottomSheetLayout
import androidx.compose.material.ModalBottomSheetValue
import androidx.compose.material.TextButton
import androidx.compose.material.rememberModalBottomSheetState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import org.hl7.fhir.r4.model.Task.TaskStatus
import org.smartregister.fhircore.engine.domain.model.ToolBarHomeNavigation
import org.smartregister.fhircore.engine.ui.theme.GreyTextColor
import org.smartregister.fhircore.engine.ui.theme.ThinGreyBackground
import org.smartregister.fhircore.quest.ui.register.tasks.BottomSheetContent
import org.smartregister.fhircore.quest.ui.register.patients.RegisterEvent
import org.smartregister.fhircore.quest.ui.register.patients.RegisterUiState
import org.smartregister.fhircore.quest.ui.register.patients.RegisterViewModel
import org.smartregister.fhircore.quest.ui.register.patients.TOP_REGISTER_SCREEN_TEST_TAG


@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterialApi::class)
@Composable
fun DashboardScreen(
  modifier: Modifier = Modifier,
  viewModel: RegisterViewModel,
  appMainViewModel: AppMainViewModel,
  onEvent: (RegisterEvent) -> Unit,
  registerUiState: RegisterUiState,
  navController: NavController,
  onAddNewCase: () -> Unit,
) {
  val lazyListState: LazyListState = rememberLazyListState()
  val coroutineScope = rememberCoroutineScope()
  val bottomSheetState = rememberModalBottomSheetState(initialValue = ModalBottomSheetValue.Hidden)
  val selectedTask by remember { mutableStateOf<RegisterViewModel.TaskItem?>(null) }
  val searchResultTasks by viewModel.searchedTasksStateFlow.collectAsState()
  val dashboardDataStateFlow by viewModel.dashboardDataStateFlow.collectAsState()


  ModalBottomSheetLayout(
    sheetState = bottomSheetState,
    sheetContent = {
      selectedTask?.let { task ->
        BottomSheetContent(task = task, onStatusUpdate = {
          var status: TaskStatus = TaskStatus.NULL
          /*when (it) {

            TaskPriority.ROUTINE -> {
              status = TaskStatus.COMPLETED
            }

            TaskPriority.URGENT -> {
              status = TaskStatus.INPROGRESS
            }

            TaskPriority.STAT -> {
              status = TaskStatus.INPROGRESS
            }

            TaskPriority.ASAP -> {
              status = TaskStatus.INPROGRESS
            }

            TaskPriority.NULL -> {
              status = TaskStatus.INPROGRESS
            }
          }*/

          viewModel.updateTask(task.task, status, it)
          coroutineScope.launch {
            bottomSheetState.hide()
          }
        },
          onCancel = {
            coroutineScope.launch {
              bottomSheetState.hide()
            }
          })
      }
    }
  ) {
    Scaffold(
      modifier = modifier.background(Color.White),
      topBar = {
        Column(modifier = modifier
          .background(Color.White)
          .fillMaxWidth()) {

          val filterActions =
            registerUiState.registerConfiguration?.registerFilter?.dataFilterActions
          TopScreenSection(
            modifier = modifier.testTag(TOP_REGISTER_SCREEN_TEST_TAG),
            title = stringResource(id = org.smartregister.fhircore.engine.R.string.appname),
            searchText = "",
            filteredRecordsCount = registerUiState.filteredRecordsCount,
            searchPlaceholder = registerUiState.registerConfiguration?.searchBar?.display,
            onSync = appMainViewModel::onEvent,
            toolBarHomeNavigation = ToolBarHomeNavigation.SYNC,
            onSearchTextChanged = { searchText ->

              onEvent(RegisterEvent.SearchRegister(searchText = searchText))
            },
            isFilterIconEnabled = filterActions?.isNotEmpty() ?: false,
          ) { event ->
            navController.popBackStack()
          }
        }
      },

      ) { innerPadding ->
      val scrollState = rememberScrollState()
      Box(modifier = modifier.padding(innerPadding).background(color = SearchHeaderColor)) {

        Column(
          modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(scrollState)
            .background(color = SearchHeaderColor)
        ) {
          Box(
            modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = 8.dp)
              .background(color = SearchHeaderColor) // Set custom color
          ) {
            Column(modifier = Modifier.padding(8.dp)) {
              Text(
                text = "Health Center",
                color = GreyTextColor,
                style = MaterialTheme.typography.h6
              )
            }
          }
          Column(
            modifier = Modifier
              .padding(horizontal = 8.dp, vertical = 8.dp)
              .background(Color.White)
          ) {
            Text(text = "Cases", style = MaterialTheme.typography.h6, modifier = Modifier.padding(vertical = 8.dp, horizontal = 8.dp))
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp, horizontal = 16.dp)
                .border(
                  width = 0.2.dp,
                  color = Color.LightGray
                )
                .background(ThinGreyBackground),
              horizontalArrangement = Arrangement.SpaceBetween
            ) {
              Text(text = "Total screened cases", modifier = Modifier.padding(vertical = 16.dp,  horizontal = 8.dp),
                style = TextStyle(
                  fontSize = 16.sp,
                  fontWeight = FontWeight(400),
                ))
              Text(text = "${dashboardDataStateFlow.totalCases}", modifier = Modifier.padding(vertical = 8.dp,  horizontal = 8.dp),
                style = TextStyle(
                fontSize = 32.sp,
                  fontWeight = FontWeight(500),
              )) // Replace with dynamic value
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp, horizontal = 16.dp)
                .background(ThinGreyBackground), horizontalArrangement = Arrangement.SpaceBetween
            ) {
              Column(
                modifier = Modifier
                  .background(ThinGreyBackground)
                  .weight(1f)
                  .border(
                    width = 0.2.dp,
                    color = Color.LightGray
                  )
              ) {
                Text(text = "Today", modifier = Modifier.padding(vertical = 8.dp,  horizontal = 8.dp),
                  style = TextStyle(
                    fontSize = 16.sp,
                    fontWeight = FontWeight(400),
                  ))
                Text(text = "${dashboardDataStateFlow.todayCases}", modifier = Modifier.padding(vertical = 8.dp,  horizontal = 8.dp),
                  style = TextStyle(
                    fontSize = 16.sp,
                    fontWeight = FontWeight(500),
                    color = Color.DarkGray
                  )
                ) // Replace with dynamic value
              }
              Spacer(modifier = Modifier.height(8.dp))
              Column(
                modifier = Modifier
                  .background(ThinGreyBackground)
                  .weight(1f)
                  .border(
                    width = 0.2.dp,
                    color = Color.LightGray
                  )
              ) {
                Text(text = "This Week", modifier = Modifier.padding(vertical = 8.dp,  horizontal = 8.dp),
                  style = TextStyle(
                    fontSize = 16.sp,
                    fontWeight = FontWeight(400),
                  ))
                Text(text = "${dashboardDataStateFlow.thisWeekCases}", modifier = Modifier.padding(vertical = 8.dp,  horizontal = 8.dp),
                  style = TextStyle(
                    fontSize = 16.sp,
                    fontWeight = FontWeight(500),
                    color = Color.DarkGray
                  )) // Replace with dynamic value
              }
              Spacer(modifier = Modifier.height(8.dp))
              Column(
                modifier = Modifier
                  .background(ThinGreyBackground)
                  .weight(1f)
                  .border(
                    width = 0.2.dp,
                    color = Color.LightGray
                  )
              ) {
                Text(text = "This Month", modifier = Modifier.padding(vertical = 8.dp,  horizontal = 8.dp),
                  style = TextStyle(
                    fontSize = 14.sp,
                    fontWeight = FontWeight(400),
                  ))
                Text(text = "${dashboardDataStateFlow.thisMonthCases}", modifier = Modifier.padding(vertical = 8.dp,  horizontal = 8.dp),
                  style = TextStyle(
                    fontSize = 16.sp,
                    fontWeight = FontWeight(500),
                    color = Color.DarkGray
                  )) // Replace with dynamic value
              }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = 4.dp, vertical = 16.dp)
              .background(LightColors.primary)
              .clickable {
                onAddNewCase()
              },
              horizontalArrangement = Arrangement.Center) {
              TextButton(onClick = { onAddNewCase() }, modifier = Modifier.padding(horizontal = 8.dp)) {
                Text(text = "Add New Case", style = MaterialTheme.typography.h6, color = Color.White)
              }
            }
            Spacer(modifier = Modifier.height(8.dp))
          }

          Spacer(modifier = Modifier.height(8.dp))

          /*Column(
            modifier = Modifier
              .padding(16.dp)
              .background(Color.White)
          ) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = "Followup", style = MaterialTheme.typography.h6)
            Spacer(modifier = Modifier.height(8.dp))

            Row(
              modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .background(SearchHeaderColor), horizontalArrangement = Arrangement.SpaceBetween
            ) {
              Text(text = "Not Contacted")
              Text(text = "3") // Replace with dynamic value
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = 16.dp)) {
              Column(
                modifier = Modifier
                  .background(Color.White)
              ) {
                Text(text = "Not Responded")
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = "5") // Replace with dynamic value
              }
              Spacer(modifier = Modifier.width(8.dp))
              Column(
                modifier = Modifier
                  .background(Color.White)
              ) {
                Text(text = "Didn't Agree")
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = "12") // Replace with dynamic value
              }
            }

            Row(modifier = Modifier
              .padding(horizontal = 16.dp)) {
              Column(
                modifier = Modifier
                  .fillMaxWidth()
                  .background(Color.White)
              ) {
                Text(text = "Agreed")
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = "3") // Replace with dynamic value
              }
              Spacer(modifier = Modifier.width(8.dp))
              Column(
                modifier = Modifier
                  .fillMaxWidth()
                  .background(Color.White)
              ) {
                Text(text = "Followups Done")
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = "12") // Replace with dynamic value
              }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = 16.dp, vertical = 16.dp)
              .background(LightColors.primary),
              horizontalArrangement = Arrangement.Center) {
              TextButton(onClick = { }, modifier = Modifier.padding(horizontal = 8.dp)) {
                Text(text = "Check New Recommendation", style = MaterialTheme.typography.h6, color = Color.White)
              }
            }
            Spacer(modifier = Modifier.height(8.dp))
          }*/
        }
      }
    }
  }
}