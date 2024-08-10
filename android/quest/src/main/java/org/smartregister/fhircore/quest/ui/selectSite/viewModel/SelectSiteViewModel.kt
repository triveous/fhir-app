package org.smartregister.fhircore.quest.ui.selectSite.viewModel

import android.app.Application
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import dagger.hilt.android.lifecycle.HiltViewModel
import org.smartregister.fhircore.engine.util.SecureSharedPreference
import org.smartregister.fhircore.engine.util.SharedPreferencesHelper
import org.smartregister.fhircore.quest.BuildConfig
import org.smartregister.fhircore.quest.R
import org.smartregister.fhircore.quest.data.local.selectSite.SelectSite
import org.smartregister.fhircore.quest.ui.selectSite.STAGING_FHIR_BASE_URL
import org.smartregister.fhircore.quest.ui.selectSite.STAGING_OAUTH_BASE_URL
import org.smartregister.fhircore.quest.ui.selectSite.URL_COMMON_FHIR
import org.smartregister.fhircore.quest.ui.selectSite.URL_COMMON_OAUTH
import org.smartregister.fhircore.quest.ui.selectSite.URL_SITE_1
import org.smartregister.fhircore.quest.ui.selectSite.URL_SITE_2
import org.smartregister.fhircore.quest.ui.selectSite.URL_SITE_3
import org.smartregister.fhircore.quest.ui.selectSite.URL_SITE_4
import org.smartregister.fhircore.quest.ui.selectSite.URL_SITE_5
import org.smartregister.fhircore.quest.ui.selectSite.URL_SITE_6
import org.smartregister.fhircore.quest.ui.selectSite.URL_SITE_7
import org.smartregister.fhircore.quest.ui.selectSite.URL_SITE_8
import org.smartregister.fhircore.quest.util.mutableLiveData
import javax.inject.Inject

/**
 * Created by Jeetesh Surana.
 */


@HiltViewModel
class SelectSiteViewModel @Inject constructor(
    val context: Application,
    val secureSharedPreference: SecureSharedPreference,
    val sharedPreferencesHelper: SharedPreferencesHelper
) : AndroidViewModel(context) {

    private val _selectSiteList = mutableLiveData(ArrayList<SelectSite>())
    val selectSiteList: LiveData<ArrayList<SelectSite>>
        get() = _selectSiteList

    // State to manage the selected site
    var selectedSite: MutableState<SelectSite?>
    var isTest: Boolean=true

    init {
        val context = context.applicationContext

        _selectSiteList.value = arrayListOf(
            SelectSite(
                name = context.getString(R.string.kle_dental_sciences),
                district = context.getString(R.string.district_bengaluru),
                state = context.getString(R.string.state_karnataka),
                backendUrl = URL_SITE_1,
                fhirBaseUrl = URL_SITE_1 + URL_COMMON_FHIR,
                oAuthBaseUrl = URL_SITE_1 + URL_COMMON_OAUTH
            ),
            SelectSite(
                name = context.getString(R.string.aiims_delhi),
                district = context.getString(R.string.district_new_delhi),
                state = context.getString(R.string.state_delhi),
                backendUrl = URL_SITE_2,
                fhirBaseUrl = URL_SITE_2 + URL_COMMON_FHIR,
                oAuthBaseUrl = URL_SITE_2 + URL_COMMON_OAUTH
            ),
            SelectSite(
                name = context.getString(R.string.msmf_bangalore),
                district = context.getString(R.string.district_bengaluru_rural),
                state = context.getString(R.string.state_karnataka),
                backendUrl = URL_SITE_3,
                fhirBaseUrl = URL_SITE_3 + URL_COMMON_FHIR,
                oAuthBaseUrl = URL_SITE_3 + URL_COMMON_OAUTH
            ),
            SelectSite(
                name = context.getString(R.string.public_health_krishnagiri),
                district = context.getString(R.string.district_krishnagiri),
                state = context.getString(R.string.state_tamil_nadu),
                backendUrl = URL_SITE_4,
                fhirBaseUrl = URL_SITE_4 + URL_COMMON_FHIR,
                oAuthBaseUrl = URL_SITE_4 + URL_COMMON_OAUTH
            ),
            SelectSite(
                name = context.getString(R.string.public_health_thanjavur),
                district = context.getString(R.string.district_thanjavur),
                state = context.getString(R.string.state_tamil_nadu),
                backendUrl = URL_SITE_5,
                fhirBaseUrl = URL_SITE_5 + URL_COMMON_FHIR,
                oAuthBaseUrl = URL_SITE_5 + URL_COMMON_OAUTH
            ),
            SelectSite(
                name = context.getString(R.string.mpmmcc_varanasi),
                district = context.getString(R.string.district_varanasi),
                state = context.getString(R.string.state_uttar_pradesh),
                backendUrl = URL_SITE_6,
                fhirBaseUrl = URL_SITE_6 + URL_COMMON_FHIR,
                oAuthBaseUrl = URL_SITE_6 + URL_COMMON_OAUTH
            ),
            SelectSite(
                name = context.getString(R.string.cachar_cancer_silchar),
                district = context.getString(R.string.district_cachar),
                state = context.getString(R.string.state_assam),
                backendUrl = URL_SITE_7,
                fhirBaseUrl = URL_SITE_7 + URL_COMMON_FHIR,
                oAuthBaseUrl = URL_SITE_7 + URL_COMMON_OAUTH
            ),
            SelectSite(
                name = context.getString(R.string.dr_borooah_cancer_guwahati),
                district = context.getString(R.string.district_guwahati),
                state = context.getString(R.string.state_assam),
                backendUrl = URL_SITE_8,
                fhirBaseUrl = URL_SITE_8 + URL_COMMON_FHIR,
                oAuthBaseUrl = URL_SITE_8 + URL_COMMON_OAUTH
            )
        )

        selectedSite = mutableStateOf(selectSiteList.value?.get(0))
    }

    fun setSelectSite(selectSite: SelectSite) {
        selectedSite.value = selectSite

        val fhirBaseUrl = getFhirBaseUrl(selectSite,isTest)
        val oauthBaseUrl = getOAuthBaseUrl(selectSite,isTest)

        secureSharedPreference.saveUrls(fhirBaseUrl, oauthBaseUrl)
        sharedPreferencesHelper.saveUrls(fhirBaseUrl, oauthBaseUrl)
    }

    private fun getFhirBaseUrl(selectSite: SelectSite, isTest: Boolean = false): String? {
        return if (BuildConfig.DEBUG && !isTest) {
            STAGING_FHIR_BASE_URL
        } else {
            selectSite.fhirBaseUrl
        }
    }

    private fun getOAuthBaseUrl(selectSite: SelectSite, isTest: Boolean = false): String? {
        return if (BuildConfig.DEBUG && !isTest) {
            STAGING_OAUTH_BASE_URL
        } else {
            selectSite.oAuthBaseUrl
        }
    }
}