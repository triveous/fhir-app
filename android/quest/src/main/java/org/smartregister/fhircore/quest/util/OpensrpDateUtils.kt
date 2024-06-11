package org.smartregister.fhircore.quest.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object OpensrpDateUtils {

    fun convertToDate(input: Date): String {
        val outputFormat = SimpleDateFormat("dd MMMM yyyy HH:mm", Locale.getDefault())

        // Format the date to the desired output format
        return outputFormat.format(input)
    }
}