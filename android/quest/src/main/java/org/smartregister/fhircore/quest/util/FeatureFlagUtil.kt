package org.smartregister.fhircore.quest.util

import com.google.android.fhir.FhirEngine
import com.google.android.fhir.get
import org.hl7.fhir.r4.model.Basic
import timber.log.Timber

object FeatureFlagUtil {

    const val FEATURE_FLAGS_RESOURCE_ID = "feature-flags"
    const val AI_INFERENCE_ENABLED_URL =
        "https://midas.iisc.ac.in/fhir/StructureDefinition/ai-inference-enabled"

    suspend fun isAiInferenceEnabled(fhirEngine: FhirEngine): Boolean {
        return try {
            val featureFlags = fhirEngine.get<Basic>(FEATURE_FLAGS_RESOURCE_ID)
            featureFlags.getExtensionByUrl(AI_INFERENCE_ENABLED_URL)
                ?.value?.primitiveValue()?.toBoolean() == true
        } catch (e: Exception) {
            Timber.e(e, "Failed to read feature flag: ai_inference_enabled")
            false
        }
    }
}