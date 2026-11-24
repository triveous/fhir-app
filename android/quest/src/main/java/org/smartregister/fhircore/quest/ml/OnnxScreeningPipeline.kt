/*
 * Copyright 2026 Indian Institute of Science (IISc)
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
 * The ONNX pipeline for any model in [ModelCatalog] whose `.onnx` files are bundled:
 *
 * 1. Read the photo with OpenCV as BGR uint8, turned per its EXIF orientation or not
 *    ([ImageLoading.applyExifRotation]).
 * 2. Resize to the size the model declares, with the model's [ImageLoading.resize].
 * 3. Pack into the model's input tensor. Models with a uint8 input get the raw pixels; float
 *    models get them through the descriptor's [InputPreprocessing]. See [ModelInputSpec].
 * 4. Run the members the screening keeps, reading the output the descriptor names.
 * 5. Read them per the model's descriptor and combine them with the rule
 *    ([ScreeningOutputInterpreter]), emitting the same result keys and analytics as before.
 *
 * For the 2-class model the camera fragment falls back to the PyTorch pipeline when its `.onnx`
 * files aren't bundled ([canRun] is false).
 */
@Singleton
class OnnxScreeningPipeline @Inject constructor(private val engine: OnnxInferenceEngine) {

  /** True when [model]'s `.onnx` files are bundled. */
  fun canRun(model: ModelDescriptor): Boolean = engine.isBundled(model)

  /** Creates the ORT sessions ahead of the first capture. Failures are reported, not thrown. */
  suspend fun warmUp(model: ModelDescriptor) {
    withContext(Dispatchers.Default) {
      try {
        engine.warmUp(model)
      } catch (e: Exception) {
        Timber.e(e, "ONNX model %s failed to load", model.id)
        PostHogAnalytics.captureError(ERROR_SOURCE, "Model ${model.id} load failed: ${e.message}")
      }
    }
  }

  /**
   * Blocking; call on a background dispatcher. Returns `null` (after reporting the error) when the
   * image cannot be processed — the same contract as the PyTorch `processImage`.
   */
  fun process(
    active: ActiveAiModel,
    absolutePath: String,
    screeningId: String?,
    runAiInference: Boolean,
  ): Result? {
    val cleanup = mutableListOf<Mat>()
    return try {
      val model = active.model
      val loading = model.imageLoading
      val original = OpenCVUtils.readBgr(absolutePath, loading.applyExifRotation)
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

      val spec = engine.inputSpec(model)
      val modelInput =
        when (loading.resize) {
          ResizeMethod.BICUBIC ->
            if (spec.width == analyzed.width() && spec.height == analyzed.height()) {
              analyzed
            } else {
              OpenCVUtils.resizeBicubic(original, spec.width, spec.height).also(cleanup::add)
            }
          ResizeMethod.BILINEAR_ANTIALIASED ->
            OpenCVUtils.resizeBilinearAntialiased(original, spec.width, spec.height)
              .also(cleanup::add)
        }

      ScreeningTimer.markStep(screeningId, "ai_inference_started")
      val members = model.activeMembers(active.rule)
      val run = engine.run(model, members, OpenCVUtils.toBgrBytes(modelInput))
      val classification =
        ScreeningOutputInterpreter.interpret(model, active.rule, active.ruleSource, run.memberOutputs)
      // One session run per member; each member reports the whole run's time, as before.
      classification.captureInferenceEvents(
        screeningId = screeningId,
        modelVersion = model.telemetryVersion,
        memberTimesMs = null,
        combinedTimeMs = run.inferenceTimeMs,
        extraProps =
          mapOf(
            PostHogAnalytics.Props.EXECUTION_PROVIDER to run.executionProvider.telemetryName,
            PostHogAnalytics.Props.MODEL_OUTPUT_COUNT to run.values.size,
          ),
      )
      ScreeningTimer.markStep(screeningId, "ai_inference_completed")

      Timber.d(
        "ONNX %s outputs %s -> %s",
        model.id,
        run.values.contentToString(),
        classification.finalClass.id,
      )
      Result(classification.toResultMap(), qualityProps, run.inferenceTimeMs)
    } catch (e: Exception) {
      Timber.e(e, "ONNX image processing failed")
      PostHogAnalytics.captureError(ERROR_SOURCE, "Image processing failed: ${e.message}")
      null
    } finally {
      cleanup.forEach { it.release() }
    }
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
