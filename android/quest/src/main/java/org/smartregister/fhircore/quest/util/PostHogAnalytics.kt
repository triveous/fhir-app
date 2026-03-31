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
        const val TASK_ID = "task_id"
        const val TASK_STATUS = "task_status"
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
    fun capture(event: String, properties: Map<String, Any>? = null) {
        try {
            PostHog.capture(event, properties = properties)
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
    fun captureError(source: String, message: String, extra: Map<String, Any>? = null) {
        val props = mutableMapOf<String, Any>(
            Props.ERROR_SOURCE to source,
            Props.ERROR_MESSAGE to message,
        )
        extra?.let { props.putAll(it) }
        capture(Events.ERROR, props)
    }

    /**
     * Capture a caught exception in PostHog error tracking.
     * Use this for caught exceptions that you still want to track
     * (e.g., sync failures, API errors). Uncaught crashes are
     * auto-captured via errorTrackingConfig.autoCapture = true.
     */
    fun captureException(throwable: Throwable, extra: Map<String, Any>? = null) {
        try {
            PostHog.captureException(throwable, properties = extra)
        } catch (e: Exception) {
            Timber.e(e, "PostHog captureException failed")
        }
    }
}
