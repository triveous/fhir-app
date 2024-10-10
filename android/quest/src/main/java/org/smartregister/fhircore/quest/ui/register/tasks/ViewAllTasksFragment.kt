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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import com.google.android.fhir.sync.CurrentSyncJobStatus
import dagger.hilt.android.AndroidEntryPoint
import org.hl7.fhir.r4.model.Task.TaskStatus
import org.smartregister.fhircore.engine.sync.OnSyncListener
import org.smartregister.fhircore.engine.sync.SyncListenerManager
import org.smartregister.fhircore.engine.ui.theme.AppTheme
import org.smartregister.fhircore.engine.ui.theme.SearchHeaderColor
import org.smartregister.fhircore.quest.R
import org.smartregister.fhircore.quest.event.EventBus
import org.smartregister.fhircore.quest.ui.main.AppMainViewModel
import org.smartregister.fhircore.quest.ui.register.patients.GenericActivityArg
import org.smartregister.fhircore.quest.ui.register.patients.RegisterViewModel
import org.smartregister.fhircore.quest.util.TaskProgressState
import org.smartregister.fhircore.quest.util.TaskProgressStatusDisplay
import javax.inject.Inject

@ExperimentalMaterialApi
@AndroidEntryPoint
class ViewAllTasksFragment : Fragment(), OnSyncListener {

    @Inject
    lateinit var syncListenerManager: SyncListenerManager

    @Inject
    lateinit var eventBus: EventBus
    private val appMainViewModel by activityViewModels<AppMainViewModel>()

    //private val registerFragmentArgs by navArgs<ViewAllTasksFragmentArgs>()
    private val registerViewModel by viewModels<RegisterViewModel>()
    val tasksViewModel by viewModels<TasksViewModel>()

    private var taskPriority: TaskProgressState = TaskProgressState.NONE
    var taskStatus: TaskStatus = TaskStatus.REQUESTED
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
            taskPriority = when (priority) {
                TaskProgressState.NOT_CONTACTED.name -> TaskProgressState.NOT_CONTACTED
                TaskProgressState.NOT_AGREED_FOR_FOLLOWUP.name -> TaskProgressState.NOT_AGREED_FOR_FOLLOWUP
                TaskProgressState.NOT_RESPONDED.name -> TaskProgressState.NOT_RESPONDED
                TaskProgressState.AGREED_FOLLOWUP_NOT_DONE.name -> TaskProgressState.AGREED_FOLLOWUP_NOT_DONE
                else -> TaskProgressState.NONE
            }

            when (status) {
                "REQUESTED" -> taskStatus = TaskStatus.REQUESTED
                "INPROGRESS" -> taskStatus = TaskStatus.INPROGRESS
                "COMPLETED" -> taskStatus = TaskStatus.COMPLETED
                else -> {}
            }
        }

        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val scaffoldState = rememberScaffoldState()

                AppTheme {
                    // Register screen provides access to the side navigation
                    Scaffold(
                        modifier = Modifier.background(SearchHeaderColor),
                        scaffoldState = scaffoldState,
                        topBar = {},
                        bottomBar = {},
                        snackbarHost = { snackBarHostState -> },
                    ) { innerPadding ->
                        Box(
                            modifier = Modifier
                              .padding(innerPadding)
                              .background(SearchHeaderColor)
                              .testTag(REGISTER_SCREEN_BOX_TAG)
                        ) {

                            ViewAllTasksScreen(
                                viewModel = tasksViewModel,
                                screenTitle = getScreenTitle(screenTitle),
                                taskStatus = taskStatus,
                                registerViewModel = registerViewModel,
                                taskPriority = taskPriority
                            ) {
                                activity?.finish()
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun getScreenTitle(labelName: String): String {
        return if (labelName.equals(TaskProgressStatusDisplay.NOT_RESPONDED.text, true)) {
            stringResource(id = R.string.not_responded)
        } else if (labelName.equals(TaskProgressStatusDisplay.NOT_CONTACTED.text, true)) {
            stringResource(id = R.string.not_contacted)
        } else if (labelName.equals(TaskProgressStatusDisplay.NOT_AGREED_FOR_FOLLOWUP.text, true)) {
            stringResource(id = R.string.not_agreed_for_followup)
        } else if (labelName.equals(TaskProgressStatusDisplay.AGREED_FOLLOWUP_NOT_DONE.text, true)) {
            stringResource(id = R.string.agreed_followup_not_done)
        } else if (labelName.equals(TaskProgressStatusDisplay.FOLLOWUP_DONE.text, true)) {
            stringResource(id = R.string.followup_done)
        } else if (labelName.equals(TaskProgressStatusDisplay.REMOVE_CASE.text, true)) {
            stringResource(id = R.string.remove_case)
        } else if (labelName.equals(TaskProgressStatusDisplay.NONE.text, true)) {
            stringResource(id = R.string.none_status)
        } else if (labelName.equals(TaskProgressState.DEFAULT.text, true)) {
            stringResource(id = R.string.default_status)
        } else if (labelName.equals(TaskProgressState.REMOVE.text, true)) {
            stringResource(id = R.string.remove_status)
        } else {
            stringResource(id = R.string.null_status)
        }
    }

    override fun onResume() {
        super.onResume()
        tasksViewModel.getAllLatestTasks()
    }

    override fun onStop() {
        super.onStop()
    }

    override fun onSync(syncJobStatus: CurrentSyncJobStatus) {

    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {

    }


    companion object {
        fun newInstance(bundle: Bundle) = ViewAllTasksFragment().apply {
            arguments = bundle
        }

        const val REGISTER_SCREEN_BOX_TAG = "fragmentRegisterScreenTestTag"
    }
}
