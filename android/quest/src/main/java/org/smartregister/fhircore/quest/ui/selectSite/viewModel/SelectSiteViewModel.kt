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
import org.smartregister.fhircore.engine.data.remote.selectSite.TenantOption
import org.smartregister.fhircore.engine.domain.networkUtils.HttpConstants.SELECT_YOUR_SITE_URL
import org.smartregister.fhircore.engine.domain.repository.SelectYourSiteRepository
import org.smartregister.fhircore.engine.util.SecureSharedPreference
import org.smartregister.fhircore.engine.util.SharedPreferencesHelper
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

    private val _tenantOptions = mutableLiveData(ArrayList<TenantOption>())
    val tenantOptions: LiveData<ArrayList<TenantOption>>
        get() = _tenantOptions

    var selectedOption: MutableState<TenantOption?> = mutableStateOf(null)
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
                val options = ArrayList<TenantOption>().apply {
                    response.forEach { (code, config) ->
                        config.code = code
                        config.tenants.orEmpty().forEach { site ->
                            add(TenantOption(config, site))
                        }
                    }
                }
                _tenantOptions.postValue(options)
                isLoading.postValue(false)
            } catch (e: Exception) {
                isLoading.postValue(false)
                mError.postValue(e.message)
            }
        }
    }

    /** Records the tenant the user picked from the flattened dropdown, along with its parent server. */
    fun selectOption(option: TenantOption) {
        selectedOption.value = option
        selectedServer.value = option.server
        selectedSite.value = option.site
    }

    fun setSelectSite(selectSite: SelectSite) {
        selectedSite.value = selectSite

        val fhirBaseUrl = selectSite.fhirBaseUrl
        val oauthBaseUrl = selectSite.authBaseUrl
        val multiTenant = selectedServer.value?.multiTenant == true

        secureSharedPreference.saveUrls(fhirBaseUrl, oauthBaseUrl)
        sharedPreferencesHelper.saveUrls(fhirBaseUrl, oauthBaseUrl)
        secureSharedPreference.saveSiteName(selectSite.name)
        sharedPreferencesHelper.saveSiteName(selectSite.name)
        sharedPreferencesHelper.saveTenant(selectSite.code, multiTenant)
    }
}
