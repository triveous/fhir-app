/*
 * Copyright 2021-2024 Ona Systems, Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.smartregister.fhircore.quest.camerax

import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.runs
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.smartregister.fhircore.ai.Outcome
import org.smartregister.fhircore.quest.ml.ImageScreeningResult
import org.smartregister.fhircore.quest.ml.ScreeningEngine
import org.smartregister.fhircore.quest.util.PostHogAnalytics
import org.smartregister.fhircore.quest.util.ScreeningTimer

/**
 * Pure unit tests for [CameraxLauncherViewModel]. No Robolectric / Hilt: the ViewModel deliberately
 * owns only Android-free logic (inference runs behind ScreeningEngine), so the camera state
 * machine, screening orchestration and result/analytics assembly are testable with plain MockK.
 */
class CameraxLauncherViewModelTest {

    private val screeningEngine: ScreeningEngine = mockk()
    private lateinit var viewModel: CameraxLauncherViewModel

    @Before
    fun setUp() {
        // ScreeningTimer / PostHogAnalytics are singletons with side effects (timers + the network
        // SDK); stub them so the unit-level logic runs in isolation.
        mockkObject(ScreeningTimer)
        mockkObject(PostHogAnalytics)
        every { ScreeningTimer.markStep(any(), any()) } just runs
        every { ScreeningTimer.incrementRetake(any()) } returns 1
        every { ScreeningTimer.incrementPhoto(any()) } returns 1
        every { PostHogAnalytics.capture(any(), any()) } just runs

        viewModel = CameraxLauncherViewModel(screeningEngine)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ───────────────────────────── state machine ─────────────────────────────

    @Test
    fun initialStateHasShutterDisabledInCaptureMode() {
        val state = viewModel.uiState.value
        assertEquals(CameraMode.CAPTURE, state.mode)
        assertFalse(state.shutterEnabled)
        assertFalse(state.isCapturing)
        assertFalse(state.flashOn)
        assertFalse(state.zoomIndicatorVisible)
        assertNull(state.capturedFilePath)
    }

    @Test
    fun onCameraBoundEnablesShutterTorchAndCaptureMode() {
        viewModel.onCameraBound()

        val state = viewModel.uiState.value
        assertEquals(CameraMode.CAPTURE, state.mode)
        assertTrue(state.shutterEnabled)
        assertTrue(state.flashOn)
    }

    @Test
    fun onCameraBindingDisablesShutter() {
        viewModel.onCameraBound()
        viewModel.onCameraBinding()

        assertFalse(viewModel.uiState.value.shutterEnabled)
    }

    @Test
    fun beginCaptureFirstCallStartsCaptureSecondCallIsRejected() {
        val first = viewModel.beginCapture()
        val second = viewModel.beginCapture()

        assertTrue(first)
        assertFalse(second)
        assertTrue(viewModel.uiState.value.isCapturing)
        assertFalse(viewModel.uiState.value.shutterEnabled)
        verify(exactly = 1) { ScreeningTimer.markStep(any(), "photo_capture_started") }
    }

    @Test
    fun onCaptureSavedSwitchesToPreviewAndComputesDuration() {
        var now = 1_000L
        viewModel.elapsedRealtimeProvider = { now }

        viewModel.beginCapture() // captureStartedMs = 1000
        now = 1_450L
        viewModel.onCaptureSaved("/data/IMG_1.jpeg") // captureSavedMs = 1450

        val state = viewModel.uiState.value
        assertEquals(CameraMode.PREVIEW, state.mode)
        assertFalse(state.shutterEnabled)
        assertFalse(state.flashOn)
        assertEquals("/data/IMG_1.jpeg", state.capturedFilePath)
        assertEquals(450L, viewModel.timeToCaptureMs)
        verify { ScreeningTimer.markStep(any(), "photo_capture_completed") }
    }

    @Test
    fun onCaptureErrorClearsCapturingFlag() {
        viewModel.beginCapture()
        viewModel.onCaptureError()

        assertFalse(viewModel.uiState.value.isCapturing)
    }

    @Test
    fun onCaptureFailedSynchronouslyReEnablesShutter() {
        viewModel.beginCapture()
        viewModel.onCaptureFailedSynchronously()

        val state = viewModel.uiState.value
        assertFalse(state.isCapturing)
        assertTrue(state.shutterEnabled)
    }

    @Test
    fun onRetakeReturnsToCaptureModeWithShutterDisabledAndIncrementsRetake() {
        viewModel.onCaptureSaved("/data/IMG_1.jpeg")
        viewModel.onRetake()

        val state = viewModel.uiState.value
        assertEquals(CameraMode.CAPTURE, state.mode)
        assertFalse(state.shutterEnabled)
        assertFalse(state.flashOn)
        assertFalse(state.isCapturing)
        assertNull(state.capturedFilePath)
        verify { ScreeningTimer.incrementRetake(any()) }
    }

    @Test
    fun toggleFlashFlipsFlashState() {
        assertFalse(viewModel.uiState.value.flashOn)
        viewModel.toggleFlash()
        assertTrue(viewModel.uiState.value.flashOn)
        viewModel.toggleFlash()
        assertFalse(viewModel.uiState.value.flashOn)
    }

    @Test
    fun toggleZoomIndicatorFlipsVisibility() {
        assertFalse(viewModel.uiState.value.zoomIndicatorVisible)
        viewModel.toggleZoomIndicator()
        assertTrue(viewModel.uiState.value.zoomIndicatorVisible)
        viewModel.toggleZoomIndicator()
        assertFalse(viewModel.uiState.value.zoomIndicatorVisible)
    }

    @Test
    fun processingStateTogglesForSubmitOverlay() {
        assertFalse(viewModel.uiState.value.isProcessing)
        viewModel.onProcessingStarted()
        assertTrue(viewModel.uiState.value.isProcessing)
        viewModel.onProcessingFinished()
        assertFalse(viewModel.uiState.value.isProcessing)
    }

    // ───────────────────────────── AI gate ─────────────────────────────

    @Test
    fun isAiInferenceEnabledDelegatesToTheScreeningEngine() = runBlocking {
        coEvery { screeningEngine.isAiActive() } returns true
        assertTrue(viewModel.isAiInferenceEnabled())

        coEvery { screeningEngine.isAiActive() } returns false
        assertFalse(viewModel.isAiInferenceEnabled())
    }

    // ───────────────────────────── result extras ─────────────────────────────

    @Test
    fun buildResultExtrasWithAResultCarriesItAsJson() {
        val extras = viewModel.buildResultExtras(aiEnabled = true, screening = screening())

        assertEquals(2, extras.size)
        assertEquals(
            CameraxLauncherFragment.AI_STATUS_COMPLETED,
            extras[CameraxLauncherFragment.CAMERA_AI_STATUS_KEY],
        )
        assertEquals(
            screening(),
            ImageScreeningResult.fromJson(extras.getValue(CameraxLauncherFragment.CAMERA_SCREENING_RESULT_KEY)),
        )
    }

    @Test
    fun buildResultExtrasWithoutResultButAiEnabledReportsAFailure() {
        val extras = viewModel.buildResultExtras(aiEnabled = true, screening = null)

        assertEquals(
            mapOf(CameraxLauncherFragment.CAMERA_AI_STATUS_KEY to CameraxLauncherFragment.AI_STATUS_FAILED),
            extras,
        )
    }

    @Test
    fun buildResultExtrasWithoutResultAndAiDisabledIsEmpty() {
        val extras = viewModel.buildResultExtras(aiEnabled = false, screening = null)
        assertTrue(extras.isEmpty())
    }

    // ───────────────────────────── analytics props ─────────────────────────────

    @Test
    fun buildPhotoCapturedPropsIncludesTimingQualityDeviceAndInference() {
        var now = 1_000L
        viewModel.elapsedRealtimeProvider = { now }
        viewModel.setScreeningId("scr-1")
        viewModel.beginCapture() // started = 1000
        now = 1_400L
        viewModel.onCaptureSaved("/data/IMG_1.jpeg") // saved = 1400, duration = 400
        now = 1_700L // props built at 1700 -> captureToResult = 300

        val props =
            viewModel.buildPhotoCapturedProps(
                screening = screening(confidence = 0.9f, inferenceTimeMs = 250L),
                qualityProps = mapOf("blur_score" to 0.1),
                deviceMetrics = mapOf("device_model" to "Pixel"),
            )

        // The legacy label and confidence format dashboards were built on.
        assertEquals("Suspicious", props[PostHogAnalytics.Props.AI_PREDICTION])
        assertEquals("90", props[PostHogAnalytics.Props.AI_CONFIDENCE])
        assertEquals("acme-oral", props[PostHogAnalytics.Props.MODEL_ID])
        assertEquals("suspicious", props[PostHogAnalytics.Props.AI_CLASS])
        assertEquals("POSITIVE", props[PostHogAnalytics.Props.AI_OUTCOME])
        assertEquals("scr-1", props[PostHogAnalytics.Props.SCREENING_ID])
        assertEquals(400L, props[PostHogAnalytics.Props.TIME_TO_CAPTURE_MS])
        assertEquals(300L, props[PostHogAnalytics.Props.CAPTURE_TO_RESULT_MS])
        assertEquals(0.1, props["blur_score"])
        assertEquals("Pixel", props["device_model"])
        assertEquals(250L, props[PostHogAnalytics.Props.INFERENCE_TIME_MS])
        assertEquals(250L, props[PostHogAnalytics.Props.COMBINED_INFERENCE_TIME_MS])
    }

    @Test
    fun buildPhotoCapturedPropsOmitsInferenceTimesWhenNull() {
        val props =
            viewModel.buildPhotoCapturedProps(
                screening = null,
                qualityProps = null,
                deviceMetrics = emptyMap(),
            )

        assertFalse(props.containsKey(PostHogAnalytics.Props.INFERENCE_TIME_MS))
        assertFalse(props.containsKey(PostHogAnalytics.Props.COMBINED_INFERENCE_TIME_MS))
        assertEquals("", props[PostHogAnalytics.Props.AI_PREDICTION])
        assertEquals("", props[PostHogAnalytics.Props.AI_CONFIDENCE])
    }

    // ───────────────────────────── prepare capture ─────────────────────────────

    @Test
    fun preparePhotoCaptureWithAiEnabledReturnsProcessedMessageAndCapturesEvent() {
        val result =
            viewModel.preparePhotoCapture(
                absolutePath = "/data/IMG_1.jpeg",
                aiEnabled = true,
                screening = screening(),
                qualityProps = emptyMap(),
                deviceMetrics = emptyMap(),
            )

        assertEquals("/data/IMG_1.jpeg", result.uri)
        assertEquals(CameraxLauncherViewModel.MESSAGE_IMAGE_PROCESSED, result.toastMessage)
        assertTrue(result.stringExtras.containsKey(CameraxLauncherFragment.CAMERA_SCREENING_RESULT_KEY))
        verify { ScreeningTimer.incrementPhoto(any()) }
        verify { PostHogAnalytics.capture(PostHogAnalytics.Events.PHOTO_CAPTURED, any()) }
    }

    @Test
    fun preparePhotoCaptureWithAiDisabledReturnsSavedMessageAndNoExtras() {
        val result =
            viewModel.preparePhotoCapture(
                absolutePath = "/data/IMG_2.jpeg",
                aiEnabled = false,
                screening = null,
                qualityProps = null,
                deviceMetrics = emptyMap(),
            )

        assertEquals(CameraxLauncherViewModel.MESSAGE_IMAGE_SAVED, result.toastMessage)
        assertTrue(result.stringExtras.isEmpty())
        verify { PostHogAnalytics.capture(PostHogAnalytics.Events.PHOTO_CAPTURED, any()) }
    }

    private fun screening(confidence: Float = 0.825f, inferenceTimeMs: Long = 100L) =
        ImageScreeningResult(
            modelId = "acme-oral",
            modelVersion = "1.0.0",
            classId = "suspicious",
            outcome = Outcome.POSITIVE,
            displayName = "Suspicious",
            confidence = confidence,
            lowConfidence = false,
            entropy = 0.4,
            scores = mapOf("suspicious" to confidence, "non_suspicious" to 1 - confidence),
            inferenceTimeMs = inferenceTimeMs,
        )
}
