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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.testTag
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import com.google.android.fhir.sync.CurrentSyncJobStatus
import dagger.hilt.android.AndroidEntryPoint
import org.hl7.fhir.r4.model.QuestionnaireResponse
import org.hl7.fhir.r4.model.Task.TaskStatus
import org.smartregister.fhircore.engine.sync.OnSyncListener
import org.smartregister.fhircore.engine.sync.SyncListenerManager
import org.smartregister.fhircore.engine.ui.theme.AppTheme
import org.smartregister.fhircore.engine.ui.theme.SearchHeaderColor
import org.smartregister.fhircore.quest.event.EventBus
import org.smartregister.fhircore.quest.ui.main.AppMainViewModel
import org.smartregister.fhircore.quest.ui.register.patients.GenericActivityArg
import org.smartregister.fhircore.quest.ui.register.patients.RegisterViewModel
import org.smartregister.fhircore.quest.util.TaskProgressState
import org.smartregister.fhircore.quest.util.extensions.rememberLifecycleEvent
import javax.inject.Inject

@ExperimentalMaterialApi
@AndroidEntryPoint
class ViewAllTasksFragment : Fragment(), OnSyncListener {

  @Inject lateinit var syncListenerManager: SyncListenerManager

  @Inject lateinit var eventBus: EventBus
  private val appMainViewModel by activityViewModels<AppMainViewModel>()
  //private val registerFragmentArgs by navArgs<ViewAllTasksFragmentArgs>()
  private val registerViewModel by viewModels<RegisterViewModel>()
  val tasksViewModel by viewModels<TasksViewModel>()

  private var taskPriority : TaskProgressState = TaskProgressState.NONE
  var taskStatus : TaskStatus = TaskStatus.REQUESTED
  var screenTitle = ""
  override fun onCreateView(
    inflater: LayoutInflater,
    container: ViewGroup?,
    savedInstanceState: Bundle?,
  ): View {
    appMainViewModel.retrieveIconsAsBitmap()

    with(arguments) {
      val priority = arguments?.getString(GenericActivityArg.TASK_PRIORITY)
      val status = arguments?.getString(GenericActivityArg.TASK_STATUS)
      val title = arguments?.getString(GenericActivityArg.SCREEN_TITLE)
      screenTitle = title.orEmpty()
      taskPriority = when(priority){
        TaskProgressState.NOT_CONTACTED.name -> TaskProgressState.NOT_CONTACTED
        TaskProgressState.NOT_AGREED_FOR_FOLLOWUP.name -> TaskProgressState.NOT_AGREED_FOR_FOLLOWUP
        TaskProgressState.NOT_RESPONDED.name -> TaskProgressState.NOT_RESPONDED
        TaskProgressState.AGREED_FOLLOWUP_NOT_DONE.name -> TaskProgressState.AGREED_FOLLOWUP_NOT_DONE
          else -> TaskProgressState.NONE
      }

      when(status){
        "REQUESTED" -> taskStatus = TaskStatus.REQUESTED
        "INPROGRESS" -> taskStatus = TaskStatus.INPROGRESS
        "COMPLETED" -> taskStatus = TaskStatus.COMPLETED
        else -> {}
      }
    }

    return ComposeView(requireContext()).apply {
      setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
      setContent {
        //val appConfig = appMainViewModel.applicationConfiguration
        //val scope = rememberCoroutineScope()
        val scaffoldState = rememberScaffoldState()
        //val uiState: AppMainUiState = appMainViewModel.appMainUiState.value
        /*val openDrawer: (Boolean) -> Unit = { open: Boolean ->
          scope.launch {
            if (open) scaffoldState.drawerState.open() else scaffoldState.drawerState.close()
          }
        }*/

        // Close side menu (drawer) when activity is not in foreground
        val lifecycleEvent = rememberLifecycleEvent()
        LaunchedEffect(lifecycleEvent) {
          //if (lifecycleEvent == Lifecycle.Event.ON_PAUSE) scaffoldState.drawerState.close()
        }

        LaunchedEffect(Unit) {
          /*registerViewModel.snackBarStateFlow.hookSnackBar(
            scaffoldState = scaffoldState,
            resourceData = null,
            navController = findNavController(),
          )*/
        }

        AppTheme {

          // Register screen provides access to the side navigation
          Scaffold(
            modifier = Modifier.background(SearchHeaderColor),
            //drawerGesturesEnabled = scaffoldState.drawerState.isOpen,
            scaffoldState = scaffoldState,
            topBar = {

            },
            bottomBar = {

            },
            snackbarHost = { snackBarHostState ->
              /*SnackBarMessage(
                snackBarHostState = snackBarHostState,
                backgroundColorHex = appConfig.snackBarTheme.backgroundColor,
                actionColorHex = appConfig.snackBarTheme.actionTextColor,
                contentColorHex = appConfig.snackBarTheme.messageTextColor,
              )*/
            },
          ) { innerPadding ->
            Box(modifier = Modifier.padding(innerPadding)
              .background(SearchHeaderColor)
              .testTag(REGISTER_SCREEN_BOX_TAG)) {

              ViewAllTasksScreen(
                viewModel = tasksViewModel,
                screenTitle = screenTitle,
                taskStatus = taskStatus,
                registerViewModel = registerViewModel,
                taskPriority = taskPriority
              ){
                activity?.finish()
              }
            }
          }
        }
      }
    }
  }

  override fun onResume() {
    super.onResume()
    tasksViewModel.getAllLatestTasks()
    //registerViewModel.getFilteredTasks(FilterType.URGENT_REFERRAL, taskStatus, taskPriority)
  }

  override fun onStop() {
    super.onStop()
    //tasksViewModel.searchText.value = "" // Clear the search term
  }

  override fun onSync(syncJobStatus: CurrentSyncJobStatus) {
    /*when (syncJobStatus) {
      is CurrentSyncJobStatus.Running ->
        if (syncJobStatus.inProgressSyncJob is SyncJobStatus.Started) {
          lifecycleScope.launch {
            registerViewModel.emitSnackBarState(
              SnackBarMessageConfig(message = getString(R.string.syncing)),
            )
          }
        } else {
          emitPercentageProgress(
            syncJobStatus.inProgressSyncJob as SyncJobStatus.InProgress,
            (syncJobStatus.inProgressSyncJob as SyncJobStatus.InProgress).syncOperation ==
              SyncOperation.UPLOAD,
          )
        }
      is CurrentSyncJobStatus.Succeeded -> {
        refreshRegisterData()
        lifecycleScope.launch {
          registerViewModel.emitSnackBarState(
            SnackBarMessageConfig(
              message = getString(R.string.sync_completed),
              //actionLabel = getString(R.string.ok).uppercase(),
              duration = SnackbarDuration.Short,
            ),
          )
        }
        registerViewModel.getAllPatients()
        registerViewModel.getAllDraftResponses()
        registerViewModel.getAllUnSyncedPatients()
      }
      is CurrentSyncJobStatus.Failed -> {
        refreshRegisterData()
        syncJobStatus.toString()
        // Show error message in snackBar message
        lifecycleScope.launch {
          registerViewModel.emitSnackBarState(
            SnackBarMessageConfig(
              message = getString(R.string.sync_completed_with_errors),
              duration = SnackbarDuration.Short,
              //actionLabel = getString(R.string.ok).uppercase(),
            ),
          )
        }
      }
      else -> {
        // Do nothing
      }
    }*/
  }

  fun refreshRegisterData(questionnaireResponse: QuestionnaireResponse? = null) {
    /*with(registerFragmentArgs) {
      registerViewModel.run {
        if (questionnaireResponse != null) {
          updateRegisterFilterState(registerId, questionnaireResponse)
        }

        pagesDataCache.clear()

        retrieveRegisterUiState(
          registerId = registerId,
          screenTitle = screenTitle,
          params = params,
          clearCache = false,
        )
      }
    }*/
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

/*
  suspend fun handleQuestionnaireSubmission(questionnaireSubmission: QuestionnaireSubmission) {
    if (questionnaireSubmission.questionnaireConfig.saveQuestionnaireResponse) {
      appMainViewModel.run {
        onQuestionnaireSubmission(questionnaireSubmission)
        retrieveAppMainUiState(refreshAll = false) // Update register counts
      }

      val (questionnaireConfig, _) = questionnaireSubmission

      refreshRegisterData()

      questionnaireConfig.snackBarMessage?.let { snackBarMessageConfig ->
        registerViewModel.emitSnackBarState(snackBarMessageConfig)
      }

      questionnaireConfig.onSubmitActions?.handleClickEvent(navController = findNavController())
    } else {
      refreshRegisterData(questionnaireSubmission.questionnaireResponse)
    }
  }
*/

/*  fun emitPercentageProgress(
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
  }*/

  companion object {
    fun newInstance(bundle: Bundle) = ViewAllTasksFragment().apply {
      arguments = bundle
    }

      const val REGISTER_SCREEN_BOX_TAG = "fragmentRegisterScreenTestTag"
  }
}
