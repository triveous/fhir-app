package org.smartregister.fhircore.quest.camerax

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.widget.AppCompatImageButton
import androidx.appcompat.widget.AppCompatImageView
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalZeroShutterLag
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.TorchState
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.setFragmentResult
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide.with
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.launch
import org.pytorch.IValue
import org.pytorch.Module
import org.pytorch.Tensor
import org.pytorch.torchvision.TensorImageUtils
import org.smartregister.fhircore.engine.util.extension.showToast
import org.smartregister.fhircore.quest.R
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.DecimalFormat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.exp

class CameraxLauncherFragment : DialogFragment() {

    private lateinit var cameraProviderFuture: ListenableFuture<ProcessCameraProvider>
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var captureButton: AppCompatImageView
    private lateinit var zoomIv: AppCompatImageView
    private lateinit var flashButton: AppCompatImageButton
    private lateinit var closeCameraIB: AppCompatImageView
    private lateinit var previewView: PreviewView
    //private lateinit var predictionScore: TextView

    private lateinit var cameraPreviewViewLay: FrameLayout
    private lateinit var previewViewImageLay: ConstraintLayout
    private lateinit var retakeButton: LinearLayout
    private lateinit var zoomIndicatorll: LinearLayout
    private lateinit var selectButton: LinearLayout
    private lateinit var cameraControlsll: LinearLayout
    private lateinit var previewImage: AppCompatImageView
    private lateinit var scaleGestureDetector: ScaleGestureDetector
    private lateinit var cameraControl: CameraControl
    private lateinit var cameraInfo: CameraInfo
    private lateinit var zoomSeekBar: CustomSeekBar

    private var fileAbsPath: String = ""
    var module : Module? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        //setStyle(STYLE_NO_FRAME, android.R.style.Theme_Holo_Light)
        setStyle(DialogFragment.STYLE_NORMAL, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_camera_launcher, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        previewView = view.findViewById(R.id.previewView)
        //predictionScore = view.findViewById(R.id.predictionScore)
        flashButton = view.findViewById(R.id.flashButton)
        closeCameraIB = view.findViewById(R.id.closeCameraIB)
        captureButton = view.findViewById(R.id.captureButton)
        zoomIv = view.findViewById(R.id.zoomIv)

        cameraPreviewViewLay = view.findViewById(R.id.camera_preview_fl)
        previewViewImageLay = view.findViewById(R.id.photo_preview_cl)
        retakeButton = view.findViewById(R.id.retake_ll)
        zoomIndicatorll = view.findViewById(R.id.zoomIndicatorll)
        selectButton = view.findViewById(R.id.done_ll)
        cameraControlsll = view.findViewById(R.id.cameraControlsll)
        previewImage = view.findViewById(R.id.previewImage)
        zoomSeekBar = view.findViewById(R.id.zoomSeekBar)

        lifecycleScope.launch {
            initModel()
        }

        selectButton.setSafeOnClickListener(interval = 6000) {
            /*requireActivity().runOnUiThread {
                progressBar.visibility = View.VISIBLE
                requireActivity().showToast("Processing image", Toast.LENGTH_SHORT)
            }*/
            lifecycleScope.launch {
                onPhotoSelected(fileAbsPath)
            }
        }

        closeCameraIB.setOnClickListener {
            cameraExecutor?.shutdown()
            dismiss()
        }

        zoomIv.setOnClickListener {
            zoomIndicatorll.visibility = if (zoomIndicatorll.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        retakeButton.setOnClickListener {
            val flashOfDrawable = context?.getDrawable(R.drawable.flash_off)
            flashButton.setImageDrawable(flashOfDrawable)
            checkPermissionAndStartCamera()
            previewViewImageLay.visibility = View.GONE
            cameraPreviewViewLay.visibility = View.VISIBLE
            cameraControlsll.visibility = View.VISIBLE
            fileAbsPath = ""
        }

        scaleGestureDetector = ScaleGestureDetector(requireContext(), object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val zoomRatio = cameraInfo.zoomState.value?.zoomRatio ?: 1f
                val scaleFactor = detector.scaleFactor
                cameraControl.setZoomRatio(zoomRatio * scaleFactor)
                return true
            }
        })


        previewView.setOnTouchListener { _, event ->
            scaleGestureDetector.onTouchEvent(event)
            return@setOnTouchListener true
        }

        checkPermissionAndStartCamera()
    }

    private fun initModel() {
        try {
            module =  Module.load(getAssetPath(requireContext(), "traced_cpu.pt"))
        } catch (e: Exception) {
            Log.e("OCS", "Error reading assets", e)
        } finally {
            //progressBar.visibility = View.GONE
        }
    }


    private fun setZoomLevel(zoomRatio: Float) {
        cameraControl.setLinearZoom(zoomRatio) // 0.0f represents 1x zoom level
    }

    private fun setupZoomControl() {
        val maxZoomRatio = cameraInfo.zoomState.value?.maxZoomRatio ?: 1f
        val minZoomRatio = cameraInfo.zoomState.value?.minZoomRatio ?: 1f
        zoomSeekBar.max = ((maxZoomRatio - minZoomRatio) * 10).toInt() // Multiplying by 10 to have finer control
        zoomSeekBar.progress = ((cameraInfo.zoomState.value?.zoomRatio ?: minZoomRatio - minZoomRatio) * 10).toInt()

        zoomSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {

                val zoomRatio = minZoomRatio + (progress / 10f)
                cameraControl.setZoomRatio(zoomRatio)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun checkPermissionAndStartCamera(){
        if (ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            // Permission granted, start camera
            startCamera()
        } else {
            // Request camera permission
            requestCameraPermission()
        }
    }

    private fun requestCameraPermission() {
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                // Permission granted, start camera
                startCamera()
            } else {
                // Permission denied, dismiss fragment
                Timber.d("Camera permission not granted")
                dismiss()
            }
        }.launch(Manifest.permission.CAMERA)
    }

    private fun setupTapToFocus() {
        previewView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                val factory = previewView.meteringPointFactory
                val point = factory.createPoint(event.x, event.y)
                val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF)
                    .setAutoCancelDuration(5, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                cameraControl.startFocusAndMetering(action)
            }
            true
        }
    }

    @OptIn(ExperimentalZeroShutterLag::class)
    private fun startCamera() {
        cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraExecutor = Executors.newSingleThreadExecutor()

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
            val resolution = Size(780, 780)

            val preview = Preview.Builder()
                .setTargetResolution(resolution)
                .build()
                .also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            val imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setTargetResolution(resolution)
                .build()

            try {
                cameraProvider.unbindAll()
                val camera = cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture
                )

                cameraControl = camera.cameraControl
                cameraInfo = camera.cameraInfo
                cameraControl.enableTorch(true)
                val flashOnDrawable = context?.getDrawable(R.drawable.flash_on)
                flashButton.setImageDrawable(flashOnDrawable)
                setZoomLevel(0.0f) //0.0f represents 1x zoom level
                setupZoomControl()
                setupTapToFocus()

                flashButton.setOnClickListener {

                    val flashOnDrawable = context?.getDrawable(R.drawable.flash_on)
                    val flashOffDrawable = context?.getDrawable(R.drawable.flash_off)

                    val flashMode = cameraInfo.torchState.value == TorchState.OFF
                    if (flashMode){
                        flashButton.setImageDrawable(flashOnDrawable)
                        TorchState.ON
                    } else {
                        flashButton.setImageDrawable(flashOffDrawable)
                        TorchState.OFF
                    }
                    cameraControl.enableTorch(cameraInfo.torchState.value == TorchState.OFF)
                }

                captureButton.setOnClickListener {
                    lifecycleScope.launch {
                        takePhoto(imageCapture)
                    }
                }

            } catch (e: Exception) {
                Timber.e("Use case binding failed: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun takePhoto(imageCapture: ImageCapture) {
        try {
            val file = File.createTempFile("IMG_", ".jpeg", requireContext().cacheDir)
            val outputOptions = ImageCapture.OutputFileOptions.Builder(file).build()

            imageCapture.takePicture(
                outputOptions, cameraExecutor, object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                        cameraControl.enableTorch(false)
                        //cameraExecutor.shutdown()
                        lifecycleScope.launch {
                            cameraPreviewViewLay.visibility = View.GONE
                            cameraControlsll.visibility = View.GONE
                            previewViewImageLay.visibility = View.VISIBLE
                            context?.let { with(it).load(file).into(previewImage) }
                            fileAbsPath = file.absolutePath
                            val flashOffDrawable = context?.getDrawable(R.drawable.flash_off)
                            flashButton.setImageDrawable(flashOffDrawable)
                            requireActivity().runOnUiThread {
                                cameraExecutor.shutdown()
                            }
                        }
                    }

                    override fun onError(exception: ImageCaptureException) {
                        Timber.e("takePhoto exception = ${exception.printStackTrace()}")
                        dismiss()
                    }
                }
            )
        }catch (e: Exception){
            e.printStackTrace()
        }
    }

    fun decodeFileToBitmap(filePath: String): Bitmap? {
        return try {
            val tStart = System.currentTimeMillis()

            val options = BitmapFactory.Options()
            options.inPreferredConfig = Bitmap.Config.ARGB_8888
            var bitmap = BitmapFactory.decodeFile(filePath, options)
            val end = System.currentTimeMillis()
            val elapseSec = (end - tStart) / 1000f
            Log.d("cls6.pt process", "bitmp $elapseSec sec")

            bitmap = Bitmap.createScaledBitmap(bitmap, 256, 256, true)

            return bitmap

        } catch (e: Exception) {
            null // Handle exception (e.g., log error)
        }
    }
    fun getAssetPath(context: Context, assetName: String): String? {
        // Access AssetManager directly (no need for Context)
        val assetManager = context.assets

        // Attempt to open the asset using open(assetName)
        val assetInputStream = assetManager.open(assetName) ?: return null

        // Create a temporary file in the internal directory (consider using cache dir for frequent access)
        val internalFile = File(context.filesDir, assetName)

        try {
            internalFile.createNewFile() // Create a new file if it doesn't exist
            val outputStream = FileOutputStream(internalFile)

            // Efficiently copy the asset data using a buffer
            val buffer = ByteArray(1024)
            var bytesRead: Int
            while (assetInputStream.read(buffer).also { bytesRead = it } > 0) {
                outputStream.write(buffer, 0, bytesRead)
            }

            outputStream.close()
            Log.d("cls6.pt process", "absolutePath")

            return internalFile.absolutePath
        } catch (e: IOException) {
            e.printStackTrace()
            return null // Indicate failure to get asset path
        } finally {
            assetInputStream.close() // Ensure closing the stream even on exceptions
        }
    }

    fun convertRGBtoBGR(inputTensor: Tensor): Tensor {
        // Check if the tensor shape is [1, 3, H, W]
        val shape = inputTensor.shape()
        require(!(shape.size != 4 || shape[1] != 3L)) { "Input tensor must have shape [1, 3, H, W]" }
        val channels = shape[1].toInt()
        val height = shape[2].toInt()
        val width = shape[3].toInt()

        // Extract tensor data
        val inputData = inputTensor.dataAsFloatArray

        // Prepare an array for the BGR data
        val outputData = FloatArray(inputData.size)

        val hw = height * width


        // Copy B values
        for (i in 0 until hw) {
            outputData[i] = inputData[2 * hw + i] // B
        }

        // Copy G values
        for (i in 0 until hw) {
            outputData[hw + i] = inputData[hw + i] // G
        }

        // Copy R values
        for (i in 0 until hw) {
            outputData[2 * hw + i] = inputData[i] // R
        }
        // Create a new tensor with the BGR data
        return Tensor.fromBlob(outputData, shape)
    }
    private fun processImage(absolutePath: String): Pair<String, String> {
        var tStart = System.currentTimeMillis()
        var tEnd = System.currentTimeMillis()

        val capturedImage = decodeFileToBitmap(absolutePath)

        val mean = floatArrayOf(0.485f, 0.456f, 0.406f)
        val std = floatArrayOf(0.229f, 0.224f, 0.225f)

        tStart = System.currentTimeMillis()

        val inputTensor = TensorImageUtils.bitmapToFloat32Tensor(capturedImage, mean, std)
        tEnd = System.currentTimeMillis()
        Log.d("cls6.pt process", "TensorImageUtils ${((tEnd - tStart) / 1000.0)} sec")


        tStart = System.currentTimeMillis()

        val bgrTensor = convertRGBtoBGR(inputTensor)

        tEnd = System.currentTimeMillis()
        Log.d("cls6.pt process", "convertRGBtoBGR ${((tEnd - tStart) / 1000.0)} sec")

        tStart = System.currentTimeMillis()

        val output = module?.forward(IValue.from(bgrTensor))
        val outputDict = output?.toDictStringKey()
        val outputTensor = outputDict?.get("logits")!!.toTensor()

        tEnd = System.currentTimeMillis()
        Log.d("cls6.pt process", "forward ${((tEnd - tStart) / 1000.0)} sec")

        var scores = outputTensor.dataAsFloatArray

        Log.d("cls6.pt process", "score ${scores}")


        var dnr = 0f
        for (i in scores.indices) {
            scores[i] = exp(scores[i])
            dnr += scores[i]
        }

        for (i in scores.indices) {
            scores[i] = scores[i] / dnr
        }

        // searching for the index with maximum score

        // searching for the index with maximum score
        var maxScore = 0f
        var maxScoreIdx = -1
        for (i in scores.indices) {
            if (scores[i] > maxScore) {
                maxScore = scores[i]
                maxScoreIdx = i
            }
        }

        val pred = (scores[maxScoreIdx] * 100)
        //score = 1 / (1 + exp(-score.toDouble()).toFloat())
        Log.d("cls6.pt process", "af score ${scores}")

        //val prediction = if (scores > 0.5f) 1 else 0


        //val elapsedSeconds = (tEnd - tStart) / 1000.0
        //val df = DecimalFormat("#0.00")
        //textview3!!.text = "Elapsed Time (Seconds): ${df.format(elapsedSeconds)}"

        val className = classes.diseases[maxScoreIdx]
        /*val df1 = DecimalFormat("#0.00")
        val confidence = if (prediction == 1) {
            (score * 100).toString()
        } else {
            ((1 - score) * 100).toString()
        }*/
        return Pair(className, "$pred")
    }

    fun View.setSafeOnClickListener(interval: Long = 1000, onSafeClick: (View) -> Unit) {
        var lastClickTime = 0L
        val safeClickListener = object : View.OnClickListener {
            override fun onClick(v: View) {
                val currentTime = SystemClock.elapsedRealtime()
                if (currentTime - lastClickTime < interval) return
                lastClickTime = currentTime
                onSafeClick(v)
            }
        }
        setOnClickListener(safeClickListener)
    }
    private fun onPhotoSelected(absolutePath : String){

        //var score = processImage(absolutePath)
        //predictionScore.text = "$score"
        val df1 = DecimalFormat("#0.00")

        val score = processImage(fileAbsPath)
        /*predictionScore.text = "$score"

        val prediction = if (score > 0.5f) 1 else 0
        val predictionResult = classes.diseases[prediction]
        val confidence = if (prediction == 1) {
            (score * 100).toString()
        } else {
            ((1 - score) * 100).toString()
        }*/
        Log.d("cls6.pt process", "score ${score.first} confi ${score.second}")

        requireActivity().runOnUiThread {
            setFragmentResult(CAMERA_RESULT_KEY, Bundle().apply {
                putString(CAMERA_RESULT_URI_KEY, absolutePath)
                putString(CAMERA_PREDICTION_KEY, score.first)
                putString(CAMERA_CONFIDENCE_KEY, "${score.second}%")
                putBoolean(CAMERA_RESULT_KEY, true)
            })
            requireActivity().showToast("Image processed successfully", Toast.LENGTH_SHORT)
            dismiss()
        }

    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    companion object {
        private const val ARG_SAVED_PHOTO_URI = "arg_saved_photo_uri"
        const val CAMERA_RESULT_KEY = "camera_result"
        const val CAMERA_RESULT_URI_KEY = "camera_result_uri"
        const val CAMERA_PREDICTION_KEY = "camera_prediction"
        const val CAMERA_CONFIDENCE_KEY = "camera_confidence"

        fun newInstance(): CameraxLauncherFragment {
            return CameraxLauncherFragment()
        }
    }
}
