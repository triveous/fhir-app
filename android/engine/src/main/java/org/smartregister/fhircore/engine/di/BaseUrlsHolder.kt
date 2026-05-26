package org.smartregister.fhircore.engine.di

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import org.smartregister.fhircore.engine.util.SecureSharedPreference
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
open class BaseUrlsHolder @Inject constructor(val secureSharedPreference: SecureSharedPreference) {
    private val _fhirServerBaseUrl = MutableLiveData<String>("")
    val fhirServerBaseUrl: LiveData<String> get() = _fhirServerBaseUrl

    private val _oauthServerBaseUrl = MutableLiveData<String>("")
    val oauthServerBaseUrl: LiveData<String> get() = _oauthServerBaseUrl

    init {
        getUpdatedData()
    }

    fun getUpdatedData() {
        val fhir = secureSharedPreference.getFhirBaseUrl()
        val oauth = secureSharedPreference.getOauthBaseUrl()
        Timber.i("BaseUrlsHolder loaded fhirBaseUrl=%s oauthBaseUrl=%s", fhir, oauth)
        _fhirServerBaseUrl.value = fhir
        _oauthServerBaseUrl.value = oauth
    }
}
