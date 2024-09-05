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

package org.smartregister.fhircore.quest.ui.register.patients

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.MaterialTheme
import androidx.compose.material.ModalBottomSheetValue
import androidx.compose.material.Scaffold
import androidx.compose.material.rememberModalBottomSheetState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.hl7.fhir.r4.model.QuestionnaireResponse
import org.smartregister.fhircore.engine.domain.model.ToolBarHomeNavigation
import org.smartregister.fhircore.engine.ui.theme.LightColors
import org.smartregister.fhircore.quest.R
import org.smartregister.fhircore.quest.theme.Colors
import org.smartregister.fhircore.quest.theme.Colors.ANTI_FLASH_WHITE
import org.smartregister.fhircore.quest.theme.Colors.BRANDEIS_BLUE
import org.smartregister.fhircore.quest.theme.Colors.CRAYOLA_LIGHT
import org.smartregister.fhircore.quest.theme.Colors.TRANSPARENT
import org.smartregister.fhircore.quest.theme.body14Medium
import org.smartregister.fhircore.quest.ui.main.components.FILTER
import org.smartregister.fhircore.quest.ui.register.components.EmptyStateSection
import org.smartregister.fhircore.quest.ui.register.tasks.TasksTopScreenSection
import org.smartregister.fhircore.quest.util.OpensrpDateUtils


const val URGENT_REFERRAL_TAB = "Urgent Referral"
const val ADD_INVESTIGATION_TAB = "Add Investigation"
const val RETAKE_PHOTO_TAB = "Retake Photo Tab"
const val URGENT_REFERRAL_PATIENTS = 0
const val ADD_INVESTIGATION_PATIENTS = 1
const val RETAKE_PHOTO_PATIENTS = 2

enum class FilterType(val label: String) {
    ALL_PATIENTS("All submissions"),
    DRAFTS("Drafts"),
}

@Composable
fun FilterRow(selectedFilter: FilterType, onFilterSelected: (FilterType) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(top = 24.dp),
        horizontalArrangement = Arrangement.Start
    ) {
        FilterType.entries.forEachIndexed { index, filter ->
            if (filter != FilterType.DRAFTS) {
                Box {
                    Card(
                        border= BorderStroke(0.5.dp,color = (if (filter == selectedFilter) TRANSPARENT else CRAYOLA_LIGHT)),
                        colors=CardDefaults.cardColors().copy(if (filter == selectedFilter) BRANDEIS_BLUE else ANTI_FLASH_WHITE),
                        shape = RoundedCornerShape(4.dp),
                        modifier = Modifier.clickable {
                            onFilterSelected(filter)
                        }
                    ) {
                        Text(
                            text = getTabName(filter.label),
                            modifier = Modifier.padding(vertical = 8.dp, horizontal = 12.dp),
                            color = if (filter == selectedFilter) Colors.WHITE else Colors.Quartz
                        )
                    }
                }
                if (index < FilterType.entries.size - 1) {
                    Spacer(modifier = Modifier.width(8.dp)) // Horizontal margin
                }
            }
        }
    }
}

@Composable
fun getTabName(labelName:String): String {
    return if (labelName.equals(FilterType.DRAFTS.label, true)) {
        stringResource(id = R.string.view_all_draft)
    } else {
        stringResource(id = R.string.view_all_submissions)
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterialApi::class)
@Composable
fun ViewAllPatientsScreen(
    modifier: Modifier = Modifier,
    viewModel: RegisterViewModel,
    screenTitle: String,
    from: String,
    registerUiState: RegisterUiState,
    onEditDraftClicked: (String) -> Unit,
    onBack: () -> Unit
) {
    val lazyListState: LazyListState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val bottomSheetState = rememberModalBottomSheetState(
        initialValue = ModalBottomSheetValue.Hidden,
        skipHalfExpanded = true
    )
    var submissions by remember { mutableStateOf("") }

    Scaffold(
        modifier = modifier.background(ANTI_FLASH_WHITE),

        topBar = {
            Column(modifier = modifier.background(ANTI_FLASH_WHITE)) {
                TasksTopScreenSection(
                    title = screenTitle,
                    toolBarHomeNavigation = ToolBarHomeNavigation.NAVIGATE_BACK
                ) {
                    onBack()
                }
            }
        }
    ) { innerPadding ->

        Box(modifier = modifier.padding(innerPadding)) {

            Box(
                modifier = modifier
                    .background(ANTI_FLASH_WHITE)
            ) {
                var selectedFilter by remember {
                    if (from.contains(FilterType.DRAFTS.name, true)) {
                        mutableStateOf(FilterType.DRAFTS)
                    } else {
                        mutableStateOf(FilterType.ALL_PATIENTS)
                    }
                }

                val filteredTasks by viewModel.filteredTasksStateFlow.collectAsState()
                val allLatestTasksStateFlow by viewModel.allLatestTasksStateFlow.collectAsState()
                val allSyncedAndUnsyncedPatients by viewModel.allPatientsStateFlow.collectAsState()
                val allSyncedPatients by viewModel.allSyncedPatientsStateFlow.collectAsState()
                val savedRes by viewModel.allSavedDraftResponse.collectAsState()
                val unSynced by viewModel.allUnSyncedStateFlow.collectAsState()

                Column(
                    modifier = modifier
                        .fillMaxHeight()
                        .background(ANTI_FLASH_WHITE)
                        .fillMaxWidth()
                ) {

                    LaunchedEffect(key1 = selectedFilter, key2 = allLatestTasksStateFlow) {
                        viewModel.getAllPatients()
                        viewModel.getAllSyncedPatients()
                        viewModel.getAllDraftResponses()
                        viewModel.getAllUnSyncedPatients()
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                    ) {
                        if (!from.contains(FilterType.DRAFTS.name, true)) {
                            FilterRow(selectedFilter) { filter ->
                                selectedFilter = filter
                                viewModel.getAllPatients()
                                viewModel.getAllSyncedPatients()
                                viewModel.getAllDraftResponses()
                                viewModel.getAllUnSyncedPatients()
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    if (selectedFilter!=FilterType.DRAFTS) {
                        Text(
                            text = submissions.uppercase(),
                            style = body14Medium().copy(
                                letterSpacing = 0.8.sp,
                                color = CRAYOLA_LIGHT
                            ),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp)
                        )
                    }

                    Box(
                        modifier = modifier
                            .fillMaxHeight()
                            .padding(horizontal = 8.dp)
                            .background(ANTI_FLASH_WHITE)
                            .fillMaxWidth()
                    ) {
                        when (selectedFilter) {
                            FilterType.ALL_PATIENTS -> {
                                submissions = stringResource(id = R.string.total_submissions,allSyncedPatients.size.toString())
                                LazyColumn(
                                    modifier = modifier
                                        .background(ANTI_FLASH_WHITE)
                                ) {
                                    items(allSyncedAndUnsyncedPatients) { patient ->
                                        Box(
                                            modifier = modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 8.dp)
                                                .background(ANTI_FLASH_WHITE)
                                        ) {
                                            if (patient.resourceType == RegisterViewModel.AllPatientsResourceType.Patient) {
                                                val patientData = patient.patient
                                                patientData?.let {
                                                    SyncedPatientCardItem(patientData, patient)
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            FilterType.DRAFTS -> {
                                var deleteDraftId by remember { mutableStateOf("") }
                                var showDeleteDialog by remember { mutableStateOf(false) }


                                if (showDeleteDialog) {
                                    DeleteRecordDialog(
                                        onDismiss = {
                                            deleteDraftId = ""
                                            showDeleteDialog = false
                                        },
                                        onConfirm = {
                                            viewModel.softDeleteDraft(deleteDraftId)
                                            deleteDraftId = ""
                                            showDeleteDialog = false
                                        },
                                        onCancel = {
                                            deleteDraftId = ""
                                            showDeleteDialog = false
                                        }
                                    )
                                }
                                submissions = stringResource(id = R.string.total_submissions,allSyncedPatients.size.toString())
                                ShowAllDrafts(
                                    modifier = modifier,
                                    drafts = savedRes,
                                    viewModel = viewModel,
                                    onDeleteResponse = { id, isShowDeleteDialog ->
                                        deleteDraftId = id
                                        showDeleteDialog = isShowDeleteDialog
                                    },
                                    onEditResponse = {
                                        onEditDraftClicked(it)
                                    },
                                    allDraftsSize = 1
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}


@Composable
fun ShowUnSyncedPatients2(
    modifier: Modifier,
    unSynced: List<RegisterViewModel.Patient2>
) {
    Box(
        modifier = modifier
            .padding(top = 64.dp, start = 16.dp, end = 16.dp)
            .fillMaxHeight()
            .fillMaxWidth()
            .background(ANTI_FLASH_WHITE)
    ) {
        if (unSynced.isEmpty()) {
            Column(
                modifier = modifier
                    .padding(bottom = 52.dp)
                    .fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Image(painter = painterResource(id = R.drawable.ic_quest_sync), contentDescription = stringResource(R.string.no_unsync_patients))
                Spacer(modifier = Modifier.padding(16.dp))
                Text(text = stringResource(id = R.string.nothing_in_un_synced))
            }
        } else {
            Box(
                modifier = modifier
                    .background(ANTI_FLASH_WHITE)
                    .fillMaxWidth()
            ) {
                LazyColumn {
                    items(unSynced) { patient ->
                        Box(
                            modifier = modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp)
                                .background(ANTI_FLASH_WHITE, shape = RoundedCornerShape(8.dp))
                        ) {

                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .background(Color.White, shape = RoundedCornerShape(8.dp)),
                                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
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
                                        Row(modifier = Modifier.padding(vertical = 4.dp)) {
                                            androidx.compose.material.Icon(
                                                modifier = Modifier.padding(horizontal = 4.dp),
                                                painter = painterResource(id = R.drawable.patient_icon),
                                                contentDescription = FILTER,
                                                tint = LightColors.primary
                                            )
                                            Text(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .padding(vertical = 4.dp, horizontal = 4.dp),
                                                text = patient.name ?: "",
                                                style = MaterialTheme.typography.h6,
                                                color = LightColors.primary
                                            )
                                            Spacer(modifier = Modifier.height(16.dp))
                                            Text(
                                                text = "Sync: Pending",
                                                modifier = Modifier.padding(
                                                    vertical = 4.dp,
                                                    horizontal = 8.dp
                                                )
                                            )
                                        }

                                        Row(modifier = Modifier.padding(vertical = 4.dp)) {
                                            Box(
                                                modifier = Modifier.padding(
                                                    vertical = 4.dp,
                                                    horizontal = 36.dp
                                                )
                                            ) {
                                                Text(
                                                    text = "Visited ${
                                                        OpensrpDateUtils.convertToDateStringFromString(
                                                            patient.lastUpdated
                                                        )
                                                    } "
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

@Composable
fun ShowAllDrafts(
    modifier: Modifier,
    drafts: List<QuestionnaireResponse>,
    viewModel: RegisterViewModel,
    onDeleteResponse: (String, Boolean) -> Unit,
    onEditResponse: (String) -> Unit?,
    allDraftsSize: Int
) {
    Box(
        modifier = modifier
            .padding(top = 8.dp)
            .fillMaxWidth()
            .background(ANTI_FLASH_WHITE)
    ) {

        if (drafts.isEmpty()) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp)
                .fillMaxSize()) {
                Text(text = "TOTAL DRAFTS: ${drafts.size}")
                Spacer(modifier = Modifier.height(4.dp))
                EmptyStateSection(
                    false,
                    textLabel = stringResource(id = R.string.no_draft_patients),
                    icon = painterResource(id = R.drawable.ic_cases_draft)
                )
            }
        } else {

            Column(
                modifier = modifier
                    .background(ANTI_FLASH_WHITE)
                    .padding(horizontal = 16.dp, vertical = 16.dp)
                    .fillMaxWidth()
            ) {

                Text(text = "TOTAL DRAFTS: ${drafts.size}")

                Spacer(modifier = Modifier.height(4.dp))

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                ) {
                    items(drafts) { response ->
                        DraftsItem(response, modifier, viewModel, onEditResponse, onDeleteResponse)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                if (allDraftsSize > 3) {
                    Row(
                        horizontalArrangement = Arrangement.Center, modifier = modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                    ) {
                        Text(

                            text = "SEE ${allDraftsSize - 3} MORE",
                            color = LightColors.primary,
                            style = MaterialTheme.typography.body2.copy(
                                fontSize = 14.sp
                            )
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }
}