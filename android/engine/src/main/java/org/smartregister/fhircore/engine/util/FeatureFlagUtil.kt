package org.smartregister.fhircore.engine.util

import com.google.android.fhir.FhirEngine
import com.google.android.fhir.db.ResourceNotFoundException
import com.google.android.fhir.get
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
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
 * extra sync to appear — and writes the result through to the FhirEngine and to disk.
 *
 * The network is never called while [mutex] is held. The mutex only guards the quick local
 * read/apply/persist steps. Holding it across a server call let one slow or unreachable server
 * (OkHttp waits up to 600 s) block every flag read on the device. The add-case AI check then timed
 * out and showed "Failed to enable AI" on phones where AI was fully enabled. Server calls are also
 * capped at [NETWORK_FETCH_TIMEOUT_MS].
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

    /**
     * True when the last [refreshFromServer] could not get the flags: a timeout, a server error or
     * no network. The previously stored flags stay in use. Screens check it when a sync finishes,
     * so a sync whose flag fetch failed reports "Sync completed with errors".
     */
    @Volatile var lastServerRefreshFailed: Boolean = false
        private set

    suspend fun isAiInferenceEnabled(): Boolean =
        readFlag(AI_INFERENCE_ENABLED_URL)

    /**
     * The AI flag as stored on this device: the FhirEngine copy, otherwise the last-known value
     * on disk. Never calls the network and never waits on a refresh in progress. The add-case AI
     * check uses this, so it answers from the device even when the server is down or slow. The
     * stored copy is whatever the last successful sync fetched.
     */
    suspend fun isAiInferenceEnabledLocally(): Boolean {
        val resourceId = sharedPreferencesHelper.getFeatureFlagsResourceId()
        val flags =
            readEngineBasic(resourceId)?.toFlagsMap()
                ?: sharedPreferencesHelper.getLastKnownFeatureFlags(resourceId)
        return flags[AI_INFERENCE_ENABLED_URL] == true
    }

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
     * current when the sync ran. Never throws. On failure (timeout, server error, offline) the
     * previously stored values remain in use and [lastServerRefreshFailed] is set.
     *
     * @return true when the flags were fetched and stored.
     */
    suspend fun refreshFromServer(): Boolean {
        val resourceId = sharedPreferencesHelper.getFeatureFlagsResourceId()
        // Outside the lock: readers keep getting the stored flags while this waits on the server.
        val basic = fetchFromNetwork(resourceId)
        if (basic == null) {
            lastServerRefreshFailed = true
            return false
        }
        mutex.withLock {
            saveToEngine(basic)
            applyAndPersist(resourceId, basic, source = "sync")
        }
        lastServerRefreshFailed = false
        return true
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
        }

        // No copy on the device yet (before the first sync stored one). Fetch it outside the lock,
        // so other readers are not held up behind the server.
        fetchFromNetwork(resourceId)?.let { basic ->
            mutex.withLock {
                saveToEngine(basic)
                applyAndPersist(resourceId, basic, source = "network")
            }
            return
        }

        mutex.withLock {
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
            withTimeoutOrNull(NETWORK_FETCH_TIMEOUT_MS) { fhirResourceDataSource.getBasic(resourceId) }
                ?: null.also {
                    Timber.w("Network read timed out for Basic/%s after %d ms", resourceId, NETWORK_FETCH_TIMEOUT_MS)
                }
        } catch (e: CancellationException) {
            throw e
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
        // Configs written before the soft/forced split carry one shared `message`; it backs both
        // fields so those servers keep showing copy until they are migrated.
        val legacyMessage = sub(APP_UPDATE_MESSAGE)?.takeIf { it.isNotBlank() }
        return AppUpdateConfig(
            minSupportedVersionCode = sub(APP_UPDATE_MIN_SUPPORTED_VERSION_CODE)?.toIntOrNull() ?: 0,
            latestVersionCode = sub(APP_UPDATE_LATEST_VERSION_CODE)?.toIntOrNull() ?: 0,
            latestVersionName = sub(APP_UPDATE_LATEST_VERSION_NAME)?.takeIf { it.isNotBlank() },
            softMessage = sub(APP_UPDATE_SOFT_MESSAGE)?.takeIf { it.isNotBlank() } ?: legacyMessage,
            forcedMessage = sub(APP_UPDATE_FORCED_MESSAGE)?.takeIf { it.isNotBlank() } ?: legacyMessage,
        )
    }

    companion object {
        /**
         * Longest wait for the flags resource (a small Basic) from the server. The shared OkHttp
         * client allows 600 s, which is meant for big sync bundles and image uploads, not for this.
         */
        const val NETWORK_FETCH_TIMEOUT_MS = 30_000L

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
         * Sub-extension: optional copy for the **soft** nudge card. Replaces the card's built-in lead
         * sentence; the blue "Click to update." call-to-action is always kept. Blank/absent falls back
         * to [APP_UPDATE_MESSAGE], then to the built-in copy.
         */
        const val APP_UPDATE_SOFT_MESSAGE = "softMessage"

        /**
         * Sub-extension: optional copy for the **forced** blocking dialog — the reason the update is
         * being forced, shown in colorError beneath the fixed explanation (e.g. "App is 6 months out
         * of date"). Blank/absent falls back to [APP_UPDATE_MESSAGE], then the line is omitted.
         */
        const val APP_UPDATE_FORCED_MESSAGE = "forcedMessage"

        /**
         * Sub-extension: the single shared message used before soft and forced copy were split. Still
         * read as the fallback for both [APP_UPDATE_SOFT_MESSAGE] and [APP_UPDATE_FORCED_MESSAGE] so
         * servers configured against the old shape keep working.
         */
        const val APP_UPDATE_MESSAGE = "message"
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface FeatureFlagUtilEntryPoint {
    fun featureFlagUtil(): FeatureFlagUtil
}
