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

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ModelInputSpecTest {

  @Test
  fun `NCHW shape is detected from the channel axis`() {
    val spec = spec(longArrayOf(1, 3, 224, 320))

    assertEquals(TensorLayout.NCHW, spec.layout)
    assertEquals(224, spec.height)
    assertEquals(320, spec.width)
    assertArrayEquals(longArrayOf(1, 3, 224, 320), spec.shape)
  }

  @Test
  fun `NHWC shape is detected from the channel axis`() {
    val spec = spec(longArrayOf(1, 224, 320, 3))

    assertEquals(TensorLayout.NHWC, spec.layout)
    assertEquals(224, spec.height)
    assertEquals(320, spec.width)
    assertArrayEquals(longArrayOf(1, 224, 320, 3), spec.shape)
  }

  @Test
  fun `dynamic batch and spatial dims fall back to the default size`() {
    val spec = spec(longArrayOf(-1, 3, -1, -1))

    assertEquals(TensorLayout.NCHW, spec.layout)
    assertEquals(256, spec.height)
    assertEquals(256, spec.width)
    assertArrayEquals(longArrayOf(1, 3, 256, 256), spec.shape)
  }

  @Test
  fun `fully dynamic shape is treated as NCHW`() {
    assertEquals(TensorLayout.NCHW, spec(longArrayOf(-1, -1, -1, -1)).layout)
  }

  @Test
  fun `shapes that are not one 3-channel image are rejected`() {
    assertThrows(IllegalArgumentException::class.java) { spec(longArrayOf(3, 256, 256)) }
    assertThrows(IllegalArgumentException::class.java) { spec(longArrayOf(2, 3, 256, 256)) }
    assertThrows(IllegalArgumentException::class.java) { spec(longArrayOf(1, 4, 256, 256)) }
  }

  @Test
  fun `NCHW uint8 packing moves each BGR channel into its own plane`() {
    val spec = ModelInputSpec("image", TensorLayout.NCHW, InputElementType.UINT8, 1, 2)
    // Two pixels, interleaved B,G,R as OpenCV stores them.
    val bgrHwc = byteArrayOf(1, 2, 3, 4, 5, 6)

    val packed = spec.packUint8(bgrHwc)

    assertArrayEquals(byteArrayOf(1, 4, 2, 5, 3, 6), ByteArray(packed.remaining()).also(packed::get))
  }

  @Test
  fun `NHWC uint8 packing passes the OpenCV bytes through untouched`() {
    val spec = ModelInputSpec("image", TensorLayout.NHWC, InputElementType.UINT8, 1, 2)
    val bgrHwc = byteArrayOf(1, 2, 3, 4, 5, 6)

    val packed = spec.packUint8(bgrHwc)

    assertArrayEquals(bgrHwc, ByteArray(packed.remaining()).also(packed::get))
  }

  @Test
  fun `float32 packing keeps raw 0-255 pixel values with no normalisation`() {
    val spec = ModelInputSpec("image", TensorLayout.NCHW, InputElementType.FLOAT32, 1, 2)
    // 0xFF must come through as 255, not as the signed byte -1.
    val bgrHwc = byteArrayOf(0, 128.toByte(), 255.toByte(), 10, 20, 30)

    val packed = spec.packFloat32(bgrHwc)

    assertArrayEquals(
      floatArrayOf(0f, 10f, 128f, 20f, 255f, 30f),
      FloatArray(packed.remaining()).also(packed::get),
      0f,
    )
  }

  @Test
  fun `packing rejects an image of the wrong size`() {
    val spec = ModelInputSpec("image", TensorLayout.NCHW, InputElementType.UINT8, 2, 2)

    assertThrows(IllegalArgumentException::class.java) { spec.packUint8(ByteArray(3)) }
    assertThrows(IllegalArgumentException::class.java) { spec.packFloat32(ByteArray(3)) }
  }

  private fun spec(shape: LongArray) =
    ModelInputSpec.fromDeclaredShape("image", shape, InputElementType.UINT8, defaultSize = 256)
}
