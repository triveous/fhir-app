package org.smartregister.fhircore.quest.camerax

import android.Manifest
import android.app.Dialog
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Size
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.AppCompatImageButton
import androidx.appcompat.widget.AppCompatImageView
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.TorchState
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.ui.geometry.Rect
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.setFragmentResult
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.bumptech.glide.Glide.*
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.smartregister.fhircore.quest.R
import timber.log.Timber
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraxLauncherFragment : DialogFragment() {

    private lateinit var cameraProviderFuture: ListenableFuture<ProcessCameraProvider>
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var captureButton: AppCompatImageView
    private lateinit var zoomIv: AppCompatImageView
    private lateinit var flashButton: AppCompatImageButton
    private lateinit var closeCameraIB: AppCompatImageView
    private lateinit var previewView: PreviewView

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

        selectButton.setOnClickListener {
            onPhotoSelected(fileAbsPath)
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

    private fun startCamera() {
        cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraExecutor = Executors.newSingleThreadExecutor()

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
            val resolution = Size(3072, 3072)

            val preview = Preview.Builder()
                .setTargetResolution(resolution)
                .build()
                .also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            val imageCapture = ImageCapture.Builder()
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
                //setupTapToFocus()

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
                        }
                    }

                    override fun onError(exception: ImageCaptureException) {
                        Timber.e("Photo exception = {ImageCaptureException@35501} \"androidx.camera.core.ImageCaptureException: Failed to write temp file\"capture failed: ${exception.message}")
                        dismiss()
                    }
                }
            )
        }catch (e: Exception){
            e.printStackTrace()
        }
    }



    private fun onPhotoSelected(absolutePath : String){
        setFragmentResult(CAMERA_RESULT_KEY, Bundle().apply {
            putString(CAMERA_RESULT_URI_KEY, absolutePath)
            putBoolean(CAMERA_RESULT_KEY, true)
        })
        dismiss()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    companion object {
        private const val ARG_SAVED_PHOTO_URI = "arg_saved_photo_uri"
        const val CAMERA_RESULT_KEY = "camera_result"
        const val CAMERA_RESULT_URI_KEY = "camera_result_uri"

        fun newInstance(): CameraxLauncherFragment {
            return CameraxLauncherFragment()
        }
    }
}
