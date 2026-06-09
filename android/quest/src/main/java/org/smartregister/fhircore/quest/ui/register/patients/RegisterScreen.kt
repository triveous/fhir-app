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
import androidx.compose.material.CircularProgressIndicator
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
import androidx.compose.runtime.LaunchedEffect
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
import org.smartregister.fhircore.engine.configuration.register.NoResultsConfig
import org.smartregister.fhircore.engine.domain.model.ToolBarHomeNavigation
import org.smartregister.fhircore.engine.sync.AppSyncWorker
import org.smartregister.fhircore.engine.ui.theme.LightColors
import org.smartregister.fhircore.engine.ui.theme.SearchHeaderColor
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
import org.smartregister.fhircore.quest.util.PostHogAnalytics
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

private const val MAX_VISIBLE_ITEMS = 3

@Composable
fun RegisterScreen(
    modifier: Modifier = Modifier,
    viewModel: RegisterViewModel,
    appMainViewModel: AppMainViewModel,
    registerUiState: RegisterUiState,
    navController: NavController,
    isOnline: Boolean = true,
) {
    val unSyncedImagesCount by viewModel.allUnSyncedImages.collectAsState()
    val unSyncedPatientsCount by viewModel.allUnSyncedStateFlow.collectAsState()
    val isShowPendingSyncBanner by viewModel.isShowPendingSyncBanner.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.isShowPendingSyncBanner()
        viewModel.setPostHogUserProperties()
        PostHogAnalytics.captureScreenView("RegisterScreen")
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { isGranted: Boolean ->
        viewModel.setPermissionGranted(isGranted)
        if (isGranted) {
            viewModel.appMainEvent?.let { mainEvent -> appMainViewModel.onEvent(mainEvent, true) }
        }
    }

    Scaffold(
        topBar = {
            RegisterTopBar(
                modifier = modifier,
                viewModel = viewModel,
                isOnline = isOnline,
                isShowPendingSyncBanner = isShowPendingSyncBanner,
                unSyncedImagesCount = unSyncedImagesCount,
                unSyncedPatientsCount = unSyncedPatientsCount,
            )
        },
    ) { innerPadding ->
        Box(modifier = modifier.padding(innerPadding)) {
            registerUiState.registerConfiguration?.noResults?.let { noResultConfig ->
                RegisterContent(
                    modifier = modifier,
                    viewModel = viewModel,
                    noResultConfig = noResultConfig,
                    navController = navController,
                    registerUiState = registerUiState,
                )
            }

            ForegroundSyncDialog(
                showDialog = viewModel.showDialog.value,
                title = stringResource(id = org.smartregister.fhircore.quest.R.string.sync_status),
                content = "${getSyncImageList(unSyncedImagesCount)} \n${getPatientsCount(unSyncedPatientsCount.size)}",
                unSyncedImagesCount,
                unSyncedPatientsCount.size,
                confirmButtonText = stringResource(id = org.smartregister.fhircore.quest.R.string.sync_now),
                dismissButtonText = stringResource(id = org.smartregister.fhircore.quest.R.string.okay),
                onDismiss = { viewModel.setShowDialog(false) },
                onConfirm = {
                    if (AppSyncWorker.mutex.isLocked) {
                        android.widget.Toast.makeText(
                            context,
                            context.getString(org.smartregister.fhircore.quest.R.string.sync_in_progress),
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        viewModel.setShowDialog(false)
                        handleSyncConfirm(viewModel, appMainViewModel, launcher)
                    }
                },
            )
        }
    }
}

@Composable
private fun RegisterTopBar(
    modifier: Modifier,
    viewModel: RegisterViewModel,
    isOnline: Boolean,
    isShowPendingSyncBanner: Boolean,
    unSyncedImagesCount: Int,
    unSyncedPatientsCount: List<Any>,
) {
    val context = LocalContext.current
    Column(Modifier.background(ANTI_FLASH_WHITE)) {
        TopScreenSection(
            modifier = modifier.testTag(TOP_REGISTER_SCREEN_TEST_TAG),
            title = stringResource(id = R.string.appname),
            toolBarHomeNavigation = ToolBarHomeNavigation.SYNC,
            isOnline = isOnline,
            onSync = {
                viewModel.appMainEvent = it
                viewModel.setShowDialog(true)
                viewModel.setPostHogUserProperties()
                if (AppSyncWorker.mutex.isLocked) {
                    android.widget.Toast.makeText(
                        context,
                        context.getString(org.smartregister.fhircore.quest.R.string.sync_in_progress),
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                }
            },
        ) { _ -> }

        Spacer(Modifier.height(4.dp))

        if (isShowPendingSyncBanner && (unSyncedImagesCount > 0 || unSyncedPatientsCount.isNotEmpty())) {
            PendingSyncBanner(viewModel)
        }
    }
}

@Composable
private fun PendingSyncBanner(viewModel: RegisterViewModel) {
    val context = LocalContext.current

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .background(ANTI_FLASH_WHITE),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFFFF)),
        elevation = CardDefaults.cardElevation(0.1.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(id = R.string.sync_pending),
                style = body14Medium().copy(color = Color(0xFF856404)),
            )
            TextButton(
                onClick = {
                    viewModel.appMainEvent = AppMainEvent.SyncData(context)
                    viewModel.setShowDialog(true)
                    viewModel.setPostHogUserProperties()
                    if (AppSyncWorker.mutex.isLocked) {
                        android.widget.Toast.makeText(
                            context,
                            context.getString(org.smartregister.fhircore.quest.R.string.sync_in_progress),
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    }
                },
            ) {
                Text(
                    text = stringResource(id = R.string.sync_now),
                    style = body14Medium().copy(color = LightColors.primary),
                )
            }
        }
    }
}

@Composable
private fun RegisterContent(
    modifier: Modifier,
    viewModel: RegisterViewModel,
    noResultConfig: NoResultsConfig,
    navController: NavController,
    registerUiState: RegisterUiState,
) {
    val allSyncedPatients by viewModel.allPatientsStateFlow.collectAsState()
    val savedRes by viewModel.allSavedDraftResponse.collectAsState()
    val isFetching by viewModel.isFetching.collectAsState()
    var deleteDraftId by remember { mutableStateOf("") }
    var showDeleteDialog by remember { mutableStateOf(false) }

    if (allSyncedPatients.isEmpty() && savedRes.isEmpty()) {
        EmptyRegisterView(
            modifier = modifier,
            noResultConfig = noResultConfig,
            navController = navController,
            isFetching = isFetching,
        )
    } else {
        PopulatedRegisterView(
            modifier = modifier,
            viewModel = viewModel,
            noResultConfig = noResultConfig,
            navController = navController,
            registerUiState = registerUiState,
            allSyncedPatients = allSyncedPatients,
            savedRes = savedRes,
            showDeleteDialog = showDeleteDialog,
            onDeleteDraft = { id, show ->
                deleteDraftId = id
                showDeleteDialog = show
            },
            onConfirmDelete = {
                viewModel.softDeleteDraft(deleteDraftId)
                PostHogAnalytics.capture(PostHogAnalytics.Events.QUESTIONNAIRE_DRAFT_DELETED)
                deleteDraftId = ""
                showDeleteDialog = false
            },
            onDismissDelete = {
                deleteDraftId = ""
                showDeleteDialog = false
            },
        )
    }
}

@Composable
private fun EmptyRegisterView(
    modifier: Modifier,
    noResultConfig: NoResultsConfig,
    navController: NavController,
    isFetching: Boolean,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .background(ANTI_FLASH_WHITE),
        verticalArrangement = Arrangement.Center,
    ) {
        Image(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .size(90.dp),
            painter = painterResource(id = org.smartregister.fhircore.quest.R.drawable.ic_cases_profile),
            contentDescription = FILTER,
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            Text(
                style = bodyNormal(fontSize = 16.sp).copy(
                    letterSpacing = .5.sp,
                    lineHeight = 24.sp,
                    textAlign = TextAlign.Center,
                    color = CRAYOLA_LIGHT,
                ),
                text = if (isFetching) {
                    stringResource(id = org.smartregister.fhircore.quest.R.string.loading_patients)
                } else {
                    stringResource(id = org.smartregister.fhircore.quest.R.string.cases_no_draft_patients)
                },
                modifier = Modifier.padding(horizontal = 40.dp),
            )
        }

        if (isFetching) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator(
                    modifier = modifier.size(48.dp),
                    strokeWidth = 4.dp,
                    color = LightColors.primary,
                )
            }
        }

        Box(modifier = Modifier.padding(top = 40.dp)) {
            NoRegisterDataView(
                outerColumnModifier = Modifier.padding(horizontal = 40.dp),
                modifier = modifier,
                noResults = noResultConfig,
            ) {
                noResultConfig.actionButton?.actions?.handleClickEvent(navController)
            }
        }
    }
}

@Composable
private fun PopulatedRegisterView(
    modifier: Modifier,
    viewModel: RegisterViewModel,
    noResultConfig: NoResultsConfig,
    navController: NavController,
    registerUiState: RegisterUiState,
    allSyncedPatients: List<RegisterViewModel.AllPatientsResourceData>,
    savedRes: List<QuestionnaireResponse>,
    showDeleteDialog: Boolean,
    onDeleteDraft: (String, Boolean) -> Unit,
    onConfirmDelete: () -> Unit,
    onDismissDelete: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .background(ANTI_FLASH_WHITE),
    ) {
        Box(
            modifier = Modifier
                .background(ANTI_FLASH_WHITE)
                .fillMaxWidth(),
        ) {
            NoRegisterDataView(modifier = modifier, noResults = noResultConfig) {
                noResultConfig.actionButton?.actions?.handleClickEvent(navController)
            }
        }

        Box(modifier = modifier.fillMaxWidth()) {
            val onEditDraftResponse: (String) -> Unit = { json ->
                registerUiState.registerConfiguration?.noResults?.let { config ->
                    val bundle = Bundle().apply {
                        putString(QUESTIONNAIRE_RESPONSE_PREFILL, json)
                    }
                    config.actionButton?.actions?.handleClickEvent(navController, bundle = bundle)
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            ) {
                LazyColumn(modifier.padding(bottom = 16.dp)) {
                    item {
                        Column {
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = stringResource(id = org.smartregister.fhircore.quest.R.string.recenty_submitted_label),
                                style = body14Medium().copy(
                                    letterSpacing = 0.8.sp,
                                    color = CRAYOLA_LIGHT,
                                ),
                            )
                            ShowAllPatients(
                                modifier = modifier,
                                patients = allSyncedPatients.take(MAX_VISIBLE_ITEMS),
                                viewModel = viewModel,
                                allPatientsSize = allSyncedPatients.size,
                            )
                        }
                        Spacer(
                            modifier = Modifier
                                .height(0.5.dp)
                                .background(Color.LightGray)
                                .fillMaxWidth(),
                        )
                    }

                    item {
                        if (showDeleteDialog) {
                            DeleteRecordDialog(
                                onDismiss = onDismissDelete,
                                onConfirm = onConfirmDelete,
                                onCancel = onDismissDelete,
                            )
                        }

                        ShowDrafts(
                            modifier = modifier,
                            drafts = savedRes.take(MAX_VISIBLE_ITEMS),
                            viewModel = viewModel,
                            onDeleteResponse = onDeleteDraft,
                            onEditResponse = onEditDraftResponse,
                            allDraftsSize = savedRes.size,
                        )
                        Spacer(
                            modifier = Modifier
                                .height(0.5.dp)
                                .background(Color.LightGray)
                                .fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ShowDrafts(
    modifier: Modifier,
    drafts: List<QuestionnaireResponse>,
    viewModel: RegisterViewModel,
    onDeleteResponse: (String, Boolean) -> Unit,
    onEditResponse: (String) -> Unit?,
    allDraftsSize: Int,
) {
    Box(
        modifier = modifier
            .padding(top = 8.dp)
            .fillMaxWidth(),
    ) {
        if (drafts.isEmpty()) {
            Column(
                modifier = modifier
                    .padding(top = 16.dp)
                    .fillMaxWidth()
                    .height(300.dp),
            ) {
                Text(
                    text = stringResource(id = org.smartregister.fhircore.quest.R.string.draft),
                    style = body14Medium().copy(letterSpacing = 0.8.sp, color = CRAYOLA_LIGHT),
                )
                EmptyStateSection(
                    isFetchingPatients = false,
                    textLabel = stringResource(id = org.smartregister.fhircore.quest.R.string.no_draft_patients),
                    icon = painterResource(id = org.smartregister.fhircore.quest.R.drawable.ic_cases_draft),
                )
            }
        } else {
            DraftsListSection(
                modifier = modifier,
                drafts = drafts,
                viewModel = viewModel,
                onEditResponse = onEditResponse,
                onDeleteResponse = onDeleteResponse,
                allDraftsSize = allDraftsSize,
            )
        }
    }
}

@OptIn(ExperimentalMaterialApi::class)
@Composable
private fun DraftsListSection(
    modifier: Modifier,
    drafts: List<QuestionnaireResponse>,
    viewModel: RegisterViewModel,
    onEditResponse: (String) -> Unit?,
    onDeleteResponse: (String, Boolean) -> Unit,
    allDraftsSize: Int,
) {
    val context = LocalContext.current

    Column(modifier = modifier.fillMaxWidth()) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(id = org.smartregister.fhircore.quest.R.string.draft),
            style = body14Medium().copy(letterSpacing = 0.8.sp, color = CRAYOLA_LIGHT),
        )
        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .height(250.dp),
        ) {
            items(drafts) { response ->
                DraftsItem(response, modifier, viewModel, onEditResponse, onDeleteResponse)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (allDraftsSize > MAX_VISIBLE_ITEMS) {
            SeeMoreButton(
                modifier = modifier,
                count = allDraftsSize - MAX_VISIBLE_ITEMS,
                onClick = {
                    val intent = Intent(context, GenericActivity::class.java).apply {
                        putExtra(GenericActivityArg.ARG_FROM, GenericActivityArg.FROM_DRAFTS)
                    }
                    context.startActivity(intent)
                },
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun ShowAllPatients(
    modifier: Modifier,
    patients: List<RegisterViewModel.AllPatientsResourceData>,
    viewModel: RegisterViewModel,
    allPatientsSize: Int,
) {
    val isFetchingPatients by viewModel.isFetching.collectAsState()

    Box(
        modifier = modifier
            .padding(top = 8.dp)
            .fillMaxWidth()
            .background(SearchHeaderColor),
    ) {
        if (patients.isEmpty()) {
            EmptyStateSection(
                isFetchingPatients = isFetchingPatients,
                textLabel = if (isFetchingPatients) {
                    stringResource(id = org.smartregister.fhircore.quest.R.string.loading_patients)
                } else {
                    stringResource(id = org.smartregister.fhircore.quest.R.string.no_patients_added)
                },
                icon = painterResource(id = org.smartregister.fhircore.quest.R.drawable.ic_small_cases_profile),
            )
        } else {
            PatientsListSection(
                modifier = modifier,
                patients = patients,
                allPatientsSize = allPatientsSize,
            )
        }
    }
}

@OptIn(ExperimentalMaterialApi::class)
@Composable
private fun PatientsListSection(
    modifier: Modifier,
    patients: List<RegisterViewModel.AllPatientsResourceData>,
    allPatientsSize: Int,
) {
    val context = LocalContext.current

    Column(
        modifier = modifier
            .padding(top = 8.dp)
            .fillMaxWidth(),
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .background(ANTI_FLASH_WHITE)
                .height(300.dp),
        ) {
            items(patients) { patient ->
                if (patient.resourceType == RegisterViewModel.AllPatientsResourceType.Patient) {
                    patient.patient?.let { patientData ->
                        SyncedPatientCardItem(patientData, patient)
                    }
                }
            }
        }

        if (allPatientsSize > MAX_VISIBLE_ITEMS) {
            SeeMoreButton(
                modifier = modifier,
                count = allPatientsSize - MAX_VISIBLE_ITEMS,
                onClick = {
                    val intent = Intent(context, GenericActivity::class.java).apply {
                        putExtra(GenericActivityArg.ARG_FROM, GenericActivityArg.FROM_PATIENTS)
                    }
                    context.startActivity(intent)
                },
            )
        }
    }
}

@Composable
private fun SeeMoreButton(
    modifier: Modifier,
    count: Int,
    onClick: () -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.Center,
        modifier = modifier
            .fillMaxWidth()
            .clickable { onClick() },
    ) {
        Text(
            text = stringResource(
                id = org.smartregister.fhircore.quest.R.string.dynamic_see_more,
                count.toString(),
            ).uppercase(),
            style = body14Medium().copy(
                textAlign = TextAlign.Center,
                color = BRANDEIS_BLUE,
                letterSpacing = 1.sp,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        )
    }
}

@Composable
fun NoRegisterDataView(
    outerColumnModifier: Modifier = Modifier.padding(16.dp),
    modifier: Modifier = Modifier,
    noResults: NoResultsConfig,
    onClick: () -> Unit,
) {
    if (noResults.actionButton != null) {
        Column(modifier = outerColumnModifier) {
            Card(
                shape = RoundedCornerShape(2.dp),
                colors = CardDefaults.cardColors(containerColor = LightColors.primary),
                modifier = Modifier
                    .clickable { onClick() }
                    .fillMaxWidth()
                    .testTag(NO_REGISTER_VIEW_BUTTON_TEST_TAG),
                elevation = CardDefaults.cardElevation(3.dp),
            ) {
                Row(
                    modifier = Modifier
                        .padding(18.dp)
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
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

@Composable
fun DeleteRecordDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(id = org.smartregister.fhircore.quest.R.string.delete_draft_title),
                fontSize = 28.sp,
                color = Color.Black,
            )
        },
        text = {
            Text(
                text = stringResource(id = org.smartregister.fhircore.quest.R.string.delete_draft_desc),
                color = Color.Gray,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = stringResource(id = R.string.yes), color = LightColors.primary)
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(text = stringResource(id = R.string.no), color = LightColors.primary)
            }
        },
        properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = true),
    )
}

private fun handleSyncConfirm(
    viewModel: RegisterViewModel,
    appMainViewModel: AppMainViewModel,
    launcher: androidx.activity.result.ActivityResultLauncher<String>,
) {
    PostHogAnalytics.capture(PostHogAnalytics.Events.SYNC_INITIATED)
    if (!viewModel.permissionGranted.value) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            viewModel.setPermissionGranted(true)
            viewModel.appMainEvent?.let { mainEvent -> appMainViewModel.onEvent(mainEvent, true) }
        }
    } else {
        viewModel.appMainEvent?.let { mainEvent -> appMainViewModel.onEvent(mainEvent, true) }
    }
}