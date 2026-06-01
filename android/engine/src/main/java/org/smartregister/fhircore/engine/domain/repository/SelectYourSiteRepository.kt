package org.smartregister.fhircore.engine.domain.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.scopes.ActivityRetainedScoped
import org.smartregister.fhircore.engine.data.remote.auth.OAuthService
import org.smartregister.fhircore.engine.data.remote.selectSite.ServerConfig
import org.smartregister.fhircore.engine.domain.networkUtils.SafeApiRequest
import javax.inject.Inject

@ActivityRetainedScoped
class SelectYourSiteRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val api: OAuthService
) : SafeApiRequest(context) {

    suspend fun getSelectYourSites(url: String): Map<String, ServerConfig> {
        return apiRequest { api.fetchSites(url) }
    }

    /**
     * Returns true if [storedFhirBaseUrl] matches any tenant in the catalog at [catalogUrl].
     * Comparison ignores trailing slashes and case. Throws if the catalog can't be fetched —
     * callers should treat exceptions as "unknown" (e.g. offline) and not migrate.
     */
    suspend fun isFhirBaseUrlInCatalog(catalogUrl: String, storedFhirBaseUrl: String): Boolean {
        val normalizedStored = storedFhirBaseUrl.trimEnd('/').lowercase()
        if (normalizedStored.isEmpty()) return false
        val catalog = getSelectYourSites(catalogUrl)
        return catalog.values.any { server ->
            server.tenants.orEmpty().any { tenant ->
                tenant.fhirBaseUrl?.trimEnd('/')?.lowercase() == normalizedStored
            }
        }
    }
}
