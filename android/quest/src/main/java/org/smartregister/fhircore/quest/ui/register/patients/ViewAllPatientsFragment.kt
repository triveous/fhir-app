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
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.viewModels
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
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.findNavController
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.paging.compose.collectAsLazyPagingItems
import com.google.android.fhir.sync.CurrentSyncJobStatus
import com.google.android.fhir.sync.SyncJobStatus
import com.google.android.fhir.sync.SyncOperation
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import org.hl7.fhir.r4.model.QuestionnaireResponse
import org.hl7.fhir.r4.model.Task.TaskPriority
import org.hl7.fhir.r4.model.Task.TaskStatus
import org.smartregister.fhircore.engine.R
import org.smartregister.fhircore.engine.domain.model.SnackBarMessageConfig
import org.smartregister.fhircore.engine.domain.model.ToolBarHomeNavigation
import org.smartregister.fhircore.engine.sync.OnSyncListener
import org.smartregister.fhircore.engine.sync.SyncListenerManager
import org.smartregister.fhircore.engine.ui.theme.AppTheme
import org.smartregister.fhircore.engine.ui.theme.SearchHeaderColor
import org.smartregister.fhircore.engine.util.SharedPreferencesHelper
import org.smartregister.fhircore.quest.event.EventBus
import org.smartregister.fhircore.quest.ui.main.AppMainUiState
import org.smartregister.fhircore.quest.ui.main.AppMainViewModel
import org.smartregister.fhircore.quest.ui.register.patients.RegisterViewModel
import org.smartregister.fhircore.quest.ui.shared.components.SnackBarMessage
import org.smartregister.fhircore.quest.ui.shared.models.QuestionnaireSubmission
import org.smartregister.fhircore.quest.util.extensions.handleClickEvent
import org.smartregister.fhircore.quest.util.extensions.hookSnackBar
import org.smartregister.fhircore.quest.util.extensions.rememberLifecycleEvent

@ExperimentalMaterialApi
@AndroidEntryPoint
class ViewAllPatientsFragment : Fragment(), OnSyncListener {

  @Inject lateinit var syncListenerManager: SyncListenerManager

  @Inject lateinit var eventBus: EventBus
  private val appMainViewModel by activityViewModels<AppMainViewModel>()
  //private val registerFragmentArgs by navArgs<ViewAllTasksFragmentArgs>()
  private val registerViewModel by viewModels<RegisterViewModel>()

  override fun onCreateView(
    inflater: LayoutInflater,
    container: ViewGroup?,
    savedInstanceState: Bundle?,
  ): View {
    //appMainViewModel.retrieveIconsAsBitmap()

    /*with(arguments) {
      val priority = arguments?.getString(GenericActivityArg.TASK_PRIORITY)
      val status = arguments?.getString(GenericActivityArg.TASK_STATUS)
      val title = arguments?.getString(GenericActivityArg.SCREEN_TITLE)
      screenTitle = title.orEmpty()
      when(priority){
        "ROUTINE" -> taskPriority = TaskPriority.ROUTINE
        "URGENT" -> taskPriority = TaskPriority.URGENT
        "ASAP" -> taskPriority = TaskPriority.ASAP
        "STAT" -> taskPriority = TaskPriority.STAT
        "NULL" -> taskPriority = TaskPriority.NULL
      }

      when(status){
        "REQUESTED" -> taskStatus = TaskStatus.REQUESTED
        "INPROGRESS" -> taskStatus = TaskStatus.INPROGRESS
        "COMPLETED" -> taskStatus = TaskStatus.COMPLETED
        else -> {}
      }
    }*/

    return ComposeView(requireContext()).apply {
      setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
      setContent {
        val scaffoldState = rememberScaffoldState()
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

              ViewAllPatientsScreen(
                viewModel = registerViewModel,
                screenTitle = "Recently Added",
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
    registerViewModel.getAllPatients()
    registerViewModel.getAllSyncedPatients()
    registerViewModel.getAllDraftResponses()
    registerViewModel.getAllUnSyncedPatients()
    //registerViewModel.getFilteredTasks(FilterType.URGENT_REFERRAL, taskStatus, taskPriority)
  }

  override fun onStop() {
    super.onStop()
    //tasksViewModel.searchText.value = "" // Clear the search term
  }

  override fun onSync(syncJobStatus: CurrentSyncJobStatus) {

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

  companion object {
    fun newInstance(bundle: Bundle) = ViewAllPatientsFragment().apply {
      arguments = bundle
    }

      const val REGISTER_SCREEN_BOX_TAG = "fragmentRegisterScreenTestTag"
  }
}
