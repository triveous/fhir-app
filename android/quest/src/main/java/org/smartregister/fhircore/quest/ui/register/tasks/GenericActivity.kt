package org.smartregister.fhircore.quest.ui.register.tasks

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material.ExperimentalMaterialApi
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import dagger.hilt.android.AndroidEntryPoint
import org.smartregister.fhircore.quest.R
import org.smartregister.fhircore.quest.ui.register.patients.ViewAllPatientsFragment
import org.smartregister.fhircore.quest.ui.register.profile.ProfileSectionFragment
import org.smartregister.fhircore.quest.ui.register.tasks.GenericActivityArg.ARG_FROM
import org.smartregister.fhircore.quest.ui.register.tasks.GenericActivityArg.FROM_PATIENTS
import org.smartregister.fhircore.quest.ui.register.tasks.GenericActivityArg.FROM_PROFILE
import org.smartregister.fhircore.quest.ui.register.tasks.GenericActivityArg.FROM_TASKS
import org.smartregister.fhircore.quest.ui.register.tasks.GenericActivityArg.SCREEN_TITLE
import org.smartregister.fhircore.quest.ui.register.tasks.GenericActivityArg.TASK_PRIORITY
import org.smartregister.fhircore.quest.ui.register.tasks.GenericActivityArg.TASK_STATUS


object GenericActivityArg {

    const val FROM_TASKS = "tasks"
    const val FROM_PATIENTS = "patients"
    const val FROM_PROFILE = "profile"

    const val ARG_FROM = "from"
    const val SCREEN_TITLE = "screenTitle"
    const val TASK_STATUS = "taskStatus"
    const val TASK_PRIORITY = "taskPriority"

}

@AndroidEntryPoint
@ExperimentalMaterialApi
class GenericActivity : AppCompatActivity() {
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
                loadFragment(ViewAllPatientsFragment.newInstance(bundleOf()))
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
}