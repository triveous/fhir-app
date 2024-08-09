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

    fun convertToDateStringFromString(input: String): String {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX") // Adjust format if necessary
        val outputFormat = SimpleDateFormat("dd MMMM yyyy HH:mm", Locale.getDefault())

        val dateObj: Date? = try {
            format.parse(input)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }

        return dateObj?.let { outputFormat.format(it) }.orEmpty()
    }

    fun convertToDateStringToDate(input: String): Date {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX") // Adjust format if necessary
        val outputFormat = SimpleDateFormat("dd MMMM yyyy HH:mm", Locale.getDefault())

        val dateObj: Date? = try {
            format.parse(input)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }

        return dateObj ?: Date()
    }
}