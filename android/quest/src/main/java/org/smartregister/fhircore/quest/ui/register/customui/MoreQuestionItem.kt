package org.smartregister.fhircore.quest.ui.register.customui

import android.content.Context
import com.google.android.fhir.datacapture.extensions.asStringValue
import com.google.android.fhir.datacapture.validation.Invalid
import com.google.android.fhir.datacapture.validation.NotValidated
import com.google.android.fhir.datacapture.validation.Valid
import com.google.android.fhir.datacapture.validation.ValidationResult
import com.google.android.fhir.datacapture.views.QuestionnaireViewItem
import org.smartregister.fhircore.engine.util.SharedPreferencesHelper

fun getValidationErrorMessage(
    context: Context,
    questionnaireViewItem: QuestionnaireViewItem,
    validationResult: ValidationResult,
    sharedPreferencesHelper: SharedPreferencesHelper
): String? {
    return when (validationResult) {
        is NotValidated,
        Valid,
        -> null

        is Invalid -> {
            var requiredTextExtensionString: String? = ""
            var validationTextExtensionString: String? = ""
            val validationTextExtension =
                questionnaireViewItem.questionnaireItem.getExtensionByUrl("http://hl7.org/fhir/StructureDefinition/validationtext")
            val requiredTextExtension =
                questionnaireViewItem.questionnaireItem.getExtensionByUrl("http://hl7.org/fhir/StructureDefinition/requiredtext")
            val languageCode = sharedPreferencesHelper.getLanguageCode()
            println("languageCode --> $languageCode")

            //extension.value.extension.get(1).extension.get(1).value
            validationTextExtension?.let { extension ->
                val translations = extension.value.extension
                for (translation in translations) {
                    val langCode = translation.getExtensionByUrl("lang")?.value?.asStringValue()
                    if (langCode == languageCode) {
                        validationTextExtensionString =
                            translation.getExtensionByUrl("content")?.value?.toString()
                    }
                }
            }

            requiredTextExtension?.let { extension ->
                val translations = extension.value.extension
                for (translation in translations) {
                    val langCode = translation.getExtensionByUrl("lang")?.value?.asStringValue()
                    if (langCode == languageCode) {
                        requiredTextExtensionString =
                            translation.getExtensionByUrl("content")?.value?.toString()
                    }
                }
            }

            if (questionnaireViewItem.questionnaireItem.required && questionnaireViewItem.answers.isEmpty()) {
                println("requiredTextExtension?.value?.asStringValue() --> ${requiredTextExtension?.value?.asStringValue()}")
                if (!requiredTextExtensionString.isNullOrEmpty()) {
                    requiredTextExtensionString
                } else {
                    validationResult.getSingleStringValidationMessage()
                }
            } else {
                println("validationTextExtension?.value?.asStringValue() --> ${validationTextExtension?.value?.asStringValue()}")
                if (!validationTextExtensionString.isNullOrEmpty()) {
                    validationTextExtensionString
                } else {
                    validationResult.getSingleStringValidationMessage()
                }

            }
        }
    }
}