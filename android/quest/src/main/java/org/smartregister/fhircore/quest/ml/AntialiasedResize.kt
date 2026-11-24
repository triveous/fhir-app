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

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Bilinear resize that averages over every source pixel an output pixel covers, as Pillow's
 * `Image.resize(size, BILINEAR)` (and torchvision's antialiased resize) does when shrinking.
 * OpenCV's bilinear and bicubic resizes read only 2 or 4 source pixels per axis, which skips most
 * of a large photo, so a model trained on this resize sees a different image from them.
 *
 * Uses Pillow's arithmetic (22-bit fixed-point weights, horizontal pass first, 8-bit rounding
 * after each pass), so the result is byte for byte Pillow's. Free of Android and OpenCV types so
 * it can be checked on the JVM.
 */
object AntialiasedResize {

  private const val PRECISION_BITS = 22
  private const val ROUNDING = 1 shl (PRECISION_BITS - 1)

  /**
   * @param readRow fills the array with one source row of interleaved 8-bit channels
   *   (`sourceWidth * channels` bytes); called once per row, top to bottom.
   * @return the resized image as interleaved bytes, row by row.
   */
  fun bilinear(
    sourceWidth: Int,
    sourceHeight: Int,
    width: Int,
    height: Int,
    channels: Int,
    readRow: (row: Int, into: ByteArray) -> Unit,
  ): ByteArray {
    require(sourceWidth > 0 && sourceHeight > 0 && width > 0 && height > 0 && channels > 0) {
      "Can't resize ${sourceWidth}x$sourceHeight to ${width}x$height"
    }
    val stride = width * channels
    val columns = weights(sourceWidth, width)
    val source = ByteArray(sourceWidth * channels)
    val narrowed = ByteArray(stride * sourceHeight)
    for (row in 0 until sourceHeight) {
      readRow(row, source)
      for (x in 0 until width) {
        val first = columns.first[x]
        val values = columns.values[x]
        for (channel in 0 until channels) {
          var sum = ROUNDING
          var offset = first * channels + channel
          for (weight in values) {
            sum += (source[offset].toInt() and 0xFF) * weight
            offset += channels
          }
          narrowed[row * stride + x * channels + channel] = toPixel(sum)
        }
      }
    }

    val rows = weights(sourceHeight, height)
    val resized = ByteArray(stride * height)
    for (y in 0 until height) {
      val first = rows.first[y]
      val values = rows.values[y]
      for (column in 0 until stride) {
        var sum = ROUNDING
        var offset = first * stride + column
        for (weight in values) {
          sum += (narrowed[offset].toInt() and 0xFF) * weight
          offset += stride
        }
        resized[y * stride + column] = toPixel(sum)
      }
    }
    return resized
  }

  private fun toPixel(sum: Int): Byte = (sum shr PRECISION_BITS).coerceIn(0, 255).toByte()

  /** For each output pixel, the first source pixel it reads and the fixed-point weights from there. */
  private class Weights(val first: IntArray, val values: Array<IntArray>)

  private fun weights(sourceSize: Int, size: Int): Weights {
    val scale = sourceSize.toDouble() / size
    val support = max(scale, 1.0)
    val first = IntArray(size)
    val values =
      Array(size) { index ->
        val center = (index + 0.5) * scale
        val from = max(0, (center - support + 0.5).toInt())
        val until = min(sourceSize, (center + support + 0.5).toInt())
        val inverse = 1.0 / support
        val raw = DoubleArray(until - from) { max(0.0, 1.0 - abs((from + it - center + 0.5) * inverse)) }
        val total = raw.sum()
        first[index] = from
        IntArray(raw.size) { (0.5 + raw[it] / total * (1 shl PRECISION_BITS)).toInt() }
      }
    return Weights(first, values)
  }
}
