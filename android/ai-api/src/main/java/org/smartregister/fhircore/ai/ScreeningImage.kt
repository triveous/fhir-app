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

package org.smartregister.fhircore.ai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.File
import java.io.IOException

/**
 * The photo the app hands to a [ScreeningModel]: the full-resolution JPEG exactly as captured, plus
 * capture details. It never carries patient data.
 *
 * Pre-processing is the model's job. The helpers below are conveniences for simple models; a model
 * that must match its training pipeline exactly (a specific resize filter, OpenCV decoding) should
 * read [file] itself.
 *
 * @property captureMetadata details such as device model; keys are listed in [MetadataKeys].
 */
class ScreeningImage(
  val file: File,
  val captureMetadata: Map<String, String> = emptyMap(),
) {

  /**
   * Decodes [file] as ARGB_8888 with its EXIF orientation applied. With [maxSide], the image is
   * scaled down (keeping its aspect ratio) so neither side is longer than that.
   */
  fun decodeBitmap(maxSide: Int? = null): Bitmap {
    require(maxSide == null || maxSide > 0) { "maxSide must be positive, was $maxSide" }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("Cannot decode $file")

    val options =
      BitmapFactory.Options().apply {
        inPreferredConfig = Bitmap.Config.ARGB_8888
        inSampleSize = maxSide?.let { sampleSizeFor(bounds.outWidth, bounds.outHeight, it) } ?: 1
      }
    var bitmap =
      BitmapFactory.decodeFile(file.absolutePath, options)
        ?: throw IOException("Cannot decode $file")

    bitmap = bitmap.rotatedPerExif()
    if (maxSide != null && maxOf(bitmap.width, bitmap.height) > maxSide) {
      val scale = maxSide.toFloat() / maxOf(bitmap.width, bitmap.height)
      bitmap =
        bitmap.replaceWith(
          Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true,
          ),
        )
    }
    return bitmap
  }

  /**
   * The photo resized (bilinear, aspect ratio not kept) to [width] × [height] as interleaved B,G,R
   * bytes in row-major order — the memory layout of `cv2.resize(cv2.imread(path), (width,
   * height))`.
   */
  fun decodeBgrBytes(width: Int, height: Int): ByteArray =
    decodeChannelBytes(width, height, bgr = true)

  /** Same as [decodeBgrBytes] in R,G,B order. */
  fun decodeRgbBytes(width: Int, height: Int): ByteArray =
    decodeChannelBytes(width, height, bgr = false)

  private fun decodeChannelBytes(width: Int, height: Int, bgr: Boolean): ByteArray {
    require(width > 0 && height > 0) { "Size must be positive, was ${width}x$height" }
    val decoded = decodeBitmap(maxSide = maxOf(width, height) * 2)
    val resized = Bitmap.createScaledBitmap(decoded, width, height, true)
    try {
      val pixels = IntArray(width * height)
      resized.getPixels(pixels, 0, width, 0, 0, width, height)
      val out = ByteArray(pixels.size * 3)
      pixels.forEachIndexed { index, argb ->
        val r = (argb shr 16 and 0xFF).toByte()
        val g = (argb shr 8 and 0xFF).toByte()
        val b = (argb and 0xFF).toByte()
        out[index * 3] = if (bgr) b else r
        out[index * 3 + 1] = g
        out[index * 3 + 2] = if (bgr) r else b
      }
      return out
    } finally {
      if (resized !== decoded) resized.recycle()
      decoded.recycle()
    }
  }

  private fun Bitmap.rotatedPerExif(): Bitmap {
    val orientation =
      try {
        ExifInterface(file.absolutePath)
          .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
      } catch (e: IOException) {
        ExifInterface.ORIENTATION_NORMAL
      }
    val matrix =
      Matrix().apply {
        when (orientation) {
          ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
          ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
          ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(270f)
          ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> postScale(-1f, 1f)
          ExifInterface.ORIENTATION_FLIP_VERTICAL -> postScale(1f, -1f)
          ExifInterface.ORIENTATION_TRANSPOSE -> {
            postRotate(90f)
            postScale(-1f, 1f)
          }
          ExifInterface.ORIENTATION_TRANSVERSE -> {
            postRotate(270f)
            postScale(-1f, 1f)
          }
          else -> return this@rotatedPerExif
        }
      }
    return replaceWith(Bitmap.createBitmap(this, 0, 0, width, height, matrix, true))
  }

  /** Returns [next], recycling this bitmap when [next] is a different one. */
  private fun Bitmap.replaceWith(next: Bitmap): Bitmap {
    if (next !== this) recycle()
    return next
  }

  /** Keys the app puts in [captureMetadata]. */
  object MetadataKeys {
    const val DEVICE_MODEL = "device_model"
    const val DEVICE_MANUFACTURER = "device_manufacturer"
    const val OS_VERSION = "os_version"
  }

  private companion object {
    /** Largest power of two that keeps the decoded image's longest side at or above [maxSide]. */
    fun sampleSizeFor(width: Int, height: Int, maxSide: Int): Int {
      var sample = 1
      while (maxOf(width, height) / (sample * 2) >= maxSide) sample *= 2
      return sample
    }
  }
}
