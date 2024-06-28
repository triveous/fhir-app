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

package org.smartregister.fhircore.quest.ui.register

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Tab
import androidx.compose.material.TabRow
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.paging.compose.LazyPagingItems
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.smartregister.fhircore.engine.configuration.navigation.NavigationMenuConfig
import org.smartregister.fhircore.engine.configuration.register.NoResultsConfig
import org.smartregister.fhircore.engine.domain.model.ResourceData
import org.smartregister.fhircore.engine.ui.components.register.RegisterHeader
import org.smartregister.fhircore.engine.ui.theme.LightColors
import org.smartregister.fhircore.engine.ui.theme.SearchHeaderColor
import org.smartregister.fhircore.engine.util.annotation.PreviewWithBackgroundExcludeGenerated
import org.smartregister.fhircore.quest.ui.main.AppMainViewModel
import org.smartregister.fhircore.quest.ui.main.components.FILTER
import org.smartregister.fhircore.quest.ui.main.components.TopScreenSection
import org.smartregister.fhircore.quest.ui.shared.components.ExtendedFab
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.ModalBottomSheetLayout
import androidx.compose.material.ModalBottomSheetValue
import androidx.compose.material.TextField
import androidx.compose.material.TextFieldDefaults
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.rememberModalBottomSheetState
import androidx.compose.material3.RadioButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import org.hl7.fhir.r4.model.Task
import org.hl7.fhir.r4.model.Task.TaskPriority
import org.hl7.fhir.r4.model.Task.TaskStatus
import org.smartregister.fhircore.engine.domain.model.ToolBarHomeNavigation
import org.smartregister.fhircore.quest.R


const val URGENT_REFERRAL_TAB = "Urgent Referral"
const val ADD_INVESTIGATION_TAB = "Add Investigation"
const val RETAKE_PHOTO_TAB = "Retake Photo Tab"
const val URGENT_REFERRAL_PATIENTS = 0
const val ADD_INVESTIGATION_PATIENTS = 1
const val RETAKE_PHOTO_PATIENTS = 2

enum class FilterType(val label: String) {
  URGENT_REFERRAL("Urgent Referral"),
  ADD_INVESTIGATION("Add. Investigation"),
  RETAKE_PHOTO("Retake photo")
}

@Composable
fun FilterRow(selectedFilter: FilterType, onFilterSelected: (FilterType) -> Unit) {
  Row(modifier = Modifier
    .fillMaxWidth()
    .horizontalScroll(rememberScrollState())
    .padding(16.dp)) {
    FilterType.entries.forEachIndexed { index, filter ->
      Box(modifier = Modifier
        .border(width = 0.5.dp, color = Color.LightGray)
        .background(if (filter == selectedFilter) LightColors.primary else SearchHeaderColor)
        .padding(8.dp)) {
        Text(
          text = filter.label,
          modifier = Modifier
            .padding(4.dp)
            .clickable {
              onFilterSelected(filter)
            },
          color = if (filter == selectedFilter) Color.White else Color.Black
        )
      }
      if (index < FilterType.entries.size - 1) {
        Spacer(modifier = Modifier.width(8.dp)) // Horizontal margin
      }
    }
  }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterialApi::class)
@Composable
fun ViewAllTasksScreen(
  modifier: Modifier = Modifier,
  openDrawer: (Boolean) -> Unit,
  viewModel : RegisterViewModel,
  appMainViewModel: AppMainViewModel,
  onEvent: (RegisterEvent) -> Unit,
  registerUiState: RegisterUiState,
  searchText: MutableState<String>,
  currentPage: MutableState<Int>,
  pagingItems: LazyPagingItems<ResourceData>,
  navController: NavController,
  screenTitle : String
) {
  val lazyListState: LazyListState = rememberLazyListState()
  val coroutineScope = rememberCoroutineScope()
  val bottomSheetState = rememberModalBottomSheetState(initialValue = ModalBottomSheetValue.Hidden)
  var selectedTask by remember { mutableStateOf<RegisterViewModel.TaskItem?>(null) }

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
        Column(modifier = modifier.background(SearchHeaderColor)) {

          // Top section has toolbar and a results counts view
          val filterActions =
            registerUiState.registerConfiguration?.registerFilter?.dataFilterActions
          TopScreenSection(
            modifier = modifier.testTag(TOP_REGISTER_SCREEN_TEST_TAG),
            title = screenTitle,
            searchText = searchText.value,
            filteredRecordsCount = registerUiState.filteredRecordsCount,
            searchPlaceholder = registerUiState.registerConfiguration?.searchBar?.display,
            onSync = appMainViewModel::onEvent,
            toolBarHomeNavigation = ToolBarHomeNavigation.NAVIGATE_BACK,
            onSearchTextChanged = { searchText ->
              onEvent(RegisterEvent.SearchRegister(searchText = searchText))
            },
            isFilterIconEnabled = filterActions?.isNotEmpty() ?: false,
          ) { event ->
            navController.popBackStack()
          }

          /*TextField(
            value = searchText.value,
            onValueChange = { searchText.value = it },
            leadingIcon = {
              Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = "Search Icon",
                tint = Color.Gray,
                modifier = Modifier.size(24.dp)
              )
            },
            placeholder = {
              Text(
                text = "Enter name or phone",
                color = Color.Gray,
                fontSize = 16.sp,
                textAlign = TextAlign.Start
              )
            },
            modifier = Modifier
              .fillMaxWidth()
              .padding(8.dp)
              .padding(all = 4.dp)
              .border(
                width = 0.5.dp,
                color = Color.DarkGray
              )
              .background(Color.White, shape = RoundedCornerShape(8.dp)),
            colors = TextFieldDefaults.textFieldColors(
              backgroundColor = Color.White,
              focusedIndicatorColor = Color.Transparent,
              unfocusedIndicatorColor = Color.Transparent,
              disabledIndicatorColor = Color.Transparent
            ),
            singleLine = true
          )*/

          // Only show counter during search
          if (searchText.value.isNotEmpty()) RegisterHeader(resultCount = pagingItems.itemCount)

          /*registerUiState.registerConfiguration?.noResults?.let { noResultConfig ->
  
            Box {
              NoRegisterDataView(
                modifier = modifier,
                viewModel = viewModel,
                noResults = noResultConfig
              ) {
                noResultConfig.actionButton?.actions?.handleClickEvent(navController)
              }
            }
          }*/
        }

      },

      floatingActionButton = {
        val fabActions = registerUiState.registerConfiguration?.fabActions
        if (!fabActions.isNullOrEmpty() && fabActions.first().visible) {
          ExtendedFab(
            modifier = modifier.testTag(FAB_BUTTON_REGISTER_TEST_TAG),
            fabActions = fabActions,
            navController = navController,
            lazyListState = lazyListState,
          )
        }
      },
    ) { innerPadding ->

      Box(modifier = modifier.padding(innerPadding)) {

        Box(
          modifier = modifier
            .background(SearchHeaderColor)
        )
        {
          val newTasks by viewModel.newTasksStateFlow.collectAsState()
          val pendingTasks by viewModel.pendingTasksStateFlow.collectAsState()
          val completedTasks by viewModel.completedTasksStateFlow.collectAsState()

          var selectedFilter by remember { mutableStateOf(FilterType.URGENT_REFERRAL) }
          val filteredTasks by viewModel.filteredTasksStateFlow.collectAsState()

          val tabTitles = listOf(TASK_NEW_TAB, TASK_PENDING_TAB, TASK_COMPLETED_TAB)
          val pagerState = rememberPagerState(pageCount = { 3 }, initialPage = 0)

          val urgentReferralTasks by viewModel.patientsStateFlow.collectAsState()
          val addInvestigationTasks by viewModel.savedDraftResponse.collectAsState()
          val retakePhotoTasks by viewModel.unSyncedStateFlow.collectAsState()

          var deleteDraftId by remember { mutableStateOf("") }
          var showDeleteDialog by remember { mutableStateOf(false) }

          Column(
            modifier = modifier
              .fillMaxHeight()
              .background(SearchHeaderColor)
              .fillMaxWidth()
          ){

            LaunchedEffect(Unit) {
              viewModel.getFilteredTasks(selectedFilter)
            }

            Row(modifier = Modifier
              .fillMaxWidth()) {
              FilterRow(selectedFilter) { filter ->
                selectedFilter = filter
              }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Box(
              modifier = modifier
                .fillMaxHeight()
                .background(SearchHeaderColor)
                .fillMaxWidth()
            ) {

              if (filteredTasks.isEmpty()){
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
                  items(filteredTasks) { task ->
                    Box(
                      modifier = modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                        .background(Color.White)
                        .clickable {
                          selectedTask = task
                          coroutineScope.launch { bottomSheetState.show() }
                          /*viewModel.updateTask(
                            task,
                            Task.TaskStatus.COMPLETED,
                            Task.TaskPriority.URGENT
                          )*/
                        }
                    ) {
                      Card(
                        modifier = Modifier
                          .fillMaxWidth()
                          .background(Color.White),
                        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                      ) {
                        Box(
                          modifier = modifier
                            .background(Color.White)
                        ) {
                          Column(
                            modifier = Modifier
                              .fillMaxWidth()
                              .padding(vertical = 16.dp, horizontal = 16.dp)
                              .background(Color.White)
                          ) {
                            Row(modifier = modifier.padding(vertical = 4.dp)) {
                              androidx.compose.material.Icon(
                                modifier = Modifier.padding(
                                  vertical = 4.dp,
                                  horizontal = 4.dp
                                ),
                                painter = painterResource(id = R.drawable.patient_icon),
                                contentDescription = FILTER,
                                tint = LightColors.primary
                              )
                              Text(
                                modifier = Modifier
                                  .weight(1f)
                                  .padding(vertical = 4.dp, horizontal = 8.dp),
                                text = task.task.description,
                                style = MaterialTheme.typography.h6,
                                color = LightColors.primary
                              )
                              Spacer(modifier = Modifier.height(16.dp))
                            }

                            Row(
                              modifier = modifier.padding(
                                vertical = 8.dp,
                                horizontal = 36.dp
                              )
                            ) {
                              Text(text = "Status: ${task.task.status}")
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
      }
    }
  }
}

@Composable
private fun ShowUnSyncedPatients(
  modifier: Modifier,
  completedTasks: List<Task>
) {
  Box(
    modifier = modifier
      .padding(top = 64.dp, start = 16.dp, end = 16.dp)
      .fillMaxHeight()
      .fillMaxWidth()
      .background(SearchHeaderColor)
  ) {

    if (completedTasks.isEmpty()) {
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
          Text(text = stringResource(id = org.smartregister.fhircore.quest.R.string.no_unsync_patients))
        }
      }
    } else {
      Box(
        modifier = modifier
          .background(SearchHeaderColor)
          .fillMaxWidth()
      ) {
        LazyColumn {
          items(completedTasks) { task ->
            Box(
              modifier = modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
                .background(Color.White)
            ) {
              Card(
                modifier = Modifier
                  .fillMaxWidth()
                  .background(Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
              ) {
                Box(
                  modifier = modifier
                    .background(Color.White)
                ) {
                  Column(
                    modifier = Modifier
                      .fillMaxWidth()
                      .padding(vertical = 16.dp, horizontal = 16.dp)
                      .background(Color.White)
                  ) {
                    Row(modifier = modifier.padding(vertical = 4.dp)) {

                      androidx.compose.material.Icon(
                        modifier = Modifier.padding(
                          vertical = 4.dp,
                          horizontal = 4.dp
                        ),
                        painter = painterResource(id = com.google.android.fhir.datacapture.R.drawable.ic_document_file),
                        contentDescription = FILTER,
                        tint = Color.Black,
                      )

                      Text(
                        modifier = Modifier
                          .weight(1f)
                          .padding(vertical = 4.dp, horizontal = 4.dp),
                        text = task.description,
                        style = MaterialTheme.typography.h6,
                        color = LightColors.primary
                      )
                      Spacer(modifier = Modifier.height(16.dp))

                      //Text(text = "Sync: Un-Synced")

                    }

                    Row(modifier = modifier.padding(vertical = 4.dp)) {
                      Text(text = "Status: ${task.status}")
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


@OptIn(ExperimentalMaterialApi::class)
@Composable
private fun ShowAllPatients(
  modifier: Modifier,
  newTasks: List<Task>,
  viewModel: RegisterViewModel,
  onSelectTask : (Task) -> Unit
) {

  val isFetchingPatients by viewModel.isFetchingTasks.collectAsState()

  Box(
    modifier = modifier
      .padding(top = 64.dp, start = 16.dp, end = 16.dp)
      .fillMaxHeight()
      .fillMaxWidth()
      .background(SearchHeaderColor)
  ) {

    if (newTasks.isEmpty()) {
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
          if (isFetchingPatients){
            Text(text = stringResource(id = org.smartregister.fhircore.quest.R.string.loading_followups))
          }else{
            Text(text = stringResource(id = org.smartregister.fhircore.quest.R.string.no_followups))
          }
        }
      }
    } else {
      Box(
        modifier = modifier
          .fillMaxHeight()
          .padding(top = 8.dp)
          .background(SearchHeaderColor)
          .fillMaxWidth()
      ) {
        LazyColumn {
           items(newTasks) { task ->
             Box(
               modifier = modifier
                 .fillMaxWidth()
                 .padding(vertical = 8.dp)
                 .background(Color.White)
                 .clickable {
                   onSelectTask(task)
                   //viewModel.updateTask(task, Task.TaskStatus.INPROGRESS, Task.TaskPriority.ROUTINE)
                   task.input
                 }
             ) {
               Card(
                 modifier = Modifier
                   .fillMaxWidth()
                   .background(Color.White),
                 elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
               ) {
                 Box(
                   modifier = modifier
                     .background(Color.White)
                 ) {
                   Column(
                     modifier = Modifier
                       .fillMaxWidth()
                       .padding(vertical = 16.dp, horizontal = 16.dp)
                       .background(Color.White)
                   ) {
                     Row(modifier = modifier.padding(vertical = 4.dp)) {

                       androidx.compose.material.Icon(
                         modifier = Modifier.padding(
                           vertical = 4.dp,
                           horizontal = 4.dp
                         ),
                         painter = painterResource(id = org.smartregister.fhircore.quest.R.drawable.patient_icon),
                         contentDescription = FILTER,
                         tint = LightColors.primary,
                       )

                       Text(
                         modifier = Modifier
                           .weight(1f)
                           .padding(vertical = 4.dp, horizontal = 4.dp),
                         text = task.description,
                         style = MaterialTheme.typography.h6,
                         color = LightColors.primary
                       )
                       Spacer(modifier = Modifier.height(16.dp))

                       //Text(text = "Sync: Un-Synced")
                     }

                     Row(modifier = modifier.padding(vertical = 4.dp)) {
                       var label = ""
                       var textColor = Color.Black
                       var color = Color.Black

                       when(task.priority){

                         TaskPriority.ROUTINE -> {
                           label = "ADD INVESTIGATION"
                           color = Color(0xFFFFF8E0)
                           textColor = Color(0xFFFFC800)
                         }

                         TaskPriority.URGENT -> {
                           label = "URGENT REFERRAL"
                           color = Color(0xFFFFCDD2)
                           textColor = Color(0xFFFF3355)
                         }

                         TaskPriority.STAT -> {
                           label = "RETAKE PHOTO"
                           color = Color.LightGray
                           textColor = Color.Gray
                         }

                         else -> {}
                       }

                       Text(
                         text = label,
                         color = textColor,
                         modifier = Modifier
                           .background(color, shape = MaterialTheme.shapes.small)
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
    }
  }
}

@Composable
fun BottomSheetContent1(task: Task, onStatusUpdate: (TaskPriority) -> Unit, onCancel: () -> Unit) {
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp)
  ) {
    val selectedStatus = remember { mutableStateOf(TaskPriority.NULL) } // Initial selected status

    Row(modifier = Modifier.padding(vertical = 4.dp, horizontal = 4.dp)) {

      androidx.compose.material.Icon(
        modifier = Modifier.padding(
          vertical = 4.dp,
          horizontal = 4.dp
        ),
        painter = painterResource(id = R.drawable.ic_patient_male),
        contentDescription = FILTER,
        tint = LightColors.primary
      )

      Text(
        modifier = Modifier
          .weight(1f)
          .padding(vertical = 4.dp, horizontal = 4.dp),
        text = task.description,
        fontSize = 20.sp,
        color = LightColors.primary
      )
      Spacer(modifier = Modifier.height(16.dp))
    }

    Text(text = "House no 112, Street no 1/22, BTM Bangalore 560029",
          modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
      color = colorResource(id = R.color.subTextGrey),
      fontSize = 14.sp
    )

    Row {
      Text(text = "Screened on: ",
        color = colorResource(id = R.color.subTextGreyBold),
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold)

      Text(text = "14 June 2024",
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
        color = colorResource(id = R.color.subTextGrey),

      fontSize = 14.sp,
      )

    }

    Row(modifier = Modifier
      .fillMaxWidth()
      .padding(vertical = 8.dp, horizontal = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween) {

      Text(text = "Phone : ",
        color = colorResource(id = R.color.subTextGreyBold),
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.align(Alignment.CenterVertically))

      Text(text = "7353489677",
        color = colorResource(id = R.color.subTextGrey),
        fontSize = 14.sp,
        modifier = Modifier.align(Alignment.CenterVertically))

      androidx.compose.material.Icon(
        modifier = Modifier
          .padding(
            vertical = 4.dp,
            horizontal = 4.dp
          )
          .width(24.dp)
          .height(24.dp)
          .align(Alignment.CenterVertically),
        painter = painterResource(id = R.drawable.ic_call),
        contentDescription = FILTER,
        tint = LightColors.primary
      )
      Text(text = "Call", color = Color.Blue)

      /*Box(modifier = Modifier
        .padding(horizontal = 4.dp)
        .clickable {

        },
        contentAlignment = Alignment.BottomEnd
        ) {
        Text(text = "Call", color = Color.Blue)
      }*/
    }


    Row(modifier = Modifier.padding(vertical = 4.dp)) {
      var label = ""
      var textColor = Color.Black
      var color = Color.Black

      when(task.priority){

        TaskPriority.ROUTINE -> {
          label = "ADD INVESTIGATION"
          color = Color(0xFFFFF8E0)
          textColor = Color(0xFFFFC800)
        }

        TaskPriority.URGENT -> {
          label = "URGENT REFERRAL TO HOSPITAL"
          color = Color(0xFFFFCDD2)
          textColor = Color(0xFFFF3355)
        }

        TaskPriority.STAT -> {
          label = "RETAKE PHOTO"
          color = Color.LightGray
          textColor = Color.Gray
        }
        TaskPriority.ASAP -> TODO()
        TaskPriority.NULL -> TODO()
      }

      Row(modifier = Modifier
        .background(color, shape = MaterialTheme.shapes.small)
        .fillMaxWidth(),
        horizontalArrangement = Arrangement.Center
      ) {
        Text(
          text = label,
          color = textColor,
          fontWeight = FontWeight.Bold,
          textAlign = TextAlign.Center,
          modifier = Modifier
            .padding(horizontal = 8.dp, vertical = 8.dp)
            .fillMaxWidth(),
        )
      }
    }

    Spacer(modifier = Modifier.height(16.dp))
    Spacer(modifier = Modifier
      .fillMaxWidth()
      .height(1.dp)
      .background(Color.LightGray))
    Spacer(modifier = Modifier.height(16.dp))

    Text(text = "CHANGE STATUS", color = colorResource(id = R.color.subTextGrey), fontSize = 14.sp)
    Spacer(modifier = Modifier.height(16.dp))


    Row(modifier = Modifier,
      verticalAlignment = Alignment.CenterVertically) {

      RadioButton(
        selected = selectedStatus.value == TaskPriority.NULL, // Compare with data source
        onClick = { selectedStatus.value = TaskPriority.NULL } // Update data source on click
      )
      Text(text = "Not responded", modifier = Modifier.padding(horizontal = 8.dp), color = colorResource(id = R.color.optionColor))
    }
    Row(modifier = Modifier,
      verticalAlignment = Alignment.CenterVertically) {
      RadioButton(
        selected = selectedStatus.value == TaskPriority.STAT, // Compare with data source
        onClick = { selectedStatus.value = TaskPriority.STAT } // Update data source on click
      )
      Text(text = "Didn't agree for follow up", modifier = Modifier.padding(horizontal = 8.dp), color = colorResource(id = R.color.optionColor))
    }
    Row(modifier = Modifier,
      verticalAlignment = Alignment.CenterVertically) {
      RadioButton(
        selected = selectedStatus.value == TaskPriority.ASAP, // Compare with data source
        onClick = { selectedStatus.value = TaskPriority.ASAP } // Update data source on click
      )
      Text(text = "Agreed, Follow up not done", modifier = Modifier.padding(horizontal = 8.dp), color = colorResource(id = R.color.optionColor))
    }
    Row(modifier = Modifier,
      verticalAlignment = Alignment.CenterVertically) {
      RadioButton(
        selected = selectedStatus.value == TaskPriority.ROUTINE, // Compare with data source
        onClick = { selectedStatus.value = TaskPriority.ROUTINE } // Update data source on click
      )
      Text(text = "Follow up done", modifier = Modifier.padding(horizontal = 8.dp), color = colorResource(id = R.color.optionColor))
    }

    Spacer(modifier = Modifier.height(16.dp))
    Spacer(modifier = Modifier
      .fillMaxWidth()
      .height(1.dp)
      .background(Color.LightGray))
    Spacer(modifier = Modifier.height(16.dp))

    Row(modifier = Modifier
      .height(48.dp)
      .fillMaxWidth()
      .padding(horizontal = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceAround) {

      Button(modifier = Modifier
        .fillMaxHeight()
        .weight(1f) // Equally divide width
        .padding(horizontal = 4.dp),
        colors = ButtonDefaults.buttonColors( // Override default colors
          backgroundColor = Color.Transparent, // Transparent background
          contentColor = Color.White // Set text color to white
        ),
        onClick = { onCancel() }) {
        Text(text = stringResource(id = R.string.cancel), color = Color(0xFF5B5959))
      }

      Button(modifier = Modifier
        .fillMaxHeight()
        .weight(1f) // Equally divide width
        .padding(horizontal = 4.dp)
        .background(LightColors.primary),
        onClick = { onStatusUpdate(selectedStatus.value) }) {
        Text(text = stringResource(id = R.string.update_status), color = Color.White)
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