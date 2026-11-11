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

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxValue
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo

/**
 * Runs one or more ONNX models on a BGR image and returns every output value, flattened into a
 * single array in model order and then output order. With one model emitting `[1, N]` that is
 * simply its N values; with three single-output models it is their three values concatenated.
 *
 * Deliberately free of Android types so it runs on the JVM against the real ONNX Runtime in unit
 * tests. [OnnxInferenceEngine] owns the Android side (assets, execution provider, lifetime).
 *
 * `OrtSession.run` is thread-safe, so one runner may serve concurrent callers. The caller keeps
 * ownership of [sessionOptions]; the sessions are closed by [close].
 */
class OnnxModelRunner(
  models: List<ByteArray>,
  sessionOptions: OrtSession.SessionOptions,
  defaultInputSize: Int = OnnxModelContract.DEFAULT_INPUT_SIZE,
) : AutoCloseable {

  private val environment = OrtEnvironment.getEnvironment()
  private val sessions: List<OrtSession>
  private val inputSpecs: List<ModelInputSpec>

  init {
    require(models.isNotEmpty()) { "At least one ONNX model is required" }
    val created = mutableListOf<OrtSession>()
    try {
      models.forEach { created += environment.createSession(it, sessionOptions) }
      inputSpecs = created.map { inputSpecOf(it, defaultInputSize) }
      val first = inputSpecs.first()
      require(inputSpecs.all { it.height == first.height && it.width == first.width }) {
        "All models must take the same input size, found " +
          inputSpecs.joinToString { "${it.height}x${it.width}" }
      }
    } catch (e: Exception) {
      created.forEach { it.close() }
      throw e
    }
    sessions = created
  }

  /** The input of the first model; every model shares its height and width. */
  val inputSpec: ModelInputSpec
    get() = inputSpecs.first()

  /** Runs every model on [bgrHwc] (see [ModelInputSpec.packUint8]) and returns all outputs. */
  fun run(bgrHwc: ByteArray): FloatArray {
    val values = mutableListOf<Float>()
    sessions.forEachIndexed { index, session ->
      val spec = inputSpecs[index]
      createInputTensor(spec, bgrHwc).use { input ->
        // Result iterates in the session's declared output order.
        session.run(mapOf(spec.name to input)).use { result ->
          result.forEach { (name, value) -> values += value.toFloats(name) }
        }
      }
    }
    return values.toFloatArray()
  }

  /**
   * Runs one all-zero image through every model so the first real capture does not pay for ORT's
   * lazy kernel initialisation.
   */
  fun warmUp() {
    run(ByteArray(inputSpec.elementCount))
  }

  override fun close() {
    sessions.forEach { it.close() }
  }

  private fun createInputTensor(spec: ModelInputSpec, bgrHwc: ByteArray): OnnxTensor =
    when (spec.elementType) {
      InputElementType.UINT8 ->
        OnnxTensor.createTensor(environment, spec.packUint8(bgrHwc), spec.shape, OnnxJavaType.UINT8)
      InputElementType.FLOAT32 ->
        OnnxTensor.createTensor(environment, spec.packFloat32(bgrHwc), spec.shape)
    }

  private fun OnnxValue.toFloats(outputName: String): List<Float> {
    val tensor =
      this as? OnnxTensor
        ?: throw IllegalStateException("Model output '$outputName' is not a tensor: $info")
    return when (tensor.info.type) {
      OnnxJavaType.FLOAT -> tensor.floatBuffer.let { buffer -> List(buffer.remaining()) { buffer.get() } }
      OnnxJavaType.DOUBLE ->
        tensor.doubleBuffer.let { buffer -> List(buffer.remaining()) { buffer.get().toFloat() } }
      else ->
        throw IllegalStateException(
          "Model output '$outputName' is ${tensor.info.type}; expected float probabilities",
        )
    }
  }

  private companion object {
    fun inputSpecOf(session: OrtSession, defaultInputSize: Int): ModelInputSpec {
      require(session.numInputs == 1L) {
        "Expected a single image input, the model declares ${session.inputNames}"
      }
      val (name, node) = session.inputInfo.entries.single()
      val info =
        node.info as? TensorInfo
          ?: throw IllegalArgumentException("Model input '$name' is not a tensor: ${node.info}")
      val elementType =
        when (info.type) {
          OnnxJavaType.UINT8 -> InputElementType.UINT8
          OnnxJavaType.FLOAT -> InputElementType.FLOAT32
          else ->
            throw IllegalArgumentException(
              "Model input '$name' is ${info.type}; only uint8 and float32 images are supported",
            )
        }
      return ModelInputSpec.fromDeclaredShape(name, info.shape, elementType, defaultInputSize)
    }
  }
}
