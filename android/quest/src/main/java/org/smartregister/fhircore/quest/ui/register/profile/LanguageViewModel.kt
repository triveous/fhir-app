package org.smartregister.fhircore.quest.ui.register.profile

import android.app.Application
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.smartregister.fhircore.engine.util.SecureSharedPreference
import org.smartregister.fhircore.engine.util.SharedPreferenceKey
import org.smartregister.fhircore.engine.util.SharedPreferencesHelper
import org.smartregister.fhircore.quest.util.LanguageCodeConstants
import org.smartregister.fhircore.quest.util.LanguageConstants
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class LanguageViewModel @Inject constructor(
    private val application: Application,
    val secureSharedPreference: SecureSharedPreference,
    val sharedPreferencesHelper: SharedPreferencesHelper
) : AndroidViewModel(application) {

    private val appContext: Context = application.applicationContext

    private val _languages = MutableStateFlow(
        listOf(
            LanguageConstants.ENGLISH,
            LanguageConstants.HINDI,
            LanguageConstants.BENGALI,
            LanguageConstants.TELUGU,
            LanguageConstants.MARATHI,
            LanguageConstants.TAMIL,
            LanguageConstants.URDU
        )
    )

    private val languagesCode = MutableStateFlow(
        listOf(
            LanguageCodeConstants.ENGLISH_CODE,
            LanguageCodeConstants.HINDI_CODE,
            LanguageCodeConstants.BENGALI_CODE,
            LanguageCodeConstants.TELUGU_CODE,
            LanguageCodeConstants.MARATHI_CODE,
            LanguageCodeConstants.TAMIL_CODE,
            LanguageCodeConstants.URDU_CODE
        )
    )

    val languages: StateFlow<List<String>> = _languages

    private val _selectedLanguage = MutableStateFlow(getLanguage() ?: languages.value[0])
    val selectedLanguage: StateFlow<String> = _selectedLanguage

    fun selectLanguage(language: String) {
        viewModelScope.launch {
            _selectedLanguage.emit(language)
        }
    }

    fun updateLanguage() {
        val langCode = languagesCode.value[getLanguagePosition()]
        sharedPreferencesHelper.write(SharedPreferenceKey.KEY_LANGUAGE.name, selectedLanguage.value)
        sharedPreferencesHelper.write(SharedPreferenceKey.KEY_LANGUAGE_CODE.name, langCode)
        secureSharedPreference.setChangeLanguage(selectedLanguage.value,langCode)
        updateResources(appContext,langCode)
    }

    private fun getLanguage(): String? {
        return sharedPreferencesHelper.read(SharedPreferenceKey.KEY_LANGUAGE.name, LanguageConstants.ENGLISH)
    }

    private fun getLanguagePosition(): Int {
        return languages.value.indexOf(selectedLanguage.value).coerceAtLeast(0)
    }

    private fun updateResources(context: Context?, languageCode: String) {
        context?.let { updateResource(it,languageCode) }
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.create(Locale(languageCode)))
    }

    private fun updateResource(context: Context, language: String): Context {
        val locale = Locale(language)
        Locale.setDefault(locale)
        val configuration = context.resources.configuration
        configuration.setLocale(locale)
        configuration.setLayoutDirection(locale)
        return context.createConfigurationContext(configuration)
    }
}
