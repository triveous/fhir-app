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

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.MaterialTheme
import androidx.compose.material.ModalBottomSheetLayout
import androidx.compose.material.ModalBottomSheetValue
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.rememberModalBottomSheetState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonColors
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.google.android.fhir.datacapture.extensions.asStringValue
import kotlinx.coroutines.launch
import org.hl7.fhir.r4.model.CodeableConcept
import org.hl7.fhir.r4.model.Coding
import org.hl7.fhir.r4.model.Task
import org.hl7.fhir.r4.model.Task.TaskOutputComponent
import org.hl7.fhir.r4.model.Task.TaskStatus
import org.smartregister.fhircore.engine.configuration.navigation.NavigationMenuConfig
import org.smartregister.fhircore.engine.configuration.register.NoResultsConfig
import org.smartregister.fhircore.engine.domain.model.SnackBarMessageConfig
import org.smartregister.fhircore.engine.domain.model.ToolBarHomeNavigation
import org.smartregister.fhircore.engine.ui.theme.LightColors
import org.smartregister.fhircore.engine.ui.theme.SearchHeaderColor
import org.smartregister.fhircore.engine.util.annotation.PreviewWithBackgroundExcludeGenerated
import org.smartregister.fhircore.engine.util.extension.valueToString
import org.smartregister.fhircore.quest.R
import org.smartregister.fhircore.quest.theme.Colors
import org.smartregister.fhircore.quest.theme.Colors.BRANDEIS_BLUE
import org.smartregister.fhircore.quest.theme.Colors.Quartz
import org.smartregister.fhircore.quest.ui.main.AppMainViewModel
import org.smartregister.fhircore.quest.ui.main.components.FILTER
import org.smartregister.fhircore.quest.ui.main.components.TopScreenSection
import org.smartregister.fhircore.quest.ui.register.components.EmptyStateSection
import org.smartregister.fhircore.quest.ui.register.patients.FAB_BUTTON_REGISTER_TEST_TAG
import org.smartregister.fhircore.quest.ui.register.patients.GenericActivity
import org.smartregister.fhircore.quest.ui.register.patients.GenericActivityArg.ARG_FROM
import org.smartregister.fhircore.quest.ui.register.patients.GenericActivityArg.SCREEN_TITLE
import org.smartregister.fhircore.quest.ui.register.patients.GenericActivityArg.TASK_PRIORITY
import org.smartregister.fhircore.quest.ui.register.patients.GenericActivityArg.TASK_STATUS
import org.smartregister.fhircore.quest.ui.register.patients.NoRegisterDataView
import org.smartregister.fhircore.quest.ui.register.patients.RegisterEvent
import org.smartregister.fhircore.quest.ui.register.patients.RegisterUiState
import org.smartregister.fhircore.quest.ui.register.patients.RegisterViewModel
import org.smartregister.fhircore.quest.ui.register.patients.TOP_REGISTER_SCREEN_TEST_TAG
import org.smartregister.fhircore.quest.ui.register.patients.getSyncImageList
import org.smartregister.fhircore.quest.ui.shared.components.ExtendedFab
import org.smartregister.fhircore.quest.util.OpensrpDateUtils.convertToDate
import org.smartregister.fhircore.quest.util.OpensrpDateUtils.convertToDateStringToDate
import org.smartregister.fhircore.quest.util.SectionTitles
import org.smartregister.fhircore.quest.util.TaskProgressState
import org.smartregister.fhircore.quest.util.TaskProgressStatusDisplay
import org.smartregister.fhircore.quest.util.dailog.ForegroundSyncDialog
import kotlin.collections.find

enum class TabType(val label: String) {
    TASK_NEW_TAB("New"), TASK_PENDING_TAB("Pending"), TASK_COMPLETED_TAB("Completed")
}

data class Section(val title: String, val items: List<RegisterViewModel.TaskItem>, val sectionTitle: String,)

@Composable
fun FilterRow2(selectedFilter: TabType, onFilterSelected: (TabType) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceAround
    ) {
        TabType.entries.forEachIndexed { index, filter ->
            Box(modifier = Modifier
                .clickable {
                    onFilterSelected(filter)
                }
                .background(
                    if (filter == selectedFilter) BRANDEIS_BLUE else SearchHeaderColor,
                    shape = RoundedCornerShape(8.dp)
                )
                .border(
                    width = 1.dp,
                    color = (if (filter == selectedFilter) BRANDEIS_BLUE else Colors.CRAYOLA_LIGHT),
                    shape = RoundedCornerShape(8.dp)
                )
                .padding(4.dp)
                .align(Alignment.CenterVertically)
                .weight(1f)) {
                Text(
                    text = getTabName(filter.label),
                    style = TextStyle(
                        fontWeight = FontWeight(600), fontSize = 16.sp
                    ),
                    modifier = Modifier
                        .padding(horizontal = 4.dp, vertical = 4.dp)
                        .align(Alignment.Center),
                    color = if (filter == selectedFilter) Color.White else Quartz
                )
            }
            if (index < FilterType.entries.size - 1) {
                Spacer(modifier = Modifier.width(8.dp)) // Horizontal margin
            }
        }
    }
}

@Composable
fun getTabName(labelName: String): String {
    return if (labelName.equals(TabType.TASK_COMPLETED_TAB.label, true)) {
        stringResource(id = R.string.status_completed)
    } else if (labelName.equals(TabType.TASK_PENDING_TAB.label, true)) {
        stringResource(id = R.string.status_pending)
    } else {
        stringResource(id = R.string.status_new)
    }
}

@OptIn(ExperimentalMaterialApi::class)
@Composable
fun PendingTasksScreen(
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
    val bottomSheetState = rememberModalBottomSheetState(
        initialValue = ModalBottomSheetValue.Hidden, skipHalfExpanded = true
    )
    var selectedTask by remember { mutableStateOf<RegisterViewModel.TaskItem?>(null) }

    val unSyncedImagesCount by viewModel.allUnSyncedImages.collectAsState()
    val isFetching by viewModel.isFetchingTasks.collectAsState()
    var totalImageLeftCountData = getSyncImageList(unSyncedImagesCount)
    var totalImageLeft by remember { mutableStateOf(totalImageLeftCountData) }
    val statusUpdateSuccessfully = stringResource(id = R.string.status_updated_successfully)
    val selectStatusToUpdate = stringResource(id = R.string.select_status_to_update)

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        viewModel.setPermissionGranted(isGranted)
        if (isGranted) {
            viewModel.appMainEvent?.let { mainEvent -> appMainViewModel.onEvent(mainEvent, true) }
        }
    }

    ModalBottomSheetLayout(sheetGesturesEnabled = false,
        sheetState = bottomSheetState,
        sheetContent = {
            selectedTask?.let { task ->
                BottomSheetContent(viewModel,task = task, onStatusUpdate = { priority ->
                    var status: TaskStatus = task.task.status
                    var taskPriority = priority
                    when (priority) {
                        TaskProgressState.FOLLOWUP_DONE -> {
                            status = TaskStatus.COMPLETED
                        }

                        TaskProgressState.NOT_AGREED_FOR_FOLLOWUP -> {
                            status = TaskStatus.COMPLETED
                        }

                        TaskProgressState.AGREED_FOLLOWUP_NOT_DONE -> {
                            status = TaskStatus.INPROGRESS
                        }

                        TaskProgressState.FOLLOWUP_NOT_DONE -> {
                            status = TaskStatus.COMPLETED
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
                            if(status == TaskStatus.REQUESTED){
                                taskPriority = TaskProgressState.NOT_RESPONDED
                                status = TaskStatus.INPROGRESS
                            }else{
                                taskPriority = TaskProgressState.NOT_RESPONDED
                                status = TaskStatus.COMPLETED
                            }
                        }

                        else -> {}
                    }

                    if (taskPriority != TaskProgressState.NONE) {
                        viewModel.updateTask(task.task, status, taskPriority)

                        coroutineScope.launch {
                            viewModel.emitSnackBarState(
                                SnackBarMessageConfig(
                                    statusUpdateSuccessfully
                                )
                            )
                            bottomSheetState.hide()
                        }
                    } else {
                        coroutineScope.launch {
                            viewModel.emitSnackBarState(SnackBarMessageConfig(selectStatusToUpdate))
                        }
                    }
                }, onCancel = {
                    coroutineScope.launch {
                        bottomSheetState.hide()
                    }
                })
            }
        }) {
        Scaffold(
            modifier = modifier
                .background(Color.White)
                .padding(bottom = 16.dp),
            topBar = {
                Column(modifier = modifier.background(SearchHeaderColor)) {
                    TopScreenSection(
                        modifier = modifier.testTag(TOP_REGISTER_SCREEN_TEST_TAG),
                        title = stringResource(id = org.smartregister.fhircore.engine.R.string.appname),
                        onSync = {
                            viewModel.appMainEvent = it
                            viewModel.setShowDialog(true)
                        },
                        toolBarHomeNavigation = ToolBarHomeNavigation.SYNC,
                    ) { event ->
                    }
                    Box(
                        modifier
                            .fillMaxWidth()
                            .padding(all = 16.dp)
                            .clickable {
                                navController.navigate(R.id.searchTasksFragment)
                            }) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(64.dp)
                                .padding(vertical = 4.dp)
                                .border(
                                    width = 1.dp, color = Colors.CRAYOLA_LIGHT
                                )
                                .background(Color.White, shape = RoundedCornerShape(8.dp)),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Search,
                                contentDescription = "Search Icon",
                                tint = Color.Gray,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = stringResource(id = R.string.enter_name_or_phone),
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
            Box(modifier = modifier.background(SearchHeaderColor)) {

                if(isFetching){
                    Column(modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center) {
                        CircularProgressIndicator(
                            modifier = modifier
                                .size(48.dp),
                            strokeWidth = 4.dp,
                            color = LightColors.primary,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(org.smartregister.fhircore.engine.R.string.loading))
                    }
                }else{
                    Box(modifier = modifier.padding(innerPadding)) {

                        val newTasks by viewModel.newTasksStateFlow.collectAsState()
                        val pendingTasks by viewModel.pendingTasksStateFlow.collectAsState()
                        val completedTasks by viewModel.completedTasksStateFlow.collectAsState()
                        val selectedTabType = remember {
                            mutableStateOf(TabType.TASK_NEW_TAB)
                        }
                        val context = LocalContext.current

                        viewModel.imageCount = unSyncedImagesCount
                        totalImageLeftCountData = getSyncImageList(viewModel.imageCount)
                        totalImageLeft = totalImageLeftCountData

                        Box(
                            modifier = modifier.background(SearchHeaderColor)
                        ) {

                            Box(modifier = Modifier.padding(bottom = 16.dp)) {
                                FilterRow2(selectedTabType.value) {
                                    selectedTabType.value = it
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))

                            when (selectedTabType.value) {

                                TabType.TASK_NEW_TAB -> {

                                    Spacer(modifier = Modifier.height(8.dp))

                                    Box(
                                        modifier = modifier
                                            .padding(
                                                top = 72.dp, start = 16.dp, end = 16.dp
                                            )
                                            .fillMaxHeight()
                                            .fillMaxWidth()
                                            .background(SearchHeaderColor)
                                    ) {

                                        val notContactedTasks = viewModel.getNotContactedNewTasks(
                                            newTasks, TaskStatus.REQUESTED
                                        )
                                        /*val notRespondedTasks = viewModel.getNotRespondedNewTasks(
                                            newTasks, TaskStatus.REQUESTED
                                        )*/

                                        val sectionsList: MutableList<Section> = mutableListOf()
                                        val section = Section(
                                            title = stringResource(id = R.string.not_contacted),
                                            items = notContactedTasks,
                                            sectionTitle = SectionTitles.NOT_CONTACTED
                                        )
                                        sectionsList.add(section)

                                        /*val section2 = Section(
                                            title = stringResource(id = R.string.not_responded),
                                            items = notRespondedTasks,
                                            sectionTitle = SectionTitles.NOT_RESPONDED
                                        )*/
                                        //sectionsList.add(section2)

                                        Spacer(modifier = Modifier.height(8.dp))

                                        LazyColumn {
                                            sectionsList.forEach { section ->
                                                item {
                                                    Box {
                                                        SectionView(viewModel, section = section,
                                                            isExpanded = true,
                                                            onSeeMoreCasesClicked = { sectionTitle, status, priority ->
                                                                val intent = Intent(
                                                                    context, GenericActivity::class.java
                                                                ).apply {
                                                                    putExtra(ARG_FROM, "tasks")
                                                                    putExtra(
                                                                        SCREEN_TITLE,
                                                                        if (sectionTitle.contains(
                                                                                SectionTitles.NOT_CONTACTED,
                                                                                true
                                                                            )
                                                                        ) {
                                                                            TaskProgressStatusDisplay.NOT_CONTACTED.text
                                                                        } else {
                                                                            TaskProgressStatusDisplay.NOT_RESPONDED.text
                                                                        }
                                                                    )
                                                                    putExtra(TASK_STATUS, status.name)
                                                                    putExtra(
                                                                        TASK_PRIORITY, priority.name
                                                                    )
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

                                TabType.TASK_PENDING_TAB -> {
                                    Spacer(modifier = Modifier.height(8.dp))

                                    Box(
                                        modifier = modifier
                                            .padding(
                                                top = 64.dp, start = 16.dp, end = 16.dp
                                            )
                                            .fillMaxHeight()
                                            .fillMaxWidth()
                                            .background(SearchHeaderColor)
                                    ) {

                                        val pendingAgreedButNotDoneTasks =
                                            viewModel.getPendingAgreedButNotDoneTasks(
                                                pendingTasks, TaskStatus.INPROGRESS
                                            )
                                        val pendingNotAgreedTasks = viewModel.getPendingNotAgreedTasks(
                                            pendingTasks, TaskStatus.INPROGRESS
                                        )
                                        val notRespondedTasks = viewModel.getNotRespondedNewTasks(
                                            pendingTasks, TaskStatus.INPROGRESS
                                        )

                                        val sectionsList: MutableList<Section> = mutableListOf()
                                        val section = Section(
                                            title = stringResource(id = R.string.not_responded),
                                            items = notRespondedTasks,
                                            sectionTitle = SectionTitles.NOT_RESPONDED
                                        )
                                        sectionsList.add(section)

                                        val section2 = Section(
                                            title = stringResource(id = R.string.agreed_for_followup),
                                            items = pendingAgreedButNotDoneTasks,
                                            sectionTitle = SectionTitles.AGREED_FOLLOWUP_NOT_DONE
                                        )
                                        sectionsList.add(section2)

                                        LazyColumn {
                                            sectionsList.forEach { section ->
                                                item {
                                                    Box {
                                                        SectionView(viewModel, section = section,
                                                            isExpanded = true,
                                                            onSeeMoreCasesClicked = { sectionTitle, status, priority ->
                                                                val intent = Intent(
                                                                    context, GenericActivity::class.java
                                                                ).apply {
                                                                    putExtra(ARG_FROM, "tasks")
                                                                    putExtra(
                                                                        SCREEN_TITLE,
                                                                        if (sectionTitle.contains(
                                                                                SectionTitles.AGREED_FOLLOWUP_NOT_DONE,
                                                                                true
                                                                            )
                                                                        ) {
                                                                            TaskProgressStatusDisplay.AGREED_FOLLOWUP_NOT_DONE.text
                                                                        } else {
                                                                            TaskProgressStatusDisplay.NOT_AGREED_FOR_FOLLOWUP.text
                                                                        }
                                                                    )
                                                                    putExtra(TASK_STATUS, status.name)
                                                                    putExtra(
                                                                        TASK_PRIORITY, priority.name
                                                                    )
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
                                    ShowUnSyncedPatients(viewModel, modifier, completedTasks) {
                                        selectedTask = it
                                        coroutineScope.launch { bottomSheetState.show() }
                                    }
                                }
                            }
                        }
                    }
                }

                ForegroundSyncDialog(showDialog = viewModel.showDialog.value,
                    title = stringResource(id = org.smartregister.fhircore.quest.R.string.sync_status),
                    content = totalImageLeft,
                    viewModel.imageCount,
                    confirmButtonText = stringResource(id = org.smartregister.fhircore.quest.R.string.sync_now),
                    dismissButtonText = stringResource(id = org.smartregister.fhircore.quest.R.string.okay),
                    onDismiss = {
                        viewModel.setShowDialog(false)
                    },
                    onConfirm = {
                        viewModel.setShowDialog(false)
                        if (!viewModel.permissionGranted.value) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                viewModel.setPermissionGranted(true)
                                viewModel.appMainEvent?.let { mainEvent ->
                                    appMainViewModel.onEvent(
                                        mainEvent, true
                                    )
                                }
                            }
                        } else {
                            viewModel.appMainEvent?.let { mainEvent ->
                                appMainViewModel.onEvent(
                                    mainEvent, true
                                )
                            }
                        }
                    })
            }
        }
    }
}

@Composable
private fun ShowUnSyncedPatients(
    viewModel: RegisterViewModel,
    modifier: Modifier,
    completedTasks: List<RegisterViewModel.TaskItem>,
    onSelectTask: (RegisterViewModel.TaskItem) -> Unit
) {
    Box(
        modifier = modifier
            .padding(top = 64.dp, start = 16.dp, end = 16.dp)
            .fillMaxHeight()
            .fillMaxWidth()
            .background(SearchHeaderColor)
    ) {

        if (completedTasks.isEmpty()) {

            EmptyStateSection(
                false,
                textLabel = stringResource(id = R.string.completed_empty_label),
                icon = painterResource(id = R.drawable.ic_recommendations_nothing_completed)
            )
        } else {
            Box(
                modifier = modifier
                    .background(SearchHeaderColor)
                    .fillMaxWidth()
            ) {
                LazyColumn {
                    items(completedTasks) { task ->
                        CardItemView(viewModel, task = task) {
                            onSelectTask(it)
                        }
                    }
                }
            }
        }
    }
}


@Composable
private fun ShowAllPatients(
    modifier: Modifier,
    newTasks: List<Task>,
    viewModel: RegisterViewModel,
    onSelectTask: (Task) -> Unit
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
                    .fillMaxWidth(), contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = modifier
                        .padding(horizontal = 16.dp)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    if (isFetchingPatients) {
                        Text(text = stringResource(id = R.string.loading_followups))
                    } else {
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
                        Box(modifier = modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                            .background(Color.White)
                            .clickable {
                                onSelectTask(task)
                                //viewModel.updateTask(task, Task.TaskStatus.INPROGRESS, Task.TaskPriority.ROUTINE)
                                task.input
                            }) {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color.White),
                                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                            ) {
                                Box(
                                    modifier = modifier.background(Color.White)
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
                                                    vertical = 4.dp, horizontal = 4.dp
                                                ),
                                                painter = painterResource(id = R.drawable.ic_patient_male),
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

                                            when (task.intent) {

                                                Task.TaskIntent.PLAN -> {
                                                    label =
                                                        stringResource(id = R.string.view_all_add_investigation).uppercase()
                                                    color = Color(0xFFFFF8E0)
                                                    textColor = Color(0xFFFFC800)
                                                }

                                                Task.TaskIntent.OPTION -> {
                                                    label =
                                                        stringResource(id = R.string.view_all_advice_to_quit_habit).uppercase()
                                                    color = Color(0xFFFFF8E0)
                                                    textColor = Color(0xFFFFC800)
                                                }

                                                Task.TaskIntent.ORDER -> {
                                                    label =
                                                        stringResource(id = R.string.view_all_urgent_referral).uppercase()
                                                    color = Color(0xFFFFCDD2)
                                                    textColor = Color(0xFFFF3355)
                                                }

                                                Task.TaskIntent.PROPOSAL -> {
                                                    label =
                                                        stringResource(id = R.string.view_all_retake_photo).uppercase()
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
                                                    .background(
                                                        color, shape = MaterialTheme.shapes.small
                                                    )
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
fun BottomSheetContent(
    viewModel: RegisterViewModel,
    task: RegisterViewModel.TaskItem,
    onStatusUpdate: (TaskProgressState) -> Unit,
    onCancel: () -> Unit
) {

    val name = task.patient?.name
        ?.firstOrNull()
        ?.given
        ?.firstOrNull()
        ?.value
        ?: ""

    val phone = task.patient?.telecom
        ?.firstOrNull()
        ?.value
        ?: ""

    val dateObj = task.patient?.extension
        ?.find { it.url?.substringAfterLast("/") == "patient-registraion-date" }
        ?.value?.asStringValue()
        ?.takeIf { it.isNotEmpty() }
        ?.let { convertToDateStringToDate(it) }
        ?: task.patient?.meta?.lastUpdated

    val date = dateObj?.let { convertToDate(it) }.orEmpty()

    //val date = task.patient?.meta?.lastUpdated?.let { convertToDate(it) }.toString()
    val address = getPatientAddress(task.patient)

    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 36.dp)
    ) {
        val selectedPriority =
            remember { mutableStateOf(TaskProgressState.NONE) } // Initial selected status

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

        Text(
            text = address,
            modifier = Modifier.padding(horizontal = 4.dp),
            color = colorResource(id = R.color.subTextGrey),
            fontSize = 14.sp
        )

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(id = R.string.view_all_screened_on),
                color = colorResource(id = R.color.subTextGreyBold),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.width(4.dp))

            Text(
                text = date,
                color = colorResource(id = R.color.subTextGrey),
                fontSize = 14.sp,
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {

            Row {
                Text(
                    text = stringResource(id = R.string.phone_with_space),
                    color = colorResource(id = R.color.subTextGreyBold),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                )

                Spacer(modifier = Modifier.width(4.dp))


                Text(
                    text = phone,
                    color = colorResource(id = R.color.subTextGrey),
                    fontSize = 14.sp,
                )
            }


            Row(
                modifier = Modifier
                    .align(Alignment.CenterVertically)
                    .clickable {
                        context.startActivity(Intent(Intent.ACTION_DIAL).apply {
                            data = Uri.parse("tel:$phone")
                        })
                    }, verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(id = R.string.view_all_call),
                    color = LightColors.primary
                )

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

        Spacer(modifier = Modifier.height(16.dp))

        MultiRecommendationStatusColumn(viewModel.getTaskCodeWithValue(task))

        Spacer(modifier = Modifier.height(8.dp))

        if (task.task.status != TaskStatus.COMPLETED) {
            Spacer(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Color.LightGray)
            )

            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(id = R.string.view_all_change_status),
                color = colorResource(id = R.color.subTextGrey),
                fontSize = 14.sp
            )
            Spacer(modifier = Modifier.height(16.dp))
        }


        val listOfOutput = mutableListOf<Task.TaskOutputComponent>()
        val op = TaskOutputComponent()
        val con = CodeableConcept()
        val codee = Coding()
        codee.code = "priority"
        con.coding = listOf()
        op.type = con
        listOfOutput.add(op)
        //task.task.output = listOfOutput

        var options: List<Pair<TaskProgressState, TaskProgressStatusDisplay>> =
            emptyList<Pair<TaskProgressState, TaskProgressStatusDisplay>>()
        when (task.task.status) {
            TaskStatus.REQUESTED -> {
                options = listOf(
                    TaskProgressState.NOT_RESPONDED to TaskProgressStatusDisplay.NOT_RESPONDED,
                    TaskProgressState.NOT_AGREED_FOR_FOLLOWUP to TaskProgressStatusDisplay.NOT_AGREED_FOR_FOLLOWUP,
                    TaskProgressState.AGREED_FOLLOWUP_NOT_DONE to TaskProgressStatusDisplay.AGREED_FOLLOWUP_NOT_DONE,
                    TaskProgressState.FOLLOWUP_DONE to TaskProgressStatusDisplay.FOLLOWUP_DONE
                )
            }

            TaskStatus.INPROGRESS -> {

                if (task.task.output.isNotEmpty()) {
                    when (task.task.output.get(0).value.valueToString()) {
                        TaskProgressState.NOT_RESPONDED.text -> {
                            //Clicked on task from Inprogress tab -> Not agreed for follow-up.
                            options = listOf(
                                TaskProgressState.NOT_RESPONDED to TaskProgressStatusDisplay.NOT_RESPONDED,
                                TaskProgressState.NOT_AGREED_FOR_FOLLOWUP to TaskProgressStatusDisplay.NOT_AGREED_FOR_FOLLOWUP,
                                TaskProgressState.AGREED_FOLLOWUP_NOT_DONE to TaskProgressStatusDisplay.AGREED_FOLLOWUP_NOT_DONE,
                            )
                        }

                        else -> {
                            options = listOf(
                                TaskProgressState.NOT_RESPONDED to TaskProgressStatusDisplay.NOT_RESPONDED,
                                TaskProgressState.NOT_AGREED_FOR_FOLLOWUP to TaskProgressStatusDisplay.NOT_AGREED_FOR_FOLLOWUP,
                                TaskProgressState.FOLLOWUP_NOT_DONE to TaskProgressStatusDisplay.FOLLOWUP_NOT_DONE,
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
                    selectedRadioColor = when (priority) {
                        TaskProgressState.FOLLOWUP_DONE -> Color.Green
                        TaskProgressState.FOLLOWUP_NOT_DONE -> Color.Gray
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
                            disabledUnselectedColor = Color.Gray
                        )
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

        if (task.task.status != TaskStatus.COMPLETED) {

            Spacer(modifier = Modifier.height(16.dp))
            Spacer(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Color.LightGray)
            )
            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier
                    .height(48.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceAround
            ) {

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
                    .background(LightColors.primary), onClick = {
                    onStatusUpdate(selectedPriority.value)
                    selectedPriority.value = TaskProgressState.NONE
                }) {
                    Text(text = stringResource(id = R.string.update_status), color = Color.White)
                }
            }
        } else {
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
fun SectionView(
    viewModel: RegisterViewModel,
    section: Section,
    isExpanded: Boolean,
    onSeeMoreCasesClicked: (String, TaskStatus, TaskProgressState) -> Unit,
    onSelectTask: (RegisterViewModel.TaskItem) -> Unit
) {
    var expanded by remember { mutableStateOf(isExpanded) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp)
                .clickable { expanded = !expanded }) {
            Text(
                text = section.title,
                fontSize = 14.sp,
                modifier = Modifier.weight(1f),
                color = colorResource(
                    id = R.color.subTextGrey
                )
            )
            Image(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
                colorFilter = ColorFilter.tint(color = Colors.BRANDEIS_BLUE)
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (section.items.isNotEmpty()) {
            if (expanded) {
                section.items.forEachIndexed { index, task ->
                    //Show max 3 elements
                    if (index <= 2) {
                        CardItemView(viewModel, task = task) {
                            onSelectTask(it)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                val itemsLabel: String = if (section.items.size > 3) {
                    stringResource(
                        id = R.string.dynamic_see_more,
                        "${section.items.size - 3}"
                    ).uppercase()
                } else {
                    ""
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.CenterHorizontally)
                        .padding(vertical = 8.dp), horizontalArrangement = Arrangement.Center
                ) {

                    Box {
                        Text(
                            text = itemsLabel,
                            fontSize = 14.sp,
                            modifier = Modifier
                                .padding(vertical = 4.dp)
                                .clickable {

                                    var priority = TaskProgressState.NONE

                                    when (section.sectionTitle) {
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
                                        section.sectionTitle, section.items[0].task.status, priority
                                    )
                                },
                            color = LightColors.primary
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .padding(vertical = 8.dp)
                        .background(color = Color.LightGray)
                        .height(1.dp)
                        .fillMaxWidth(),
                )
            }
        } else {
            var emptyString = ""
            var icon = painterResource(id = R.drawable.ic_patient_male)
            when (section.sectionTitle) {

                stringResource(id = R.string.not_contacted) -> {
                    emptyString = stringResource(id = R.string.not_contacted_empty_label)
                    icon = painterResource(id = R.drawable.ic_recommendations_contact)
                }

                stringResource(id = R.string.not_responded) -> {
                    emptyString = stringResource(id = R.string.not_responded_empty_label)
                    icon = painterResource(id = R.drawable.ic_recommendations_responsed)
                }

                stringResource(id = R.string.pending_not_agreed) -> {
                    emptyString = stringResource(id = R.string.not_agrred_empty_label)
                    icon = painterResource(id = R.drawable.ic_recommendations_followup_agree)

                }

                stringResource(id = R.string.pending_agreed_not_done) -> {
                    emptyString = stringResource(id = R.string.agrred_not_done_empty_label)
                    icon = painterResource(id = R.drawable.ic_recommendations_not_agreed)
                }
            }

            EmptyStateSection(
                false, textLabel = emptyString, icon = icon
            )
        }


    }
}


@Composable
fun CardItemView(
    viewModel: RegisterViewModel,
    task: RegisterViewModel.TaskItem,
    onSelectTask: (RegisterViewModel.TaskItem) -> Unit
) {
    val name = task.patient?.name
        ?.firstOrNull()
        ?.given
        ?.firstOrNull()
        ?.value
        ?: ""

    val phone = task.patient?.telecom
        ?.firstOrNull()
        ?.value
        ?: ""
    val taskStatusList = viewModel.getTaskCodeWithValue(task)
    println("CardItemView getTaskStatusList--> $taskStatusList")

    RecommendationItem(name, phone, taskStatusList, task.task?.status) {
        onSelectTask(task)
    }
}

@PreviewWithBackgroundExcludeGenerated
@Composable
private fun PreviewNoRegistersView() {
    NoRegisterDataView(
        noResults = NoResultsConfig(
            title = "Title",
            message = "This is message",
            actionButton = NavigationMenuConfig(display = "Button Text", id = "1"),
        ),
    ) {}
}