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

package org.smartregister.fhircore.quest.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Before
import org.junit.Test
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar

class ImageQualityAnalyzerTest {

  @Before
  fun loadOpenCv() {
    try {
      System.loadLibrary("opencv_java4")
    } catch (exception: UnsatisfiedLinkError) {
      assumeNoException(exception)
    }
  }

  @Test
  fun `analyze returns image quality metrics for synthetic image`() {
    val mat = Mat(32, 32, CvType.CV_8UC3, Scalar(128.0, 128.0, 128.0))
    try {
      val props = ImageQualityAnalyzer.analyze(mat)

      assertEquals(32, props[PostHogAnalytics.Props.IMAGE_WIDTH])
      assertEquals(32, props[PostHogAnalytics.Props.IMAGE_HEIGHT])
      assertTrue((props[PostHogAnalytics.Props.BRIGHTNESS_MEAN] as Double) > 0.0)
      assertTrue(props.containsKey(PostHogAnalytics.Props.BLUR_LAPLACIAN_VARIANCE))
      assertTrue(props.containsKey(PostHogAnalytics.Props.CONTRAST_STDDEV))
      assertTrue(props.containsKey(PostHogAnalytics.Props.NOISE_ESTIMATE))
      assertEquals(true, props[PostHogAnalytics.Props.IS_UNUSABLE])
    } finally {
      mat.release()
    }
  }
}
