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

import android.os.Build
import kotlin.math.pow
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

object ImageQualityAnalyzer {
  private const val MIN_BLUR_VARIANCE = 100.0
  private const val MIN_BRIGHTNESS = 40.0
  private const val MAX_BRIGHTNESS = 220.0

  fun analyze(mat: Mat): Map<String, Any> {
    val gray = Mat()
    val laplacian = Mat()
    val blurred = Mat()
    val residual = Mat()
    val mean = MatOfDouble()
    val stdDev = MatOfDouble()
    val laplacianMean = MatOfDouble()
    val laplacianStdDev = MatOfDouble()
    val residualMean = MatOfDouble()
    val residualStdDev = MatOfDouble()

    try {
      if (mat.channels() > 1) {
        Imgproc.cvtColor(mat, gray, Imgproc.COLOR_BGR2GRAY)
      } else {
        mat.copyTo(gray)
      }

      Core.meanStdDev(gray, mean, stdDev)
      Imgproc.Laplacian(gray, laplacian, CvType.CV_64F)
      Core.meanStdDev(laplacian, laplacianMean, laplacianStdDev)

      Imgproc.GaussianBlur(gray, blurred, Size(3.0, 3.0), 0.0)
      Core.absdiff(gray, blurred, residual)
      Core.meanStdDev(residual, residualMean, residualStdDev)

      val brightness = mean.first()
      val blurVariance = laplacianStdDev.first().pow(2.0)
      // First-cut thresholds for launch telemetry; tune from field distributions in PostHog.
      val isUnusable =
        blurVariance < MIN_BLUR_VARIANCE ||
          brightness < MIN_BRIGHTNESS ||
          brightness > MAX_BRIGHTNESS

      return mapOf(
        PostHogAnalytics.Props.BLUR_LAPLACIAN_VARIANCE to blurVariance,
        PostHogAnalytics.Props.BRIGHTNESS_MEAN to brightness,
        PostHogAnalytics.Props.CONTRAST_STDDEV to stdDev.first(),
        PostHogAnalytics.Props.NOISE_ESTIMATE to residualStdDev.first(),
        PostHogAnalytics.Props.IS_UNUSABLE to isUnusable,
        PostHogAnalytics.Props.DEVICE_MODEL to Build.MODEL.orEmpty(),
        PostHogAnalytics.Props.DEVICE_MANUFACTURER to Build.MANUFACTURER.orEmpty(),
        PostHogAnalytics.Props.OS_VERSION to Build.VERSION.SDK_INT,
        PostHogAnalytics.Props.IMAGE_WIDTH to mat.width(),
        PostHogAnalytics.Props.IMAGE_HEIGHT to mat.height(),
      )
    } finally {
      gray.release()
      laplacian.release()
      blurred.release()
      residual.release()
      mean.release()
      stdDev.release()
      laplacianMean.release()
      laplacianStdDev.release()
      residualMean.release()
      residualStdDev.release()
    }
  }

  private fun MatOfDouble.first(): Double = toArray().firstOrNull() ?: 0.0
}
