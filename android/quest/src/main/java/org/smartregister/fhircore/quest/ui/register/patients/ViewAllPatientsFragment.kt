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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.fhir.sync.CurrentSyncJobStatus
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.hl7.fhir.r4.model.QuestionnaireResponse
import org.smartregister.fhircore.engine.configuration.workflow.ActionTrigger
import org.smartregister.fhircore.engine.configuration.workflow.ApplicationWorkflow
import org.smartregister.fhircore.engine.domain.model.SnackBarMessageConfig
import org.smartregister.fhircore.engine.sync.AppSyncWorker
import org.smartregister.fhircore.engine.sync.OnSyncListener
import org.smartregister.fhircore.engine.sync.SyncListenerManager
import org.smartregister.fhircore.engine.ui.theme.AppTheme
import org.smartregister.fhircore.engine.ui.theme.SearchHeaderColor
import org.smartregister.fhircore.engine.util.extension.encodeResourceToString
import org.smartregister.fhircore.engine.util.extension.isDeviceOnline
import org.smartregister.fhircore.engine.util.extension.isIn
import org.smartregister.fhircore.quest.R
import org.smartregister.fhircore.quest.event.EventBus
import org.smartregister.fhircore.quest.ui.main.AppMainEvent
import org.smartregister.fhircore.quest.ui.main.AppMainViewModel
import org.smartregister.fhircore.quest.ui.questionnaire.QuestionnaireActivity
import org.smartregister.fhircore.quest.ui.shared.QuestionnaireHandler
import org.smartregister.fhircore.quest.ui.shared.components.AiEnableFailedDialog
import org.smartregister.fhircore.quest.ui.shared.components.SnackBarMessage
import org.smartregister.fhircore.quest.ui.shared.components.rememberIsDeviceOnline
import org.smartregister.fhircore.quest.util.extensions.interpolateActionParamsValue
import javax.inject.Inject

@ExperimentalMaterialApi
@AndroidEntryPoint
class ViewAllPatientsFragment : Fragment(), OnSyncListener {

  @Inject lateinit var syncListenerManager: SyncListenerManager

  @Inject lateinit var eventBus: EventBus
  private val appMainViewModel by activityViewModels<AppMainViewModel>()
  //private val registerFragmentArgs by navArgs<ViewAllTasksFragmentArgs>()
  private val registerViewModel by viewModels<RegisterViewModel>()
  var from = ""

  /** The register whose "Add new case" action names the registration form drafts reopen. */
  private val registerId: String by lazy {
    val topMenuConfig = appMainViewModel.navigationConfiguration.clientRegisters.first()
    topMenuConfig.actions?.find { it.trigger == ActionTrigger.ON_CLICK }?.id ?: topMenuConfig.id
  }

  override fun onCreateView(
    inflater: LayoutInflater,
    container: ViewGroup?,
    savedInstanceState: Bundle?,
  ): View {

    from = arguments?.getString(GenericActivityArg.ARG_FROM).toString()

    lifecycleScope.launchWhenCreated {
      registerViewModel.retrieveRegisterUiState(
        registerId = registerId,
        screenTitle = "screenTitle",
        clearCache = false,
      )
    }

    return ComposeView(requireContext()).apply {
      setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
      setContent {
        val scaffoldState = rememberScaffoldState()
        LaunchedEffect(Unit) {
          registerViewModel.snackBarStateFlow.collectLatest { snackBar ->
            if (snackBar.message.isNotEmpty()) {
              scaffoldState.snackbarHostState.showSnackbar(
                message = snackBar.message,
                actionLabel = snackBar.actionLabel,
                duration = snackBar.duration,
              )
            }
          }
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
              val appConfig = appMainViewModel.applicationConfiguration
              SnackBarMessage(
                snackBarHostState = snackBarHostState,
                backgroundColorHex = appConfig.snackBarTheme.backgroundColor,
                actionColorHex = appConfig.snackBarTheme.actionTextColor,
                contentColorHex = appConfig.snackBarTheme.messageTextColor,
              )
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
                screenTitle = if (from.contains(FilterType.DRAFTS.name, true))
                {stringResource(id = R.string.draft)} else {
                  stringResource(id = R.string.toolbar_recently_added)},
                onBack = {
                  activity?.finish()
                }
              )

              if (registerViewModel.showAiEnableFailedDialog.value) {
                val isOnline by rememberIsDeviceOnline()
                AiEnableFailedDialog(
                  isOnline = isOnline,
                  onRetry = {
                    if (registerViewModel.onAiEnableRetry(isOnline = requireContext().isDeviceOnline())) {
                      startSync()
                    }
                  },
                  onDismiss = registerViewModel::dismissAiEnableFailedDialog,
                )
              }
            }
          }
        }
      }
    }
  }

  /** A sync that is already running will bring the update too, so the retry just waits for it. */
  private fun startSync() {
    if (!AppSyncWorker.mutex.isLocked) {
      appMainViewModel.onEvent(AppMainEvent.SyncData(requireContext()), isForeground = true)
    }
  }

  private fun showQuestionnaire(registerUiState: RegisterUiState, draft: QuestionnaireResponse) {
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
                bundle.putString(
                  QuestionnaireActivity.QUESTIONNAIRE_RESPONSE_PREFILL,
                  draft.encodeResourceToString(),
                )

                // Submitting a draft registers a case too, so it goes through the same gate
                viewLifecycleOwner.lifecycleScope.launch {
                  registerViewModel.launchIfAiUpToDate(questionnaireConfigInterpolated.id) {
                    // Only once the form really opens: backing out of it is what saves the draft
                    // again, so a blocked or debounced launch must leave the stored draft alone
                    registerViewModel.deleteIfNotOldDraft(draft.id)
                    (activity as QuestionnaireHandler).launchQuestionnaire(
                      context = requireActivity(),
                      questionnaireConfig = questionnaireConfigInterpolated,
                      actionParams = interpolatedParams,
                      extraIntentBundle = bundle
                    )
                  }
                }
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
    syncListenerManager.registerSyncListener(this, lifecycle)
  }

  override fun onStop() {
    super.onStop()
    //tasksViewModel.searchText.value = "" // Clear the search term
  }

  override fun onSync(syncJobStatus: CurrentSyncJobStatus) {
    if (syncJobStatus !is CurrentSyncJobStatus.Succeeded && syncJobStatus !is CurrentSyncJobStatus.Failed) {
      return
    }
    lifecycleScope.launch {
      val questionnaireId = registerViewModel.registrationQuestionnaireId(registerId)
      if (registerViewModel.reconcileAiAfterSync(questionnaireId)) {
        registerViewModel.emitSnackBarState(
          SnackBarMessageConfig(message = getString(R.string.ai_enabled_successfully)),
        )
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

  companion object {
    fun newInstance(bundle: Bundle) = ViewAllPatientsFragment().apply {
      arguments = bundle
    }

      const val REGISTER_SCREEN_BOX_TAG = "fragmentRegisterScreenTestTag"
  }
}
