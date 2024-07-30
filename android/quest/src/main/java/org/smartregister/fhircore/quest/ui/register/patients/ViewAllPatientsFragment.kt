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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.Scaffold
import androidx.compose.material.rememberScaffoldState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.testTag
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.fhir.sync.CurrentSyncJobStatus
import dagger.hilt.android.AndroidEntryPoint
import org.smartregister.fhircore.engine.configuration.workflow.ActionTrigger
import org.smartregister.fhircore.engine.configuration.workflow.ApplicationWorkflow
import javax.inject.Inject
import org.smartregister.fhircore.engine.sync.OnSyncListener
import org.smartregister.fhircore.engine.sync.SyncListenerManager
import org.smartregister.fhircore.engine.ui.theme.AppTheme
import org.smartregister.fhircore.engine.ui.theme.SearchHeaderColor
import org.smartregister.fhircore.engine.util.extension.isIn
import org.smartregister.fhircore.quest.event.EventBus
import org.smartregister.fhircore.quest.ui.main.AppMainViewModel
import org.smartregister.fhircore.quest.ui.questionnaire.QuestionnaireActivity
import org.smartregister.fhircore.quest.ui.shared.QuestionnaireHandler
import org.smartregister.fhircore.quest.util.extensions.interpolateActionParamsValue

@ExperimentalMaterialApi
@AndroidEntryPoint
class ViewAllPatientsFragment : Fragment(), OnSyncListener {

  @Inject lateinit var syncListenerManager: SyncListenerManager

  @Inject lateinit var eventBus: EventBus
  private val appMainViewModel by activityViewModels<AppMainViewModel>()
  //private val registerFragmentArgs by navArgs<ViewAllTasksFragmentArgs>()
  private val registerViewModel by viewModels<RegisterViewModel>()
  var from = ""

  override fun onCreateView(
    inflater: LayoutInflater,
    container: ViewGroup?,
    savedInstanceState: Bundle?,
  ): View {

    from = arguments?.getString(GenericActivityArg.ARG_FROM).toString()
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
            Box(modifier = Modifier
              .padding(innerPadding)
              .background(SearchHeaderColor)
              .testTag(REGISTER_SCREEN_BOX_TAG)) {

              ViewAllPatientsScreen(
                viewModel = registerViewModel,
                from = from,
                registerUiState = registerViewModel.registerUiState.value,
                onEditDraftClicked = {
                  showQuestionnaire(registerViewModel.registerUiState.value, it)
                },
                screenTitle = if (from.contains(FilterType.DRAFTS.name, true)) {"Drafts"} else {"Recently Added"},
                onBack = {
                  activity?.finish()
                }
              )
            }
          }
        }
      }
    }
  }

  private fun showQuestionnaire(registerUiState: RegisterUiState, payloadJson: String) {
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
                bundle.putString(QuestionnaireActivity.QUESTIONNAIRE_RESPONSE_PREFILL, payloadJson)

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
