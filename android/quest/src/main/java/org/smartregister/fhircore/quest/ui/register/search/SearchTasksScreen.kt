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

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.MaterialTheme
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import kotlinx.coroutines.launch
import org.smartregister.fhircore.engine.configuration.navigation.NavigationMenuConfig
import org.smartregister.fhircore.engine.configuration.register.NoResultsConfig
import org.smartregister.fhircore.engine.ui.theme.LightColors
import org.smartregister.fhircore.engine.ui.theme.SearchHeaderColor
import org.smartregister.fhircore.engine.util.annotation.PreviewWithBackgroundExcludeGenerated
import org.smartregister.fhircore.quest.ui.main.AppMainViewModel
import org.smartregister.fhircore.quest.ui.main.components.FILTER
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.ModalBottomSheetLayout
import androidx.compose.material.ModalBottomSheetValue
import androidx.compose.material.rememberModalBottomSheetState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.unit.sp
import org.hl7.fhir.r4.model.Task
import org.hl7.fhir.r4.model.Task.TaskPriority
import org.hl7.fhir.r4.model.Task.TaskStatus
import org.smartregister.fhircore.engine.domain.model.ToolBarHomeNavigation
import org.smartregister.fhircore.quest.R
import org.smartregister.fhircore.quest.ui.register.tasks.BottomSheetContent
import org.smartregister.fhircore.quest.ui.register.patients.NoRegisterDataView
import org.smartregister.fhircore.quest.ui.register.patients.RegisterEvent
import org.smartregister.fhircore.quest.ui.register.patients.RegisterUiState
import org.smartregister.fhircore.quest.ui.register.patients.RegisterViewModel
import org.smartregister.fhircore.quest.ui.register.patients.TOP_REGISTER_SCREEN_TEST_TAG


@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterialApi::class)
@Composable
fun SearchTasksScreen(
    modifier: Modifier = Modifier,
    viewModel : RegisterViewModel,
    appMainViewModel: AppMainViewModel,
    onEvent: (RegisterEvent) -> Unit,
    registerUiState: RegisterUiState,
    searchText: MutableState<String>,
    navController: NavController,
) {
  val lazyListState: LazyListState = rememberLazyListState()
  val coroutineScope = rememberCoroutineScope()
  val bottomSheetState = rememberModalBottomSheetState(initialValue = ModalBottomSheetValue.Hidden)
  var selectedTask by remember { mutableStateOf<RegisterViewModel.TaskItem?>(null) }
  val searchResultTasks by viewModel.searchedTasksStateFlow.collectAsState()


  ModalBottomSheetLayout(
    sheetState = bottomSheetState,
    sheetContent = {
      selectedTask?.let { task ->
        BottomSheetContent(task = task, onStatusUpdate = {
          var status : TaskStatus = TaskStatus.NULL
          when(it){

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
          }

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
        Column(modifier = modifier.background(Color.White).fillMaxWidth()) {

          // Top section has toolbar and a results counts view
          val filterActions =
            registerUiState.registerConfiguration?.registerFilter?.dataFilterActions
          SearchBarSection(
            modifier = modifier.testTag(TOP_REGISTER_SCREEN_TEST_TAG),
            onSync = appMainViewModel::onEvent,
            toolBarHomeNavigation = ToolBarHomeNavigation.NAVIGATE_BACK,
            onSearchTextChanged = { searchText ->
              if (searchText.isEmpty()){
                viewModel.clearSearch()
              }else{
                viewModel.searchTasks(searchText)
              }
            },
          ) { event ->
            navController.popBackStack()
          }
        }
      },

    ) { innerPadding ->

      Box(modifier = modifier.padding(innerPadding)) {

        Box(
          modifier = modifier
            .background(SearchHeaderColor)
        )
        {


          Column(
            modifier = modifier
              .fillMaxHeight()
              .background(SearchHeaderColor)
              .fillMaxWidth()
          ){

            LaunchedEffect(key1 = searchResultTasks) {
              //viewModel.getFilteredTasks(selectedFilter, taskStatus, taskPriority)
            }


            Spacer(modifier = Modifier.height(16.dp))

            Box(
              modifier = modifier
                .fillMaxHeight()
                .background(SearchHeaderColor)
                .fillMaxWidth()
            ) {

              if (searchResultTasks.isEmpty()){
                Box(
                  modifier = modifier
                    .background(SearchHeaderColor)
                    .padding(top = 48.dp)
                    .fillMaxWidth(),
                  contentAlignment = Alignment.Center
                ) {

                  Box(
                    modifier = modifier
                      .padding(horizontal = 16.dp)
                      .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                  ) {
                    Text(text = stringResource(id = R.string.no_cases))
                  }
                }
              }else{
                LazyColumn {
                  items(searchResultTasks) { task ->
                    Box(
                      modifier = modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                        .background(Color.White)
                        .clickable {
                          selectedTask = task
                          coroutineScope.launch { bottomSheetState.show() }
                        }
                    ) {
                      CardItemView(task, onSelectTask = {
                        selectedTask = task
                        coroutineScope.launch { bottomSheetState.show() }
                      })
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
}

@Composable
fun CardItemView(task: RegisterViewModel.TaskItem, onSelectTask : (RegisterViewModel.TaskItem) -> Unit) {
  Box(
    modifier = Modifier
      .fillMaxWidth()
      .padding(vertical = 8.dp)
      .background(Color.White)
      .clickable {
        onSelectTask(task)
        //viewModel.updateTask(task, Task.TaskStatus.INPROGRESS, Task.TaskPriority.ROUTINE)
      }
  ) {
    Card(
      modifier = Modifier
        .fillMaxWidth()
        .background(Color.White),
      elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
      Box(
        modifier = Modifier
          .background(Color.White)
      ) {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp, horizontal = 16.dp)
            .background(Color.White)
        ) {
          var name = ""
          var phone = ""
          if (task.patient?.name?.isNotEmpty() == true && task.patient?.name?.get(0)?.given?.isNotEmpty() == true){
            name = task.patient?.name?.get(0)?.given?.get(0)?.value.toString()
            phone = task.patient?.telecom?.get(0)?.value.toString()
          }
          Row(modifier = Modifier.padding(vertical = 4.dp)) {

            androidx.compose.material.Icon(
              modifier = Modifier.padding(
                vertical = 4.dp,
                horizontal = 4.dp
              ),
              painter = painterResource(id = R.drawable.ic_patient_male),
              contentDescription = FILTER,
              tint = LightColors.primary,
            )

            Column(modifier = Modifier.padding(vertical = 4.dp)) {
              Text(
                modifier = Modifier
                  .padding(vertical = 4.dp, horizontal = 4.dp),
                text = "$name",
                fontSize = 18.sp,
                color = LightColors.primary
              )
              Spacer(modifier = Modifier.height(4.dp))
              Text(
                text = "Phone ${phone}",
                color = colorResource(id = R.color.subTextGrey),
                fontSize = 14.sp,
                modifier = Modifier
                  .padding(horizontal = 4.dp, vertical = 4.dp)
              )
              Spacer(modifier = Modifier.height(4.dp))
              Row(modifier = Modifier.padding(vertical = 4.dp)) {
                var label = ""
                var textColor = Color.Black
                var color = Color.Black

                when(task.task.intent){

                  Task.TaskIntent.PLAN -> {
                    label = "ADD INVESTIGATION"
                    color = Color(0xFFFFF8E0)
                    textColor = Color(0xFFFFC800)
                  }

                  Task.TaskIntent.ORDER -> {
                    label = "URGENT REFERRAL"
                    color = Color(0xFFFFCDD2)
                    textColor = Color(0xFFFF3355)
                  }

                  Task.TaskIntent.PROPOSAL -> {
                    label = "RETAKE PHOTO"
                    color = Color.LightGray
                    textColor = Color.Gray
                  }

                  else -> {
                    label = ""
                    color = Color.LightGray
                    textColor = Color.Gray
                  }
                }

                Text(
                  text = label,
                  color = textColor,
                  fontSize = 14.sp,
                  modifier = Modifier
                    .background(color, shape = MaterialTheme.shapes.small)
                    .padding(horizontal = 4.dp, vertical = 4.dp)
                )


                var statusLabel = ""
                var statusTextColor = Color.Black
                var statusColor = Color.Black

                when(task.task.status){

                  TaskStatus.INPROGRESS -> {
                    statusLabel = TaskStatus.INPROGRESS.name
                    statusColor = Color(0xFFFFF8E0)
                    statusTextColor = Color(0xFFFFC800)
                  }

                  TaskStatus.REQUESTED -> {
                    statusLabel = TaskStatus.REQUESTED.name
                    statusColor = Color(0xFFFFCDD2)
                    statusTextColor = Color(0xFFFF3355)
                  }

                  TaskStatus.COMPLETED -> {
                    statusLabel = TaskStatus.COMPLETED.name
                    statusColor = Color.LightGray
                    statusTextColor = Color.Green
                  }

                  else -> {
                    statusLabel = ""
                    statusColor = Color.LightGray
                    statusTextColor = Color.Gray
                  }
                }

                Spacer(modifier = Modifier.width(16.dp))

                Text(
                  text = statusLabel,
                  color = statusTextColor,
                  fontSize = 14.sp,
                  modifier = Modifier
                    .background(statusColor, shape = MaterialTheme.shapes.small)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                )
              }
            }
          }
        }
      }
    }
  }

}

@PreviewWithBackgroundExcludeGenerated
@Composable
private fun PreviewNoRegistersView() {
  NoRegisterDataView(
    viewModel = viewModel(),
    noResults =
      NoResultsConfig(
        title = "Title",
        message = "This is message",
        actionButton = NavigationMenuConfig(display = "Button Text", id = "1"),
      ),
  ) {}
}