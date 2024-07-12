package org.smartregister.fhircore.quest.util

enum class TaskProgressStatus(val text: String) {
    ASAP("Not responded"),
    URGENT("Didn't agree for follow up"),
    STAT("Agreed, Follow up not done"),
    ROUTINE("Follow up done"),
    NULL("null"),
    NONE("none"),
    DEFAULT("none")
}