package org.smartregister.fhircore.quest.ui.selectSite.viewModel

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.smartregister.fhircore.engine.data.remote.selectSite.SelectSite
import org.smartregister.fhircore.engine.data.remote.selectSite.ServerConfig
import org.smartregister.fhircore.engine.domain.networkUtils.HttpConstants.SELECT_YOUR_SITE_URL
import org.smartregister.fhircore.engine.domain.repository.SelectYourSiteRepository
import org.smartregister.fhircore.engine.util.SecureSharedPreference
import org.smartregister.fhircore.engine.util.SharedPreferencesHelper
import org.smartregister.fhircore.quest.BuildConfig
import org.smartregister.fhircore.quest.ui.selectSite.STAGING_FHIR_BASE_URL
import org.smartregister.fhircore.quest.ui.selectSite.STAGING_OAUTH_BASE_URL
import org.smartregister.fhircore.quest.util.mutableLiveData
import javax.inject.Inject

/**
 * Created by Jeetesh Surana.
 */


@HiltViewModel
class SelectSiteViewModel @Inject constructor(
    private val selectYourSiteRepository: SelectYourSiteRepository,
    val secureSharedPreference: SecureSharedPreference,
    val sharedPreferencesHelper: SharedPreferencesHelper
) : ViewModel() {
    var mError = mutableLiveData("")
    var isLoading = mutableLiveData(false)

    private val _serverList = mutableLiveData(ArrayList<ServerConfig>())
    val serverList: LiveData<ArrayList<ServerConfig>>
        get() = _serverList

    private val _tenantList = mutableLiveData(ArrayList<SelectSite>())
    val tenantList: LiveData<ArrayList<SelectSite>>
        get() = _tenantList

    var selectedServer: MutableState<ServerConfig?> = mutableStateOf(null)
    var selectedSite: MutableState<SelectSite?> = mutableStateOf(null)
    var isTest: Boolean = false

    init {
        getSelectSites()
    }

    private fun getSelectSites() {
        viewModelScope.launch(Dispatchers.IO) {
            isLoading.postValue(true)
            try {
                val response = selectYourSiteRepository.getSelectYourSites(SELECT_YOUR_SITE_URL)
                val servers = ArrayList<ServerConfig>().apply {
                    response.forEach { (code, config) ->
                        add(config.apply { this.code = code })
                    }
                }
                _serverList.postValue(servers)
                isLoading.postValue(false)
            } catch (e: Exception) {
                isLoading.postValue(false)
                mError.postValue(e.message)
            }
        }
    }

    /**
     * Sets the active server and updates the tenant list. Returns true when the server has exactly
     * one tenant — caller can use this to auto-advance past the tenant picker.
     */
    fun selectServer(server: ServerConfig): Boolean {
        selectedServer.value = server
        val tenants = ArrayList(server.tenants.orEmpty())
        _tenantList.postValue(tenants)
        selectedSite.value = tenants.firstOrNull()
        return tenants.size == 1
    }

    fun setSelectSite(selectSite: SelectSite) {
        selectedSite.value = selectSite

        val fhirBaseUrl = getFhirBaseUrl(selectSite, isTest)
        val oauthBaseUrl = getOAuthBaseUrl(selectSite, isTest)

        secureSharedPreference.saveUrls(fhirBaseUrl, oauthBaseUrl)
        sharedPreferencesHelper.saveUrls(fhirBaseUrl, oauthBaseUrl)
        secureSharedPreference.saveSiteName(selectSite.name)
        sharedPreferencesHelper.saveSiteName(selectSite.name)
    }

    private fun getFhirBaseUrl(selectSite: SelectSite, isTest: Boolean = false): String? {
        return if (BuildConfig.BUILD_TYPE.equals("release", true) || isTest) {
            selectSite.fhirBaseUrl
        } else {
            STAGING_FHIR_BASE_URL
        }
    }

    private fun getOAuthBaseUrl(selectSite: SelectSite, isTest: Boolean = false): String? {
        return if (BuildConfig.BUILD_TYPE.equals("release", true) || isTest) {
            selectSite.authBaseUrl
        } else {
            STAGING_OAUTH_BASE_URL
        }
    }
}
