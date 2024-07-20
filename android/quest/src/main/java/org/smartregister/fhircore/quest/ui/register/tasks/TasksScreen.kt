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

import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.TextButton
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.rememberModalBottomSheetState
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonColors
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import org.hl7.fhir.r4.model.CodeableConcept
import org.hl7.fhir.r4.model.Coding
import org.hl7.fhir.r4.model.Task
import org.hl7.fhir.r4.model.Task.TaskOutputComponent
import org.hl7.fhir.r4.model.Task.TaskPriority
import org.hl7.fhir.r4.model.Task.TaskStatus
import org.smartregister.fhircore.engine.domain.model.SnackBarMessageConfig
import org.smartregister.fhircore.engine.domain.model.ToolBarHomeNavigation
import org.smartregister.fhircore.engine.util.extension.valueToString
import org.smartregister.fhircore.quest.R
import org.smartregister.fhircore.quest.ui.register.components.EmptyStateSection
import org.smartregister.fhircore.quest.ui.register.patients.FAB_BUTTON_REGISTER_TEST_TAG
import org.smartregister.fhircore.quest.ui.register.patients.GenericActivity
import org.smartregister.fhircore.quest.ui.register.patients.NoRegisterDataView
import org.smartregister.fhircore.quest.ui.register.patients.RegisterEvent
import org.smartregister.fhircore.quest.ui.register.patients.RegisterUiState
import org.smartregister.fhircore.quest.ui.register.patients.RegisterViewModel
import org.smartregister.fhircore.quest.ui.register.patients.TOP_REGISTER_SCREEN_TEST_TAG
import org.smartregister.fhircore.quest.ui.register.patients.GenericActivityArg.ARG_FROM
import org.smartregister.fhircore.quest.ui.register.patients.GenericActivityArg.SCREEN_TITLE
import org.smartregister.fhircore.quest.ui.register.patients.GenericActivityArg.TASK_PRIORITY
import org.smartregister.fhircore.quest.ui.register.patients.GenericActivityArg.TASK_STATUS
import org.smartregister.fhircore.quest.util.OpensrpDateUtils.convertToDate
import org.smartregister.fhircore.quest.util.SectionTitles
import org.smartregister.fhircore.quest.util.TaskProgressState
import org.smartregister.fhircore.quest.util.TaskProgressStatusDisplay


const val TASK_NEW_TAB = "New"
const val TASK_PENDING_TAB = "Pending"
const val TASK_COMPLETED_TAB = "Completed"
const val TASK_NEW_PATIENTS = 0
const val TASK_PENDING_PATIENTS = 1
const val TASK_COMPLETED_PATIENTS = 2

enum class TabType(val label: String) {
  TASK_NEW_TAB("New"),
  TASK_PENDING_TAB("Pending"),
  TASK_COMPLETED_TAB("Completed")
}

data class Section(val title: String, val items: List<RegisterViewModel.TaskItem>)


@Composable
fun FilterRow2(selectedFilter: TabType, onFilterSelected: (TabType) -> Unit) {
  Row(modifier = Modifier
    .fillMaxWidth()
    .horizontalScroll(rememberScrollState())
    .padding(16.dp),
    horizontalArrangement = Arrangement.SpaceAround
  ) {
    TabType.entries.forEachIndexed { index, filter ->
      Box(modifier = Modifier
        .background(
          if (filter == selectedFilter) LightColors.primary else SearchHeaderColor,
          shape = RoundedCornerShape(8.dp)
        )
        .border(
          width = 1.dp,
          color = (if (filter == selectedFilter) LightColors.primary else Color.LightGray),
          shape = RoundedCornerShape(8.dp)
        )
        .padding(8.dp)
        .align(Alignment.CenterVertically)
        .weight(1f)
      ) {
        Text(
          text = filter.label,
          style = TextStyle(
            fontWeight = FontWeight(600),
            fontSize = 16.sp
          ),
          modifier = Modifier
            .padding(horizontal = 4.dp, vertical = 4.dp)
            .align(Alignment.Center)
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
fun PendingTasksScreen(
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
) {
  val lazyListState: LazyListState = rememberLazyListState()
  val coroutineScope = rememberCoroutineScope()
  val bottomSheetState = rememberModalBottomSheetState(initialValue = ModalBottomSheetValue.Hidden, skipHalfExpanded = true)
  var selectedTask by remember { mutableStateOf<RegisterViewModel.TaskItem?>(null) }

  ModalBottomSheetLayout(
    sheetGesturesEnabled = false,
    sheetState = bottomSheetState,
    sheetContent = {
      selectedTask?.let { task ->
        BottomSheetContent(task = task, onStatusUpdate = { priority ->
          var status : TaskStatus = task.task.status
          var taskPriority = priority
          when(priority){
            TaskProgressState.FOLLOWUP_DONE -> {
              status = TaskStatus.COMPLETED
            }
            TaskProgressState.NOT_AGREED_FOR_FOLLOWUP -> {
              status = TaskStatus.INPROGRESS
            }
            TaskProgressState.AGREED_FOLLOWUP_NOT_DONE -> {
              status = TaskStatus.INPROGRESS
            }

            TaskProgressState.NONE -> {
              //taskPriority = TaskProgressState.FOLLOWUP_DONE
              //status = TaskStatus.REJECTED
            }
            TaskProgressState.REMOVE -> {
              taskPriority = TaskProgressState.REMOVE
              status = TaskStatus.REJECTED
            }

            TaskProgressState.NOT_RESPONDED -> {
              //Status remain same only moves Not contacted to no responded section
              taskPriority = TaskProgressState.NOT_RESPONDED
              status = task.task.status
            }

            else -> { }
          }

          if(taskPriority != TaskProgressState.NONE){
            viewModel.updateTask(task.task, status, taskPriority)
            coroutineScope.launch {
              viewModel.emitSnackBarState(SnackBarMessageConfig("Status updated successfully"))
              bottomSheetState.hide()
            }
          }else{
            coroutineScope.launch {
              viewModel.emitSnackBarState(SnackBarMessageConfig("Select the status to update"))
            }
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
      modifier = modifier
        .background(Color.White)
        .padding(bottom = 16.dp),
      topBar = {
        Column(modifier = modifier.background(SearchHeaderColor)) {

          // Top section has toolbar and a results counts view
          val filterActions =
            registerUiState.registerConfiguration?.registerFilter?.dataFilterActions
          TopScreenSection(
            modifier = modifier.testTag(TOP_REGISTER_SCREEN_TEST_TAG),
            title = stringResource(id = org.smartregister.fhircore.engine.R.string.appname),
            searchText = searchText.value,
            filteredRecordsCount = registerUiState.filteredRecordsCount,
            searchPlaceholder = registerUiState.registerConfiguration?.searchBar?.display,
            onSync = appMainViewModel::onEvent,
            toolBarHomeNavigation = ToolBarHomeNavigation.SYNC,
            onSearchTextChanged = { searchText ->
              onEvent(RegisterEvent.SearchRegister(searchText = searchText))
            },
            isFilterIconEnabled = filterActions?.isNotEmpty() ?: false,
          ) { event ->


          }

          Box(
            modifier
              .fillMaxWidth()
              .padding(all = 16.dp)
              .clickable {
                navController.navigate(R.id.searchTasksFragment)
              }
          ) {
            Row(modifier = Modifier
              .fillMaxWidth()
              .height(64.dp)
              .padding(vertical = 4.dp)
              .border(
                width = 0.5.dp,
                color = Color.DarkGray
              )
              .background(Color.White, shape = RoundedCornerShape(8.dp)),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.Center){
              Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = "Search Icon",
                tint = Color.Gray,
                modifier = Modifier.size(24.dp)
              )
              Spacer(modifier = Modifier.width(4.dp))
              Text(
                text = "Enter name or phone",
                color = Color.Gray,
                fontSize = 16.sp,
                textAlign = TextAlign.Start
              )
            }
          }
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

        val isFetchingTasks by viewModel.isFetchingTasks.collectAsState()
        val newTasks by viewModel.newTasksStateFlow.collectAsState()
        val pendingTasks by viewModel.pendingTasksStateFlow.collectAsState()
        val completedTasks by viewModel.completedTasksStateFlow.collectAsState()

        var selectedTabType = remember {
          mutableStateOf(TabType.TASK_NEW_TAB)
        }
        val tabTitles = listOf(TASK_NEW_TAB, TASK_PENDING_TAB, TASK_COMPLETED_TAB)
        val pagerState = rememberPagerState(pageCount = { 3 }, initialPage = 0)
        val context = LocalContext.current

        LaunchedEffect(selectedTabType) {

        }
        Box(
          modifier = modifier
            .background(SearchHeaderColor)
        )
        {

          Box(modifier = Modifier.padding(bottom = 16.dp )) {
            FilterRow2(selectedTabType.value) {
              selectedTabType.value = it
            }
          }
          Spacer(modifier = Modifier.height(8.dp))

          when (selectedTabType.value) {

            TabType.TASK_NEW_TAB -> {

              Spacer(modifier = Modifier.height(8.dp))

              Box(    modifier = modifier
                .padding(top = 72.dp, start = 16.dp, end = 16.dp)
                .fillMaxHeight()
                .fillMaxWidth()
                .background(SearchHeaderColor)) {

                val notContactedTasks = viewModel.getNotContactedNewTasks(newTasks, TaskStatus.REQUESTED)
                val notRespondedTasks = viewModel.getNotRespondedNewTasks(newTasks,  TaskStatus.REQUESTED)

                val sectionsList : MutableList<Section> = mutableListOf()
                val section = Section(
                  title = stringResource(id = R.string.not_contacted),
                  items = notContactedTasks
                )
                sectionsList.add(section)

                val section2 = Section(
                  title = stringResource(id = R.string.not_responded),
                  items = notRespondedTasks
                )
                sectionsList.add(section2)

                Spacer(modifier = Modifier.height(8.dp))

                LazyColumn {
                  sectionsList.forEach { section ->
                    item {
                      Box {
                        SectionView(
                          section = section,
                          isExpanded = true,
                          onSeeMoreCasesClicked = { title, status, priority ->
                            val intent = Intent(context, GenericActivity::class.java).apply {
                              putExtra(ARG_FROM, "tasks")
                              putExtra(SCREEN_TITLE, if(title.contains(SectionTitles.NOT_CONTACTED, true)) {
                                TaskProgressStatusDisplay.NOT_CONTACTED.text}
                              else{ TaskProgressStatusDisplay.NOT_RESPONDED.text })
                              putExtra(TASK_STATUS, status.name)
                              putExtra(TASK_PRIORITY, priority.name)
                            }

                            context.startActivity(intent)

                            /*navController.navigate(R.id.viewAllTasksFragment, args = bundleOf(
                              NavigationArg.SCREEN_TITLE to title,
                              NavigationArg.TASK_STATUS to status.name,
                              NavigationArg.TASK_PRIORITY to priority.name
                            ))*/
                          },
                          onSelectTask = {
                            selectedTask = it
                            coroutineScope.launch { bottomSheetState.show() }
                          })
                      }
                    }
                  }
                }
              }
            }

            TabType.TASK_PENDING_TAB -> {
              Spacer(modifier = Modifier.height(8.dp))

              Box(modifier = modifier
                .padding(top = 64.dp, start = 16.dp, end = 16.dp)
                .fillMaxHeight()
                .fillMaxWidth()
                .background(SearchHeaderColor)) {

                val pendingAgreedButNotDoneTasks = viewModel.getPendingAgreedButNotDoneTasks(pendingTasks,  TaskStatus.INPROGRESS)
                val pendingNotAgreedTasks = viewModel.getPendingNotAgreedTasks(pendingTasks,  TaskStatus.INPROGRESS)

                val sectionsList : MutableList<Section> = mutableListOf()
                val section = Section(
                  title = stringResource(id = R.string.pending_agreed_not_done),
                  items = pendingAgreedButNotDoneTasks
                )
                sectionsList.add(section)

                val section2 = Section(
                  title = stringResource(id = R.string.pending_not_agreed),
                  items = pendingNotAgreedTasks
                )
                sectionsList.add(section2)

                LazyColumn {
                  sectionsList.forEach { section ->
                    item {
                      Box {
                        SectionView(
                          section = section,
                          isExpanded = true,
                          onSeeMoreCasesClicked = { title, status, priority ->
                            val intent = Intent(context, GenericActivity::class.java).apply {
                              putExtra(ARG_FROM, "tasks")
                              putExtra(SCREEN_TITLE, if(title.contains(SectionTitles.AGREED_FOLLOWUP_NOT_DONE, true)) {
                                TaskProgressStatusDisplay.AGREED_FOLLOWUP_NOT_DONE.text
                              }else{
                                TaskProgressStatusDisplay.NOT_AGREED_FOR_FOLLOWUP.text
                              })
                              putExtra(TASK_STATUS, status.name)
                              putExtra(TASK_PRIORITY, priority.name)
                            }
                            context.startActivity(intent)
                          },
                          onSelectTask = {
                            selectedTask = it
                            coroutineScope.launch { bottomSheetState.show() }
                          })
                      }
                    }
                  }
                }
              }
            }

            TabType.TASK_COMPLETED_TAB -> {
              Spacer(modifier = Modifier.height(8.dp))
              ShowUnSyncedPatients(modifier, completedTasks){
                selectedTask = it
                coroutineScope.launch { bottomSheetState.show() }
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
  completedTasks: List<RegisterViewModel.TaskItem>,
  onSelectTask : (RegisterViewModel.TaskItem) -> Unit
) {
  Box(
    modifier = modifier
      .padding(top = 64.dp, start = 16.dp, end = 16.dp)
      .fillMaxHeight()
      .fillMaxWidth()
      .background(SearchHeaderColor)
  ) {

    if (completedTasks.isEmpty()) {

      EmptyStateSection(false,
        textLabel = stringResource(id = R.string.completed_empty_label),
        icon = painterResource(id =R.drawable.ic_completed_empty), LightColors.primary)
    } else {
      Box(
        modifier = modifier
          .background(SearchHeaderColor)
          .fillMaxWidth()
      ) {
        LazyColumn {
          items(completedTasks) { task ->
            CardItemView(task = task){
              onSelectTask(it)
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
            Text(text = stringResource(id = R.string.loading_followups))
          }else{
            Text(text = stringResource(id = R.string.no_followups))
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
                        painter = painterResource(id = org.smartregister.fhircore.quest.R.drawable.ic_patient_male),
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

                    }

                    Row(modifier = modifier.padding(vertical = 4.dp)) {
                      var label = ""
                      var textColor = Color.Black
                      var color = Color.Black

                      when(task.intent){

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
fun BottomSheetContent(task: RegisterViewModel.TaskItem, onStatusUpdate: (TaskProgressState) -> Unit, onCancel: () -> Unit) {

  var name = ""
  var phone = ""
  var date = ""
  var address = ""
  if (task.patient?.name?.isNotEmpty() == true && task.patient?.name?.get(0)?.given?.isNotEmpty() == true){
    name = task.patient?.name?.get(0)?.given?.get(0)?.value.toString()
    phone = task.patient?.telecom?.get(0)?.value.toString()
    date = task.patient?.meta?.lastUpdated?.let { convertToDate(it) }.toString()
    address = getPatientAddress(task.patient)
  }
  val context = LocalContext.current
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 36.dp)
  ) {
    val selectedPriority = remember { mutableStateOf(TaskProgressState.NONE) } // Initial selected status

    Row(modifier = Modifier.padding(vertical = 4.dp, horizontal = 4.dp)) {

      androidx.compose.material.Icon(
        modifier = Modifier.padding(
          vertical = 4.dp
        ),
        painter = painterResource(id = R.drawable.ic_patient_male),
        contentDescription = FILTER,
        tint = LightColors.primary
      )
      Spacer(modifier = Modifier.width(8.dp))

      Text(
        modifier = Modifier
          .weight(1f)
          .padding(vertical = 4.dp),
        text = name,
        fontSize = 24.sp,
        color = LightColors.primary
      )
    }

    Spacer(modifier = Modifier.height(8.dp))

    Text(text = address,
      modifier = Modifier.padding(horizontal = 4.dp),
      color = colorResource(id = R.color.subTextGrey),
      fontSize = 14.sp
    )

    Spacer(modifier = Modifier.height(8.dp))

    Row(modifier = Modifier
      .fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically) {
      Text(text = "Screened on ",
        color = colorResource(id = R.color.subTextGreyBold),
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold)

      Spacer(modifier = Modifier.width(4.dp))

      Text(text = date,
        color = colorResource(id = R.color.subTextGrey),
        fontSize = 14.sp,
      )
    }

    Spacer(modifier = Modifier.height(8.dp))

    Row(modifier = Modifier
      .fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween) {

      Row {
        Text(
          text = "Phone ",
          color = colorResource(id = R.color.subTextGreyBold),
          fontSize = 14.sp,
          fontWeight = FontWeight.Bold,
        )

        Spacer(modifier = Modifier.width(4.dp))


        Text(text = phone,
          color = colorResource(id = R.color.subTextGrey),
          fontSize = 14.sp,)
      }


      Row(modifier = Modifier
        .align(Alignment.CenterVertically)
        .clickable {
          context.startActivity(Intent(Intent.ACTION_DIAL).apply { data = Uri.parse("tel:$phone") })
        },
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text(text = "Call", color = LightColors.primary)

        Spacer(modifier = Modifier.width(4.dp))

        Icon(
          modifier = Modifier
            .padding(
              horizontal = 4.dp
            )
            .width(24.dp)
            .height(24.dp)
            .align(Alignment.CenterVertically),
          painter = painterResource(id = R.drawable.ic_call),
          contentDescription = FILTER,
          tint = LightColors.primary
        )
      }
    }

    Spacer(modifier = Modifier.height(8.dp))

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


    if (task.task.status != TaskStatus.COMPLETED){
      Spacer(modifier = Modifier
        .fillMaxWidth()
        .height(1.dp)
        .background(Color.LightGray))

      Spacer(modifier = Modifier.height(16.dp))
      Text(text = "CHANGE STATUS", color = colorResource(id = R.color.subTextGrey), fontSize = 14.sp)
      Spacer(modifier = Modifier.height(16.dp))
    }


    //val taskImportance = remember { mutableStateOf(TaskPriority.NULL) }

    val listOfOutput = mutableListOf<Task.TaskOutputComponent>()
    val op = TaskOutputComponent()
    val con = CodeableConcept()
    val codee = Coding()
    codee.code = "priority"
    con.coding = listOf()
    op.type = con
    listOfOutput.add(op)
    //task.task.output = listOfOutput

    var options : List<Pair<TaskProgressState, TaskProgressStatusDisplay>> = emptyList<Pair<TaskProgressState, TaskProgressStatusDisplay>>()
    when(task.task.status) {
      TaskStatus.REQUESTED -> {
        options = listOf(
          TaskProgressState.NOT_RESPONDED to TaskProgressStatusDisplay.NOT_RESPONDED,
          TaskProgressState.NOT_AGREED_FOR_FOLLOWUP to TaskProgressStatusDisplay.NOT_AGREED_FOR_FOLLOWUP,
          TaskProgressState.AGREED_FOLLOWUP_NOT_DONE to TaskProgressStatusDisplay.AGREED_FOLLOWUP_NOT_DONE
        )
      }

      TaskStatus.INPROGRESS -> {

        if (task.task.output.isNotEmpty()){
          when(task.task.output.get(0).value.valueToString()){
            TaskProgressState.NOT_AGREED_FOR_FOLLOWUP.text -> {
              //Clicked on task from Inprogress tab -> Not agreed for follow-up.
              options = listOf(
                TaskProgressState.AGREED_FOLLOWUP_NOT_DONE to TaskProgressStatusDisplay.AGREED_FOLLOWUP_NOT_DONE,
                TaskProgressState.REMOVE to TaskProgressStatusDisplay.REMOVE_CASE
              )
            }

            TaskProgressState.AGREED_FOLLOWUP_NOT_DONE.text -> {
              //Clicked on task from Inprogress tab -> Agreed, Follow up not done section.
              options = listOf(
                TaskProgressState.NOT_AGREED_FOR_FOLLOWUP to TaskProgressStatusDisplay.NOT_AGREED_FOR_FOLLOWUP,
                TaskProgressState.FOLLOWUP_DONE to TaskProgressStatusDisplay.FOLLOWUP_DONE,
              )
            }
          }
        }
      }
      TaskStatus.COMPLETED -> {
        options = listOf()
      }
      else -> {

      }
    }

    Column { // Arrange radio buttons and labels vertically
      options.forEach { (priority, label) ->
        Row( // Arrange RadioButton and label horizontally
          verticalAlignment = Alignment.CenterVertically
        ) {
          var selectedRadioColor = Color.Gray
          selectedRadioColor = when(priority){
            TaskProgressState.FOLLOWUP_DONE -> Color.Green
            TaskProgressState.NOT_AGREED_FOR_FOLLOWUP -> Color(0xFFFFC800)
            TaskProgressState.NOT_RESPONDED -> Color.Red
            TaskProgressState.REMOVE -> Color.Red
            TaskProgressState.AGREED_FOLLOWUP_NOT_DONE -> Color(0xFFFFC800)
            TaskProgressState.NONE -> Color.Red
            TaskProgressState.NOT_CONTACTED -> Color.Gray
            TaskProgressState.DEFAULT -> Color.Gray
          }


          RadioButton(
            selected = selectedPriority.value == priority,
            onClick = { selectedPriority.value = priority },
            colors = RadioButtonColors(
              selectedColor = selectedRadioColor,
              unselectedColor = selectedRadioColor,
              disabledSelectedColor = Color.Gray,
              disabledUnselectedColor = Color.Gray)
          )
          Text(
            text = label.text,
            modifier = Modifier
              .padding(horizontal = 8.dp)
              .clickable {
                selectedPriority.value = priority
              },
            color = colorResource(id = R.color.optionColor)
          )
        }
      }
    }

    if (task.task.status != TaskStatus.COMPLETED){

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
            backgroundColor = Color.White, // Transparent background
            contentColor = Color.White // Set text color to white
          ),
          onClick = {
            onCancel()
            selectedPriority.value = TaskProgressState.NONE
          }) {
          Text(text = stringResource(id = R.string.cancel), color = Color(0xFF5B5959))
        }

        Button(modifier = Modifier
          .fillMaxHeight()
          .weight(1f) // Equally divide width
          .padding(horizontal = 4.dp)
          .background(LightColors.primary),
          onClick = {
            onStatusUpdate(selectedPriority.value)
            selectedPriority.value = TaskProgressState.NONE
          }) {
          Text(text = stringResource(id = R.string.update_status), color = Color.White)
        }
      }
    }else{
      Button(modifier = Modifier
        .fillMaxWidth()
        //.weight(1f) // Equally divide width

        .padding(horizontal = 16.dp),
        colors = ButtonDefaults.buttonColors( // Override default colors
          backgroundColor = Color.White, // Transparent background
          contentColor = Color.White // Set text color to white
        ),
        onClick = { onCancel() }) {
        Text(text = stringResource(id = R.string.close), color = Color(0xFF5B5959))
      }
    }
  }
}

@Composable
fun SectionView(section: Section, isExpanded: Boolean, onSeeMoreCasesClicked: (String, TaskStatus, TaskProgressState) -> Unit, onSelectTask: (RegisterViewModel.TaskItem) -> Unit) {
  var expanded by remember { mutableStateOf(isExpanded) }
  Column(modifier = Modifier.fillMaxWidth()) {
    Row(modifier = Modifier
      .fillMaxWidth()
      .padding(top = 16.dp)
      .clickable { expanded = !expanded }) {
      Text(text = section.title, fontSize = 14.sp, modifier = Modifier.weight(1f),
        color = colorResource(
          id = R.color.subTextGrey
        ))
      Icon(
        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
        contentDescription = null
      )
    }

    Spacer(modifier = Modifier.height(8.dp))

    if (section.items.isNotEmpty()){
      if (expanded) {
        section.items.forEachIndexed { index, task ->
          //Show max 3 elements
          if (index <= 2){
            CardItemView(task = task){
              onSelectTask(it)
            }
          }
        }

        Spacer(modifier = Modifier.height(8.dp))

        val itemsLabel: String

        if (section.items.size > 3){
          itemsLabel = "See ${section.items.size - 3} more"
        }else{
          itemsLabel = ""
        }
        val context = LocalContext.current
        Row(modifier = Modifier
          .fillMaxWidth()
          .align(Alignment.CenterHorizontally)
          .padding(vertical = 8.dp),
          horizontalArrangement = Arrangement.Center) {

          Box {
            Text(
              text = itemsLabel, fontSize = 14.sp, modifier = Modifier
                .padding(vertical = 4.dp)
                .clickable {

                  var priority = TaskProgressState.NONE

                  when (section.title) {
                    SectionTitles.NOT_CONTACTED -> {
                      priority = TaskProgressState.NOT_CONTACTED
                    }

                    SectionTitles.NOT_RESPONDED -> {
                      priority = TaskProgressState.NOT_RESPONDED

                    }

                    SectionTitles.AGREED_FOLLOWUP_NOT_DONE -> {
                      priority = TaskProgressState.AGREED_FOLLOWUP_NOT_DONE

                    }

                    SectionTitles.NOT_AGREED_FOR_FOLLOWUP -> {
                      priority = TaskProgressState.NOT_AGREED_FOR_FOLLOWUP

                    }
                  }

                  onSeeMoreCasesClicked(
                    section.title,
                    section.items[0].task.status,
                    priority
                  )
                },
              color = LightColors.primary
            )
          }
        }

        Box(modifier = Modifier
          .padding(vertical = 8.dp)
          .background(color = Color.LightGray)
          .height(1.dp)
          .fillMaxWidth(),)
      }
    }else{
      var emptyString = ""
      var icon = painterResource(id = R.drawable.ic_patient_male)
      when(section.title){

        stringResource(id = R.string.not_contacted) -> {
          emptyString = stringResource(id = R.string.not_contacted_empty_label)
          icon = painterResource(id = R.drawable.ic_not_contacted_empty)
        }

        stringResource(id = R.string.not_responded) -> {
          emptyString = stringResource(id = R.string.not_responded_empty_label)
          icon = painterResource(id = R.drawable.ic_not_responded_empty)
        }

        stringResource(id = R.string.pending_not_agreed) -> {
          emptyString = stringResource(id = R.string.not_agrred_empty_label)
          icon = painterResource(id = R.drawable.ic_not_agreed_empty)

        }

        stringResource(id = R.string.pending_agreed_not_done) -> {
          emptyString = stringResource(id = R.string.agrred_not_done_empty_label)
          icon = painterResource(id = R.drawable.ic_followup_not_done_empty)
        }
      }

      EmptyStateSection(false,
        textLabel = emptyString,
        icon = icon, LightColors.primary)
    }


  }
}


@Composable
fun CardItemView(task: RegisterViewModel.TaskItem, onSelectTask : (RegisterViewModel.TaskItem) -> Unit) {
  Box(
    modifier = Modifier
      .fillMaxWidth()
      .padding(vertical = 8.dp)
      .background(Color.White, shape = RoundedCornerShape(8.dp))
      .clickable {
        onSelectTask(task)
        //viewModel.updateTask(task, Task.TaskStatus.INPROGRESS, Task.TaskPriority.ROUTINE)
      }
  ) {
    Card(
      modifier = Modifier
        .fillMaxWidth()
        .background(Color.White, shape = RoundedCornerShape(8.dp)),
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
                style = TextStyle(
                  fontWeight = FontWeight(500)
                ),
                color = LightColors.primary
              )
              Spacer(modifier = Modifier.height(4.dp))
              Text(
                text = "Phone ${phone}",
                color = colorResource(id = R.color.subTextGrey),
                fontSize = 14.sp,
                style = TextStyle(
                  fontWeight = FontWeight(500)
                ),
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
                  style = TextStyle(
                    fontWeight = FontWeight(500)
                  ),
                  modifier = Modifier
                    .background(color, shape = MaterialTheme.shapes.small)
                    .padding(horizontal = 4.dp, vertical = 4.dp)
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