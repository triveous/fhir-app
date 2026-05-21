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


    fun scaleImageMat(filePath: String): Mat? {
        return try {
            // Load image
            val imageMat = Imgcodecs.imread(filePath)
            if (imageMat.empty()) {
                throw IOException("Failed to decode file: $filePath")
            }

            val resizedMat = Mat()
            Imgproc.resize(imageMat, resizedMat, Size(256.0, 256.0), 0.0, 0.0, Imgproc.INTER_CUBIC)
            // Ensure the image has 3 channels (BGR)
            require(imageMat.channels() == 3) { "Image must have 3 channels (BGR)" }

            val scaleStartTime = System.currentTimeMillis()

            // Convert to float for precise calculations
            val floatMat = Mat()
            resizedMat.convertTo(floatMat, CvType.CV_32FC3)

            // Split channels (B, G, R)
            val channels = mutableListOf<Mat>()
            Core.split(floatMat, channels)
            val bChannel = channels[0]
            val gChannel = channels[1]
            val rChannel = channels[2]

            // Calculate mean for each channel
            val meanB = Core.mean(bChannel).`val`[0]
            val meanG = Core.mean(gChannel).`val`[0]
            val meanR = Core.mean(rChannel).`val`[0]

            // Compute scaling factors (handle zero mean)
            val bb = if (meanB != 0.0) 128.0 / meanB else 1.0
            val gg = if (meanG != 0.0) 128.0 / meanG else 1.0
            val rr = if (meanR != 0.0) 128.0 / meanR else 1.0

            // Apply scaling to each channel
            Core.multiply(bChannel, Mat(bChannel.size(), bChannel.type(), Scalar(bb)), bChannel)
            Core.multiply(gChannel, Mat(gChannel.size(), gChannel.type(), Scalar(gg)), gChannel)
            Core.multiply(rChannel, Mat(rChannel.size(), rChannel.type(), Scalar(rr)), rChannel)

            // Merge channels back
            val scaledFloatMat = Mat()
            Core.merge(listOf(channels[0], channels[1], channels[2]), scaledFloatMat)

            val clippedMat = Mat()
            Core.max(scaledFloatMat, Scalar.all(0.0), clippedMat) // Floor at 0
            Core.min(clippedMat, Scalar.all(255.0), clippedMat)   // Cap at 255

            val channels2 = mutableListOf<Mat>()
            Core.split(clippedMat, channels2)

            val scaledMatRoundedChannels = roundChannelsToOneDecimals(listOf(channels2[0], channels2[1], channels2[2]))

            val mergedMat = Mat()
            Core.merge(scaledMatRoundedChannels, mergedMat)

            val finalMat = Mat()
            mergedMat.convertTo(finalMat, CvType.CV_8UC3)

            val scaleEndTime = System.currentTimeMillis()
            lastScaleImageTime = (scaleEndTime - scaleStartTime)

            finalMat
        } catch (e: Exception) {
            println("Error scaling image: ${e.message}")
            Timber.e(e, "Error scaling image: ${e.message}")
            null
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