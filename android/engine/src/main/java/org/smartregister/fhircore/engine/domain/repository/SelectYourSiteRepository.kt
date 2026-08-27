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
     * Returns true only when [storedFhirBaseUrl] matches a tenant in the catalog at [catalogUrl]
     * AND that same tenant's current authBaseUrl matches [storedOauthBaseUrl]. A tenant whose
     * identity provider migrated (fhirBaseUrl unchanged, authBaseUrl updated) needs the same
     * re-prompt as a decommissioned tenant — otherwise the device keeps authenticating against
     * an issuer the FHIR resource server no longer trusts, and every request 401s while the
     * device's own connectivity and fhirBaseUrl look perfectly healthy.
     *
     * Comparison ignores trailing slashes and case. Throws if the catalog can't be fetched —
     * callers should treat exceptions as "unknown" (e.g. offline) and not migrate.
     */
    suspend fun isStoredSiteCurrent(
        catalogUrl: String,
        storedFhirBaseUrl: String,
        storedOauthBaseUrl: String?,
    ): Boolean {
        val normalizedFhir = storedFhirBaseUrl.trimEnd('/').lowercase()
        if (normalizedFhir.isEmpty()) return false
        val normalizedOauth = storedOauthBaseUrl?.trimEnd('/')?.lowercase().orEmpty()
        val catalog = getSelectYourSites(catalogUrl)
        val matchingTenant =
            catalog.values
                .asSequence()
                .flatMap { it.tenants.orEmpty() }
                .find { it.fhirBaseUrl?.trimEnd('/')?.lowercase() == normalizedFhir }
                ?: return false
        return matchingTenant.authBaseUrl?.trimEnd('/')?.lowercase() == normalizedOauth
    }
}
