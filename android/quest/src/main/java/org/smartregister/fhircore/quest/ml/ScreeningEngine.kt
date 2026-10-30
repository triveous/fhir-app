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

import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.annotation.VisibleForTesting
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.smartregister.fhircore.ai.AI_API_VERSION
import org.smartregister.fhircore.ai.ClassDescriptor
import org.smartregister.fhircore.ai.ModelDescriptor
import org.smartregister.fhircore.ai.ScreeningImage
import org.smartregister.fhircore.ai.ScreeningModel
import org.smartregister.fhircore.ai.ScreeningOutputValidator
import org.smartregister.fhircore.engine.util.FeatureFlagUtil
import org.smartregister.fhircore.quest.BuildConfig
import org.smartregister.fhircore.quest.util.PostHogAnalytics
import org.smartregister.fhircore.quest.util.ScreeningTimer
import timber.log.Timber

/** Whether this install can screen photos, and with which model. */
enum class AiModelState {
  /** No model in the APK, or the tenant's `ai-model-id` names one the APK lacks. */
  NOT_INSTALLED,

  /** Several models and nothing says which one to use. */
  AMBIGUOUS,

  /** Installed; loads on first use. */
  NOT_LOADED,

  READY,

  /** Installed but unusable: registered under the wrong id, load error, API mismatch. */
  LOAD_FAILED,
}

data class AiModelStatus(
  val state: AiModelState,
  val modelId: String? = null,
  val modelVersion: String? = null,
  val problem: String? = null,
) {
  /** True when a model is selected and not known to be broken. */
  val isUsable: Boolean
    get() = state == AiModelState.READY || state == AiModelState.NOT_LOADED

  /** True when a model this app carries is selected, whether or not it works. */
  val isSelected: Boolean
    get() = isUsable || state == AiModelState.LOAD_FAILED
}

/**
 * The app's single entry point to AI screening. Knows every installed model (the code plugins
 * bound with Hilt, see [ScreeningModel]), picks the active one, loads it once per process, and turns
 * its output into an [ImageScreeningResult].
 *
 * The model is a black box: anything it throws, any output that breaks the contract, and any call
 * slower than [classifyTimeoutMs] becomes "no AI result" for that photo (null), reported to
 * telemetry. It never crashes the app and is never read as a negative result.
 */
@Singleton
class ScreeningEngine
@Inject
constructor(
  @ApplicationContext private val context: Context,
  private val pluginModels: Map<String, @JvmSuppressWildcards ScreeningModel>,
  private val featureFlagUtil: FeatureFlagUtil,
) {

  /** Build-time default (-PaiModelId). Overridable for tests. */
  @VisibleForTesting var defaultModelId: String = BuildConfig.DEFAULT_AI_MODEL_ID

  @VisibleForTesting var classifyTimeoutMs: Long = DEFAULT_CLASSIFY_TIMEOUT_MS

  /** Runs classifications; a timed-out call is abandoned here rather than blocking the caller. */
  private val classifyScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  /** Serialises loading only; the state below is read without it so status() never waits on a load. */
  private val loadMutex = Mutex()
  private val loadedIds: MutableSet<String> = ConcurrentHashMap.newKeySet()
  private val loadErrors = ConcurrentHashMap<String, String>()

  private val discovered: Map<String, Installed> by lazy { discover() }

  /** Installed models, discovered once per process. Reading descriptors may touch plugin code, so IO. */
  private suspend fun installed(): Map<String, Installed> = withContext(Dispatchers.IO) { discovered }

  /** Every installed model id, sorted. */
  suspend fun installedModelIds(): List<String> = installed().keys.sorted()

  /** The descriptor of an installed, usable model; null otherwise. */
  suspend fun descriptor(modelId: String): ModelDescriptor? =
    (installed()[modelId] as? Installed.Usable)?.model?.descriptor

  /** A class of an installed model, e.g. to show its result-screen text. */
  suspend fun classDescriptor(modelId: String?, classId: String?): ClassDescriptor? =
    if (modelId == null || classId == null) null else descriptor(modelId)?.classById(classId)

  /**
   * True when photos should be screened: AI is on for the tenant and a model is selected. With no
   * model installed, capture and submission carry on exactly as with AI switched off — the default
   * build. A selected model that is broken still counts: its photos are recorded as "AI failed" and
   * the case comes out inconclusive, so a bad model is visible instead of silently screening nothing.
   */
  suspend fun isAiActive(): Boolean = featureFlagUtil.isAiInferenceEnabled() && status().isSelected

  suspend fun status(): AiModelStatus {
    val selected = select()
    val id = selected.modelId ?: return AiModelStatus(selected.state, problem = selected.problem)
    return when (val entry = installed()[id]) {
      null ->
        AiModelStatus(
          AiModelState.NOT_INSTALLED,
          id,
          problem = "Model '$id' is not in this app. Installed: ${installedModelIds().ifEmpty { listOf("none") }}",
        )
      is Installed.Broken -> AiModelStatus(AiModelState.LOAD_FAILED, id, problem = entry.problem)
      is Installed.Usable -> {
        val version = entry.model.descriptor.version
        val error = loadErrors[id]
        val loaded = id in loadedIds
        when {
          error != null -> AiModelStatus(AiModelState.LOAD_FAILED, id, version, error)
          loaded -> AiModelStatus(AiModelState.READY, id, version)
          else -> AiModelStatus(AiModelState.NOT_LOADED, id, version)
        }
      }
    }
  }

  /**
   * Loads the active model ahead of the first photo. Returns true when it is ready. A failed load
   * is reported and retried on the next call. Safe to call repeatedly and concurrently.
   */
  suspend fun warmUp(): Boolean = activeModel() != null

  /**
   * Screens one photo with the active model. Null when there is no usable model, or when the model
   * failed, timed out or broke the contract (all reported to telemetry).
   */
  suspend fun screen(file: File, screeningId: String?): ImageScreeningResult? {
    val model = activeModel() ?: return null
    val descriptor = model.descriptor
    val startedMs = SystemClock.elapsedRealtime()
    ScreeningTimer.markStep(screeningId, "ai_inference_started")
    return try {
      val call = classifyScope.async { model.classify(ScreeningImage(file, captureMetadata())) }
      val output =
        withTimeoutOrNull(classifyTimeoutMs) { call.await() }
          ?: run {
            call.cancel()
            reportFailure(descriptor.id, "Model '${descriptor.id}' took over $classifyTimeoutMs ms")
            return null
          }
      ScreeningOutputValidator.validate(descriptor, output)
      val result = ImageScreeningResult.from(descriptor, output, SystemClock.elapsedRealtime() - startedMs)
      captureInference(screeningId, result)
      ScreeningTimer.markStep(screeningId, "ai_inference_completed")
      Timber.d("AI %s: %s (%.3f)", descriptor.id, result.classId, result.confidence)
      result
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Timber.e(e, "AI model %s failed on %s", descriptor.id, file.name)
      reportFailure(descriptor.id, "Image processing failed: ${e.message}")
      null
    }
  }

  /** Reports that AI is on but can't run, so support sees why photos have no AI result. */
  suspend fun reportUnavailable(screeningId: String?) {
    val status = status()
    PostHogAnalytics.capture(
      PostHogAnalytics.Events.AI_MODEL_UNAVAILABLE,
      mapOf(
        PostHogAnalytics.Props.SCREENING_ID to screeningId,
        PostHogAnalytics.Props.MODEL_ID to status.modelId,
        PostHogAnalytics.Props.AI_MODEL_STATE to status.state.name,
        PostHogAnalytics.Props.ERROR_MESSAGE to status.problem,
      ),
    )
  }

  private suspend fun activeModel(): ScreeningModel? {
    val id = select().modelId ?: return null
    val model = (installed()[id] as? Installed.Usable)?.model ?: return null
    if (id in loadedIds) return model
    return loadMutex.withLock {
      if (id in loadedIds) return@withLock model
      try {
        model.warmUp(context)
        loadedIds += id
        loadErrors.remove(id)
        model
      } catch (e: CancellationException) {
        throw e
      } catch (e: Throwable) {
        // Throwable: a native library that fails to load surfaces as UnsatisfiedLinkError.
        Timber.e(e, "AI model %s failed to load", id)
        loadErrors[id] = e.message ?: e.javaClass.simpleName
        PostHogAnalytics.captureError(ERROR_SOURCE, "Model $id load failed: ${e.message}")
        null
      }
    }
  }

  private data class Selection(
    val modelId: String?,
    val state: AiModelState = AiModelState.NOT_INSTALLED,
    val problem: String? = null,
  )

  /** Tenant flag, then build default, then the only installed model. */
  private suspend fun select(): Selection {
    val tenantChoice =
      try {
        featureFlagUtil.getAiModelId()
      } catch (e: Exception) {
        Timber.w(e, "Could not read the ai-model-id flag")
        null
      }
    tenantChoice?.let {
      return Selection(it)
    }
    defaultModelId.takeIf { it.isNotBlank() }?.let {
      return Selection(it)
    }
    val installed = installed()
    return when (installed.size) {
      0 -> Selection(null, AiModelState.NOT_INSTALLED, "No AI model is installed in this app")
      1 -> Selection(installed.keys.single())
      else ->
        Selection(
          null,
          AiModelState.AMBIGUOUS,
          "Several AI models are installed (${installedModelIds()}); build with -PaiModelId or set " +
            "the tenant's ai-model-id flag",
        )
    }
  }

  private fun discover(): Map<String, Installed> {
    val found = linkedMapOf<String, Installed>()
    pluginModels.toSortedMap().forEach { (key, model) ->
      found[key] =
        when {
          model.descriptor.id != key ->
            Installed.Broken("Plugin registered as '$key' but its descriptor id is '${model.descriptor.id}'")
          model.descriptor.minAppApiVersion > AI_API_VERSION ->
            Installed.Broken(
              "Model '$key' needs app API ${model.descriptor.minAppApiVersion}; this app has $AI_API_VERSION",
            )
          else -> Installed.Usable(model)
        }
    }
    Timber.i("AI models installed: %s", found.keys.ifEmpty { setOf("none") })
    return found
  }

  private fun captureMetadata(): Map<String, String> =
    mapOf(
      ScreeningImage.MetadataKeys.DEVICE_MODEL to Build.MODEL.orEmpty(),
      ScreeningImage.MetadataKeys.DEVICE_MANUFACTURER to Build.MANUFACTURER.orEmpty(),
      ScreeningImage.MetadataKeys.OS_VERSION to Build.VERSION.SDK_INT.toString(),
    )

  /**
   * One `model_inference_completed` per ensemble member (named by the model's components), then one
   * for the overall result, named `ensemble` when there are members and the model id otherwise.
   */
  private fun captureInference(screeningId: String?, result: ImageScreeningResult) {
    result.components.forEach { component ->
      PostHogAnalytics.capture(
        PostHogAnalytics.Events.MODEL_INFERENCE_COMPLETED,
        inferenceProps(screeningId, result) +
          mapOf(
            PostHogAnalytics.Props.MODEL_NAME to component.name,
            PostHogAnalytics.Props.MODEL_PREDICTION to component.classId,
            PostHogAnalytics.Props.AI_OUTCOME to component.outcome.name,
            PostHogAnalytics.Props.MODEL_CONFIDENCE to component.confidencePercent,
            PostHogAnalytics.Props.MODEL_ENTROPY to component.entropy,
            PostHogAnalytics.Props.LOW_CONFIDENCE to component.lowConfidence,
            PostHogAnalytics.Props.COMBINED_INFERENCE_TIME_MS to null,
          ),
      )
    }
    PostHogAnalytics.capture(
      PostHogAnalytics.Events.MODEL_INFERENCE_COMPLETED,
      inferenceProps(screeningId, result) +
        mapOf(
          PostHogAnalytics.Props.MODEL_NAME to
            if (result.components.isEmpty()) result.modelId else ENSEMBLE_MODEL_NAME,
          PostHogAnalytics.Props.MODEL_PREDICTION to result.classId,
          PostHogAnalytics.Props.AI_OUTCOME to result.outcome.name,
          PostHogAnalytics.Props.MODEL_CONFIDENCE to result.confidencePercent,
          PostHogAnalytics.Props.MODEL_ENTROPY to result.entropy,
          PostHogAnalytics.Props.LOW_CONFIDENCE to result.lowConfidence,
          PostHogAnalytics.Props.COMBINED_INFERENCE_TIME_MS to result.inferenceTimeMs,
        ),
    )
  }

  private fun inferenceProps(screeningId: String?, result: ImageScreeningResult): Map<String, Any?> =
    mapOf(
      PostHogAnalytics.Props.SCREENING_ID to screeningId,
      PostHogAnalytics.Props.MODEL_ID to result.modelId,
      PostHogAnalytics.Props.MODEL_VERSION to result.modelVersion,
      PostHogAnalytics.Props.INFERENCE_TIME_MS to result.inferenceTimeMs,
      PostHogAnalytics.Props.MODEL_OUTPUT_COUNT to result.scores.size,
    )

  private fun reportFailure(modelId: String, message: String) {
    PostHogAnalytics.captureError(ERROR_SOURCE, "[$modelId] $message")
  }

  private sealed class Installed {
    class Usable(val model: ScreeningModel) : Installed()

    class Broken(val problem: String) : Installed()
  }

  companion object {
    const val DEFAULT_CLASSIFY_TIMEOUT_MS = 15_000L
    const val ENSEMBLE_MODEL_NAME = "ensemble"
    private const val ERROR_SOURCE = "ScreeningEngine"
  }
}
