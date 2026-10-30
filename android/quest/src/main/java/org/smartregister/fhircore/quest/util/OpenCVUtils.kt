package org.smartregister.fhircore.quest.util

import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import timber.log.Timber

/**
 * Image decoding and resizing backed by OpenCV, matching Python's `cv2` so a model gets the same
 * pixels on the phone as in its training/validation scripts.
 *
 * The OpenCV native library (`opencv_java4`) is loaded once on first access of this object. If
 * loading fails it is logged but not fatal — subsequent calls into OpenCV throw
 * `UnsatisfiedLinkError`, which callers treat as "image could not be processed".
 */
object OpenCVUtils {

    init {
        try {
            System.loadLibrary("opencv_java4")
        } catch (e: Throwable) {
            Timber.e(e, "Failed to load OpenCV")
        }
    }

    /**
     * Decodes [filePath] the way `cv2.imread(path)` does: 8-bit, 3-channel BGR,
     * alpha dropped, EXIF orientation applied.
     *
     * @return A [CvType.CV_8UC3] Mat the caller must release, or `null` if the
     *         file cannot be decoded.
     */
    fun readBgr(filePath: String): Mat? {
        val mat = Imgcodecs.imread(filePath, Imgcodecs.IMREAD_COLOR)
        if (mat.empty() || mat.type() != CvType.CV_8UC3) {
            Timber.e("Failed to decode image as 8-bit BGR: %s", filePath)
            mat.release()
            return null
        }
        return mat
    }

    /**
     * Resizes [source] to [width] × [height], i.e.
     * `cv2.resize(src, (width, height), interpolation=interpolation)` with one of
     * the `Imgproc.INTER_*` constants. The caller owns the returned Mat.
     */
    fun resize(source: Mat, width: Int, height: Int, interpolation: Int): Mat {
        val resized = Mat()
        Imgproc.resize(
            source,
            resized,
            Size(width.toDouble(), height.toDouble()),
            0.0,
            0.0,
            interpolation,
        )
        return resized
    }

    /** [resize] with bicubic interpolation (`cv2.INTER_CUBIC`). */
    fun resizeBicubic(source: Mat, width: Int, height: Int): Mat =
        resize(source, width, height, Imgproc.INTER_CUBIC)
}
