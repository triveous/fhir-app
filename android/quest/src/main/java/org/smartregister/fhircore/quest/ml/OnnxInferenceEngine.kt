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

import ai.onnxruntime.OrtSession
import ai.onnxruntime.providers.NNAPIFlags
import android.content.Context
import android.os.SystemClock
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.EnumSet
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import org.smartregister.fhircore.quest.util.PostHogAnalytics
import timber.log.Timber

/**
 * App-scoped owner of the ONNX screening models. Each model's sessions are created once per process
 * (not once per camera dialog, as the PyTorch modules are) straight from the asset bytes, so
 * nothing is copied to `filesDir`.
 *
 * Loading is lazy and thread-safe: [warmUp] and [run] may race and a model is still created
 * exactly once. A failed load is not cached, so the next call retries.
 */
@Singleton
class OnnxInferenceEngine @Inject constructor(@ApplicationContext private val context: Context) {

  private val bundledAssets: Set<String> by lazy { context.assets.list("").orEmpty().toSet() }

  /** True when every `.onnx` file [model] lists is bundled in the APK. */
  fun isBundled(model: ModelDescriptor): Boolean =
    model.onnxAssets.isNotEmpty() && model.onnxAssets.all { it in bundledAssets }

  private val loaded = ConcurrentHashMap<String, LoadedModel>()
  private val loadLock = Any()

  /** Loads [model] and runs one warm-up pass. Blocking; call off the main thread. */
  fun warmUp(model: ModelDescriptor) {
    load(model)
  }

  /** The input [model] declares, loading it if needed. Blocking. */
  fun inputSpec(model: ModelDescriptor): ModelInputSpec = load(model).runner.inputSpec

  /**
   * Runs the members of [model] in [members] on [bgrHwc] (the bytes of a CV_8UC3 Mat sized to
   * [inputSpec]). Blocking.
   */
  fun run(model: ModelDescriptor, members: List<ModelMember>, bgrHwc: ByteArray): OnnxRunResult {
    val loadedModel = load(model)
    val indices = members.map { member -> model.members.indexOfFirst { it.id == member.id } }
    val startedMs = SystemClock.elapsedRealtime()
    val outputs = loadedModel.runner.runEach(bgrHwc, indices)
    return OnnxRunResult(
      memberOutputs = outputs,
      inferenceTimeMs = SystemClock.elapsedRealtime() - startedMs,
      executionProvider = loadedModel.executionProvider,
    )
  }

  private fun load(model: ModelDescriptor): LoadedModel {
    loaded[model.id]?.let {
      return it
    }
    synchronized(loadLock) {
      loaded[model.id]?.let {
        return it
      }
      val startedMs = SystemClock.elapsedRealtime()
      val files = model.onnxAssets.map { name -> context.assets.open(name).use { it.readBytes() } }
      val (runner, provider) = createRunner(model, files, OnnxModelContract.EXECUTION_PROVIDER)
      runner.warmUp()
      Timber.i(
        "ONNX model %s ready on %s in %d ms, input %s",
        model.id,
        provider.telemetryName,
        SystemClock.elapsedRealtime() - startedMs,
        runner.inputSpec,
      )
      return LoadedModel(runner, provider).also { loaded[model.id] = it }
    }
  }

  /**
   * Accelerated providers do not throw on unsupported hardware — ORT just assigns the nodes it
   * cannot place to CPU — so the only fallback needed here is for a session that fails to build.
   */
  private fun createRunner(
    model: ModelDescriptor,
    files: List<ByteArray>,
    preferred: ExecutionProvider,
  ): Pair<OnnxModelRunner, ExecutionProvider> {
    fun runner(options: OrtSession.SessionOptions) =
      OnnxModelRunner(
        models = files,
        sessionOptions = options,
        preprocessing = model.inputPreprocessing,
        outputName = model.onnxOutputName,
      )
    if (preferred != ExecutionProvider.CPU) {
      try {
        return sessionOptions(preferred).use(::runner) to preferred
      } catch (e: Exception) {
        Timber.w(e, "Falling back to CPU: %s session failed", preferred.telemetryName)
        PostHogAnalytics.captureError(
          "OnnxInferenceEngine",
          "${preferred.telemetryName} session failed, using cpu: ${e.message}",
        )
      }
    }
    return sessionOptions(ExecutionProvider.CPU).use(::runner) to ExecutionProvider.CPU
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

/** Each member's outputs from one run, the wall time of the session run(s) and where they ran. */
class OnnxRunResult(
  val memberOutputs: List<FloatArray>,
  val inferenceTimeMs: Long,
  val executionProvider: ExecutionProvider,
) {
  /** Every output value, flattened in member order. */
  val values: FloatArray
    get() = memberOutputs.fold(FloatArray(0)) { all, values -> all + values }
}
