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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.ModalBottomSheetLayout
import androidx.compose.material.ModalBottomSheetValue
import androidx.compose.material.rememberModalBottomSheetState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import kotlinx.coroutines.launch
import org.hl7.fhir.r4.model.Task.TaskStatus
import org.smartregister.fhircore.engine.ui.theme.SearchHeaderColor
import org.smartregister.fhircore.quest.R
import org.smartregister.fhircore.quest.theme.Colors.CRAYOLA
import org.smartregister.fhircore.quest.theme.bodyNormal
import org.smartregister.fhircore.quest.ui.main.AppMainViewModel
import org.smartregister.fhircore.quest.ui.register.patients.RegisterEvent
import org.smartregister.fhircore.quest.ui.register.patients.RegisterUiState
import org.smartregister.fhircore.quest.ui.register.patients.RegisterViewModel
import org.smartregister.fhircore.quest.ui.register.patients.TOP_REGISTER_SCREEN_TEST_TAG
import org.smartregister.fhircore.quest.ui.register.tasks.BottomSheetContent
import org.smartregister.fhircore.quest.util.TaskProgressState


@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterialApi::class)
@Composable
fun SearchTasksScreen(
    modifier: Modifier = Modifier,
    viewModel: RegisterViewModel,
    appMainViewModel: AppMainViewModel,
    onEvent: (RegisterEvent) -> Unit,
    registerUiState: RegisterUiState,
    searchText: MutableState<String>,
    navController: NavController,
) {
    val lazyListState: LazyListState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val bottomSheetState = rememberModalBottomSheetState(initialValue = ModalBottomSheetValue.Hidden, skipHalfExpanded = true)
    var selectedTask by remember { mutableStateOf<RegisterViewModel.TaskItem?>(null) }
    val searchResultTasks by viewModel.searchedTasksStateFlow.collectAsState()


    ModalBottomSheetLayout(
        sheetState = bottomSheetState,
        sheetContent = {
            selectedTask?.let { task ->
                BottomSheetContent(viewModel = viewModel, task = task,
                    onStatusUpdate = { priority ->
                        var status: TaskStatus = task.task.status
                        var taskPriority = priority
                        when (priority) {
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

                            else -> {}
                        }

                        viewModel.updateTask(task.task, status, taskPriority)
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
            modifier = modifier.background(Color.White).imePadding(),
            topBar = {
                Column(
                    modifier = modifier
                      .background(Color.White)
                      .fillMaxWidth()
                ) {

                    // Top section has toolbar and a results counts view
                    val filterActions =
                        registerUiState.registerConfiguration?.registerFilter?.dataFilterActions
                    SearchBarSection(
                        modifier = modifier.testTag(TOP_REGISTER_SCREEN_TEST_TAG),
                        onSearchTextChanged = { searchText ->
                            if (searchText.isEmpty()) {
                                viewModel.clearSearch()
                            } else {
                                viewModel.searchTasks(searchText)
                            }
                        },
                    ) { event ->
                        navController.popBackStack()
                    }
                }
            },

            ) { innerPadding ->

            Box(modifier = modifier.padding(innerPadding).imePadding()) {
                Box(modifier = modifier.background(SearchHeaderColor).imePadding())
                {
                    Column(
                        modifier = modifier
                          .fillMaxHeight()
                          .background(SearchHeaderColor)
                          .fillMaxWidth().imePadding()
                    ) {

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

                            if (searchResultTasks.isEmpty()) {
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
                            } else {
                                LazyColumn(modifier = Modifier.padding(bottom = 60.dp)) {
                                    items(searchResultTasks) { task ->
                                        Box(
                                            modifier = modifier
                                              .fillMaxWidth()
                                              .padding(horizontal = 16.dp)
                                              .padding(bottom = 2.dp)
                                        ) {
                                            org.smartregister.fhircore.quest.ui.register.tasks.CardItemView(
                                                viewModel,
                                                task,
                                                onSelectTask = {
                                                    selectedTask = task
                                                    coroutineScope.launch { bottomSheetState.show() }
                                                })

                                            var statusLabel = ""
                                            var statusIcon: Painter = painterResource(id = R.drawable.ic_rec_new)
                                            val statusTextColor = CRAYOLA

                                            when (task.task.status) {

                                                TaskStatus.INPROGRESS -> {
                                                    statusLabel = stringResource(id = R.string.status_pending)
                                                    statusIcon = painterResource(id = R.drawable.ic_rec_pending)
                                                }

                                                TaskStatus.REQUESTED -> {
                                                    statusLabel = stringResource(id = R.string.status_new)
                                                    statusIcon = painterResource(id = R.drawable.ic_rec_new)
                                                }

                                                TaskStatus.COMPLETED -> {
                                                    statusLabel = stringResource(id = R.string.status_completed)
                                                    statusIcon = painterResource(id = R.drawable.ic_rec_completed)
                                                }

                                                else -> {
                                                }
                                            }

                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.End,
                                                modifier = modifier
                                                  .fillMaxWidth()
                                                  .padding(top = 28.dp, end = 28.dp)
                                            ) {
                                                Image(painter = statusIcon, contentDescription = statusLabel)
                                                Spacer(modifier = Modifier.padding(2.dp))
                                                Text(
                                                    text = statusLabel,
                                                    color = statusTextColor,
                                                    style = bodyNormal(12.sp)
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
}