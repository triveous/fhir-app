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
import org.smartregister.fhircore.engine.data.remote.selectSite.SelectYourSiteResponse
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
    private var mResponse = mutableLiveData(SelectYourSiteResponse())

    private val _selectSiteList = mutableLiveData(ArrayList<SelectSite>())
    val selectSiteList: LiveData<ArrayList<SelectSite>>
        get() = _selectSiteList

    // State to manage the selected site
    var selectedSite: MutableState<SelectSite?> = mutableStateOf(null)
    var isTest: Boolean= false

    init {
        getSelectSites()
    }

    private fun getSelectSites(){
        viewModelScope.launch(Dispatchers.IO) {
            isLoading.postValue(true)
            try {
                val response = selectYourSiteRepository.getSelectYourSites(SELECT_YOUR_SITE_URL)
                mResponse.postValue(response)
                val list= ArrayList<SelectSite>()
                response.sites?.let { list.addAll(it) }
                _selectSiteList.postValue(list)
                viewModelScope.launch(Dispatchers.Main) {
                    selectedSite.value = list[0]
                }
                isLoading.postValue(false)
            } catch (e: Exception) {
                isLoading.postValue(false)
                mError.postValue(e.message)
            }
        }
    }

    fun setSelectSite(selectSite: SelectSite) {
        selectedSite.value = selectSite

        val fhirBaseUrl = getFhirBaseUrl(selectSite,isTest)
        val oauthBaseUrl = getOAuthBaseUrl(selectSite,isTest)

        secureSharedPreference.saveUrls(fhirBaseUrl, oauthBaseUrl)
        sharedPreferencesHelper.saveUrls(fhirBaseUrl, oauthBaseUrl)
        secureSharedPreference.saveSiteName(selectSite.name)
        sharedPreferencesHelper.saveSiteName(selectSite.name)
    }

    private fun getFhirBaseUrl(selectSite: SelectSite, isTest: Boolean = false): String? {
        return if (BuildConfig.BUILD_TYPE.equals("release",true) || isTest) {
            println("BuildConfig.DEBUG--> true")
            selectSite.fhirBaseUrl
        } else {
            println("BuildConfig.DEBUG-->  false")
            STAGING_FHIR_BASE_URL
        }
    }

    private fun getOAuthBaseUrl(selectSite: SelectSite, isTest: Boolean = false): String? {
        return if (BuildConfig.BUILD_TYPE.equals("release",true) || isTest) {
            println("BuildConfig.DEBUG-->  true")
            selectSite.authBaseUrl
        } else {
            println("BuildConfig.DEBUG-->  false")
            STAGING_OAUTH_BASE_URL
        }
    }
}