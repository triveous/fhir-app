package org.smartregister.fhircore.engine.di

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import org.smartregister.fhircore.engine.util.STAGING_FHIR_BASE_URL
import org.smartregister.fhircore.engine.util.STAGING_OAUTH_BASE_URL
import org.smartregister.fhircore.engine.util.SecureSharedPreference
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
open class BaseUrlsHolder @Inject constructor(val secureSharedPreference: SecureSharedPreference) {
    private val _fhirServerBaseUrl = MutableLiveData<String>(STAGING_FHIR_BASE_URL)
    val fhirServerBaseUrl: LiveData<String> get() = _fhirServerBaseUrl

    private val _oauthServerBaseUrl = MutableLiveData<String>(STAGING_OAUTH_BASE_URL)
    val oauthServerBaseUrl: LiveData<String> get() = _oauthServerBaseUrl

    init {
        getUpdatedData()
    }

    fun getUpdatedData() {
        _fhirServerBaseUrl.value = secureSharedPreference.getFhirBaseUrl()
        _oauthServerBaseUrl.value = secureSharedPreference.getOauthBaseUrl()
    }
}