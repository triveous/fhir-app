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

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

enum class TensorLayout {
  NCHW,
  NHWC,
}

enum class InputElementType {
  UINT8,
  FLOAT32,
}

/**
 * The image input a model declares, and how to pack a BGR image into it.
 *
 * The app never normalises: pixel values reach the model exactly as `cv2.imread` + `cv2.resize`
 * produce them (BGR order, 0–255). The only per-model choices are the tensor layout and element
 * type, both read from the model itself so a re-exported model cannot silently disagree with the
 * app about them.
 */
data class ModelInputSpec(
  val name: String,
  val layout: TensorLayout,
  val elementType: InputElementType,
  val height: Int,
  val width: Int,
) {

  val shape: LongArray
    get() =
      when (layout) {
        TensorLayout.NCHW -> longArrayOf(1, CHANNELS.toLong(), height.toLong(), width.toLong())
        TensorLayout.NHWC -> longArrayOf(1, height.toLong(), width.toLong(), CHANNELS.toLong())
      }

  val elementCount: Int
    get() = CHANNELS * height * width

  /**
   * Packs [bgrHwc] — the bytes of a `height`×`width` CV_8UC3 Mat, i.e. interleaved B,G,R per pixel —
   * into a direct uint8 buffer in this input's layout.
   */
  fun packUint8(bgrHwc: ByteArray): ByteBuffer {
    requireImageSize(bgrHwc)
    val out = ByteBuffer.allocateDirect(elementCount).order(ByteOrder.nativeOrder())
    when (layout) {
      TensorLayout.NHWC -> out.put(bgrHwc)
      TensorLayout.NCHW -> {
        val pixels = height * width
        for (channel in 0 until CHANNELS) {
          for (pixel in 0 until pixels) out.put(bgrHwc[pixel * CHANNELS + channel])
        }
      }
    }
    out.rewind()
    return out
  }

  /**
   * Same as [packUint8] but as float32, for models whose graph starts from float input. Values
   * stay on the 0–255 scale.
   */
  fun packFloat32(bgrHwc: ByteArray): FloatBuffer {
    requireImageSize(bgrHwc)
    val out =
      ByteBuffer.allocateDirect(elementCount * Float.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
    when (layout) {
      TensorLayout.NHWC -> bgrHwc.forEach { out.put(it.toUnsignedFloat()) }
      TensorLayout.NCHW -> {
        val pixels = height * width
        for (channel in 0 until CHANNELS) {
          for (pixel in 0 until pixels) out.put(bgrHwc[pixel * CHANNELS + channel].toUnsignedFloat())
        }
      }
    }
    out.rewind()
    return out
  }

  private fun requireImageSize(bgrHwc: ByteArray) {
    require(bgrHwc.size == elementCount) {
      "Expected $elementCount bytes for a ${height}x$width BGR image, got ${bgrHwc.size}"
    }
  }

  private fun Byte.toUnsignedFloat(): Float = (toInt() and 0xFF).toFloat()

  companion object {
    const val CHANNELS = 3

    /**
     * Builds the spec from the shape a model declares for its input. Dynamic dims arrive as
     * negative values; dynamic height/width fall back to [defaultSize], and a fully dynamic shape
     * is treated as NCHW (slide 3 permutes HWC → CHW).
     */
    fun fromDeclaredShape(
      name: String,
      shape: LongArray,
      elementType: InputElementType,
      defaultSize: Int,
    ): ModelInputSpec {
      require(shape.size == 4) {
        "Model input '$name' must be rank 4 (one BGR image), found ${shape.contentToString()}"
      }
      require(shape[0] == 1L || shape[0] < 0) {
        "Model input '$name' must take a batch of 1, found ${shape.contentToString()}"
      }
      val channels = CHANNELS.toLong()
      val layout =
        when {
          shape[1] == channels -> TensorLayout.NCHW
          shape[3] == channels -> TensorLayout.NHWC
          shape[1] < 0 && shape[3] < 0 -> TensorLayout.NCHW
          else ->
            throw IllegalArgumentException(
              "Model input '$name' has no 3-channel axis: ${shape.contentToString()}",
            )
        }
      val (height, width) =
        when (layout) {
          TensorLayout.NCHW -> shape[2] to shape[3]
          TensorLayout.NHWC -> shape[1] to shape[2]
        }
      return ModelInputSpec(
        name = name,
        layout = layout,
        elementType = elementType,
        height = if (height > 0) height.toInt() else defaultSize,
        width = if (width > 0) width.toInt() else defaultSize,
      )
    }
  }
}
