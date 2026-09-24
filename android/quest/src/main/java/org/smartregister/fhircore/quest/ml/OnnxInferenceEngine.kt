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

import ai.onnxruntime.OrtSession
import ai.onnxruntime.providers.NNAPIFlags
import android.content.Context
import android.os.SystemClock
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.EnumSet
import javax.inject.Inject
import javax.inject.Singleton
import org.smartregister.fhircore.quest.util.PostHogAnalytics
import timber.log.Timber

/**
 * App-scoped owner of the ONNX screening model. The session is created once per process (not once
 * per camera dialog, as the PyTorch modules are) straight from the asset bytes, so nothing is
 * copied to `filesDir`.
 *
 * Loading is lazy and thread-safe: [warmUp] and [run] may race and the model is still created
 * exactly once. A failed load is not cached, so the next call retries.
 */
@Singleton
class OnnxInferenceEngine @Inject constructor(@ApplicationContext private val context: Context) {

  /** True when every file in [OnnxModelContract.ASSET_NAMES] is bundled in the APK. */
  val isModelBundled: Boolean by lazy {
    val bundled = context.assets.list("").orEmpty().toSet()
    OnnxModelContract.ASSET_NAMES.all { it in bundled }
  }

  @Volatile private var loaded: LoadedModel? = null
  private val loadLock = Any()

  /** Loads the model and runs one warm-up pass. Blocking; call off the main thread. */
  fun warmUp() {
    load()
  }

  /** The input the bundled model declares, loading the model if needed. Blocking. */
  fun inputSpec(): ModelInputSpec = load().runner.inputSpec

  /** Runs the model on [bgrHwc] (the bytes of a CV_8UC3 Mat sized to [inputSpec]). Blocking. */
  fun run(bgrHwc: ByteArray): OnnxRunResult {
    val model = load()
    val startedMs = SystemClock.elapsedRealtime()
    val values = model.runner.run(bgrHwc)
    return OnnxRunResult(
      values = values,
      inferenceTimeMs = SystemClock.elapsedRealtime() - startedMs,
      executionProvider = model.executionProvider,
    )
  }

  private fun load(): LoadedModel {
    loaded?.let {
      return it
    }
    synchronized(loadLock) {
      loaded?.let {
        return it
      }
      val startedMs = SystemClock.elapsedRealtime()
      val models =
        OnnxModelContract.ASSET_NAMES.map { name -> context.assets.open(name).use { it.readBytes() } }
      val (runner, provider) = createRunner(models, OnnxModelContract.EXECUTION_PROVIDER)
      runner.warmUp()
      Timber.i(
        "ONNX model ready on %s in %d ms, input %s",
        provider.telemetryName,
        SystemClock.elapsedRealtime() - startedMs,
        runner.inputSpec,
      )
      return LoadedModel(runner, provider).also { loaded = it }
    }
  }

  /**
   * Accelerated providers do not throw on unsupported hardware — ORT just assigns the nodes it
   * cannot place to CPU — so the only fallback needed here is for a session that fails to build.
   */
  private fun createRunner(
    models: List<ByteArray>,
    preferred: ExecutionProvider,
  ): Pair<OnnxModelRunner, ExecutionProvider> {
    if (preferred != ExecutionProvider.CPU) {
      try {
        return sessionOptions(preferred).use { OnnxModelRunner(models, it) } to preferred
      } catch (e: Exception) {
        Timber.w(e, "Falling back to CPU: %s session failed", preferred.telemetryName)
        PostHogAnalytics.captureError(
          "OnnxInferenceEngine",
          "${preferred.telemetryName} session failed, using cpu: ${e.message}",
        )
      }
    }
    return sessionOptions(ExecutionProvider.CPU).use { OnnxModelRunner(models, it) } to
      ExecutionProvider.CPU
  }

  private fun sessionOptions(provider: ExecutionProvider) =
    OrtSession.SessionOptions().apply {
      setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
      when (provider) {
        ExecutionProvider.CPU -> Unit
        ExecutionProvider.XNNPACK -> {
          // ORT's guidance: XNNPACK owns the threads; a second intra-op pool would contend.
          setIntraOpNumThreads(1)
          addXnnpack(mapOf("intra_op_num_threads" to XNNPACK_THREADS.toString()))
        }
        ExecutionProvider.NNAPI -> addNnapi(EnumSet.of(NNAPIFlags.CPU_DISABLED))
      }
    }

  private class LoadedModel(val runner: OnnxModelRunner, val executionProvider: ExecutionProvider)

  private companion object {
    const val XNNPACK_THREADS = 2
  }
}

/** Every output value of one run, the wall time of the session run(s) and where they ran. */
class OnnxRunResult(
  val values: FloatArray,
  val inferenceTimeMs: Long,
  val executionProvider: ExecutionProvider,
)
