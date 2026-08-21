package org.smartregister.fhircore.engine.util

import com.google.android.fhir.FhirEngine
import com.google.android.fhir.db.ResourceNotFoundException
import com.google.android.fhir.get
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.hl7.fhir.r4.model.Basic
import org.smartregister.fhircore.engine.data.remote.fhir.resource.FhirResourceDataSource
import org.smartregister.fhircore.engine.util.extension.updateLastUpdated
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads feature flag state from the local FhirEngine first so reads stay fast and offline-safe,
 * then falls back to the network and finally to the persisted last-known values. The local copy
 * is kept fresh by [refreshFromServer], which every sync invokes (see AppSyncWorker): it fetches
 * the flags with a direct instance read — a `Basic?_id=<id>` search can be served stale from
 * HAPI's search-results cache for up to a minute, which is why flag changes used to need an
 * extra sync to appear — and writes the result through to the FhirEngine and to disk. A
 * single-flight mutex guarantees concurrent consumers share one refresh.
 */
@Singleton
class FeatureFlagUtil @Inject constructor(
    private val fhirEngine: FhirEngine,
    private val fhirResourceDataSource: FhirResourceDataSource,
    private val sharedPreferencesHelper: SharedPreferencesHelper,
) {

    private val mutex = Mutex()
    @Volatile private var cachedFlags: Map<String, Boolean> = emptyMap()
    @Volatile private var cachedUpdateConfig: AppUpdateConfig = AppUpdateConfig.NONE

    suspend fun isAiInferenceEnabled(): Boolean =
        readFlag(AI_INFERENCE_ENABLED_URL)

    /**
     * Returns the app-update configuration (target version + soft/forced behaviour) that the same
     * feature-flags [Basic] resource carries. Shares the engine-first / network / last-known read
     * path with the boolean flags, so it is fast, offline-safe, and a forced update stays enforced
     * even after the device goes offline (the last-known config is persisted to disk).
     */
    suspend fun getAppUpdateConfig(): AppUpdateConfig {
        val resourceId = sharedPreferencesHelper.getFeatureFlagsResourceId()
        refresh(resourceId)
        return cachedUpdateConfig
    }

    /**
     * Fetches the latest feature flags from the server and stores them locally (FhirEngine +
     * SharedPreferences) so subsequent reads — including offline ones — see the values that were
     * current when the sync ran. Never throws; on failure the previously stored values remain.
     */
    suspend fun refreshFromServer() {
        val resourceId = sharedPreferencesHelper.getFeatureFlagsResourceId()
        mutex.withLock {
            val basic = fetchFromNetwork(resourceId) ?: return
            saveToEngine(basic)
            applyAndPersist(resourceId, basic, source = "sync")
        }
    }

    private suspend fun readFlag(extensionUrl: String): Boolean {
        val resourceId = sharedPreferencesHelper.getFeatureFlagsResourceId()
        refresh(resourceId)
        return cachedFlags[extensionUrl] == true
    }

    private suspend fun refresh(resourceId: String) {
        mutex.withLock {
            readEngineBasic(resourceId)?.let { basic ->
                applyAndPersist(resourceId, basic, source = "engine")
                return
            }

            fetchFromNetwork(resourceId)?.let { basic ->
                saveToEngine(basic)
                applyAndPersist(resourceId, basic, source = "network")
                return
            }

            cachedFlags = sharedPreferencesHelper.getLastKnownFeatureFlags(resourceId)
            cachedUpdateConfig = sharedPreferencesHelper.getLastKnownAppUpdateConfig(resourceId)
            Timber.w(
                "Feature flags unavailable id=%s; using last-known flags=%s update=%s",
                resourceId, cachedFlags, cachedUpdateConfig,
            )
        }
    }

    private suspend fun readEngineBasic(resourceId: String): Basic? =
        try {
            fhirEngine.get<Basic>(resourceId)
        } catch (e: ResourceNotFoundException) {
            null
        } catch (e: Exception) {
            Timber.w(e, "Engine read failed for Basic/%s", resourceId)
            null
        }

    private suspend fun fetchFromNetwork(resourceId: String): Basic? =
        try {
            fhirResourceDataSource.getBasic(resourceId)
        } catch (e: Exception) {
            Timber.w(e, "Network read failed for Basic/%s", resourceId)
            null
        }

    /**
     * Upserts the fetched flags resource into the FhirEngine local-only (no pending upload), so
     * the engine-first read path and offline cold starts observe the refreshed values.
     */
    private suspend fun saveToEngine(basic: Basic) {
        try {
            basic.updateLastUpdated()
            fhirEngine.create(basic, isLocalOnly = true)
        } catch (e: Exception) {
            Timber.w(e, "Failed to store Basic/%s in FhirEngine", basic.idElement.idPart)
        }
    }

    private fun applyAndPersist(resourceId: String, basic: Basic, source: String) {
        val flags = basic.toFlagsMap()
        val updateConfig = basic.toAppUpdateConfig()
        cachedFlags = flags
        cachedUpdateConfig = updateConfig
        sharedPreferencesHelper.saveLastKnownFeatureFlags(resourceId, flags)
        sharedPreferencesHelper.saveLastKnownAppUpdateConfig(resourceId, updateConfig)
        Timber.d("Feature flags from %s id=%s: flags=%s update=%s", source, resourceId, flags, updateConfig)
    }

    private fun Basic.toFlagsMap(): Map<String, Boolean> =
        extension.orEmpty().mapNotNull { ext ->
            val value = ext.value?.primitiveValue()?.toBoolean()
            if (ext.url != null && value != null) ext.url to value else null
        }.toMap()

    /**
     * Parses the single nested `app-update` extension into an [AppUpdateConfig]. The parent
     * extension groups the policy as one cohesive concept; its sub-extensions carry the two version
     * thresholds and the display fields. Absent parent → [AppUpdateConfig.NONE].
     */
    private fun Basic.toAppUpdateConfig(): AppUpdateConfig {
        val parent = getExtensionByUrl(APP_UPDATE_URL) ?: return AppUpdateConfig.NONE
        fun sub(url: String): String? = parent.getExtensionByUrl(url)?.value?.primitiveValue()
        return AppUpdateConfig(
            minSupportedVersionCode = sub(APP_UPDATE_MIN_SUPPORTED_VERSION_CODE)?.toIntOrNull() ?: 0,
            latestVersionCode = sub(APP_UPDATE_LATEST_VERSION_CODE)?.toIntOrNull() ?: 0,
            latestVersionName = sub(APP_UPDATE_LATEST_VERSION_NAME)?.takeIf { it.isNotBlank() },
            message = sub(APP_UPDATE_MESSAGE)?.takeIf { it.isNotBlank() },
        )
    }

    companion object {
        const val AI_INFERENCE_ENABLED_URL =
            "https://midas.iisc.ac.in/fhir/StructureDefinition/ai-inference-enabled"

        /** Parent (nested) extension that groups the whole app-update policy on the feature-flags Basic. */
        const val APP_UPDATE_URL =
            "https://midas.iisc.ac.in/fhir/StructureDefinition/app-update"

        /** Sub-extension: hard floor. Installs with a lower `versionCode` are force-updated. */
        const val APP_UPDATE_MIN_SUPPORTED_VERSION_CODE = "minSupportedVersionCode"

        /** Sub-extension: newest `versionCode`. Installs below it (but at/above the floor) are softly nudged. */
        const val APP_UPDATE_LATEST_VERSION_CODE = "latestVersionCode"

        /** Sub-extension: human-readable target version shown in the prompt, e.g. `AA_v1.7.8`. Display only. */
        const val APP_UPDATE_LATEST_VERSION_NAME = "latestVersionName"

        /**
         * Sub-extension: optional, server-configurable text shown on the **soft**-update nudge banner.
         * Blank/absent falls back to the built-in `app_update_soft_message` copy. Forced updates ignore it.
         */
        const val APP_UPDATE_MESSAGE = "message"
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface FeatureFlagUtilEntryPoint {
    fun featureFlagUtil(): FeatureFlagUtil
}
