package org.smartregister.fhircore.quest.util

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
 * Holds per-process feature flag state. The flags are fetched from `Basic/<tenant>-feature-flags`
 * on first read (or whenever the active tenant changes) and cached in memory for the rest of the
 * session. A single-flight mutex guarantees that concurrent consumers share one network call.
 */
@Singleton
class FeatureFlagUtil @Inject constructor(
    private val fhirResourceDataSource: FhirResourceDataSource,
    private val sharedPreferencesHelper: SharedPreferencesHelper,
) {

    private val mutex = Mutex()
    @Volatile private var cachedFlags: Map<String, Boolean> = emptyMap()
    @Volatile private var cachedForResourceId: String? = null

    suspend fun isAiInferenceEnabled(): Boolean =
        readFlag(AI_INFERENCE_ENABLED_URL)

    private suspend fun readFlag(extensionUrl: String): Boolean {
        val resourceId = sharedPreferencesHelper.getFeatureFlagsResourceId()
        if (cachedForResourceId != resourceId) {
            refresh(resourceId)
        }
        return cachedFlags[extensionUrl] == true
    }

    private suspend fun refresh(resourceId: String) {
        mutex.withLock {
            // Another waiter may have already refreshed for this id while we waited on the lock.
            if (cachedForResourceId == resourceId) return
            try {
                val bundle =
                    fhirResourceDataSource.getResource("Basic?_id=$resourceId&_count=1")
                val basic = bundle.entry.firstOrNull()?.resource as? Basic
                cachedFlags = basic?.extension.orEmpty().mapNotNull { ext ->
                    val value = ext.value?.primitiveValue()?.toBoolean()
                    if (ext.url != null && value != null) ext.url to value else null
                }.toMap()
                cachedForResourceId = resourceId
                Timber.i("Refreshed feature flags id=%s: %s", resourceId, cachedFlags)
            } catch (e: Exception) {
                // Leave the previous cache (if any) in place; treat all flags as off for *this*
                // tenant until the next invalidate(). Logging once per failure is enough.
                cachedFlags = emptyMap()
                cachedForResourceId = resourceId
                Timber.w(e, "Failed to fetch feature flags Basic/%s", resourceId)
            }
        }
    }

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
