package org.smartregister.fhircore.quest.di.config

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import org.smartregister.fhircore.engine.configuration.app.AuthConfiguration
import org.smartregister.fhircore.engine.util.SharedPreferencesHelper
import org.smartregister.fhircore.quest.BuildConfig
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Created by Jeetesh Surana.
 */
@Singleton
class AuthConfigurationHelper@Inject constructor(val sharedPreferencesHelper: SharedPreferencesHelper) {

    private val _authConfiguration = MutableLiveData<AuthConfiguration>()
    val authConfiguration: LiveData<AuthConfiguration> get() = _authConfiguration

    init {
        _authConfiguration.value = AuthConfiguration(
            fhirServerBaseUrl = sharedPreferencesHelper.getFhirBaseUrl(),
            oauthServerBaseUrl = sharedPreferencesHelper.getOauthBaseUrl(),
            clientId = BuildConfig.OAUTH_CLIENT_ID,
            accountType = BuildConfig.APPLICATION_ID,
        )
    }

    fun getUpdateAuthConfiguration(){
        _authConfiguration.value = AuthConfiguration(
            fhirServerBaseUrl = sharedPreferencesHelper.getFhirBaseUrl(),
            oauthServerBaseUrl = sharedPreferencesHelper.getOauthBaseUrl(),
            clientId = BuildConfig.OAUTH_CLIENT_ID,
            accountType = BuildConfig.APPLICATION_ID,
        )
    }

}