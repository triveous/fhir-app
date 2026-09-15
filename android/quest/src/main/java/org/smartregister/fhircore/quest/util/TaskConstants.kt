package org.smartregister.fhircore.quest.util

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import org.smartregister.fhircore.quest.R
import org.smartregister.fhircore.quest.ui.register.tasks.TaskCode

enum class TaskProgressStatusDisplay(val text: String) {
    NOT_RESPONDED("Not responded"),
    NOT_CONTACTED("Not contacted"),
    NOT_AGREED_FOR_FOLLOWUP("Didn't agree for follow up"),
    AGREED_FOLLOWUP_NOT_DONE("Agreed, follow up not done"),
    FOLLOWUP_DONE("Follow up done"),
    FOLLOWUP_NOT_DONE("Follow up not done"),
    REMOVE_CASE("Not agreed, remove case"),
    NULL("null"),
    NONE("none"),
    DEFAULT("none")
}

// `.text` above is the persisted/compared English value (stored on the FHIR Task and used in
// equals() checks) and must stay fixed; this is only for what gets rendered on screen.
@Composable
fun TaskProgressStatusDisplay.localizedText(): String =
    when (this) {
        TaskProgressStatusDisplay.NOT_RESPONDED -> stringResource(R.string.not_responded)
        TaskProgressStatusDisplay.NOT_CONTACTED -> stringResource(R.string.not_contacted)
        TaskProgressStatusDisplay.NOT_AGREED_FOR_FOLLOWUP ->
            stringResource(R.string.not_agreed_for_followup)
        TaskProgressStatusDisplay.AGREED_FOLLOWUP_NOT_DONE ->
            stringResource(R.string.agreed_followup_not_done)
        TaskProgressStatusDisplay.FOLLOWUP_DONE -> stringResource(R.string.followup_done)
        TaskProgressStatusDisplay.FOLLOWUP_NOT_DONE -> stringResource(R.string.followup_not_done)
        TaskProgressStatusDisplay.REMOVE_CASE -> stringResource(R.string.remove_case)
        TaskProgressStatusDisplay.NULL -> stringResource(R.string.null_status)
        TaskProgressStatusDisplay.NONE -> stringResource(R.string.none_status)
        TaskProgressStatusDisplay.DEFAULT -> stringResource(R.string.none_status)
    }



enum class TaskProgressState(val text: String) {
    NOT_CONTACTED("NOT_CONTACTED"),
    NOT_RESPONDED("NOT_RESPONDED"),
    AGREED_FOLLOWUP_NOT_DONE("AGREED_FOLLOWUP_NOT_DONE"),
    NOT_AGREED_FOR_FOLLOWUP("NOT_AGREED_FOR_FOLLOWUP"),
    FOLLOWUP_DONE("FOLLOWUP_DONE"),
    FOLLOWUP_NOT_DONE("FOLLOWUP_NOT_DONE"),
    DEFAULT("DEFAULT"),
    NONE("NONE"),
    REMOVE("REMOVE")
}

object SectionTitles {
    const val NOT_CONTACTED = "NOT CONTACTED"
    const val NOT_RESPONDED = "NOT RESPONDED"
    const val AGREED_FOLLOWUP_NOT_DONE = "AGREED FOR FOLLOW UP"
    const val NOT_AGREED_FOR_FOLLOWUP = "NOT AGREED FOR FOLLOWUP"

}

// Task.description is server-generated content (from the care-plan/PlanDefinition backend), not
// an app string resource, so it can't carry a FHIR translation extension the way the
// Questionnaire does. This maps the known English values we see in practice to a localized
// resource; anything unrecognized (a new/changed server value) falls back to the raw text as-is
// rather than disappearing.
@Composable
fun localizedTaskDescription(description: String?): String {
    if (description.isNullOrBlank()) return ""
    val taskCode = TaskCode.fromCode(description)
    if (taskCode != null) {
        return taskCode.localizedLabel()
    }
    return when (description.trim()) {
        "Follow up: Refer to hospital for additional investigation" ->
            stringResource(R.string.task_desc_refer_hospital_investigation)
        "Follow up: Retake photo" -> stringResource(R.string.task_desc_retake_photo)
        "Follow up: Advice to quit the habit" ->
            stringResource(R.string.task_desc_advice_quit_habit)
        "Task for specialist to review patient diagnosis" ->
            stringResource(R.string.task_desc_specialist_review)
        "Task for specialist to screen patient" ->
            stringResource(R.string.task_desc_specialist_screen)
        else -> description
    }
}