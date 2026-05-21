package org.smartregister.fhircore.quest.util

import android.graphics.Bitmap
import android.util.Log
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import org.pytorch.DType
import org.pytorch.Tensor
import timber.log.Timber
import java.io.IOException
import java.math.BigDecimal
import java.math.RoundingMode

object OpenCVUtils {
    private var lastScaleImageTime: Long = 0L

    init {
        try {
            System.loadLibrary("opencv_java4")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }


    /**
     * Loads an image from the specified file path, resizes it to 256x256 pixels,
     * and normalizes/scales each color channel (BGR) to achieve a target mean of 128.0.
     * The scaled pixel values are then clipped to the valid range [0.0, 255.0],
     * rounded to one decimal place, and converted back to an 8-bit 3-channel matrix.
     *
     * This function manages and releases all intermediate native OpenCV [Mat] allocations
     * to prevent memory leaks in the Android environment.
     *
     * @param filePath The absolute file path of the image to be processed.
     * @return A scaled, normalized 256x256 [Mat] of type [CvType.CV_8UC3] (BGR),
     *         or `null` if the image loading or processing fails.
     */
    fun scaleImageMat(filePath: String): Mat? {
        // Track the starting time for performance logging
        val scaleStartTime = System.currentTimeMillis()

        // Keep track of all temporary Mats to ensure they are released in the finally block
        var originalMat: Mat? = null
        var resizedMat: Mat? = null
        var floatMat: Mat? = null
        var scaledFloatMat: Mat? = null
        var clippedMat: Mat? = null
        var mergedMat: Mat? = null
        var finalMat: Mat? = null

        val channels = mutableListOf<Mat>()
        val channels2 = mutableListOf<Mat>()
        var scaledMatRoundedChannels: List<Mat>? = null

        // Temporary scale Mats that are used for channel-wise multiplication
        var scaleB: Mat? = null
        var scaleG: Mat? = null
        var scaleR: Mat? = null

        return try {
            // 1. Load the image from disk
            originalMat = Imgcodecs.imread(filePath)
            if (originalMat.empty()) {
                throw IOException("Failed to decode image file: $filePath")
            }

            // 2. Validate channel count (must be BGR 3-channel)
            require(originalMat.channels() == 3) { "Input image must have exactly 3 channels (BGR), found ${originalMat.channels()}" }

            // 3. Resize the image to 256x256 using bicubic interpolation
            resizedMat = Mat()
            Imgproc.resize(originalMat, resizedMat, Size(256.0, 256.0), 0.0, 0.0, Imgproc.INTER_CUBIC)

            // 4. Convert the resized image to 32-bit floating-point format for precise scaling calculations
            floatMat = Mat()
            resizedMat.convertTo(floatMat, CvType.CV_32FC3)

            // 5. Split BGR channels to calculate individual means and apply scaling
            Core.split(floatMat, channels)
            val bChannel = channels[0]
            val gChannel = channels[1]
            val rChannel = channels[2]

            // 6. Calculate the mean intensity for each channel
            val meanB = Core.mean(bChannel).`val`[0]
            val meanG = Core.mean(gChannel).`val`[0]
            val meanR = Core.mean(rChannel).`val`[0]

            // 7. Compute scale factors to target a mean value of 128.0 (handle zero means)
            val factorB = if (meanB != 0.0) 128.0 / meanB else 1.0
            val factorG = if (meanG != 0.0) 128.0 / meanG else 1.0
            val factorR = if (meanR != 0.0) 128.0 / meanR else 1.0

            // 8. Multiply each channel by its respective scaling factor
            scaleB = Mat(bChannel.size(), bChannel.type(), Scalar(factorB))
            scaleG = Mat(gChannel.size(), gChannel.type(), Scalar(factorG))
            scaleR = Mat(rChannel.size(), rChannel.type(), Scalar(factorR))

            Core.multiply(bChannel, scaleB, bChannel)
            Core.multiply(gChannel, scaleG, gChannel)
            Core.multiply(rChannel, scaleR, rChannel)

            // 9. Merge the scaled channels back into a single 3-channel matrix
            scaledFloatMat = Mat()
            Core.merge(listOf(bChannel, gChannel, rChannel), scaledFloatMat)

            // 10. Clip pixel values to the range [0.0, 255.0]
            clippedMat = Mat()
            Core.max(scaledFloatMat, Scalar.all(0.0), clippedMat) // Lower bound
            Core.min(clippedMat, Scalar.all(255.0), clippedMat)   // Upper bound

            // 11. Split clipped matrix to round values of each channel
            Core.split(clippedMat, channels2)

            // 12. Round the channel values to one decimal place
            scaledMatRoundedChannels = roundChannelsToOneDecimals(listOf(channels2[0], channels2[1], channels2[2]))

            // 13. Merge rounded channels back
            mergedMat = Mat()
            Core.merge(scaledMatRoundedChannels, mergedMat)

            // 14. Convert back to standard 8-bit unsigned format (CV_8UC3)
            finalMat = Mat()
            mergedMat.convertTo(finalMat, CvType.CV_8UC3)

            // Record duration for logging
            lastScaleImageTime = System.currentTimeMillis() - scaleStartTime

            finalMat
        } catch (e: Exception) {
            println("Error scaling image: ${e.message}")
            Timber.e(e, "Error scaling image: ${e.message}")
            finalMat?.release()
            null
        } finally {
            // Clean up and release all intermediate native Mat allocations
            originalMat?.release()
            resizedMat?.release()
            floatMat?.release()
            channels.forEach { it.release() }
            scaleB?.release()
            scaleG?.release()
            scaleR?.release()
            scaledFloatMat?.release()
            clippedMat?.release()
            channels2.forEach { it.release() }
            scaledMatRoundedChannels?.forEach { it.release() }
            mergedMat?.release()
        }
    }

    fun roundChannelsToOneDecimals(channels: List<Mat>): List<Mat> {
        return channels.map { channel ->
            // Ensure channel is CV_32F
            val floatChannel = if (channel.type() != CvType.CV_32F) {
                val temp = Mat()
                channel.convertTo(temp, CvType.CV_32F)
                temp
            } else {
                channel
            }

            // Create output Mat
            val roundedChannel = Mat()

            // Round to 2 decimal places
            //roundMatToTwoDecimals(floatChannel, roundedChannel)
            roundedChannel.create(floatChannel.size(), CvType.CV_32F)

            // Step 1: Multiply by 10 to shift 1 decimal places
            val tempScaled = Mat()
            Core.multiply(floatChannel, Scalar.all(10.0), tempScaled)

            val meantempScaled = Core.mean(tempScaled).`val`[0]
            // Step 2: Convert to CV_32S to round to nearest integer
            val tempInt = Mat()
            tempScaled.convertTo(tempInt, CvType.CV_32S)

            val meantempInt = Core.mean(tempInt).`val`[0]

            // Step 3: Convert back to CV_32F
            val tempFloat = Mat()
            tempInt.convertTo(tempFloat, CvType.CV_32F)

            val meantempFloat = Core.mean(tempFloat).`val`[0]

            // Step 4: Divide by 10 to shift back
            Core.divide(tempFloat, Scalar.all(10.0), roundedChannel)

            val meandivideroundedChannel = Core.mean(roundedChannel).`val`[0]

            // Clean up
            tempScaled.release()
            tempInt.release()
            tempFloat.release()


            // Release temporary floatChannel if it was created
            if (floatChannel != channel) {
                floatChannel.release()
            }

            roundedChannel
        }
    }
}