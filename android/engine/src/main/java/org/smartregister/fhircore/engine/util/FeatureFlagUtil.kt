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

    suspend fun isAiInferenceEnabled(): Boolean =
        readFlag(AI_INFERENCE_ENABLED_URL)

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
            applyAndPersist(resourceId, basic.toFlagsMap(), source = "sync")
        }
    }

    private suspend fun readFlag(extensionUrl: String): Boolean {
        val resourceId = sharedPreferencesHelper.getFeatureFlagsResourceId()
        refresh(resourceId)
        return cachedFlags[extensionUrl] == true
    }

    private suspend fun refresh(resourceId: String) {
        mutex.withLock {
            readFromEngine(resourceId)?.let { flags ->
                applyAndPersist(resourceId, flags, source = "engine")
                return
            }

            fetchFromNetwork(resourceId)?.let { basic ->
                saveToEngine(basic)
                applyAndPersist(resourceId, basic.toFlagsMap(), source = "network")
                return
            }

            val persisted = sharedPreferencesHelper.getLastKnownFeatureFlags(resourceId)
            cachedFlags = persisted
            Timber.w("Feature flags unavailable id=%s; using last-known %s", resourceId, persisted)
        }
    }

    private suspend fun readFromEngine(resourceId: String): Map<String, Boolean>? =
        try {
            fhirEngine.get<Basic>(resourceId).toFlagsMap()
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

    private fun applyAndPersist(resourceId: String, flags: Map<String, Boolean>, source: String) {
        cachedFlags = flags
        sharedPreferencesHelper.saveLastKnownFeatureFlags(resourceId, flags)
        Timber.d("Feature flags from %s id=%s: %s", source, resourceId, flags)
    }

    private fun Basic.toFlagsMap(): Map<String, Boolean> =
        extension.orEmpty().mapNotNull { ext ->
            val value = ext.value?.primitiveValue()?.toBoolean()
            if (ext.url != null && value != null) ext.url to value else null
        }.toMap()

    companion object {
        const val AI_INFERENCE_ENABLED_URL =
            "https://midas.iisc.ac.in/fhir/StructureDefinition/ai-inference-enabled"
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface FeatureFlagUtilEntryPoint {
    fun featureFlagUtil(): FeatureFlagUtil
}
