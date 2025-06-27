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

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
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
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.findNavController
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.google.android.fhir.sync.CurrentSyncJobStatus
import com.google.android.fhir.sync.SyncJobStatus
import com.google.android.fhir.sync.SyncOperation
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.hl7.fhir.r4.model.QuestionnaireResponse
import org.smartregister.fhircore.engine.R
import org.smartregister.fhircore.engine.domain.model.SnackBarMessageConfig
import org.smartregister.fhircore.engine.sync.OnSyncListener
import org.smartregister.fhircore.engine.sync.SyncListenerManager
import org.smartregister.fhircore.engine.ui.theme.AppTheme
import org.smartregister.fhircore.engine.ui.theme.SearchHeaderColor
import org.smartregister.fhircore.engine.util.SharedPreferencesHelper
import org.smartregister.fhircore.engine.util.extension.isDeviceOnline
import org.smartregister.fhircore.quest.event.AppEvent
import org.smartregister.fhircore.quest.event.EventBus
import org.smartregister.fhircore.quest.navigation.MainNavigationScreen
import org.smartregister.fhircore.quest.ui.main.AppMainActivity
import org.smartregister.fhircore.quest.ui.main.AppMainViewModel
import org.smartregister.fhircore.quest.ui.shared.components.SnackBarMessage
import org.smartregister.fhircore.quest.ui.shared.models.QuestionnaireSubmission
import org.smartregister.fhircore.quest.util.extensions.handleClickEvent
import org.smartregister.fhircore.quest.util.extensions.hookSnackBar
import org.smartregister.fhircore.quest.util.extensions.rememberLifecycleEvent
import javax.inject.Inject

@ExperimentalMaterialApi
@AndroidEntryPoint
class RegisterFragment : Fragment(), OnSyncListener {

  @Inject lateinit var syncListenerManager: SyncListenerManager
  @Inject lateinit var eventBus: EventBus
  private val appMainViewModel by activityViewModels<AppMainViewModel>()
  private val registerFragmentArgs by navArgs<RegisterFragmentArgs>()
  private val registerViewModel by viewModels<RegisterViewModel>()

  // Track if main sync completed but waiting for image upload completion
  private var isWaitingForImageUpload = false
  private var hasShownSyncCompleted = false
  private var hasShownSyncing = false

  override fun onCreateView(
    inflater: LayoutInflater,
    container: ViewGroup?,
    savedInstanceState: Bundle?,
  ): View {
    appMainViewModel.retrieveIconsAsBitmap()

    with(registerFragmentArgs) {
      lifecycleScope.launchWhenCreated {
        registerViewModel.retrieveRegisterUiState(
          registerId = registerId,
          screenTitle = screenTitle,
          params = params,
          clearCache = false,
        )
      }
    }

    return ComposeView(requireContext()).apply {
      setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
      setContent {
        val appConfig = appMainViewModel.applicationConfiguration
        val scope = rememberCoroutineScope()
        val scaffoldState = rememberScaffoldState()
        val openDrawer: (Boolean) -> Unit = { open: Boolean ->
          scope.launch {
            if (open) scaffoldState.drawerState.open() else scaffoldState.drawerState.close()
          }
        }

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
          // Register screen provides access to the side navigation
          Scaffold(
            modifier = Modifier.background(SearchHeaderColor).padding(bottom = 12.dp),
            drawerGesturesEnabled = scaffoldState.drawerState.isOpen,
            scaffoldState = scaffoldState,
            drawerContent = {

            },
            bottomBar = {

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
            Box(
              modifier = Modifier
                .padding(innerPadding)
                .background(SearchHeaderColor)
                .testTag(REGISTER_SCREEN_BOX_TAG)
            ) {
              RegisterScreen(
                registerUiState = registerViewModel.registerUiState.value,
                navController = findNavController(),
                appMainViewModel = appMainViewModel,
                viewModel = registerViewModel,
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
    // Reset sync state flags
    isWaitingForImageUpload = false
    hasShownSyncCompleted = false
    hasShownSyncing = false

    registerViewModel.getAllPatients()
    registerViewModel.getAllSyncedPatients()
    registerViewModel.getAllDraftResponses()
    registerViewModel.getAllUnSyncedPatients()
    registerViewModel.getAllUnSyncedPatientsImages()

    syncListenerManager.registerSyncListener(this, lifecycle)
  }

  override fun onStop() {
    super.onStop()
    registerViewModel.searchText.value = "" // Clear the search term
  }

  override fun onDestroyView() {
    super.onDestroyView()
    // Reset sync state flags
    isWaitingForImageUpload = false
    hasShownSyncCompleted = false
    hasShownSyncing = false
  }

  override fun onSync(syncJobStatus: CurrentSyncJobStatus) {
    when (syncJobStatus) {
      is CurrentSyncJobStatus.Running ->
        if (syncJobStatus.inProgressSyncJob is SyncJobStatus.Started) {
          // Reset flags when new sync starts
          isWaitingForImageUpload = false
          hasShownSyncCompleted = false
          if (!hasShownSyncing){
            hasShownSyncing = true
            lifecycleScope.launch {
              registerViewModel.emitSnackBarState(
                SnackBarMessageConfig(message = getString(R.string.syncing)),
              )
            }
          }
        } else {
          val progressSyncJob = syncJobStatus.inProgressSyncJob as SyncJobStatus.InProgress

          // Check if this is image upload progress
          if (progressSyncJob.syncOperation == SyncOperation.UPLOAD) {
            lifecycleScope.launch {
              registerViewModel.emitSnackBarState(
                SnackBarMessageConfig(message = getString(R.string.uploading_images_title)),
              )
            }
          }

          emitPercentageProgress(
            progressSyncJob,
            progressSyncJob.syncOperation == SyncOperation.UPLOAD,
          )
        }
      is CurrentSyncJobStatus.Succeeded -> {
        // Check if we were waiting for image upload completion
        if (isWaitingForImageUpload && !hasShownSyncCompleted) {
          // This success means image upload is also complete
          hasShownSyncCompleted = true
          lifecycleScope.launch {
            refreshRegisterData()
            registerViewModel.emitSnackBarState(
              SnackBarMessageConfig(
                message = getString(R.string.sync_completed),
                duration = SnackbarDuration.Short,
              ),
            )
            delay(200)
            registerViewModel.getAllPatients()
            registerViewModel.getAllSyncedPatients()
            registerViewModel.getAllDraftResponses()
            registerViewModel.getAllUnSyncedPatients()
            registerViewModel.getAllUnSyncedPatientsImages()
          }
          isWaitingForImageUpload = false
        } else if (!isWaitingForImageUpload && !hasShownSyncCompleted) {
          // Regular sync completion without image uploads
          hasShownSyncCompleted = true
          lifecycleScope.launch {
            refreshRegisterData()
            registerViewModel.emitSnackBarState(
              SnackBarMessageConfig(
                message = getString(R.string.sync_completed),
                duration = SnackbarDuration.Short,
              ),
            )
            delay(200)
            registerViewModel.getAllPatients()
            registerViewModel.getAllSyncedPatients()
            registerViewModel.getAllDraftResponses()
            registerViewModel.getAllUnSyncedPatients()
            registerViewModel.getAllUnSyncedPatientsImages()
          }
        }
      }
      is CurrentSyncJobStatus.Failed -> {
        // Reset state on failure
        isWaitingForImageUpload = false
        hasShownSyncCompleted = false
        refreshRegisterData()
        syncJobStatus.toString()
        // Show error message in snackBar message
        lifecycleScope.launch {
          registerViewModel.emitSnackBarState(
            SnackBarMessageConfig(
              message = getString(R.string.sync_completed_with_errors),
              duration = SnackbarDuration.Short,
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
    with(registerFragmentArgs) {
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
    }
  }

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    viewLifecycleOwner.lifecycleScope.launch {
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
    }
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

  fun emitPercentageProgress(
    progressSyncJobStatus: SyncJobStatus.InProgress,
    isUploadSync: Boolean,
  ) {
    lifecycleScope.launch {
      val percentageProgress: Int = calculateActualPercentageProgress(progressSyncJobStatus)
      registerViewModel.emitPercentageProgressState(percentageProgress, isUploadSync)

      // If this is image upload progress, set flag to wait for completion
      if (isUploadSync && !hasShownSyncCompleted) {
        isWaitingForImageUpload = true
      }
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
    fun newInstance(bundle: Bundle) = RegisterFragment().apply {
      arguments = bundle
    }

    const val REGISTER_SCREEN_BOX_TAG = "fragmentRegisterScreenTestTag"
  }
}
