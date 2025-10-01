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

    fun getLastScaleImageTime(): Long = lastScaleImageTime

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

            val scaledMatTwoDecimalRoundedChannels = roundChannelsToTwoDecimals(listOf(channels[0], channels[1], channels[2]))

            // Merge channels back
            val scaledFloatMat = Mat()
            Core.merge(scaledMatTwoDecimalRoundedChannels, scaledFloatMat)

            val meanR0 = Core.mean(channels[2]).`val`[0]
            val meanG0 = Core.mean(channels[1]).`val`[0]
            val meanB0 = Core.mean(channels[0]).`val`[0]

            // Clamp values to [0, 255] and convert back to 8-bit
            val scaledMat = Mat()
            Core.normalize(scaledFloatMat, scaledMat, 0.0, 255.0, Core.NORM_MINMAX)

            //val clippedMat = Mnormalizeat()
            //Core.max(scaledFloatMat, Scalar.all(0.0), clippedMat) // Floor at 0
            //Core.min(clippedMat, Scalar.all(255.0), clippedMat)   // Cap at 255

            val channels2 = mutableListOf<Mat>()
            Core.split(scaledMat, channels2)

            val meanRN = Core.mean(channels2[2]).`val`[0]
            val meanGN = Core.mean(channels2[1]).`val`[0]
            val meanBN = Core.mean(channels2[0]).`val`[0]

            val scaledMatRoundedChannels = roundChannelsToOneDecimals(listOf(channels2[0], channels2[1], channels2[2]))

            val bChannel2 = scaledMatRoundedChannels[0]
            val gChannel2 = scaledMatRoundedChannels[1]
            val rChannel2 = scaledMatRoundedChannels[2]

            val mergedMat = Mat()
            Core.merge(scaledMatRoundedChannels, mergedMat)

            val finalMat = Mat()
            mergedMat.convertTo(finalMat, CvType.CV_8UC3)

            val channels3 = mutableListOf<Mat>()
            Core.split(finalMat, channels3)
            val bChannel3 = channels3[0]
            val gChannel3 = channels3[1]
            val rChannel3 = channels3[2]

            val meanB2 = Core.mean(bChannel2).`val`[0]
            val meanG2 = Core.mean(gChannel2).`val`[0]
            val meanR2 = Core.mean(rChannel2).`val`[0]

            val meanB3 = Core.mean(bChannel3).`val`[0]
            val meanG3 = Core.mean(gChannel3).`val`[0]
            val meanR3 = Core.mean(rChannel3).`val`[0]


            val scaleEndTime = System.currentTimeMillis()
            lastScaleImageTime = (scaleEndTime - scaleStartTime)

            finalMat
        } catch (e: Exception) {
            println("Error scaling image: ${e.message}")
            null
        }
    }

    private fun convertToUint8(input: Mat, output: Mat) {
        if (input.type() != CvType.CV_32FC3) {
            throw IllegalArgumentException("Input must be CV_32FC3")
        }
        output.create(input.size(), CvType.CV_8UC3)
        val rows = input.rows()
        val cols = input.cols()
        for (row in 0 until rows) {
            for (col in 0 until cols) {
                val pixel = input.get(row, col) // [R, G, B] as float
                val uint8Pixel = doubleArrayOf(
                    // Round to nearest integer, clip to [0, 255]
                    BigDecimal(pixel[0]).setScale(0, RoundingMode.HALF_UP).toInt().coerceIn(0, 255).toDouble(),
                    BigDecimal(pixel[1]).setScale(0, RoundingMode.HALF_UP).toInt().coerceIn(0, 255).toDouble(),
                    BigDecimal(pixel[2]).setScale(0, RoundingMode.HALF_UP).toInt().coerceIn(0, 255).toDouble()
                )
                output.put(row, col, *uint8Pixel)
            }
        }
    }

    fun roundChannelsToTwoDecimals2(channels: List<Mat>): List<Mat> {
        channels.forEach { channel ->
            // Convert to CV_32F if not already
            if (channel.type() != CvType.CV_32F) {
                val floatMat = Mat()
                channel.convertTo(floatMat, CvType.CV_32F)
                channel.release()
                channel.create(floatMat.size(), CvType.CV_32F)
                floatMat.copyTo(channel)
                floatMat.release()
            }

            // Round pixel values to 2 decimal places
            val rows = channel.rows()
            val cols = channel.cols()
            for (row in 0 until rows) {
                for (col in 0 until cols) {
                    val pixel = channel.get(row, col)[0]
                    val roundedPixel = BigDecimal(pixel).setScale(2, RoundingMode.HALF_UP).toDouble()
                    channel.put(row, col, roundedPixel)
                }
            }
        }
        return channels
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

    fun roundChannelsToTwoDecimals(channels: List<Mat>): List<Mat> {
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

            // Step 1: Multiply by 100 to shift 2 decimal places
            val tempScaled = Mat()
            Core.multiply(floatChannel, Scalar.all(100.0), tempScaled)

            val meantempScaled = Core.mean(tempScaled).`val`[0]
            // Step 2: Convert to CV_32S to round to nearest integer
            val tempInt = Mat()
            tempScaled.convertTo(tempInt, CvType.CV_32S)

            val meantempInt = Core.mean(tempInt).`val`[0]

            // Step 3: Convert back to CV_32F
            val tempFloat = Mat()
            tempInt.convertTo(tempFloat, CvType.CV_32F)

            val meantempFloat = Core.mean(tempFloat).`val`[0]

            // Step 4: Divide by 100 to shift back
            Core.divide(tempFloat, Scalar.all(100.0), roundedChannel)

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

    private fun roundMatToTwoDecimals(src: Mat, dst: Mat) {
        if (src.type() != CvType.CV_32F) {
            throw IllegalArgumentException("Source must be CV_32F")
        }

        dst.create(src.size(), CvType.CV_32F)

        // Step 1: Multiply by 100 to shift 2 decimal places
        val tempScaled = Mat()
        Core.multiply(src, Scalar.all(100.0), tempScaled)

        // Step 2: Convert to CV_32S to round to nearest integer
        val tempInt = Mat()
        tempScaled.convertTo(tempInt, CvType.CV_32S)

        // Step 3: Convert back to CV_32F
        val tempFloat = Mat()
        tempInt.convertTo(tempFloat, CvType.CV_32F)

        // Step 4: Divide by 100 to shift back
        Core.divide(tempFloat, Scalar.all(100.0), dst)

        // Clean up
        tempScaled.release()
        tempInt.release()
        tempFloat.release()
    }

    private fun clipMat(input: Mat, output: Mat) {
        // Ensure input is CV_32FC3
        if (input.type() != CvType.CV_32FC3) {
            throw IllegalArgumentException("Input must be CV_32FC3")
        }

        // Create output Mat with same size and type
        output.create(input.size(), CvType.CV_32FC3)

        val rows = input.rows()
        val cols = input.cols()

        var pixelsClipped = 0
        for (row in 0 until rows) {
            for (col in 0 until cols) {
                val pixel = input.get(row, col) // Array of 3 floats (R, G, B)
//                val clippedPixel = doubleArrayOf(
//                    pixel[0].coerceIn(0.0, 255.0),
//                    pixel[1].coerceIn(0.0, 255.0),
//                    pixel[2].coerceIn(0.0, 255.0)
//                )

                if(pixel[0] < 0.0) {
                    pixel[0] = 0.0
                    pixelsClipped++
                }
                if(pixel[0] > 255.0) {
                    pixel[0] = 255.0
                    pixelsClipped++
                }
                if(pixel[1] < 0.0) {
                    pixel[1] = 0.0
                    pixelsClipped++
                }
                if(pixel[1] > 255.0) {
                    pixel[1] = 255.0
                    pixelsClipped++
                }
                if(pixel[2] < 0.0) {
                    pixel[2] = 0.0
                    pixelsClipped++
                }
                if(pixel[2] > 255.0) {
                    pixel[2] = 255.0
                    pixelsClipped++
                }

                val clippedPixel = doubleArrayOf(
                    pixel[0],
                    pixel[1],
                    pixel[2]
                )

                output.put(row, col, *clippedPixel)
            }
        }
        println("Pixels changed =$pixelsClipped")
    }


    private fun scaleChannel(channel: Mat, scale: Double) {
        // Ensure channel is CV_32F
        if (channel.type() != CvType.CV_32F) {
            throw IllegalArgumentException("Channel must be CV_32F for scaling")
        }

        // Get dimensions
        val rows = channel.rows()
        val cols = channel.cols()

        // Iterate over each pixel and multiply by scale, rounding to 2 decimal places
        for (row in 0 until rows) {
            for (col in 0 until cols) {
                val pixel = channel.get(row, col)[0] // Get float value
                val scaledPixel = (pixel * scale) // Perform scaling
                // Round to 2 decimal places using BigDecimal
                val roundedPixel = BigDecimal(scaledPixel).setScale(2, RoundingMode.HALF_UP).toDouble()
                channel.put(row, col, roundedPixel) // Put rounded value back
            }
        }
    }

    fun decodeFileToMat(filePath: String): Bitmap? {
        return try {
            //scale
            val scaledMat = scaleImageMat(filePath)
            // Convert BGR to RGB (OpenCV loads images in BGR, but Android Bitmap expects RGB)
            val rgbMat = Mat()
            Imgproc.cvtColor(scaledMat, rgbMat, Imgproc.COLOR_BGR2RGB)

            // Convert Mat to Bitmap
            val bitmap = Bitmap.createBitmap(rgbMat.cols(), rgbMat.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(rgbMat, bitmap)
            scaledMat?.release()
            rgbMat.release()
            bitmap
        } catch (e: Exception) {
            Log.e("Error", "Error decoding file to bitmap: $filePath", e)
            null
        }
    }



    fun printChannelMeans(inputTensor: Tensor) {
        try {
            // Get tensor properties
            val shape = inputTensor.shape() // e.g., [3, height, width] or [height, width, 3]
            val dataType = inputTensor.dtype() // Should be FLOAT32

            // Determine dimensions
            val height: Int
            val width: Int
            val channels: Int
            when (shape.size) {
                3 -> {
                    if (shape[0] == 3L) { // [3, height, width] (channels-first)
                        channels = shape[0].toInt()
                        height = shape[1].toInt()
                        width = shape[2].toInt()
                    } else { // [height, width, 3] (channels-last)
                        height = shape[0].toInt()
                        width = shape[1].toInt()
                        channels = shape[2].toInt()
                    }
                }
                4 -> { // [1, 3, height, width] or [1, height, width, 3]
                    if (shape[0] != 1L) throw IllegalArgumentException("Batch size must be 1")
                    if (shape[1] == 3L) { // [1, 3, height, width]
                        channels = shape[1].toInt()
                        height = shape[2].toInt()
                        width = shape[3].toInt()
                    } else { // [1, height, width, 3]
                        height = shape[1].toInt()
                        width = shape[2].toInt()
                        channels = shape[3].toInt()
                    }
                }
                else -> throw IllegalArgumentException("Unsupported tensor shape: ${shape.joinToString()}")
            }
            require(channels == 3) { "Tensor must have 3 channels (RGB)" }
            require(dataType == DType.FLOAT32) { "Tensor must be FLOAT32, got $dataType" }

            // Get tensor data as FloatArray
            val data = inputTensor.getDataAsFloatArray()

            // Create Mat
            val mat = Mat(height, width, CvType.CV_32FC3)

            // Copy data to Mat (handle channels-first or channels-last)
            if (shape[0] == 3L || (shape.size == 4 && shape[1] == 3L)) { // Channels-first: [3, h, w] or [1, 3, h, w]
                // Transpose to [h, w, 3]
                val reshapedData = FloatArray(height * width * channels)
                for (c in 0 until channels) {
                    for (h in 0 until height) {
                        for (w in 0 until width) {
                            reshapedData[(h * width + w) * channels + c] = data[c * height * width + h * width + w]
                        }
                    }
                }
                mat.put(0, 0, reshapedData)
            } else { // Channels-last: [h, w, 3] or [1, h, w, 3]
                mat.put(0, 0, data)
            }

            // Convert to RGB to match Python code (if tensor is BGR, remove this)
            val rgbMat = Mat()
            Imgproc.cvtColor(mat, rgbMat, Imgproc.COLOR_BGR2RGB)

            // Split channels (R, G, B)
            val channelsList = mutableListOf<Mat>()
            Core.split(rgbMat, channelsList)
            val rChannel = channelsList[0] // Red
            val gChannel = channelsList[1] // Green
            val bChannel = channelsList[2] // Blue

            // Compute means
            val meanR = Core.mean(rChannel).`val`[0]
            val meanG = Core.mean(gChannel).`val`[0]
            val meanB = Core.mean(bChannel).`val`[0]
            println("Channel means: meanR=$meanR, meanG=$meanG, meanB=$meanB")

            // Release Mats
            mat.release()
            rgbMat.release()
            channelsList.forEach { it.release() }
        } catch (e: Exception) {
            println("Error computing channel means: ${e.message}")
        }
    }

    fun resizeImage(inputBitmap: Bitmap, targetWidth: Int, targetHeight: Int): Bitmap {
        // Convert Bitmap to Mat
        val inputMat = Mat()
        Utils.bitmapToMat(inputBitmap, inputMat)

        // Create output Mat
        val outputMat = Mat()

        // Resize the image
        Imgproc.resize(
            inputMat,
            outputMat,
            Size(targetWidth.toDouble(), targetHeight.toDouble()),
            0.0,
            0.0,
            Imgproc.INTER_CUBIC
        )

        // Convert back to Bitmap
        val outputBitmap = Bitmap.createBitmap(
            targetWidth,
            targetHeight,
            Bitmap.Config.ARGB_8888
        )
        Utils.matToBitmap(outputMat, outputBitmap)

        // Release Mat resources
        inputMat.release()
        outputMat.release()

        return outputBitmap
    }
}