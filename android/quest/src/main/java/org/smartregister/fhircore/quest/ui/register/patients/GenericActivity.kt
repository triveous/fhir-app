package org.smartregister.fhircore.quest.ui.register.patients

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material.ExperimentalMaterialApi
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.hl7.fhir.r4.model.IdType
import org.hl7.fhir.r4.model.QuestionnaireResponse
import org.smartregister.fhircore.engine.configuration.QuestionnaireConfig
import org.smartregister.fhircore.engine.util.extension.parcelable
import org.smartregister.fhircore.engine.util.extension.serializable
import org.smartregister.fhircore.quest.R
import org.smartregister.fhircore.quest.event.AppEvent
import org.smartregister.fhircore.quest.event.EventBus
import org.smartregister.fhircore.quest.ui.questionnaire.QuestionnaireActivity
import org.smartregister.fhircore.quest.ui.register.profile.ProfileSectionFragment
import org.smartregister.fhircore.quest.ui.register.patients.GenericActivityArg.ARG_FROM
import org.smartregister.fhircore.quest.ui.register.patients.GenericActivityArg.FROM_DRAFTS
import org.smartregister.fhircore.quest.ui.register.patients.GenericActivityArg.FROM_PATIENTS
import org.smartregister.fhircore.quest.ui.register.patients.GenericActivityArg.FROM_PROFILE
import org.smartregister.fhircore.quest.ui.register.patients.GenericActivityArg.FROM_TASKS
import org.smartregister.fhircore.quest.ui.register.patients.GenericActivityArg.SCREEN_TITLE
import org.smartregister.fhircore.quest.ui.register.patients.GenericActivityArg.TASK_PRIORITY
import org.smartregister.fhircore.quest.ui.register.patients.GenericActivityArg.TASK_STATUS
import org.smartregister.fhircore.quest.ui.register.tasks.ViewAllTasksFragment
import org.smartregister.fhircore.quest.ui.shared.QuestionnaireHandler
import org.smartregister.fhircore.quest.ui.shared.models.QuestionnaireSubmission
import timber.log.Timber
import javax.inject.Inject


object GenericActivityArg {

    const val FROM_TASKS = "tasks"
    const val FROM_PATIENTS = "patients"
    const val FROM_DRAFTS = "drafts"
    const val FROM_PROFILE = "profile"

    const val ARG_FROM = "from"
    const val SCREEN_TITLE = "screenTitle"
    const val TASK_STATUS = "taskStatus"
    const val TASK_PRIORITY = "taskPriority"

}

@AndroidEntryPoint
@ExperimentalMaterialApi
class GenericActivity : AppCompatActivity(), QuestionnaireHandler {
    @Inject
    lateinit var eventBus: EventBus

    override val startForResult =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { activityResult ->
            if (activityResult.resultCode == Activity.RESULT_OK) {
                lifecycleScope.launch { onSubmitQuestionnaire(activityResult) }
            }
        }

    @OptIn(ExperimentalMaterialApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_generic)
        val color = ContextCompat.getColor(this, R.color.simple_light_blue_500)
        window.statusBarColor = color
        val from = intent.getStringExtra(ARG_FROM)

        when(from){

            FROM_PATIENTS -> {
                loadFragment(ViewAllPatientsFragment.newInstance(bundleOf(
                    ARG_FROM to FROM_PATIENTS
                )))
            }

            FROM_DRAFTS -> {
                loadFragment(ViewAllPatientsFragment.newInstance(bundleOf(
                    ARG_FROM to FROM_DRAFTS
                )))
            }

            FROM_TASKS -> {
                val title = intent.getStringExtra(SCREEN_TITLE)
                val status = intent.getStringExtra(TASK_STATUS)
                val priority = intent.getStringExtra(TASK_PRIORITY)
                val bundle = bundleOf(
                    SCREEN_TITLE to title,
                    TASK_STATUS to status,
                    TASK_PRIORITY to priority
                )
                loadFragment(ViewAllTasksFragment.newInstance(bundle))
            }

            FROM_PROFILE -> {
                loadFragment(ProfileSectionFragment.newInstance(bundleOf()))
            }
        }

        // Load fragment based on argument
        /*if (fragmentName == "FragA") {
            loadFragment(FragmentA())
        } else if (fragmentName == "FragB") {
            loadFragment(FragmentB())
        } else {
            // Handle default case or invalid argument
        }
*/

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
    }

    private fun loadFragment(fragment: Fragment) {
        val fragmentManager = supportFragmentManager
        val transaction = fragmentManager.beginTransaction()
        transaction.replace(R.id.fragment_container, fragment) // Replace with your container ID
        transaction.commit()
    }

    override suspend fun onSubmitQuestionnaire(activityResult: ActivityResult) {
        if (activityResult.resultCode == RESULT_OK) {
            val questionnaireResponse: QuestionnaireResponse? =
                activityResult.data?.serializable(QuestionnaireActivity.QUESTIONNAIRE_RESPONSE)
                        as QuestionnaireResponse?
            val extractedResourceIds =
                activityResult.data?.serializable(
                    QuestionnaireActivity.QUESTIONNAIRE_SUBMISSION_EXTRACTED_RESOURCE_IDS,
                ) as List<IdType>? ?: emptyList()
            val questionnaireConfig =
                activityResult.data?.parcelable(QuestionnaireActivity.QUESTIONNAIRE_CONFIG)
                        as QuestionnaireConfig?

            if (questionnaireConfig != null && questionnaireResponse != null) {
                eventBus.triggerEvent(
                    AppEvent.OnSubmitQuestionnaire(
                        QuestionnaireSubmission(
                            questionnaireConfig = questionnaireConfig,
                            questionnaireResponse = questionnaireResponse,
                            extractedResourceIds = extractedResourceIds,
                        ),
                    ),
                )
            } else Timber.e("QuestionnaireConfig & QuestionnaireResponse are both null")
        }
    }
}