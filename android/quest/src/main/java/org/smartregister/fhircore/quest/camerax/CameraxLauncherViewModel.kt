package org.smartregister.fhircore.quest.camerax

import android.os.SystemClock
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.smartregister.fhircore.quest.util.FeatureFlagUtil
import org.smartregister.fhircore.quest.util.PostHogAnalytics
import org.smartregister.fhircore.quest.util.ScreeningTimer
import javax.inject.Inject

/** Which screen the camera dialog is showing. */
enum class CameraMode {
    /** Live camera preview with the shutter + controls. */
    CAPTURE,

    /** The just-captured still being previewed with retake / select. */
    PREVIEW,
}

/**
 * Single source of truth for everything the camera dialog renders. The Fragment observes this and
 * applies it to views/hardware; it never mutates UI state ad-hoc. Keeping it as one immutable value
 * is what closed the original retake race — the shutter is only ever enabled by an explicit state
 * transition (camera bound), never imperatively from a handler running ahead of the async bind.
 */
data class CameraUiState(
    val mode: CameraMode = CameraMode.CAPTURE,
    val shutterEnabled: Boolean = false,
    val isCapturing: Boolean = false,
    val flashOn: Boolean = false,
    val zoomIndicatorVisible: Boolean = false,
    val capturedFilePath: String? = null,
    // True while the selected photo is being processed/saved on submit. The Fragment shows a
    // blocking "processing" overlay so the user knows work is happening and can't submit twice.
    val isProcessing: Boolean = false,
)

/** Result of preparing a captured photo for hand-off back to the questionnaire. */
data class PhotoCaptureResult(
    val uri: String,
    /** String extras (prediction / confidence / per-model results) for the fragment-result bundle. */
    val stringExtras: Map<String, String>,
    val toastMessage: String,
)

/**
 * Holds the camera dialog's UI state and the non-AI orchestration around a capture: the camera
 * state machine, screening timing/analytics, the feature-flag gate, and assembly of the
 * fragment-result bundle + capture analytics props.
 *
 * The AI inference pipeline (model loading, OpenCV pre-processing, the PyTorch forward passes and
 * ensemble logic) is intentionally NOT here — it stays frozen in [CameraxLauncherFragment]. This
 * ViewModel only consumes the already-computed inference output (a plain result map) when building
 * the bundle and analytics, so the inference behavior is unchanged and this logic stays unit
 * testable without any Android / AI dependencies.
 */
@HiltViewModel
class CameraxLauncherViewModel
@Inject
constructor(
    private val featureFlagUtil: FeatureFlagUtil,
) : ViewModel() {

    /**
     * Indirection over [SystemClock.elapsedRealtime] so timing math is unit testable without
     * Robolectric. Tests override this to drive deterministic capture durations.
     */
    @set:VisibleForTesting
    var elapsedRealtimeProvider: () -> Long = { SystemClock.elapsedRealtime() }

    private val _uiState = MutableStateFlow(CameraUiState())
    val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()

    private var screeningId: String? = null
    private var captureStartedMs: Long? = null
    private var captureSavedMs: Long? = null

    @get:VisibleForTesting
    var timeToCaptureMs: Long? = null
        private set

    fun setScreeningId(id: String?) {
        screeningId = id
    }

    fun screeningId(): String? = screeningId

    suspend fun isAiInferenceEnabled(): Boolean = featureFlagUtil.isAiInferenceEnabled()

    // ───────────────────────────── camera state machine ─────────────────────────────

    /** Camera is (re)binding asynchronously; the shutter must not fire until [onCameraBound]. */
    fun onCameraBinding() {
        _uiState.update { it.copy(shutterEnabled = false) }
    }

    /** Camera bound and ready: enable the shutter, return to capture mode, torch on. */
    fun onCameraBound() {
        _uiState.update {
            it.copy(mode = CameraMode.CAPTURE, shutterEnabled = true, flashOn = true)
        }
    }

    /**
     * Marks the start of a capture.
     *
     * @return true if the capture may proceed, false if one is already in flight (the original
     *   `isCapturing` re-entrancy guard).
     */
    fun beginCapture(): Boolean {
        if (_uiState.value.isCapturing) return false
        captureStartedMs = elapsedRealtimeProvider()
        ScreeningTimer.markStep(screeningId, STEP_PHOTO_CAPTURE_STARTED)
        _uiState.update { it.copy(isCapturing = true, shutterEnabled = false) }
        return true
    }

    /** The photo was written to [absolutePath]; switch to preview and record capture duration. */
    fun onCaptureSaved(absolutePath: String) {
        val savedMs = elapsedRealtimeProvider()
        captureSavedMs = savedMs
        timeToCaptureMs = savedMs - (captureStartedMs ?: savedMs)
        ScreeningTimer.markStep(screeningId, STEP_PHOTO_CAPTURE_COMPLETED)
        _uiState.update {
            it.copy(
                mode = CameraMode.PREVIEW,
                shutterEnabled = false,
                flashOn = false,
                capturedFilePath = absolutePath,
            )
        }
    }

    /** Async capture failed; the Fragment rebinds the camera (which re-enables the shutter). */
    fun onCaptureError() {
        _uiState.update { it.copy(isCapturing = false) }
    }

    /** Capture threw synchronously before dispatch; re-enable the shutter so the user can retry. */
    fun onCaptureFailedSynchronously() {
        _uiState.update { it.copy(isCapturing = false, shutterEnabled = true) }
    }

    /** User chose to retake; return to capture mode. Shutter is re-enabled on the next bind. */
    fun onRetake() {
        ScreeningTimer.incrementRetake(screeningId)
        _uiState.update {
            it.copy(
                mode = CameraMode.CAPTURE,
                isCapturing = false,
                shutterEnabled = false,
                flashOn = false,
                capturedFilePath = null,
            )
        }
    }

    fun toggleFlash() {
        _uiState.update { it.copy(flashOn = !it.flashOn) }
    }

    fun toggleZoomIndicator() {
        _uiState.update { it.copy(zoomIndicatorVisible = !it.zoomIndicatorVisible) }
    }

    /** Submit tapped: the photo is being processed/saved. Blocks re-submits and shows progress. */
    fun onProcessingStarted() {
        _uiState.update { it.copy(isProcessing = true) }
    }

    /** Processing finished (e.g. it failed and we stay on the preview); hide the overlay. */
    fun onProcessingFinished() {
        _uiState.update { it.copy(isProcessing = false) }
    }

    // ───────────────────────────── result / analytics assembly ─────────────────────────────

    /**
     * Builds the analytics props for a [PostHogAnalytics.Events.PHOTO_CAPTURED] event. Pure: the
     * inference output ([resultMap], [qualityProps], [combinedInferenceTimeMs]) is supplied by the
     * caller after the frozen pipeline has run.
     */
    @VisibleForTesting
    fun buildPhotoCapturedProps(
        resultMap: Map<String, Any>?,
        qualityProps: Map<String, Any>?,
        combinedInferenceTimeMs: Long?,
        deviceMetrics: Map<String, Any>,
    ): Map<String, Any> {
        val props =
            mutableMapOf<String, Any>(
                PostHogAnalytics.Props.AI_PREDICTION to
                    (resultMap?.get(CameraxLauncherFragment.CAMERA_PREDICTION_KEY) ?: ""),
                PostHogAnalytics.Props.AI_CONFIDENCE to
                    (resultMap?.get(CameraxLauncherFragment.CAMERA_CONFIDENCE_KEY) ?: ""),
            )
        val captureToResultMs = captureSavedMs?.let { elapsedRealtimeProvider() - it }
        props[PostHogAnalytics.Props.SCREENING_ID] = screeningId.orEmpty()
        timeToCaptureMs?.let { props[PostHogAnalytics.Props.TIME_TO_CAPTURE_MS] = it }
        captureToResultMs?.let { props[PostHogAnalytics.Props.CAPTURE_TO_RESULT_MS] = it }
        qualityProps?.let { props.putAll(it) }
        props.putAll(deviceMetrics)
        combinedInferenceTimeMs?.let {
            props[PostHogAnalytics.Props.INFERENCE_TIME_MS] = it
            props[PostHogAnalytics.Props.COMBINED_INFERENCE_TIME_MS] = it
        }
        return props
    }

    /**
     * Builds the string extras placed into the fragment-result bundle. When inference produced a
     * non-empty [resultMap] every prediction/confidence key is forwarded; when there is no result
     * but AI is enabled, prediction/confidence are emitted blank (matching the original contract).
     */
    @VisibleForTesting
    fun buildResultExtras(aiEnabled: Boolean, resultMap: Map<String, Any>?): Map<String, String> {
        if (resultMap != null) {
            return buildMap {
                put(
                    CameraxLauncherFragment.CAMERA_PREDICTION_KEY,
                    resultMap[CameraxLauncherFragment.CAMERA_PREDICTION_KEY].asStringOrEmpty(),
                )
                put(
                    CameraxLauncherFragment.CAMERA_CONFIDENCE_KEY,
                    resultMap[CameraxLauncherFragment.CAMERA_CONFIDENCE_KEY].asStringOrEmpty(),
                )
                put(CameraxLauncherFragment.CAMERA_MODEL6_PREDICTION_KEY, resultMap["model6_prediction"].asStringOrEmpty())
                put(CameraxLauncherFragment.CAMERA_MODEL6_CONFIDENCE_KEY, resultMap["model6_confidence"].asStringOrEmpty())
                put(CameraxLauncherFragment.CAMERA_MODEL8_PREDICTION_KEY, resultMap["model8_prediction"].asStringOrEmpty())
                put(CameraxLauncherFragment.CAMERA_MODEL8_CONFIDENCE_KEY, resultMap["model8_confidence"].asStringOrEmpty())
                put(CameraxLauncherFragment.CAMERA_MODEL82_PREDICTION_KEY, resultMap["model82_prediction"].asStringOrEmpty())
                put(CameraxLauncherFragment.CAMERA_MODEL82_CONFIDENCE_KEY, resultMap["model82_confidence"].asStringOrEmpty())
            }
        }
        if (aiEnabled) {
            return mapOf(
                CameraxLauncherFragment.CAMERA_PREDICTION_KEY to "",
                CameraxLauncherFragment.CAMERA_CONFIDENCE_KEY to "",
            )
        }
        return emptyMap()
    }

    /**
     * Records the photo against the screening, fires the capture analytics event, and returns the
     * data the Fragment needs to publish the fragment result. The frozen inference output is passed
     * in via [resultMap]/[qualityProps]/[combinedInferenceTimeMs].
     */
    fun preparePhotoCapture(
        absolutePath: String,
        aiEnabled: Boolean,
        resultMap: Map<String, Any>?,
        qualityProps: Map<String, Any>?,
        combinedInferenceTimeMs: Long?,
        deviceMetrics: Map<String, Any>,
    ): PhotoCaptureResult {
        ScreeningTimer.incrementPhoto(screeningId)
        PostHogAnalytics.capture(
            PostHogAnalytics.Events.PHOTO_CAPTURED,
            buildPhotoCapturedProps(resultMap, qualityProps, combinedInferenceTimeMs, deviceMetrics),
        )
        return PhotoCaptureResult(
            uri = absolutePath,
            stringExtras = buildResultExtras(aiEnabled, resultMap),
            toastMessage = if (aiEnabled) MESSAGE_IMAGE_PROCESSED else MESSAGE_IMAGE_SAVED,
        )
    }

    companion object {
        private const val STEP_PHOTO_CAPTURE_STARTED = "photo_capture_started"
        private const val STEP_PHOTO_CAPTURE_COMPLETED = "photo_capture_completed"

        @VisibleForTesting const val MESSAGE_IMAGE_PROCESSED = "Image processed successfully"

        @VisibleForTesting const val MESSAGE_IMAGE_SAVED = "Image saved successfully"
    }
}

private fun Any?.asStringOrEmpty(): String = this?.toString().orEmpty()
