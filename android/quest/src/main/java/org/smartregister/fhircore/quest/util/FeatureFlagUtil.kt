package org.smartregister.fhircore.quest.util

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
import org.smartregister.fhircore.engine.util.SharedPreferencesHelper
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads feature flag state from local FhirEngine first so values updated by sync are visible
 * without an app restart. Read order: local FhirEngine (already synced Basic/<id>), network
 * fetch, then persisted last-known values. Successful reads write through to disk so offline
 * cold starts still see the most recent values. A single-flight mutex guarantees concurrent
 * consumers share one refresh.
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

            readFromNetwork(resourceId)?.let { flags ->
                applyAndPersist(resourceId, flags, source = "network")
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

    private suspend fun readFromNetwork(resourceId: String): Map<String, Boolean>? =
        try {
            val bundle = fhirResourceDataSource.getResource("Basic?_id=$resourceId&_count=1")
            val basic = bundle.entry.firstOrNull()?.resource as? Basic
            basic?.toFlagsMap()
        } catch (e: Exception) {
            Timber.w(e, "Network read failed for Basic/%s", resourceId)
            null
        }

    private fun applyAndPersist(resourceId: String, flags: Map<String, Boolean>, source: String) {
        cachedFlags = flags
        sharedPreferencesHelper.saveLastKnownFeatureFlags(resourceId, flags)
        Timber.i("Feature flags from %s id=%s: %s", source, resourceId, flags)
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
