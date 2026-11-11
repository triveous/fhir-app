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

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlin.math.exp
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Runs [OnnxModelRunner] against real ONNX Runtime with the contract fixtures from
 * scripts/make_onnx_test_fixtures.py. Every fixture outputs, per input channel c,
 * `sigmoid((mean(c) - 128) / 32)`, so the uniform B=10 / G=128 / R=250 image below yields three
 * distinct values whose order proves BGR reached the model un-swapped and correctly permuted.
 */
class OnnxModelRunnerTest {

  @Before
  fun requireNativeOnnxRuntime() {
    assumeTrue(
      "ONNX Runtime ships no native library for this JVM (os.arch=${System.getProperty("os.arch")}," +
        " e.g. an x86_64 JDK under Rosetta); run on an arm64 JDK or linux-x64 to execute",
      NATIVE_RUNTIME_AVAILABLE,
    )
  }

  @Test
  fun `uint8 NCHW model - the stated contract - receives BGR in channel order`() {
    runner("probs_uint8_nchw.onnx").use { runner ->
      assertEquals(
        ModelInputSpec("image", TensorLayout.NCHW, InputElementType.UINT8, 256, 256),
        runner.inputSpec,
      )
      assertArrayEquals(EXPECTED, runner.run(bgrImage()), TOLERANCE)
    }
  }

  @Test
  fun `float32 NHWC model gets the same raw pixels in its own layout`() {
    runner("probs_float32_nhwc.onnx").use { runner ->
      assertEquals(TensorLayout.NHWC, runner.inputSpec.layout)
      assertEquals(InputElementType.FLOAT32, runner.inputSpec.elementType)
      assertArrayEquals(EXPECTED, runner.run(bgrImage()), TOLERANCE)
    }
  }

  @Test
  fun `dynamic input dims default to 256`() {
    runner("probs_uint8_nchw_dynamic.onnx").use { runner ->
      assertEquals(256, runner.inputSpec.height)
      assertEquals(256, runner.inputSpec.width)
      assertArrayEquals(EXPECTED, runner.run(bgrImage()), TOLERANCE)
    }
  }

  @Test
  fun `separate outputs are concatenated in declared order`() {
    runner("probs_uint8_nchw_split.onnx").use { runner ->
      assertArrayEquals(EXPECTED, runner.run(bgrImage()), TOLERANCE)
    }
  }

  @Test
  fun `several model files are concatenated in list order`() {
    runner("probs_uint8_nchw.onnx", "probs_float32_nhwc.onnx").use { runner ->
      assertArrayEquals(EXPECTED + EXPECTED, runner.run(bgrImage()), TOLERANCE)
    }
  }

  @Test
  fun `runner output feeds the interpreter end to end`() {
    runner("probs_uint8_nchw.onnx").use { runner ->
      runner.warmUp()
      val prediction = ScreeningOutputInterpreter.interpret(runner.run(bgrImage()))

      assertEquals(listOf(false, false, true), prediction.components.map { it.isSuspicious })
      assertEquals("Non-Suspicious", prediction.toResultMap()["camera_prediction"])
    }
  }

  @Test
  fun `a graph exported without Sigmoid is caught by the interpreter`() {
    runner("logits_uint8_nchw.onnx").use { runner ->
      val logits = runner.run(bgrImage())

      assertArrayEquals(floatArrayOf(-3.6875f, 0f, 3.8125f), logits, TOLERANCE)
      assertThrows(OnnxContractException::class.java) {
        ScreeningOutputInterpreter.interpret(logits)
      }
    }
  }

  private fun runner(vararg fixtures: String): OnnxModelRunner {
    val models = fixtures.map { name ->
      checkNotNull(javaClass.getResourceAsStream("/onnx/$name")) { "Missing fixture $name" }
        .use { it.readBytes() }
    }
    return OrtSession.SessionOptions().use { OnnxModelRunner(models, it) }
  }

  /** 256×256 image, every pixel B=10, G=128, R=250, laid out as a CV_8UC3 Mat's bytes. */
  private fun bgrImage(): ByteArray {
    val pixel = byteArrayOf(10, 128.toByte(), 250.toByte())
    return ByteArray(256 * 256 * 3) { pixel[it % 3] }
  }

  private companion object {
    const val TOLERANCE = 1e-6f

    // UnsatisfiedLinkError is an Error, which runCatching also catches.
    val NATIVE_RUNTIME_AVAILABLE = runCatching { OrtEnvironment.getEnvironment() }.isSuccess

    val EXPECTED = floatArrayOf(sigmoid((10f - 128f) / 32f), 0.5f, sigmoid((250f - 128f) / 32f))

    fun sigmoid(x: Float) = (1 / (1 + exp(-x.toDouble()))).toFloat()
  }
}
