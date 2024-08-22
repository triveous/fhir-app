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

package org.smartregister.fhircore.quest.ui.register.profile

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.Scaffold
import androidx.compose.material.rememberScaffoldState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.testTag
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import com.google.android.fhir.sync.CurrentSyncJobStatus
import dagger.hilt.android.AndroidEntryPoint
import org.hl7.fhir.r4.model.Task.TaskPriority
import org.hl7.fhir.r4.model.Task.TaskStatus
import org.smartregister.fhircore.engine.sync.OnSyncListener
import org.smartregister.fhircore.engine.sync.SyncListenerManager
import org.smartregister.fhircore.engine.ui.theme.AppTheme
import org.smartregister.fhircore.engine.ui.theme.SearchHeaderColor
import org.smartregister.fhircore.quest.event.EventBus
import org.smartregister.fhircore.quest.ui.login.LoginActivity
import org.smartregister.fhircore.quest.ui.main.AppMainViewModel
import org.smartregister.fhircore.quest.ui.register.patients.RegisterViewModel
import javax.inject.Inject

@ExperimentalMaterialApi
@AndroidEntryPoint
class ProfileSectionFragment : Fragment(), OnSyncListener {

    @Inject lateinit var syncListenerManager: SyncListenerManager

    @Inject lateinit var eventBus: EventBus
    private val appMainViewModel by activityViewModels<AppMainViewModel>()
    //private val profileFragmentArgs by navArgs<ProfileSectionFragmentArgs>()
    private val registerViewModel by viewModels<RegisterViewModel>()
    var taskPriority : TaskPriority = TaskPriority.URGENT
    var taskStatus : TaskStatus = TaskStatus.REQUESTED
    var userNameText = ""
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        appMainViewModel.retrieveIconsAsBitmap()

        /*with(profileFragmentArgs){
            userNameText = userName
        }*/

        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
            val scaffoldState = rememberScaffoldState()



                AppTheme {

                    // Register screen provides access to the side navigation
                    Scaffold(
                        modifier = Modifier.background(SearchHeaderColor),
                        scaffoldState = scaffoldState,
                        snackbarHost = { snackBarHostState ->

                        },
                    ) { innerPadding ->

                        val isLogout by registerViewModel.isLogout.collectAsState()

                        LaunchedEffect(isLogout) {
                            if (isLogout){
                                val intent = Intent(requireActivity(), LoginActivity::class.java)
                                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                                startActivity(intent)
                                requireActivity().finishAffinity()
                            }
                        }

                        Box(modifier = Modifier
                            .padding(innerPadding)
                            .background(SearchHeaderColor)
                            .fillMaxSize()
                            .testTag(REGISTER_SCREEN_BOX_TAG)) {

                            ProfileSectionScreen(
                                onEvent = registerViewModel::onEvent,
                                registerUiState = registerViewModel.registerUiState.value,
                                searchText = registerViewModel.searchText,
                                appMainViewModel = appMainViewModel,
                                viewModel = registerViewModel,
                                userName = userNameText,
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
        //registerViewModel.getAllLatestTasks()
        //registerViewModel.getFilteredTasks(FilterType.URGENT_REFERRAL, taskStatus, taskPriority)
    }

    override fun onStop() {
        super.onStop()
        //registerViewModel.searchText.value = "" // Clear the search term
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
        fun newInstance(bundle: Bundle) = ProfileSectionFragment().apply {
            arguments = bundle
        }

        const val REGISTER_SCREEN_BOX_TAG = "fragmentRegisterScreenTestTag"
    }
}
