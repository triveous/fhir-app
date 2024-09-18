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

package org.smartregister.fhircore.quest.ui.register.dashboard

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.Scaffold
import androidx.compose.material.rememberScaffoldState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.findNavController
import androidx.navigation.fragment.findNavController
import androidx.paging.compose.collectAsLazyPagingItems
import com.google.android.fhir.sync.CurrentSyncJobStatus
import com.google.android.fhir.sync.SyncJobStatus
import com.google.android.fhir.sync.SyncOperation
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import org.smartregister.fhircore.engine.configuration.workflow.ActionTrigger
import org.smartregister.fhircore.engine.configuration.workflow.ApplicationWorkflow
import org.smartregister.fhircore.engine.sync.OnSyncListener
import org.smartregister.fhircore.engine.sync.SyncListenerManager
import org.smartregister.fhircore.engine.ui.theme.AppTheme
import org.smartregister.fhircore.engine.ui.theme.SearchHeaderColor
import org.smartregister.fhircore.engine.util.SharedPreferencesHelper
import org.smartregister.fhircore.engine.util.extension.isIn
import org.smartregister.fhircore.quest.event.EventBus
import org.smartregister.fhircore.quest.ui.main.AppMainViewModel
import org.smartregister.fhircore.quest.ui.register.patients.RegisterViewModel
import org.smartregister.fhircore.quest.ui.register.tasks.ViewAllTasksFragment
import org.smartregister.fhircore.quest.ui.shared.QuestionnaireHandler
import org.smartregister.fhircore.quest.ui.shared.components.SnackBarMessage
import org.smartregister.fhircore.quest.ui.shared.models.QuestionnaireSubmission
import org.smartregister.fhircore.quest.util.extensions.handleClickEvent
import org.smartregister.fhircore.quest.util.extensions.hookSnackBar
import org.smartregister.fhircore.quest.util.extensions.interpolateActionParamsValue
import org.smartregister.fhircore.quest.util.extensions.rememberLifecycleEvent
import org.smartregister.fhircore.quest.util.manageSyncMessage
import javax.inject.Inject

@ExperimentalMaterialApi
@AndroidEntryPoint
class DashboardFragment : Fragment(), OnSyncListener {

    @Inject lateinit var syncListenerManager: SyncListenerManager

    @Inject lateinit var eventBus: EventBus
    private val appMainViewModel by activityViewModels<AppMainViewModel>()
    //private val registerFragmentArgs by navArgs<SearchTasksFragmentArgs>()
    private val registerViewModel by viewModels<RegisterViewModel>()
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        appMainViewModel.retrieveIconsAsBitmap()
        val topMenuConfig = appMainViewModel.navigationConfiguration.clientRegisters.first()
        val topMenuConfigId =
            topMenuConfig.actions?.find { it.trigger == ActionTrigger.ON_CLICK }?.id ?: topMenuConfig.id

        lifecycleScope.launchWhenCreated {
            registerViewModel.retrieveRegisterUiState(
                registerId = topMenuConfigId,
                screenTitle = "screenTitle",
                clearCache = false,
            )
        }
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val appConfig = appMainViewModel.applicationConfiguration
                val scaffoldState = rememberScaffoldState()

                // Close side menu (drawer) when activity is not in foreground
                val lifecycleEvent = rememberLifecycleEvent()
                LaunchedEffect(lifecycleEvent) {
                    if (lifecycleEvent == Lifecycle.Event.ON_PAUSE) scaffoldState.drawerState.close()
                }

                LaunchedEffect(Unit) {
                    registerViewModel.snackBarStateFlow.hookSnackBar(
                        scaffoldState = scaffoldState,
                        resourceData = null,
                        navController = findNavController(),
                    )
                }

                AppTheme {
                    val pagingItems =
                        registerViewModel.paginatedRegisterData
                            .collectAsState(emptyFlow())
                            .value
                            .collectAsLazyPagingItems()
                    // Register screen provides access to the side navigation
                    Scaffold(
                        modifier = Modifier.background(SearchHeaderColor).padding(bottom = 12.dp),
                        drawerGesturesEnabled = scaffoldState.drawerState.isOpen,
                        scaffoldState = scaffoldState,
                        bottomBar = {
                            // TODO Activate bottom nav via view configuration
                            /* BottomScreenSection(
                              navController = navController,
                              mainNavigationScreens = MainNavigationScreen.appScreens
                            )*/
                        },
                        snackbarHost = { snackBarHostState ->
                            SnackBarMessage(
                                snackBarHostState = snackBarHostState,
                                backgroundColorHex = appConfig.snackBarTheme.backgroundColor,
                                actionColorHex = appConfig.snackBarTheme.actionTextColor,
                                contentColorHex = appConfig.snackBarTheme.messageTextColor,
                            )
                        },
                    ) { innerPadding ->
                        Box(modifier = Modifier.padding(innerPadding)
                            .background(SearchHeaderColor)
                            .testTag(REGISTER_SCREEN_BOX_TAG)) {

                            DashboardScreen(
                                onEvent = registerViewModel::onEvent,
                                registerUiState = registerViewModel.registerUiState.value,
                                navController = findNavController(),
                                appMainViewModel = appMainViewModel,
                                viewModel = registerViewModel
                            ) {
                                showQuestionnaire()
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        registerViewModel.getDashboardCasedData()
        syncListenerManager.registerSyncListener(this, lifecycle)
        registerViewModel.getAllUnSyncedPatientsImages()
        //registerViewModel.getFilteredTasks(FilterType.URGENT_REFERRAL, taskStatus, taskPriority)
    }

    override fun onStop() {
        super.onStop()
        registerViewModel.searchText.value = "" // Clear the search term
    }

    private fun showQuestionnaire() {
        val registerUiState = registerViewModel.registerUiState.value
        registerUiState.registerConfiguration?.noResults?.let { noResultConfig ->

            noResultConfig.actionButton?.actions?.let {
                val onClickAction =
                    it.find { it.trigger.isIn(ActionTrigger.ON_CLICK, ActionTrigger.ON_QUESTIONNAIRE_SUBMISSION) }

                onClickAction?.let { theConfig ->
                    val actionConfig = theConfig.interpolate(emptyMap())
                    val interpolatedParams = interpolateActionParamsValue(actionConfig, null)
                    when (actionConfig.workflow?.let { ApplicationWorkflow.valueOf(it) }) {
                        ApplicationWorkflow.LAUNCH_QUESTIONNAIRE -> {
                            actionConfig.questionnaire?.let { questionnaireConfig ->
                                val questionnaireConfigInterpolated = questionnaireConfig.interpolate(emptyMap())
                                val bundle = Bundle()
                                //bundle.putString(QuestionnaireActivity.QUESTIONNAIRE_RESPONSE_PREFILL, "")

                                (activity as QuestionnaireHandler).launchQuestionnaire(
                                    context = requireActivity(),
                                    questionnaireConfig = questionnaireConfigInterpolated,
                                    actionParams = interpolatedParams,
                                    extraIntentBundle = bundle
                                )
                            }
                        }
                        else -> {

                        }
                    }
                }
            }
        }
    }

    override fun onSync(syncJobStatus: CurrentSyncJobStatus) {
        requireActivity().manageSyncMessage(registerViewModel,syncJobStatus)
        when (syncJobStatus) {
            is CurrentSyncJobStatus.Running ->
                if (syncJobStatus.inProgressSyncJob is SyncJobStatus.Started) {
                } else {
                    emitPercentageProgress(
                        syncJobStatus.inProgressSyncJob as SyncJobStatus.InProgress,
                        (syncJobStatus.inProgressSyncJob as SyncJobStatus.InProgress).syncOperation ==
                                SyncOperation.UPLOAD,
                    )
                }
            is CurrentSyncJobStatus.Succeeded -> {
                registerViewModel.getAllPatients()
                registerViewModel.getAllDraftResponses()
                registerViewModel.getAllUnSyncedPatients()
            }
            is CurrentSyncJobStatus.Failed -> {
                syncJobStatus.toString()
            }
            else -> {
                // Do nothing
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        /*viewLifecycleOwner.lifecycleScope.launch {
          viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.CREATED) {
            // Each register should have unique eventId
            eventBus.events
              .getFor(MainNavigationScreen.Home.eventId(registerFragmentArgs.registerId))
              .onEach { appEvent ->
                if (appEvent is AppEvent.OnSubmitQuestionnaire) {
                  handleQuestionnaireSubmission(appEvent.questionnaireSubmission)
                }
              }
              .launchIn(lifecycleScope)
          }
        }*/
    }

    suspend fun handleQuestionnaireSubmission(questionnaireSubmission: QuestionnaireSubmission) {
        if (questionnaireSubmission.questionnaireConfig.saveQuestionnaireResponse) {
            appMainViewModel.run {
                onQuestionnaireSubmission(questionnaireSubmission)
//                retrieveAppMainUiState(refreshAll = false) // Update register counts
            }

            val (questionnaireConfig, _) = questionnaireSubmission


            questionnaireConfig.snackBarMessage?.let { snackBarMessageConfig ->
                registerViewModel.emitSnackBarState(snackBarMessageConfig)
            }

            questionnaireConfig.onSubmitActions?.handleClickEvent(navController = findNavController())
        } else {

        }
    }

    fun emitPercentageProgress(
        progressSyncJobStatus: SyncJobStatus.InProgress,
        isUploadSync: Boolean,
    ) {
        lifecycleScope.launch {
            val percentageProgress: Int = calculateActualPercentageProgress(progressSyncJobStatus)
            registerViewModel.emitPercentageProgressState(percentageProgress, isUploadSync)
        }
    }

    private fun getSyncProgress(completed: Int, total: Int) =
        completed * 100 / if (total > 0) total else 1

    private fun calculateActualPercentageProgress(
        progressSyncJobStatus: SyncJobStatus.InProgress,
    ): Int {
        val totalRecordsOverall =
            registerViewModel.sharedPreferencesHelper.read(
                SharedPreferencesHelper.PREFS_SYNC_PROGRESS_TOTAL +
                        progressSyncJobStatus.syncOperation.name,
                1L,
            )
        val isProgressTotalLess = progressSyncJobStatus.total <= totalRecordsOverall
        val currentProgress: Int
        val currentTotalRecords =
            if (isProgressTotalLess) {
                currentProgress =
                    totalRecordsOverall.toInt() - progressSyncJobStatus.total +
                            progressSyncJobStatus.completed
                totalRecordsOverall.toInt()
            } else {
                registerViewModel.sharedPreferencesHelper.write(
                    SharedPreferencesHelper.PREFS_SYNC_PROGRESS_TOTAL +
                            progressSyncJobStatus.syncOperation.name,
                    progressSyncJobStatus.total.toLong(),
                )
                currentProgress = progressSyncJobStatus.completed
                progressSyncJobStatus.total
            }

        return getSyncProgress(currentProgress, currentTotalRecords)
    }

    companion object {
        fun newInstance(bundle: Bundle) = ViewAllTasksFragment().apply {
            arguments = bundle
        }

        const val REGISTER_SCREEN_BOX_TAG = "fragmentRegisterScreenTestTag"
    }
}
