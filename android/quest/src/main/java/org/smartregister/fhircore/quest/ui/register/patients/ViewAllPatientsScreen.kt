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

import android.os.Bundle
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.MaterialTheme
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.smartregister.fhircore.engine.ui.theme.LightColors
import org.smartregister.fhircore.engine.ui.theme.SearchHeaderColor
import org.smartregister.fhircore.quest.ui.main.components.FILTER
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.ModalBottomSheetValue
import androidx.compose.material.Scaffold
import androidx.compose.material.rememberModalBottomSheetState
import androidx.compose.material3.Icon
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import com.google.android.fhir.datacapture.extensions.asStringValue
import org.hl7.fhir.r4.model.Patient
import org.hl7.fhir.r4.model.QuestionnaireResponse
import org.smartregister.fhircore.engine.domain.model.ToolBarHomeNavigation
import org.smartregister.fhircore.engine.util.extension.encodeResourceToString
import org.smartregister.fhircore.quest.R
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
  UNSYNCED("Unsynced"),
  DRAFTS("Drafts"),
  SYNCED("Synced")
}

@Composable
fun FilterRow(selectedFilter: FilterType, onFilterSelected: (FilterType) -> Unit) {
  Row(modifier = Modifier
    .fillMaxWidth()
    .horizontalScroll(rememberScrollState())
    .padding(16.dp),
    horizontalArrangement = Arrangement.SpaceBetween
    ) {
    FilterType.entries.forEachIndexed { index, filter ->
      if (filter != FilterType.DRAFTS){
        Box(modifier = Modifier
          .border(
            width = 0.5.dp,
            color = (if (filter == selectedFilter) LightColors.primary else Color.LightGray),
            shape = RoundedCornerShape(8.dp)
          )
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
  val bottomSheetState = rememberModalBottomSheetState(initialValue = ModalBottomSheetValue.Hidden, skipHalfExpanded = true)
  //var selectedTask by remember { mutableStateOf<TasksViewModel.TaskItem?>(null) }

  Scaffold(
    modifier = modifier.background(SearchHeaderColor),

    topBar = {
      Column(modifier = modifier.background(SearchHeaderColor)) {
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

        Box(modifier = modifier
            .background(SearchHeaderColor)){
          var selectedFilter by remember {
            if (from.contains(FilterType.DRAFTS.name, true)){
              mutableStateOf(FilterType.DRAFTS)
            }else{
              mutableStateOf(FilterType.ALL_PATIENTS)
            }
          }

          val filteredTasks by viewModel.filteredTasksStateFlow.collectAsState()
          val allLatestTasksStateFlow by viewModel.allLatestTasksStateFlow.collectAsState()

          val allSyncedPatients by viewModel.allSyncedPatientsStateFlow.collectAsState()
          val allSyncedAndUnsyncedPatients by viewModel.allPatientsStateFlow.collectAsState()
          val savedRes by viewModel.allSavedDraftResponse.collectAsState()
          val unSynced by viewModel.allUnSyncedStateFlow.collectAsState()

          Column(
            modifier = modifier
              .fillMaxHeight()
              .background(SearchHeaderColor)
              .fillMaxWidth()
          ){

            LaunchedEffect(key1 = selectedFilter, key2 = allLatestTasksStateFlow) {
              viewModel.getAllSyncedPatients()
              viewModel.getAllPatients()
              viewModel.getAllDraftResponses()
              viewModel.getAllUnSyncedPatients()
            }

            Row(modifier = Modifier
              .fillMaxWidth()) {
              if (!from.contains(FilterType.DRAFTS.name, true)){
                FilterRow(selectedFilter) { filter ->
                  selectedFilter = filter
                  viewModel.getAllSyncedPatients()
                  viewModel.getAllPatients()
                  viewModel.getAllDraftResponses()
                  viewModel.getAllUnSyncedPatients()
                }
              }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Box(
              modifier = modifier
                .fillMaxHeight()
                .background(SearchHeaderColor)
                .fillMaxWidth()
            ) {


              when(selectedFilter){

                FilterType.ALL_PATIENTS -> {
                  LazyColumn(modifier = modifier
                    .background(SearchHeaderColor)) {
                    items(allSyncedAndUnsyncedPatients) { patient ->
                      Box(
                        modifier = modifier
                          .fillMaxWidth()
                          .padding(horizontal = 8.dp)
                          .background(SearchHeaderColor)
                      ) {
                        if(patient.resourceType == RegisterViewModel.AllPatientsResourceType.Patient) {
                          val patientData = patient.patient
                          patientData?.let {
                            ShowSyncedPatientCard(patientData, patient)
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

                FilterType.UNSYNCED -> {
                  ShowUnSyncedPatients2(modifier = modifier, unSynced = unSynced)
                }

                FilterType.SYNCED -> {
                  LazyColumn(modifier = modifier
                    .background(SearchHeaderColor)) {
                    items(allSyncedPatients) { patient ->
                      Box(
                        modifier = modifier
                          .fillMaxWidth()
                          .padding(horizontal = 8.dp)
                          .background(SearchHeaderColor)
                      ) {
                        if(patient.resourceType == RegisterViewModel.AllPatientsResourceType.Patient) {
                          val patientData = patient.patient
                          patientData?.let {
                            ShowSyncedPatientCard(patientData, patient)
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
fun ShowUnSyncedPatients2(
  modifier: Modifier,
  unSynced: List<RegisterViewModel.Patient2>
) {
  Box(
    modifier = modifier
      .padding(start = 8.dp, end = 8.dp)
      .fillMaxHeight()
      .fillMaxWidth()
      .background(SearchHeaderColor)
  ) {

    if (unSynced.isEmpty()) {
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
          Text(text = stringResource(id = R.string.no_unsync_patients))
        }
      }
    } else {
      Box(
        modifier = modifier
          .background(SearchHeaderColor, )
          .fillMaxWidth()
      ) {
        LazyColumn {
          items(unSynced) { patient ->
            Box(
              modifier = modifier
                .fillMaxWidth()
                //.padding(vertical = 4.dp)
                .background(SearchHeaderColor, shape = RoundedCornerShape(8.dp))
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
                        painter = painterResource(id = org.smartregister.fhircore.quest.R.drawable.patient_icon),
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
                      Text(text = "Sync: Pending",
                        modifier = Modifier.padding(
                          vertical = 4.dp,
                          horizontal = 8.dp
                        ))
                    }

                    Row(modifier = Modifier.padding(vertical = 4.dp)) {
                      Box(modifier = Modifier.padding(vertical = 4.dp, horizontal = 36.dp)) {
                        Text(text = "Visited ${OpensrpDateUtils.convertToDateStringFromString(patient.lastUpdated)} ")
                      }
                    }
                  }
                }
              }

              /*Card(
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
                        painter = painterResource(id = R.drawable.ic_patient_male),
                        contentDescription = FILTER,
                        tint = LightColors.primary,
                      )

                      Text(
                        modifier = Modifier
                          .weight(1f)
                          .padding(vertical = 4.dp, horizontal = 4.dp),
                        text = patient.name,
                        style = MaterialTheme.typography.h6,
                        color = LightColors.primary
                      )
                      Spacer(modifier = Modifier.height(16.dp))

                      Text(text = "Sync: Pending")

                    }

                    Row(modifier = modifier.padding(vertical = 4.dp)) {
                      Text(text = "Gender: ${patient.gender}")
                    }
                  }
                }
              }*/
            }
          }
        }
      }
    }
  }
}

@Composable
fun ShowSyncedPatientCard(patientData: Patient, patient: RegisterViewModel.AllPatientsResourceData) {
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
          .background(Color.White, shape = RoundedCornerShape(8.dp))
      ) {
        Row(modifier = Modifier.padding(vertical = 4.dp)) {
          androidx.compose.material.Icon(
            modifier = Modifier.padding(horizontal = 4.dp),
            painter = painterResource(id = org.smartregister.fhircore.quest.R.drawable.patient_icon),
            contentDescription = FILTER,
            tint = LightColors.primary
          )
          Text(
            modifier = Modifier
              .weight(1f)
              .padding(vertical = 4.dp, horizontal = 4.dp),
            text = patientData?.name?.firstOrNull()?.given?.firstOrNull()?.value ?: "",
            style = MaterialTheme.typography.h6,
            color = LightColors.primary
          )
          Spacer(modifier = Modifier.height(16.dp))
          /*Text(text = "Synced",
            modifier = Modifier.padding(
              vertical = 4.dp,
              horizontal = 8.dp
            ))*/
        }

        Row(modifier = Modifier.padding(vertical = 4.dp)) {
          Box(modifier = Modifier.padding(vertical = 4.dp, horizontal = 36.dp)) {
            if (patient.patient?.extension?.isNotEmpty() == true){
              val extension = patient.patient?.extension?.find { it.url?.substringAfterLast("/").equals("patient-registraion-date") }
              Text(text = "Visited ${extension?.value?.asStringValue()?.let {
                OpensrpDateUtils.convertToDateStringFromString(
                  it
                )
              }}")
            }else{
              Text(text = "Visited ${OpensrpDateUtils.convertToDate(patient.meta.lastUpdated)}")
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
      .background(SearchHeaderColor)
  ) {

    if (drafts.isEmpty()) {
      Column(
        modifier = modifier
          .background(SearchHeaderColor)
          .padding(top = 16.dp, start = 16.dp, end = 16.dp)
          .fillMaxWidth()
          .height(300.dp),
      ) {

        Spacer(modifier = Modifier.height(8.dp))

        Text(text = "TOTAL DRAFTS: ${drafts.size}")
        Spacer(modifier = Modifier.height(4.dp))


        Box(modifier = modifier
          .background(SearchHeaderColor)
          .fillMaxWidth(),
          contentAlignment = Alignment.Center
        ) {
          Column(
            modifier = modifier
              .padding(horizontal = 16.dp)
              .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
          ) {
            Icon(
              modifier = Modifier.padding(
                vertical = 8.dp,
                horizontal = 8.dp
              ),
              painter = painterResource(id = R.drawable.ic_draft),
              contentDescription = FILTER,
            )

            Text(text = stringResource(id = org.smartregister.fhircore.quest.R.string.no_draft_patients))
          }
        }
      }
    } else {

      Column(
        modifier = modifier
          .background(SearchHeaderColor)
          .padding(horizontal = 16.dp, vertical = 16.dp)
          .fillMaxWidth()
      ) {

        Text(text = "TOTAL DRAFTS: ${drafts.size}")

        Spacer(modifier = Modifier.height(4.dp))

        LazyColumn(modifier = Modifier
          .fillMaxWidth()) {
          items(drafts) { response ->
            val result = response?.item?.firstOrNull()?.item.takeIf { (it?.size ?: 0) >= 1 }
            val title = result?.get(1)?.answer?.firstOrNull()?.value?.asStringValue() ?: "Guest"
            Box(
              modifier = modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
                .background(Color.White, shape = RoundedCornerShape(8.dp))
                .border(
                  width = 0.dp,
                  color = Color.White,
                  shape = RoundedCornerShape(8.dp),
                ),
            ) {
              Card(
                modifier = Modifier
                  .fillMaxWidth()
                  .background(Color.White, shape = RoundedCornerShape(8.dp)),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
              ) {
                Box(
                  modifier = modifier
                    .background(Color.White)
                ) {
                  Column(
                    modifier = Modifier
                      .fillMaxWidth()
                      .padding(vertical = 8.dp, horizontal = 16.dp)
                      .background(Color.White)
                  ) {
                    Row(modifier = modifier.padding(vertical = 4.dp)) {
                      androidx.compose.material.Icon(
                        modifier = Modifier.padding(vertical = 4.dp, horizontal = 4.dp),
                        painter = painterResource(id = R.drawable.ic_draft),
                        contentDescription = FILTER,
                      )
                      Text(
                        modifier = Modifier
                          .weight(1f)
                          .padding(vertical = 4.dp, horizontal = 8.dp),
                        text = title,
                        style = MaterialTheme.typography.h6,
                        color = Color.DarkGray
                      )
                      Spacer(modifier = Modifier.height(8.dp))
                      Box(
                        modifier = Modifier.clickable {
                          val json = response.encodeResourceToString()
                          onEditResponse(json)
                          viewModel.softDeleteDraft(response.id)
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
                        onDeleteResponse(response.id, true)
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
                      Text(text = "Created: ${response?.meta?.lastUpdated?.let {
                        OpensrpDateUtils.convertToDate(it)
                      }}")
                    }
                  }
                }
              }
            }
          }
        }

        Spacer(modifier = Modifier.height(8.dp))
        if (allDraftsSize > 3){
          Row(horizontalArrangement = Arrangement.Center, modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)) {
            Text(text = "SEE ${allDraftsSize - 3} MORE", color = LightColors.primary, style = MaterialTheme.typography.body2.copy(
              fontSize = 14.sp
            ))
          }
          Spacer(modifier = Modifier.height(8.dp))
        }
      }
    }
  }
}

