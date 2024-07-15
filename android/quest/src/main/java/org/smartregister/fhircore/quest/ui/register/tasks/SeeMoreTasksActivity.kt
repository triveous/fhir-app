package org.smartregister.fhircore.quest.ui.register.tasks

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.Scaffold
import androidx.compose.material.rememberScaffoldState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import dagger.hilt.android.AndroidEntryPoint
import org.hl7.fhir.r4.model.Task
import org.smartregister.fhircore.engine.domain.model.ToolBarHomeNavigation
import org.smartregister.fhircore.engine.ui.theme.AppTheme
import org.smartregister.fhircore.engine.ui.theme.SearchHeaderColor
import org.smartregister.fhircore.quest.navigation.NavigationArg
import org.smartregister.fhircore.quest.ui.register.patients.TOP_REGISTER_SCREEN_TEST_TAG
import org.smartregister.fhircore.quest.ui.register.patients.GenericActivityArg.TASK_PRIORITY
import org.smartregister.fhircore.quest.ui.register.patients.GenericActivityArg.TASK_STATUS

@AndroidEntryPoint
@ExperimentalMaterialApi
class SeeMoreTasksActivity : AppCompatActivity() {

    val tasksViewModel by viewModels<TasksViewModel>()
    private var taskPriority : Task.TaskPriority = Task.TaskPriority.URGENT
    var taskStatus : Task.TaskStatus = Task.TaskStatus.REQUESTED
    var screenTitle = ""
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        with(intent.extras){
            screenTitle = this?.getString(NavigationArg.SCREEN_TITLE, "").toString()
            val status = this?.getString(TASK_STATUS, "").toString()
            val priority = this?.getString(TASK_PRIORITY, "").toString()

            when(priority){
                "ROUTINE" -> taskPriority = Task.TaskPriority.ROUTINE
                "URGENT" -> taskPriority = Task.TaskPriority.URGENT
                "ASAP" -> taskPriority = Task.TaskPriority.ASAP
                "STAT" -> taskPriority = Task.TaskPriority.STAT
                "NULL" -> taskPriority = Task.TaskPriority.NULL
            }

            when(status){
                "REQUESTED" -> taskStatus = Task.TaskStatus.REQUESTED
                "INPROGRESS" -> taskStatus = Task.TaskStatus.INPROGRESS
                "COMPLETED" -> taskStatus = Task.TaskStatus.COMPLETED
                else -> {}
            }
        }

        setContent {
            AppTheme {
                val scaffoldState = rememberScaffoldState()

                Scaffold(
                    modifier = Modifier.background(SearchHeaderColor).statusBarsPadding(),
                    scaffoldState = scaffoldState,
                    topBar = {
                        Box(modifier = Modifier) {
                            TasksTopScreenSection(
                                modifier = Modifier.testTag(TOP_REGISTER_SCREEN_TEST_TAG),
                                title = screenTitle,
                                toolBarHomeNavigation = ToolBarHomeNavigation.NAVIGATE_BACK,
                            ) { event ->
                                finish()
                                //navController.popBackStack()
                            }
                        }
                    },
                    snackbarHost = { snackBarHostState ->

                    },
                ) { innerPadding ->
                    Box(modifier = Modifier
                        .background(SearchHeaderColor).padding(innerPadding)
                        ) {
                        /*ViewAllTasksScreen(
                            viewModel = tasksViewModel,
                            screenTitle = screenTitle,
                            taskStatus = taskStatus,
                            taskPriority = taskPriority
                        ){
                            finish()
                        }*/
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        tasksViewModel.getAllLatestTasks()
    }
}