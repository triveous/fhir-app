package org.smartregister.fhircore.quest.util

import com.google.android.fhir.datacapture.extensions.asStringValue
import org.hl7.fhir.r4.model.Patient
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object OpensrpDateUtils {

    fun convertToDate(input: Date): String {
        val outputFormat = SimpleDateFormat("dd MMMM yyyy hh:mm a", Locale.getDefault())
        outputFormat.timeZone = TimeZone.getDefault()
        // Format the date to the desired output format
        return outputFormat.format(input)
    }

    fun getRegistrationDateFromExtension(patient: Patient?): String {
        val extension = patient?.extension?.find { it.url?.substringAfterLast("/").equals("patient-registraion-date") }
        if(extension != null && extension.value?.asStringValue()?.isNotEmpty() == true){
            val date = convertToDateStringToDate(extension.value?.asStringValue())
            date?.let {
                return convertToDate(date)
            }
        }
        return ""
    }


    fun convertToDateStringFromString(input: String): String {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.getDefault())
        val outputFormat = SimpleDateFormat("dd MMMM yyyy HH:mm", Locale.getDefault())
        outputFormat.timeZone = TimeZone.getDefault()
        val dateObj: Date? = try {
            format.parse(input)
        } catch (exception: Exception) {
            Timber.e(exception, "An error occurred while convertToDateStringFromString")
            null
        }

        return dateObj?.let { outputFormat.format(it) }.orEmpty()
    }

    fun convertToDateStringToDate(input: String?): Date? {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.getDefault())
        format.timeZone = TimeZone.getDefault()
        val dateObj: Date? = try {
            format.parse(input)
        } catch (exception: Exception) {
            Timber.e(exception, "An error occurred while convertToDateStringToDate")
            null
        }
        return dateObj
    }
}