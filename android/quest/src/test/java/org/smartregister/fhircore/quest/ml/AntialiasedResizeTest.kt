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

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** Expected pixels are Pillow 11.0's `Image.fromarray(image).resize((w, h), Image.BILINEAR)`. */
class AntialiasedResizeTest {

  @Test
  fun `shrinking gives Pillow's pixels`() {
    val expected = intArrayOf(
      126, 134, 132, 128, 130, 128, 130, 135, 127, 135, 123, 116, 132, 126, 127, 127, 126, 132,
      132, 122, 133, 131, 124, 123, 120, 132, 118, 120, 140, 118, 130, 123, 121, 133, 134, 125,
    )
    assertArrayEquals(expected, resize(23, 17, 4, 3))
  }

  @Test
  fun `enlarging gives Pillow's pixels`() {
    val expected = intArrayOf(
      0, 53, 106, 21, 74, 127, 48, 101, 154, 74, 127, 180, 100, 153, 206, 127, 180, 233, 148,
      201, 254, 46, 99, 152, 69, 122, 175, 98, 151, 167, 127, 180, 105, 155, 117, 133, 130, 110,
      163, 80, 133, 186, 106, 159, 169, 132, 160, 195, 152, 175, 166, 154, 207, 47, 186, 87, 78,
      127, 58, 111, 31, 84, 137, 167, 220, 60, 195, 127, 88, 170, 71, 112, 53, 106, 116, 88,
      111, 152, 106, 135, 188, 110, 163, 216, 100, 153, 78, 131, 111, 109, 133, 95, 148, 81,
      134, 187, 119, 172, 134, 159, 157, 137, 190, 115, 168, 17, 70, 123, 50, 103, 156, 92, 145,
      198, 133, 186, 239, 174, 227, 98, 216, 159, 66, 249, 46, 99,
    )
    assertArrayEquals(expected, resize(5, 4, 7, 6))
  }

  @Test
  fun `the same size leaves every pixel alone`() {
    assertArrayEquals(IntArray(5 * 4 * 3) { pixel(it / 3 % 5, it / 15, it % 3) }, resize(5, 4, 5, 4))
  }

  @Test
  fun `the brightest photo does not overflow`() {
    val white = AntialiasedResize.bilinear(3000, 8, 256, 256, 3) { _, into -> into.fill(255.toByte()) }
    assertEquals(setOf(255.toByte()), white.toSet())
  }

  @Test
  fun `only the 3-class model changes how the photo is loaded`() {
    assertEquals(ImageLoading.DEFAULT, ModelCatalog.BINARY.imageLoading)
    assertEquals(InputPreprocessing.RAW_BGR, ModelCatalog.BINARY.inputPreprocessing)
    assertEquals(ImageLoading(applyExifRotation = true, resize = ResizeMethod.BICUBIC), ImageLoading.DEFAULT)
    assertEquals(
      ImageLoading(applyExifRotation = false, resize = ResizeMethod.BILINEAR_ANTIALIASED),
      ModelCatalog.THREE_CLASS.imageLoading,
    )
    assertEquals(
      InputPreprocessing(channelOrder = ChannelOrder.BGR, scale = 1f / 255f),
      ModelCatalog.THREE_CLASS.inputPreprocessing,
    )
  }

  private fun resize(sourceWidth: Int, sourceHeight: Int, width: Int, height: Int): IntArray =
    AntialiasedResize.bilinear(sourceWidth, sourceHeight, width, height, 3) { row, into ->
        for (i in into.indices) into[i] = pixel(i / 3, row, i % 3).toByte()
      }
      .map { it.toInt() and 0xFF }
      .toIntArray()

  private fun pixel(x: Int, y: Int, channel: Int) = (x * 37 + y * 91 + channel * 53 + x * y * 7) % 256
}
