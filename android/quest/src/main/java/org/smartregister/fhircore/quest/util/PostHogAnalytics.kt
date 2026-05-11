package org.smartregister.fhircore.quest.util

import com.posthog.PostHog
import org.smartregister.fhircore.quest.BuildConfig
import timber.log.Timber
import java.security.MessageDigest

/**
 * Centralized PostHog analytics helper for capturing events,
 * identifying users, and setting user/group properties.
 */
object PostHogAnalytics {

    // ── User Property Keys ──
    private const val PROP_FLWID = "flwid"
    private const val PROP_VERSION_CODE = "app_version_code"
    private const val PROP_VERSION_NAME = "app_version_name"
    private const val PROP_PENDING_SYNC_IMAGES = "pending_sync_images"
    private const val PROP_PENDING_SYNC_CASES = "pending_sync_cases"

    // ── Event Names ──
    object Events {
        const val SCREEN_VIEW = "screen_view"
        const val SYNC_INITIATED = "sync_initiated"
        const val SYNC_COMPLETED = "sync_completed"
        const val QUESTIONNAIRE_OPENED = "questionnaire_opened"
        const val QUESTIONNAIRE_SUBMITTED = "questionnaire_submitted"
        const val QUESTIONNAIRE_DRAFT_SAVED = "questionnaire_draft_saved"
        const val QUESTIONNAIRE_DRAFT_DELETED = "questionnaire_draft_deleted"
        const val AI_INFERENCE_COMPLETED = "ai_inference_completed"
        const val AI_RESULT_VIEWED = "ai_result_viewed"
        const val AI_REFER_CASE = "ai_refer_case"
        const val PHOTO_CAPTURED = "photo_captured"
        const val TASK_STATUS_UPDATED = "task_status_updated"
        const val SCREENING_STEP_COMPLETED = "screening_step_completed"
        const val SCREENING_COMPLETED = "screening_completed"
        const val SCREENING_ABANDONED = "screening_abandoned"
        const val MODEL_INFERENCE_COMPLETED = "model_inference_completed"
        const val PHOTO_RETAKEN = "photo_retaken"
        const val IMAGE_UPLOAD_COMPLETED = "image_upload_completed"
        const val ERROR = "error"
    }

    // ── Event Property Keys ──
    object Props {
        const val SCREEN_NAME = "screen_name"
        const val QUESTIONNAIRE_ID = "questionnaire_id"
        const val IS_SUSPICIOUS = "is_suspicious"
        const val REFER_CASE = "refer_case"
        const val AI_PREDICTION = "ai_prediction"
        const val AI_CONFIDENCE = "ai_confidence"
        const val AI_VERDICT = "ai_verdict"
        const val AI_OVERRIDDEN = "ai_overridden"
        const val TASK_ID = "task_id"
        const val TASK_STATUS = "task_status"
        const val TASK_CODE = "task_code"
        const val LINKED_QUESTIONNAIRE_ID = "linked_questionnaire_id"
        const val QUESTIONNAIRE_KIND = "questionnaire_kind"
        const val SCREENING_ID = "screening_id"
        const val STEP_NAME = "step_name"
        const val STEP_DURATION_MS = "step_duration_ms"
        const val CUMULATIVE_DURATION_MS = "cumulative_duration_ms"
        const val TOTAL_DURATION_MS = "total_duration_ms"
        const val OUTCOME = "outcome"
        const val PHOTO_COUNT = "photo_count"
        const val RETAKE_COUNT = "retake_count"
        const val RETAKE_INDEX = "retake_index"
        const val BATTERY_DELTA_PCT = "battery_delta_pct"
        const val MODEL_NAME = "model_name"
        const val MODEL_VERSION = "model_version"
        const val MODEL_PREDICTION = "model_prediction"
        const val MODEL_CONFIDENCE = "model_confidence"
        const val MODEL_ENTROPY = "model_entropy"
        const val LOW_CONFIDENCE = "low_confidence"
        const val IMAGE_COUNT = "image_count"
        const val SUSPICIOUS_IMAGE_COUNT = "suspicious_image_count"
        const val NON_SUSPICIOUS_IMAGE_COUNT = "non_suspicious_image_count"
        const val LOW_CONFIDENCE_IMAGE_COUNT = "low_confidence_image_count"
        const val MEAN_CONFIDENCE = "mean_confidence"
        const val INFERENCE_TIME_MS = "inference_time_ms"
        const val COMBINED_INFERENCE_TIME_MS = "combined_inference_time_ms"
        const val CAPTURE_TO_RESULT_MS = "capture_to_result_ms"
        const val BATTERY_PCT = "battery_pct"
        const val BATTERY_CHARGING = "battery_charging"
        const val MEMORY_USED_MB = "memory_used_mb"
        const val MEMORY_AVAILABLE_MB = "memory_available_mb"
        const val BLUR_LAPLACIAN_VARIANCE = "blur_laplacian_variance"
        const val BRIGHTNESS_MEAN = "brightness_mean"
        const val CONTRAST_STDDEV = "contrast_stddev"
        const val NOISE_ESTIMATE = "noise_estimate"
        const val IS_UNUSABLE = "is_unusable"
        const val DEVICE_MODEL = "device_model"
        const val DEVICE_MANUFACTURER = "device_manufacturer"
        const val OS_VERSION = "os_version"
        const val IMAGE_WIDTH = "image_width"
        const val IMAGE_HEIGHT = "image_height"
        const val TIME_TO_CAPTURE_MS = "time_to_capture_ms"
        const val SYNC_STATUS = "sync_status"
        const val SYNC_DURATION_MS = "sync_duration_ms"
        const val PENDING_IMAGES_AFTER = "pending_images_after"
        const val PENDING_CASES_AFTER = "pending_cases_after"
        const val DOCUMENT_ID = "document_id"
        const val UPLOAD_DURATION_MS = "upload_duration_ms"
        const val RESPONSE_CODE = "response_code"
        const val PENDING_DOCUMENTS = "pending_documents"
        const val BYTES_UPLOADED = "bytes_uploaded"
        const val ERROR_MESSAGE = "error_message"
        const val ERROR_SOURCE = "error_source"
    }

    /**
     * SHA-256 hash a string to anonymize PII before sending to PostHog.
     */
    fun hashId(id: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(id.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Identify the user and set persistent user properties including
     * hashed flwid and pending sync counts so all future events
     * can be filtered by these in PostHog.
     * Note: flwId is hashed using SHA-256 to avoid sending PII.
     * Site name is intentionally excluded to protect location privacy.
     */
    fun identifyUser(
        flwId: String,
        pendingSyncImages: Int = 0,
        pendingSyncCases: Int = 0,
    ) {
        try {
            if (flwId.isBlank()) return

            val hashedFlwId = hashId(flwId)

            val userProperties = mutableMapOf<String, Any>(
                PROP_FLWID to hashedFlwId,
                PROP_VERSION_CODE to BuildConfig.VERSION_CODE.toString(),
                PROP_VERSION_NAME to BuildConfig.VERSION_NAME,
                PROP_PENDING_SYNC_IMAGES to pendingSyncImages,
                PROP_PENDING_SYNC_CASES to pendingSyncCases,
            )

            PostHog.identify(hashedFlwId, userProperties = userProperties)
        } catch (e: Exception) {
            Timber.e(e, "PostHog identifyUser failed")
        }
    }

    /**
     * Capture a generic event with optional properties.
     * Site and flwid are automatically associated via identify().
     */
    fun capture(event: String, properties: Map<String, Any?>? = null) {
        try {
            val cleanProperties =
                properties
                    ?.filterValues { value ->
                        when (value) {
                            null -> false
                            is String -> value.isNotBlank()
                            is Collection<*> -> value.isNotEmpty()
                            is Map<*, *> -> value.isNotEmpty()
                            else -> true
                        }
                    }
                    ?.mapValues { it.value as Any }
            PostHog.capture(event, properties = cleanProperties)
        } catch (e: Exception) {
            Timber.e(e, "PostHog capture failed for event: $event")
        }
    }

    /**
     * Capture a screen view event using PostHog's built-in screen tracking.
     */
    fun captureScreenView(screenName: String) {
        try {
            PostHog.screen(screenName)
        } catch (e: Exception) {
            Timber.e(e, "PostHog screen capture failed for: $screenName")
        }
    }

    /**
     * Capture an error event for logging in PostHog.
     */
    fun captureError(source: String, message: String, extra: Map<String, Any?>? = null) {
        val props = mutableMapOf<String, Any>(
            Props.ERROR_SOURCE to source,
            Props.ERROR_MESSAGE to message,
        )
        extra?.forEach { (key, value) -> value?.let { props[key] = it } }
        capture(Events.ERROR, props)
    }

    /**
     * Capture a caught exception in PostHog error tracking.
     * Use this for caught exceptions that you still want to track
     * (e.g., sync failures, API errors). Uncaught crashes are
     * auto-captured via errorTrackingConfig.autoCapture = true.
     */
    fun captureException(throwable: Throwable, extra: Map<String, Any?>? = null) {
        try {
            val cleanProperties =
                extra
                    ?.filterValues { value ->
                        when (value) {
                            null -> false
                            is String -> value.isNotBlank()
                            is Collection<*> -> value.isNotEmpty()
                            is Map<*, *> -> value.isNotEmpty()
                            else -> true
                        }
                    }
                    ?.mapValues { it.value as Any }
            PostHog.captureException(throwable, properties = cleanProperties)
        } catch (e: Exception) {
            Timber.e(e, "PostHog captureException failed")
        }
    }
}
