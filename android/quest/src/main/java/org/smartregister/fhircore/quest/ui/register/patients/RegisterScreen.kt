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

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.AlertDialog
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.navigation.NavController
import org.hl7.fhir.r4.model.QuestionnaireResponse
import org.smartregister.fhircore.engine.R
import org.smartregister.fhircore.engine.configuration.navigation.NavigationMenuConfig
import org.smartregister.fhircore.engine.configuration.register.NoResultsConfig
import org.smartregister.fhircore.engine.domain.model.ToolBarHomeNavigation
import org.smartregister.fhircore.engine.ui.components.register.LoaderDialog
import org.smartregister.fhircore.engine.ui.theme.LightColors
import org.smartregister.fhircore.engine.ui.theme.SearchHeaderColor
import org.smartregister.fhircore.engine.util.annotation.PreviewWithBackgroundExcludeGenerated
import org.smartregister.fhircore.quest.event.ToolbarClickEvent
import org.smartregister.fhircore.quest.theme.Colors.ANTI_FLASH_WHITE
import org.smartregister.fhircore.quest.theme.Colors.BRANDEIS_BLUE
import org.smartregister.fhircore.quest.theme.Colors.CRAYOLA_LIGHT
import org.smartregister.fhircore.quest.theme.body14Medium
import org.smartregister.fhircore.quest.theme.bodyNormal
import org.smartregister.fhircore.quest.ui.main.AppMainEvent
import org.smartregister.fhircore.quest.ui.main.AppMainViewModel
import org.smartregister.fhircore.quest.ui.main.components.FILTER
import org.smartregister.fhircore.quest.ui.main.components.TopScreenSection
import org.smartregister.fhircore.quest.ui.questionnaire.QuestionnaireActivity.Companion.QUESTIONNAIRE_RESPONSE_PREFILL
import org.smartregister.fhircore.quest.ui.register.components.EmptyStateSection
import org.smartregister.fhircore.quest.util.dailog.ForegroundSyncDialog
import org.smartregister.fhircore.quest.util.extensions.handleClickEvent


const val NO_REGISTER_VIEW_COLUMN_TEST_TAG = "noRegisterViewColumnTestTag"
const val NO_REGISTER_VIEW_TITLE_TEST_TAG = "noRegisterViewTitleTestTag"
const val NO_REGISTER_VIEW_MESSAGE_TEST_TAG = "noRegisterViewMessageTestTag"
const val NO_REGISTER_VIEW_BUTTON_TEST_TAG = "noRegisterViewButtonTestTag"
const val NO_REGISTER_VIEW_BUTTON_ICON_TEST_TAG = "noRegisterViewButtonIconTestTag"
const val NO_REGISTER_VIEW_BUTTON_TEXT_TEST_TAG = "noRegisterViewButtonTextTestTag"
const val REGISTER_CARD_TEST_TAG = "registerCardListTestTag"
const val FIRST_TIME_SYNC_DIALOG = "firstTimeSyncTestTag"
const val FAB_BUTTON_REGISTER_TEST_TAG = "fabTestTag"
const val TOP_REGISTER_SCREEN_TEST_TAG = "topScreenTestTag"
const val ALL_PATIENTS_TAB = "ALL CASES"
const val DRAFT_PATIENTS_TAB = "DRAFT"
const val UNSYNCED_PATIENTS_TAB = "UN-SYNCED"
const val ALL_PATIENTS = 0
const val DRAFT_PATIENTS = 1
const val UNSYNCED_PATIENTS = 2


@Composable
fun RegisterScreen(
    modifier: Modifier = Modifier,
    openDrawer: (Boolean) -> Unit,
    viewModel: RegisterViewModel,
    appMainViewModel: AppMainViewModel,
    onEvent: (RegisterEvent) -> Unit,
    registerUiState: RegisterUiState,
    searchText: MutableState<String>,
    navController: NavController,
    toolBarHomeNavigation: ToolBarHomeNavigation = ToolBarHomeNavigation.OPEN_DRAWER,
) {
    var showDialog by remember { mutableStateOf(false) }
    var appMainEvent : AppMainEvent?=null
    var imageCount =1
    var totalImageLeftCountData = getSyncImagelist(imageCount)
    var totalImageLeft by remember { mutableStateOf(totalImageLeftCountData) }


    val permissionGranted = remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        permissionGranted.value = isGranted
    }


    Scaffold(
        topBar = {
            Column {
                val filterActions =
                    registerUiState.registerConfiguration?.registerFilter?.dataFilterActions
                TopScreenSection(
                    modifier = modifier.testTag(TOP_REGISTER_SCREEN_TEST_TAG),
                    title = stringResource(id = R.string.appname),
                    searchText = searchText.value,
                    filteredRecordsCount = registerUiState.filteredRecordsCount,
                    searchPlaceholder = registerUiState.registerConfiguration?.searchBar?.display,
                    toolBarHomeNavigation = ToolBarHomeNavigation.SYNC,
                    onSync = {
                        appMainEvent=it
                        showDialog = true },
                    onSearchTextChanged = { searchText ->
                        onEvent(RegisterEvent.SearchRegister(searchText = searchText))
                    },
                    isFilterIconEnabled = filterActions?.isNotEmpty() ?: false,
                ) { event ->
                    when (event) {
                        ToolbarClickEvent.Navigate -> when (toolBarHomeNavigation) {
                            ToolBarHomeNavigation.OPEN_DRAWER -> openDrawer(true)
                            ToolBarHomeNavigation.NAVIGATE_BACK -> navController.popBackStack()
                            ToolBarHomeNavigation.SYNC -> {

                            }
                        }

                        ToolbarClickEvent.FilterData -> {
                            onEvent(RegisterEvent.ResetFilterRecordsCount)
                            filterActions?.handleClickEvent(navController)
                        }
                    }
                }
            }
        },) { innerPadding ->

        Box(
            modifier = modifier.padding(innerPadding),
        ) {
            if (registerUiState.isFirstTimeSync) {
                val isSyncUpload =
                    registerUiState.isSyncUpload.collectAsState(initial = false).value
                LoaderDialog(
                    modifier = modifier.testTag(FIRST_TIME_SYNC_DIALOG),
                    percentageProgressFlow = registerUiState.progressPercentage,
                    dialogMessage = stringResource(
                        id = if (isSyncUpload) R.string.syncing_up else R.string.syncing_down,
                    ),
                    showPercentageProgress = true,
                )
            }
            registerUiState.registerConfiguration?.noResults?.let { noResultConfig ->
                val allSyncedPatients by viewModel.allSyncedPatientsStateFlow.collectAsState()
                val savedRes by viewModel.allSavedDraftResponse.collectAsState()
                val unSynced by viewModel.allUnSyncedStateFlow.collectAsState()
                val unSyncedImagesCount by viewModel.allUnSyncedImages.collectAsState()
                val isFetching by viewModel.isFetching.collectAsState()
                var deleteDraftId by remember { mutableStateOf("") }
                var showDeleteDialog by remember { mutableStateOf(false) }

                imageCount = unSyncedImagesCount
                totalImageLeftCountData = getSyncImagelist(imageCount)
                totalImageLeft = totalImageLeftCountData
                if (allSyncedPatients.isEmpty() && savedRes.isEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight()
                            .background(ANTI_FLASH_WHITE), verticalArrangement = Arrangement.Center
                    ) {

                        Image(
                            modifier = Modifier
                                .align(Alignment.CenterHorizontally)
                                .size(90.dp),
                            painter = painterResource(id = org.smartregister.fhircore.quest.R.drawable.ic_cases_profile),
                            contentDescription = FILTER
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 16.dp),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Text(
                                style = bodyNormal(fontSize = 16.sp).copy(letterSpacing = .5.sp, lineHeight = 24.sp, textAlign = TextAlign.Center, color = CRAYOLA_LIGHT),
                                text = if (isFetching) stringResource(id = org.smartregister.fhircore.quest.R.string.loading_patients) else stringResource(
                                    id = org.smartregister.fhircore.quest.R.string.cases_no_draft_patients

                                ), modifier = Modifier.padding(horizontal = 40.dp)
                            )
                        }

                        Box(
                            modifier = Modifier.padding(top = 40.dp)
                        ) {
                            NoRegisterDataView(
                                Modifier.padding(horizontal = 40.dp),
                                modifier = modifier, noResults = noResultConfig
                            ) {
                                noResultConfig.actionButton?.actions?.handleClickEvent(navController)
                            }
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight()
                            .background(ANTI_FLASH_WHITE)
                    ) {
                        Box(
                            modifier = Modifier
                                .background(ANTI_FLASH_WHITE)
                                .fillMaxWidth()
                        ) {
                            NoRegisterDataView(
                                modifier = modifier, noResults = noResultConfig
                            ) {
                                noResultConfig.actionButton?.actions?.handleClickEvent(navController)
                            }
                        }

                        Box(
                            modifier = modifier.fillMaxWidth()
                        ) {
                            val sections = listOf(PatientSection(title = "ALL PATIENTS",
                                patients = allSyncedPatients.take(3),
                                onDeleteResponse = { id, isShowDeleteDialog ->
                                    deleteDraftId = id
                                    showDeleteDialog = isShowDeleteDialog
                                },
                                onEditResponse = {

                                }), PatientSection(title = "DRAFTS",
                                drafts = savedRes.take(3),
                                onDeleteResponse = { id, isShowDeleteDialog ->
                                    deleteDraftId = id
                                    showDeleteDialog = isShowDeleteDialog
                                },
                                onEditResponse = {
                                    registerUiState.registerConfiguration?.noResults?.let { noResultConfig ->
                                        val bundle = Bundle()
                                        bundle.putString(QUESTIONNAIRE_RESPONSE_PREFILL, it)
                                        noResultConfig.actionButton?.actions?.handleClickEvent(
                                            navController, bundle = bundle
                                        )
                                    }
                                }))

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp)
                            ) {
                                LazyColumn(modifier.padding(bottom = 16.dp)) {

                                    sections.forEach { section ->
                                        item {
                                            if (section.patients != null) {
                                                Column {
                                                    Spacer(modifier = Modifier.height(16.dp))
                                                    Text(
                                                        text = stringResource(id = org.smartregister.fhircore.quest.R.string.recenty_submitted_label),
                                                        style = body14Medium().copy(
                                                            letterSpacing = 0.8.sp,
                                                            color = CRAYOLA_LIGHT
                                                        )
                                                    )

                                                    ShowAllPatients(
                                                        modifier,
                                                        section.patients,
                                                        viewModel,
                                                        onDeleteResponse = { id, isShowDeleteDialogue ->
                                                            deleteDraftId = id
                                                            showDeleteDialog = isShowDeleteDialogue
                                                        },
                                                        onEditResponse = {
                                                            registerUiState.registerConfiguration?.noResults?.let { noResultConfig ->
                                                                val bundle = Bundle()
                                                                bundle.putString(
                                                                    QUESTIONNAIRE_RESPONSE_PREFILL,
                                                                    it
                                                                )
                                                                noResultConfig.actionButton?.actions?.handleClickEvent(
                                                                    navController, bundle = bundle
                                                                )
                                                            }
                                                        },
                                                        allPatientsSize = allSyncedPatients.size
                                                    )
                                                }

                                            } else if (section.drafts != null) {

                                                if (showDeleteDialog) {
                                                    DeleteRecordDialog(onDismiss = {
                                                        deleteDraftId = ""
                                                        showDeleteDialog = false
                                                    }, onConfirm = {
                                                        viewModel.softDeleteDraft(deleteDraftId)
                                                        deleteDraftId = ""
                                                        showDeleteDialog = false
                                                    }, onCancel = {
                                                        deleteDraftId = ""
                                                        showDeleteDialog = false
                                                    })
                                                }

                                                ShowDrafts(
                                                    modifier,
                                                    section.drafts,
                                                    viewModel,
                                                    onDeleteResponse = { id, isShowDeleteDialogue ->
                                                        deleteDraftId = id
                                                        showDeleteDialog = isShowDeleteDialogue
                                                    },
                                                    onEditResponse = {
                                                        registerUiState.registerConfiguration?.noResults?.let { noResultConfig ->
                                                            val bundle = Bundle()
                                                            bundle.putString(
                                                                QUESTIONNAIRE_RESPONSE_PREFILL, it
                                                            )
                                                            noResultConfig.actionButton?.actions?.handleClickEvent(
                                                                navController, bundle = bundle
                                                            )
                                                        }
                                                    },
                                                    allDraftsSize = savedRes.size
                                                )
                                            }
                                            Spacer(
                                                modifier = Modifier
                                                    .height(0.5.dp)
                                                    .background(Color.LightGray)
                                                    .fillMaxWidth()
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            ForegroundSyncDialog(
                showDialog = showDialog,
                title = stringResource(id = org.smartregister.fhircore.quest.R.string.sync_status),
                content = totalImageLeft,
                imageCount,
                confirmButtonText = stringResource(id = org.smartregister.fhircore.quest.R.string.sync_now),
                dismissButtonText = stringResource(id = org.smartregister.fhircore.quest.R.string.okay),
                onDismiss = {
                    showDialog = false
                },
                onConfirm = {
                    Log.e("TAG","ForegroundSyncDialog --> onConfirm --> ")
                    showDialog = false
                    if (!permissionGranted.value) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            permissionGranted.value = true
                            appMainEvent?.let { mainEvent -> appMainViewModel.onEvent(mainEvent,true) }
                        }
                    }else{
                        appMainEvent?.let { mainEvent -> appMainViewModel.onEvent(mainEvent,true) }
                    }
                }
            )
        }
    }
}

@Composable
private fun getSyncImagelist(imageCount: Int) =
    stringResource(id = org.smartregister.fhircore.quest.R.string.image_left, imageCount.toString())

@OptIn(ExperimentalMaterialApi::class)
@Composable
fun ShowDrafts(
    modifier: Modifier,
    drafts: List<QuestionnaireResponse>,
    viewModel: RegisterViewModel,
    onDeleteResponse: (String, Boolean) -> Unit,
    onEditResponse: (String) -> Unit?,
    allDraftsSize: Int
) {
    Box(modifier = modifier
        .padding(top = 8.dp)
        .fillMaxWidth()) {
        if (drafts.isEmpty()) {
            Column(
                modifier = modifier
                    .padding(top = 16.dp)
                    .fillMaxWidth()
                    .height(300.dp),
            ) {
                Text(text = stringResource(id = org.smartregister.fhircore.quest.R.string.draft), style = body14Medium().copy(letterSpacing = 0.8.sp, color = CRAYOLA_LIGHT))

                EmptyStateSection(
                    false,
                    textLabel = stringResource(id = org.smartregister.fhircore.quest.R.string.no_draft_patients),
                    icon = painterResource(id = org.smartregister.fhircore.quest.R.drawable.ic_cases_draft)
                )
            }
        } else {

            val context = LocalContext.current
            Column(
                modifier = modifier.fillMaxWidth()
            ) {
                Spacer(modifier = Modifier.height(8.dp))

                Text(text = stringResource(id = org.smartregister.fhircore.quest.R.string.draft), style = body14Medium().copy(letterSpacing = 0.8.sp, color = CRAYOLA_LIGHT))

                Spacer(modifier = Modifier.height(16.dp))

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(250.dp)
                ) {
                    items(drafts) { response ->
                        DraftsItem(response, modifier, viewModel, onEditResponse, onDeleteResponse)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                if (allDraftsSize > 3) {
                    Row(horizontalArrangement = Arrangement.Center,
                        modifier = modifier
                            .fillMaxWidth()
                            .clickable {
                                val intent = Intent(context, GenericActivity::class.java).apply {
                                    putExtra(
                                        GenericActivityArg.ARG_FROM, GenericActivityArg.FROM_DRAFTS
                                    )
                                }
                                context.startActivity(intent)
                            }) {
                        Text(
                            text = stringResource(
                                id = org.smartregister.fhircore.quest.R.string.dynamic_see_more,
                                "${allDraftsSize - 3}"
                            ).uppercase(), style = body14Medium().copy(
                                textAlign = TextAlign.Center, color = BRANDEIS_BLUE, letterSpacing = 1.sp
                            ), modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }
}


@OptIn(ExperimentalMaterialApi::class)
@Composable
private fun ShowAllPatients(
    modifier: Modifier,
    patients: List<RegisterViewModel.AllPatientsResourceData>,
    viewModel: RegisterViewModel,
    onDeleteResponse: (String, Boolean) -> Unit,
    onEditResponse: (String) -> Unit,
    allPatientsSize: Int,
) {

    val isFetchingPatients by viewModel.isFetching.collectAsState()

    Box(
        modifier = modifier
            .padding(top = 8.dp)
            .fillMaxWidth()
            .background(SearchHeaderColor)
    ) {

        if (patients.isEmpty()) {
            EmptyStateSection(
                isFetchingPatients,
                textLabel = if (isFetchingPatients) stringResource(id = org.smartregister.fhircore.quest.R.string.loading_patients) else stringResource(
                    id = org.smartregister.fhircore.quest.R.string.no_patients_added
                ),
                icon = painterResource(id = org.smartregister.fhircore.quest.R.drawable.ic_small_cases_profile))

        } else {
            val context = LocalContext.current
            Column(
                modifier = modifier
                    .padding(top = 8.dp)
                    .fillMaxWidth()
            ) {

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(ANTI_FLASH_WHITE)
                        .height(300.dp)
                ) {
                    items(patients) { patient ->
                        if (patient.resourceType == RegisterViewModel.AllPatientsResourceType.Patient) {
                            val patientData = patient.patient
                            patientData?.let {
                                SyncedPatientCardItem(patientData, patient)
                            }
                        }
                    }
                }

                if (allPatientsSize > 3) {
                    Row(horizontalArrangement = Arrangement.Center,
                        modifier = modifier
                            .fillMaxWidth()
                            .clickable {
                                val intent = Intent(context, GenericActivity::class.java).apply {
                                    putExtra(
                                        GenericActivityArg.ARG_FROM,
                                        GenericActivityArg.FROM_PATIENTS
                                    )
                                }
                                context.startActivity(intent)
                            }) {
                        Text(
                            text = stringResource(
                                id = org.smartregister.fhircore.quest.R.string.dynamic_see_more,
                                "${allPatientsSize - 3}"
                            ).uppercase(), style = body14Medium().copy(
                                textAlign = TextAlign.Center, color = BRANDEIS_BLUE, letterSpacing = 1.sp
                            ), modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun NoRegisterDataView(
    outerColumnModifier: Modifier=Modifier.padding(16.dp),
    modifier: Modifier = Modifier,
    noResults: NoResultsConfig,
    onClick: () -> Unit,
) {
    if (noResults.actionButton != null) {
        Column(modifier = outerColumnModifier) {
            Card(shape = RoundedCornerShape(2.dp),
                colors = CardDefaults.cardColors(containerColor = LightColors.primary),
                modifier = Modifier
                    .clickable { onClick() }
                    .fillMaxWidth()
                    .testTag(NO_REGISTER_VIEW_BUTTON_TEST_TAG), // Set corner radius here
                elevation = CardDefaults.cardElevation(3.dp)) {
                Row(
                    modifier = Modifier
                        .padding(18.dp)
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = modifier
                            .padding(end = 8.dp)
                            .testTag(NO_REGISTER_VIEW_BUTTON_ICON_TEST_TAG),
                    )
                    Text(
                        text = stringResource(id = org.smartregister.fhircore.quest.R.string.add_patient),
                        color = Color.White,
                        modifier = modifier.testTag(NO_REGISTER_VIEW_BUTTON_TEXT_TEST_TAG),
                    )
                }
            }
        }
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


@Composable
fun DeleteRecordDialog(
    onDismiss: () -> Unit, onConfirm: () -> Unit, onCancel: () -> Unit
) {

    AlertDialog(onDismissRequest = onDismiss, title = {
        Text(
            text = stringResource(id = org.smartregister.fhircore.quest.R.string.delete_draft_title),
            fontSize = 28.sp,
            color = Color.Black
        )
    }, text = {
        Text(
            text = stringResource(id = org.smartregister.fhircore.quest.R.string.delete_draft_desc),
            color = Color.Gray
        )
    }, confirmButton = {
        TextButton(onClick = onConfirm) {
            Text(text = stringResource(id = R.string.yes), color = LightColors.primary)
        }
    }, dismissButton = {
        TextButton(onClick = onCancel) {
            Text(text = stringResource(id = R.string.no), color = LightColors.primary)
        }
    }, properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = true)
    )
}

data class PatientSection(
    val title: String,
    val patients: List<RegisterViewModel.AllPatientsResourceData>? = null, // Assuming patients have the same type for now
    val drafts: List<QuestionnaireResponse>? = null, // Assuming patients have the same type for now
    val onDeleteResponse: (String, Boolean) -> Unit,
    val onEditResponse: (String) -> Unit // Update based on your edit action type
)
