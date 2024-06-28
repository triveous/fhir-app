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
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.ModalBottomSheetLayout
import androidx.compose.material.ModalBottomSheetValue
import androidx.compose.material.TextField
import androidx.compose.material.TextFieldDefaults
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.rememberModalBottomSheetState
import androidx.compose.material3.RadioButton
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.content.contentValuesOf
import androidx.core.os.bundleOf
import org.hl7.fhir.r4.model.Task
import org.hl7.fhir.r4.model.Task.TaskPriority
import org.hl7.fhir.r4.model.Task.TaskStatus
import org.smartregister.fhircore.quest.R
import org.smartregister.fhircore.quest.extensions.parseDate
import org.smartregister.fhircore.quest.navigation.NavigationArg
import org.smartregister.fhircore.quest.util.OpensrpDateUtils.convertToDate


const val TASK_NEW_TAB = "NEW"
const val TASK_PENDING_TAB = "PENDING"
const val TASK_COMPLETED_TAB = "COMPLETED"
const val TASK_NEW_PATIENTS = 0
const val TASK_PENDING_PATIENTS = 1
const val TASK_COMPLETED_PATIENTS = 2

data class Section(val title: String, val items: List<RegisterViewModel.TaskItem>)

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
  val bottomSheetState = rememberModalBottomSheetState(initialValue = ModalBottomSheetValue.Hidden)
  var selectedTask by remember { mutableStateOf<RegisterViewModel.TaskItem?>(null) }

  ModalBottomSheetLayout(
    sheetState = bottomSheetState,
    sheetContent = {
      selectedTask?.let { task ->
        BottomSheetContent(task = task, onStatusUpdate = { priority ->
          var status : TaskStatus = TaskStatus.NULL

          when(priority){
            TaskPriority.ROUTINE -> {
              status = TaskStatus.COMPLETED
            }
            TaskPriority.URGENT -> {
              status = TaskStatus.INPROGRESS
            }
            TaskPriority.STAT -> {
              status = TaskStatus.INPROGRESS
            }

            TaskPriority.NULL -> {

            }

            TaskPriority.ASAP -> {
              //Status remain same only moves Not contacted to no responded section
              status = task.task.status
            }
          }

          viewModel.updateTask(task.task, status, priority)
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
            title = stringResource(id = org.smartregister.fhircore.engine.R.string.appname),
            searchText = searchText.value,
            filteredRecordsCount = registerUiState.filteredRecordsCount,
            searchPlaceholder = registerUiState.registerConfiguration?.searchBar?.display,
            onSync = appMainViewModel::onEvent,
            onSearchTextChanged = { searchText ->
              onEvent(RegisterEvent.SearchRegister(searchText = searchText))
            },
            isFilterIconEnabled = filterActions?.isNotEmpty() ?: false,
          ) { event ->


          }

          TextField(
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
          )

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

          val tabTitles = listOf(TASK_NEW_TAB, TASK_PENDING_TAB, TASK_COMPLETED_TAB)
          val pagerState = rememberPagerState(pageCount = { 3 }, initialPage = 0)

          TabRow(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            backgroundColor = SearchHeaderColor,
            selectedTabIndex = pagerState.currentPage,
            contentColor = SearchHeaderColor,
          ) {
            tabTitles.forEachIndexed { index, title ->
              Tab(
                text = {
                  Text(
                    title,
                    color = if (pagerState.currentPage == index) Color.White else Color.Black,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                  )
                },
                selected = pagerState.currentPage == index,
                selectedContentColor = SearchHeaderColor,
                modifier = Modifier
                  .background(
                    if (pagerState.currentPage == index) LightColors.primary else SearchHeaderColor
                  )
                  /*.border(
                    width = 0.5.dp,
                    color = if (pagerState.currentPage == index) LightColors.primary else Color.DarkGray
                  )*/
                  .padding(horizontal = 4.dp),
                onClick = {
                  CoroutineScope(Dispatchers.Main).launch {
                    pagerState.scrollToPage(index)
                  }
                }
              )
            }
          }
          HorizontalPager(state = pagerState) {
            // Content for each tab (your fragment content goes here)
            tabTitles.forEach { title ->
              when (pagerState.currentPage) {
                TASK_NEW_PATIENTS -> {
                  Box(    modifier = modifier
                    .padding(top = 64.dp, start = 16.dp, end = 16.dp)
                    .fillMaxHeight()
                    .fillMaxWidth()
                    .background(SearchHeaderColor)) {

                      if (newTasks.isNotEmpty()){
                        val notContactedTasks = viewModel.getNotContactedNewTasks(newTasks, TaskStatus.REQUESTED)
                        val notRespondedTasks = viewModel.getNotRespondedNewTasks(newTasks,  TaskStatus.REQUESTED)

                        val sections = listOf(
                          Section(
                            title = stringResource(id = R.string.not_contacted),
                            items = notContactedTasks
                          ),
                          Section(
                            title = stringResource(id = R.string.not_responded),
                            items = notRespondedTasks
                          )
                        )

                        LazyColumn {
                          sections.forEach { section ->
                            item {
                              Box {
                                SectionView(
                                  section = section,
                                  isExpanded = true,
                                  onSeeMoreCasesClicked = {
                                    navController.navigate(R.id.viewAllTasksFragment, args = bundleOf(
                                      NavigationArg.SCREEN_TITLE to it
                                    ))
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

                  /*ShowAllPatients(modifier, newTasks, viewModel){
                    selectedTask = it
                    coroutineScope.launch { bottomSheetState.show() }
                  }*/
                }

                TASK_PENDING_PATIENTS -> {

                  Box(    modifier = modifier
                    .padding(top = 64.dp, start = 16.dp, end = 16.dp)
                    .fillMaxHeight()
                    .fillMaxWidth()
                    .background(SearchHeaderColor)) {

                    if (newTasks.isNotEmpty()){
                      val notContactedPendingTasks = viewModel.getNotContactedNewTasks(pendingTasks,  TaskStatus.INPROGRESS)
                      val notRespondedPendingTasks = viewModel.getNotRespondedNewTasks(pendingTasks,  TaskStatus.INPROGRESS)

                      val sections = listOf(
                        Section(
                          title = "Not Contacted",
                          items = notContactedPendingTasks
                        ),
                        Section(
                          title = "Not Responded",
                          items = notRespondedPendingTasks
                        )
                      )

                      LazyColumn {
                        sections.forEach { section ->
                          item {
                            Box() {
                              SectionView(
                                section = section,
                                isExpanded = true,
                                onSeeMoreCasesClicked = {
                                  navController.navigate(R.id.viewAllTasksFragment, args = bundleOf(
                                    NavigationArg.SCREEN_TITLE to it
                                  ))
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


                  /*Box(
                    modifier = modifier
                      .padding(top = 64.dp, start = 16.dp, end = 16.dp)
                      .fillMaxHeight()
                      .fillMaxWidth()
                      .background(SearchHeaderColor)
                  ) {

                    if (pendingTasks.isEmpty()) {
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
                          Text(text = stringResource(id = org.smartregister.fhircore.quest.R.string.no_draft_patients))
                        }
                      }
                    } else {
                      Box(
                        modifier = modifier
                          .fillMaxHeight()
                          .background(SearchHeaderColor)
                          .fillMaxWidth()
                      ) {
                        LazyColumn {
                          items(pendingTasks) { task ->
                            Box(
                              modifier = modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp)
                                .background(Color.White)
                                .clickable {
                                  selectedTask = task
                                  coroutineScope.launch { bottomSheetState.show() }
                                  *//*viewModel.updateTask(
                                    task,
                                    Task.TaskStatus.COMPLETED,
                                    Task.TaskPriority.URGENT
                                  )*//*
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
                                        painter = painterResource(id = org.smartregister.fhircore.quest.R.drawable.patient_icon),
                                        contentDescription = FILTER,
                                        tint = LightColors.primary
                                      )
                                      Text(
                                        modifier = Modifier
                                          .weight(1f)
                                          .padding(vertical = 4.dp, horizontal = 8.dp),
                                        text = task.description,
                                        style = MaterialTheme.typography.h6,
                                        color = LightColors.primary
                                      )
                                      Spacer(modifier = Modifier.height(16.dp))
                                      *//*Box(
                                        modifier = Modifier.clickable {
                                          *//**//*val json = response.encodeResourceToString()
                                          registerUiState.registerConfiguration?.noResults?.let { noResultConfig ->
                                            val bundle = Bundle()
                                            bundle.putString(QUESTIONNAIRE_RESPONSE_PREFILL, json)
                                            noResultConfig.actionButton?.actions?.handleClickEvent(
                                              navController,
                                              bundle = bundle
                                            )
                                          }*//**//*
                                        }
                                      ) {
                                        Icon(
                                          modifier = Modifier.padding(
                                            vertical = 4.dp,
                                            horizontal = 8.dp
                                          ),
                                          painter = painterResource(id = org.smartregister.fhircore.quest.R.drawable.edit_draft),
                                          contentDescription = FILTER,
                                        )
                                      }*//*
                                    }

                                    Row(
                                      modifier = modifier.padding(
                                        vertical = 8.dp,
                                        horizontal = 36.dp
                                      )
                                    ) {
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
                  }*/
                }

                TASK_COMPLETED_PATIENTS -> {
                  ShowUnSyncedPatients(modifier, completedTasks)
                }
              }
            }
          }
        }


        /*if (registerUiState.isFirstTimeSync) {
          val isSyncUpload = registerUiState.isSyncUpload.collectAsState(initial = false).value
          LoaderDialog(
            modifier = modifier.testTag(FIRST_TIME_SYNC_DIALOG),
            percentageProgressFlow = registerUiState.progressPercentage,
            dialogMessage =
              stringResource(
                id = if (isSyncUpload) R.string.syncing_up else R.string.syncing_down,
              ),
            showPercentageProgress = true,
          )
        }
        if (
          registerUiState.totalRecordsCount > 0 &&
            registerUiState.registerConfiguration?.registerCard != null
        ) {
          RegisterCardList(
            modifier = modifier.testTag(REGISTER_CARD_TEST_TAG),
            registerCardConfig = registerUiState.registerConfiguration.registerCard,
            pagingItems = pagingItems,
            navController = navController,
            lazyListState = lazyListState,
            onEvent = onEvent,
            registerUiState = registerUiState,
            currentPage = currentPage,
            showPagination = searchText.value.isEmpty(),
          )
        } else {
  
          registerUiState.registerConfiguration?.noResults?.let { noResultConfig ->
  
            Box (modifier = modifier
              .background(SearchHeaderColor))
            {
              val tasks by viewModel.patientsStateFlow.collectAsState()
  
  
              LazyColumn {
                items(tasks) { task ->
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
                              text = task.toString(),
                              style = MaterialTheme.typography.h6,
                              color = LightColors.primary
                            )
                            Spacer(modifier = Modifier.height(16.dp))
  
                            Text(text = "Sync: Un-Synced")
  
                          }
  
                          Row(modifier = modifier.padding(vertical = 4.dp)) {
                            Text(text = "Gender: ${task.toString()}")
                          }
                        }
                      }
                    }
                  }
                }
              }
  
  
              *//*val tabTitles = listOf(ALL_PATIENTS_TAB, DRAFT_PATIENTS_TAB, UNSYNCED_PATIENTS_TAB)
             val pagerState = rememberPagerState(pageCount = { 3 }, initialPage = 0)
 
             val patients by viewModel.patientsStateFlow.collectAsState()
             val savedRes by viewModel.savedDraftResponse.collectAsState()
             val unSynced by viewModel.unSyncedStateFlow.collectAsState()
             var deleteDraftId by remember { mutableStateOf("") }
             var showDeleteDialog by remember { mutableStateOf(false) }*//*
 
 
 *//*            TabRow(
               modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
               backgroundColor = SearchHeaderColor,
               selectedTabIndex = pagerState.currentPage,
               contentColor = SearchHeaderColor,
             ) {
               tabTitles.forEachIndexed { index, title ->
                 Tab(
                   text = { Text(title, color = Color.Gray, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                   selected = pagerState.currentPage == index,
                   selectedContentColor = SearchHeaderColor,
                   modifier = Modifier
                     .clip(RoundedCornerShape(24.dp))
                     .background(
                       if (pagerState.currentPage == index) Color.White else SearchHeaderColor
                     ),
                   onClick = {
                     CoroutineScope(Dispatchers.IO).launch {
                       pagerState.scrollToPage(index)
                     }
                   }
                 )
               }
             }
             HorizontalPager(state = pagerState) {
               // Content for each tab (your fragment content goes here)
               tabTitles.forEach { title ->
                 when (pagerState.currentPage) {
                     ALL_PATIENTS -> {
 
                       ShowAllPatients(modifier, patients, viewModel)
                     }
                     DRAFT_PATIENTS -> {
 
                       Box(
                         modifier = modifier
                           .padding(top = 64.dp, start = 16.dp, end = 16.dp)
                           .fillMaxHeight()
                           .fillMaxWidth()
                           .background(SearchHeaderColor)
                       ) {
 
                         if (savedRes.isEmpty()) {
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
                               Text(text = stringResource(id = org.smartregister.fhircore.quest.R.string.no_draft_patients))
                             }
                           }
                         } else {
                           Box(
                             modifier = modifier
                               .fillMaxHeight()
                               .background(SearchHeaderColor)
                               .fillMaxWidth()
                           ) {
                             LazyColumn {
                               items(savedRes) { response ->
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
                                             modifier = Modifier.padding(vertical = 4.dp, horizontal = 4.dp),
                                             painter = painterResource(id = org.smartregister.fhircore.quest.R.drawable.ic_draft),
                                             contentDescription = FILTER,
                                           )
                                           Text(
                                             modifier = Modifier
                                               .weight(1f)
                                               .padding(vertical = 4.dp, horizontal = 8.dp),
                                             text = response.item[0].item[0].answer[0].value.asStringValue(),
                                             style = MaterialTheme.typography.h6,
                                             color = Color.DarkGray
                                           )
                                           Spacer(modifier = Modifier.height(16.dp))
                                           Box(
                                             modifier = Modifier.clickable {
                                               val json = response.encodeResourceToString()
                                               registerUiState.registerConfiguration?.noResults?.let { noResultConfig ->
                                                 val bundle = Bundle()
                                                 bundle.putString(QUESTIONNAIRE_RESPONSE_PREFILL, json)
                                                 noResultConfig.actionButton?.actions?.handleClickEvent(
                                                   navController,
                                                   bundle = bundle
                                                 )
                                               }
                                             }
                                           ) {
                                             Icon(
                                               modifier = Modifier.padding(
                                                 vertical = 4.dp,
                                                 horizontal = 8.dp
                                               ),
                                               painter = painterResource(id = org.smartregister.fhircore.quest.R.drawable.edit_draft),
                                               contentDescription = FILTER,
                                             )
                                           }
                                           Box(modifier = modifier.clickable {
                                             deleteDraftId = response.id.extractLogicalIdUuid()
                                             showDeleteDialog = true
                                           }) {
                                             androidx.compose.material.Icon(
                                               modifier = Modifier.padding(
                                                 vertical = 4.dp,
                                                 horizontal = 8.dp
                                               ),
                                               painter = painterResource(id = org.smartregister.fhircore.quest.R.drawable.ic_delete_draft),
                                               contentDescription = FILTER,
                                             )
                                           }
                                         }
 
                                         Row(modifier = modifier.padding(vertical = 8.dp, horizontal = 36.dp)) {
                                           Text(text = "Created: ${convertToDate(response.meta.lastUpdated)}")
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
                     UNSYNCED_PATIENTS -> {
                       ShowUnSyncedPatients(modifier, unSynced)
                     }
                 }
               }
             }*//*
           }
         }
       }*/
      }
    }
  }
}

@Composable
private fun ShowUnSyncedPatients(
  modifier: Modifier,
  completedTasks: List<RegisterViewModel.TaskItem>
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
                        text = task.task.description,
                        style = MaterialTheme.typography.h6,
                        color = LightColors.primary
                      )
                      Spacer(modifier = Modifier.height(16.dp))

                      //Text(text = "Sync: Un-Synced")

                    }

                    Row(modifier = modifier.padding(vertical = 4.dp)) {
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
fun BottomSheetContent(task: RegisterViewModel.TaskItem, onStatusUpdate: (TaskPriority) -> Unit, onCancel: () -> Unit) {

  var name = ""
  var phone = ""
  var date = ""
  var address = ""
  if (task.patient?.name?.isNotEmpty() == true && task.patient?.name?.get(0)?.given?.isNotEmpty() == true){
    name = task.patient?.name?.get(0)?.given?.get(0)?.value.toString()
    phone = task.patient?.telecom?.get(0)?.value.toString()
    date = task.patient?.meta?.lastUpdated?.let { convertToDate(it) }.toString()
    address = task.patient?.address?.get(0)?.text.toString()
  }

  Column(
    modifier = Modifier
      .fillMaxWidth()
      .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 36.dp)
  ) {
    val selectedStatus = remember { mutableStateOf(TaskPriority.NULL) } // Initial selected status

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

      Row(modifier = Modifier.align(Alignment.CenterVertically),
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
    Spacer(modifier = Modifier
      .fillMaxWidth()
      .height(1.dp)
      .background(Color.LightGray))

    Spacer(modifier = Modifier.height(16.dp))

    Text(text = "CHANGE STATUS", color = colorResource(id = R.color.subTextGrey), fontSize = 14.sp)
    Spacer(modifier = Modifier.height(16.dp))

    //val taskImportance = remember { mutableStateOf(TaskPriority.NULL) }

    var options : List<Pair<TaskPriority, String>> = emptyList<Pair<Task.TaskPriority, String>>()
    when(task.task.status) {
      TaskStatus.REQUESTED -> {
        options = listOf(
          TaskPriority.ASAP to "Not responded",
          TaskPriority.URGENT to "Didn't agree for follow up",
          TaskPriority.STAT to "Agreed, Follow up not done",
          TaskPriority.ROUTINE to "Follow up done"
        )
      }

      TaskStatus.INPROGRESS -> {
        options = listOf(
          TaskPriority.ROUTINE to "Follow up done"
        )

      }
      TaskStatus.COMPLETED -> {
        options = listOf(
          TaskPriority.NULL to "Follow up done"
        )
      }

      else -> {

      }
    }

    Column { // Arrange radio buttons and labels vertically
      options.forEach { (importance, label) ->
        Row( // Arrange RadioButton and label horizontally
          verticalAlignment = Alignment.CenterVertically
        ) {
          RadioButton(
            selected = selectedStatus.value == importance,
            onClick = { selectedStatus.value = importance }
          )
          Text(
            text = label,
            modifier = Modifier
              .padding(horizontal = 8.dp)
              .clickable { selectedStatus.value = importance }, // Make text clickable
            color = colorResource(id = R.color.optionColor)
          )
        }
      }
    }

/*
    Row(modifier = Modifier,
      verticalAlignment = Alignment.CenterVertically) {

      RadioButton(
        selected = selectedStatus.value == TaskPriority.NULL, // Compare with data source
        onClick = { selectedStatus.value = TaskPriority.NULL } // Update data source on click
      )
      Text(text = "Not responded",
        modifier = Modifier.padding(horizontal = 8.dp),
        color = colorResource(id = R.color.optionColor)
      )
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
    }*/

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

@Composable
fun SectionView(section: Section, isExpanded: Boolean, onSeeMoreCasesClicked: (String) -> Unit, onSelectTask: (RegisterViewModel.TaskItem) -> Unit) {
  var expanded by remember { mutableStateOf(isExpanded) }
  Column(modifier = Modifier.fillMaxWidth()) {
    Row(modifier = Modifier
      .fillMaxWidth()
      .clickable { expanded = !expanded }) {
      Text(text = section.title, fontSize = 18.sp, modifier = Modifier.weight(1f),
        color = colorResource(
        id = R.color.subTextGrey
      ))
      Icon(
        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
        contentDescription = null
      )
    }

    Spacer(modifier = Modifier.height(8.dp))

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

      Row(modifier = Modifier
        .fillMaxWidth()
        .align(Alignment.CenterHorizontally)
        .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.Center) {

        Text(text = itemsLabel, fontSize = 18.sp, modifier = Modifier
            .clickable {
                onSeeMoreCasesClicked(section.title)
            },
          color = LightColors.primary
        )
      }

      Box(modifier = Modifier
        .padding(vertical = 8.dp)
        .background(color = Color.LightGray)
        .height(1.dp)
        .fillMaxWidth(),)
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