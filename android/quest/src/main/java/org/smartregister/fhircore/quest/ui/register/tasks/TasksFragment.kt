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
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.Scaffold
import androidx.compose.material.SnackbarDuration
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
import org.hl7.fhir.r4.model.QuestionnaireResponse
import org.smartregister.fhircore.engine.R
import org.smartregister.fhircore.engine.domain.model.SnackBarMessageConfig
import org.smartregister.fhircore.engine.sync.OnSyncListener
import org.smartregister.fhircore.engine.sync.SyncListenerManager
import org.smartregister.fhircore.engine.ui.theme.AppTheme
import org.smartregister.fhircore.engine.ui.theme.SearchHeaderColor
import org.smartregister.fhircore.engine.util.SharedPreferencesHelper
import org.smartregister.fhircore.quest.event.EventBus
import org.smartregister.fhircore.quest.ui.main.AppMainActivity
import org.smartregister.fhircore.quest.ui.main.AppMainViewModel
import org.smartregister.fhircore.quest.ui.register.patients.RegisterViewModel
import org.smartregister.fhircore.quest.ui.shared.components.SnackBarMessage
import org.smartregister.fhircore.quest.ui.shared.models.QuestionnaireSubmission
import org.smartregister.fhircore.quest.util.extensions.handleClickEvent
import org.smartregister.fhircore.quest.util.extensions.hookSnackBar
import org.smartregister.fhircore.quest.util.extensions.rememberLifecycleEvent
import javax.inject.Inject

@ExperimentalMaterialApi
@AndroidEntryPoint
class TasksFragment : Fragment(), OnSyncListener {

  @Inject lateinit var syncListenerManager: SyncListenerManager

  @Inject lateinit var eventBus: EventBus
  private val appMainViewModel by activityViewModels<AppMainViewModel>()
  //private val registerFragmentArgs by navArgs<RegisterFragmentArgs>()
  private val registerViewModel by viewModels<RegisterViewModel>()


  override fun onCreateView(
    inflater: LayoutInflater,
    container: ViewGroup?,
    savedInstanceState: Bundle?,
  ): View {
    appMainViewModel.retrieveIconsAsBitmap()

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

              PendingTasksScreen(
                viewModel = registerViewModel,
                appMainViewModel = appMainViewModel,
                onEvent = registerViewModel::onEvent,
                registerUiState = registerViewModel.registerUiState.value,
                searchText = registerViewModel.searchText,
                navController = findNavController(),
                isOnline = (activity as AppMainActivity).isOnline.collectAsState().value
              )
            }
          }
        }
      }
    }
  }

  override fun onResume() {
    super.onResume()
    registerViewModel.getAllTasks()
    registerViewModel.getAllUnSyncedPatientsImages()
    registerViewModel.getAllUnSyncedPatients()
    syncListenerManager.registerSyncListener(this, lifecycle)
  }

  override fun onStop() {
    super.onStop()
    registerViewModel.searchText.value = "" // Clear the search term
  }

  override fun onSync(syncJobStatus: CurrentSyncJobStatus) {
    when (syncJobStatus) {
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
        registerViewModel.getAllSyncedPatients()
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
    }
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

  suspend fun handleQuestionnaireSubmission(questionnaireSubmission: QuestionnaireSubmission) {
    if (questionnaireSubmission.questionnaireConfig.saveQuestionnaireResponse) {
      appMainViewModel.run {
        onQuestionnaireSubmission(questionnaireSubmission)
//        retrieveAppMainUiState(refreshAll = false) // Update register counts
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

  private fun launchDialPad(phone: String) {
    startActivity(Intent(Intent.ACTION_DIAL).apply { data = Uri.parse(phone) })
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
    fun newInstance(bundle: Bundle) = TasksFragment().apply {
      arguments = bundle
    }

      const val REGISTER_SCREEN_BOX_TAG = "fragmentRegisterScreenTestTag"
  }
}
