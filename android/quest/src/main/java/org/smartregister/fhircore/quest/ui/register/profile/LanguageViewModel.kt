package org.smartregister.fhircore.quest.ui.register.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.smartregister.fhircore.engine.util.SecureSharedPreference
import org.smartregister.fhircore.engine.util.SharedPreferenceKey
import org.smartregister.fhircore.engine.util.SharedPreferencesHelper
import org.smartregister.fhircore.quest.util.LanguageConstants
import javax.inject.Inject

@HiltViewModel
class LanguageViewModel @Inject constructor(
    val secureSharedPreference: SecureSharedPreference,
    val sharedPreferencesHelper: SharedPreferencesHelper
) : ViewModel() {

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

    val languages: StateFlow<List<String>> = _languages

    private val _selectedLanguage = MutableStateFlow(getLanguage() ?: languages.value[0])
    val selectedLanguage: StateFlow<String> = _selectedLanguage

    fun selectLanguage(language: String) {
        viewModelScope.launch {
            _selectedLanguage.emit(language)
        }
    }

    fun updateLanguage() {
        sharedPreferencesHelper.write(SharedPreferenceKey.KEY_LANGUAGE.name, selectedLanguage.value)
    }

    private fun getLanguage(): String? {
        return sharedPreferencesHelper.read(
            SharedPreferenceKey.KEY_LANGUAGE.name,
            LanguageConstants.ENGLISH
        )
    }

    private fun getLanguagePosition(): Int {
        return languages.value.indexOf(getLanguage()).coerceAtLeast(0)
    }
}
