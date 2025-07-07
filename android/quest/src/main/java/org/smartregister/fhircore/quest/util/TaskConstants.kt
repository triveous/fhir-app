package org.smartregister.fhircore.quest.util

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