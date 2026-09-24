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

package org.smartregister.fhircore.quest.ml

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencv.core.Mat
import org.smartregister.fhircore.quest.util.ImageQualityAnalyzer
import org.smartregister.fhircore.quest.util.OpenCVUtils
import org.smartregister.fhircore.quest.util.PostHogAnalytics
import org.smartregister.fhircore.quest.util.ScreeningTimer
import timber.log.Timber

/**
 * The ONNX replacement for the camera fragment's PyTorch `processImage`:
 *
 * 1. Read the photo with OpenCV as BGR uint8.
 * 2. Resize (bicubic) to the size the model declares.
 * 3. Pack into the model's input tensor — no normalisation; see [ModelInputSpec].
 * 4. Run the model; its outputs are already probabilities (Sigmoid is in the graph).
 * 5. Interpret them exactly as before and emit the same result keys and analytics.
 *
 * Active only when the model is bundled ([isModelBundled]); otherwise the fragment keeps using the
 * PyTorch pipeline.
 */
@Singleton
class OnnxScreeningPipeline @Inject constructor(private val engine: OnnxInferenceEngine) {

  val isModelBundled: Boolean
    get() = engine.isModelBundled

  /** Creates the ORT session ahead of the first capture. Failures are reported, not thrown. */
  suspend fun warmUp() {
    withContext(Dispatchers.Default) {
      try {
        engine.warmUp()
      } catch (e: Exception) {
        Timber.e(e, "ONNX model failed to load")
        PostHogAnalytics.captureError(ERROR_SOURCE, "Model load failed: ${e.message}")
      }
    }
  }

  /**
   * Blocking; call on a background dispatcher. Returns `null` (after reporting the error) when the
   * image cannot be processed — the same contract as the PyTorch `processImage`.
   */
  fun process(absolutePath: String, screeningId: String?, runAiInference: Boolean): Result? {
    val cleanup = mutableListOf<Mat>()
    return try {
      val original = OpenCVUtils.readBgr(absolutePath)
      if (original == null) {
        PostHogAnalytics.captureError(
          ERROR_SOURCE,
          "Image processing failed: unable to decode image",
        )
        return null
      }
      cleanup += original

      // The analyzer sees the same 256×256 bicubic image size as before, but now un-normalised,
      // so brightness_mean reports real exposure instead of the grey-world constant ~128.
      val analyzed =
        OpenCVUtils.resizeBicubic(original, QUALITY_ANALYSIS_SIZE, QUALITY_ANALYSIS_SIZE)
          .also(cleanup::add)
      val qualityProps = ImageQualityAnalyzer.analyze(analyzed)
      if (!runAiInference) return Result(emptyMap(), qualityProps, 0L)

      val spec = engine.inputSpec()
      val modelInput =
        if (spec.width == analyzed.width() && spec.height == analyzed.height()) {
          analyzed
        } else {
          OpenCVUtils.resizeBicubic(original, spec.width, spec.height).also(cleanup::add)
        }

      ScreeningTimer.markStep(screeningId, "ai_inference_started")
      val run = engine.run(OpenCVUtils.toBgrBytes(modelInput))
      val prediction = ScreeningOutputInterpreter.interpret(run.values)
      prediction.components.forEach { captureModelInference(screeningId, it, run, null) }
      captureModelInference(screeningId, prediction.ensemble, run, run.inferenceTimeMs)
      ScreeningTimer.markStep(screeningId, "ai_inference_completed")

      Timber.d(
        "ONNX outputs %s -> %s",
        run.values.contentToString(),
        prediction.ensemble.displayPrediction,
      )
      Result(prediction.toResultMap(), qualityProps, run.inferenceTimeMs)
    } catch (e: Exception) {
      Timber.e(e, "ONNX image processing failed")
      PostHogAnalytics.captureError(ERROR_SOURCE, "Image processing failed: ${e.message}")
      null
    } finally {
      cleanup.forEach { it.release() }
    }
  }

  /**
   * Same event and properties as the PyTorch pipeline, so dashboards keep working. One session
   * run now produces every component's value, so each component reports the whole run's time.
   */
  private fun captureModelInference(
    screeningId: String?,
    result: ModelPrediction,
    run: OnnxRunResult,
    combinedInferenceTimeMs: Long?,
  ) {
    PostHogAnalytics.capture(
      PostHogAnalytics.Events.MODEL_INFERENCE_COMPLETED,
      mapOf(
        PostHogAnalytics.Props.SCREENING_ID to screeningId,
        PostHogAnalytics.Props.MODEL_NAME to result.modelName,
        PostHogAnalytics.Props.MODEL_VERSION to OnnxModelContract.MODEL_VERSION,
        PostHogAnalytics.Props.MODEL_PREDICTION to result.prediction,
        PostHogAnalytics.Props.MODEL_CONFIDENCE to result.confidence,
        PostHogAnalytics.Props.MODEL_ENTROPY to result.entropy,
        PostHogAnalytics.Props.LOW_CONFIDENCE to result.lowConfidence,
        PostHogAnalytics.Props.INFERENCE_TIME_MS to run.inferenceTimeMs,
        PostHogAnalytics.Props.COMBINED_INFERENCE_TIME_MS to combinedInferenceTimeMs,
        PostHogAnalytics.Props.EXECUTION_PROVIDER to run.executionProvider.telemetryName,
        PostHogAnalytics.Props.MODEL_OUTPUT_COUNT to run.values.size,
      ),
    )
  }

  /** Mirrors the fragment's `ImageProcessingResult`. */
  data class Result(
    val resultMap: Map<String, Any>,
    val qualityProps: Map<String, Any>,
    val inferenceTimeMs: Long,
  )

  private companion object {
    const val ERROR_SOURCE = "OnnxScreeningPipeline"

    /** The image size ImageQualityAnalyzer has always been fed; keeps blur metrics comparable. */
    const val QUALITY_ANALYSIS_SIZE = 256
  }
}
