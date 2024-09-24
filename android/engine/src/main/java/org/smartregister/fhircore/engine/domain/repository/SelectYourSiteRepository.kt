package org.smartregister.fhircore.engine.domain.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.scopes.ActivityRetainedScoped
import org.smartregister.fhircore.engine.data.remote.auth.OAuthService
import org.smartregister.fhircore.engine.data.remote.selectSite.SelectYourSiteResponse
import org.smartregister.fhircore.engine.domain.networkUtils.SafeApiRequest
import javax.inject.Inject

@ActivityRetainedScoped
class SelectYourSiteRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val api: OAuthService
) : SafeApiRequest(context) {

    suspend fun getSelectYourSites(url:String): SelectYourSiteResponse {
        return apiRequest { api.fetchSites(url) }
    }
}