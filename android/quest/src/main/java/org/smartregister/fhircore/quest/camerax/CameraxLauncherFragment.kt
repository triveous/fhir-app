package org.smartregister.fhircore.quest.camerax

import android.Manifest
import android.app.Dialog
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.AppCompatImageButton
import androidx.appcompat.widget.AppCompatImageView
import androidx.camera.core.CameraSelector
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
    private lateinit var flashButton: AppCompatImageButton
    private lateinit var previewView: PreviewView

    private lateinit var cameraPreviewViewLay: FrameLayout
    private lateinit var previewViewImageLay: ConstraintLayout
    private lateinit var retakeButton: AppCompatImageButton
    private lateinit var selectButton: AppCompatImageButton
    private lateinit var previewImage: AppCompatImageView

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
        captureButton = view.findViewById(R.id.captureButton)

        cameraPreviewViewLay = view.findViewById(R.id.camera_preview_fl)
        previewViewImageLay = view.findViewById(R.id.photo_preview_cl)
        retakeButton = view.findViewById(R.id.photo_retake)
        selectButton = view.findViewById(R.id.photo_select)
        previewImage = view.findViewById(R.id.previewImage)


        selectButton.setOnClickListener {
            onPhotoSelected(fileAbsPath)
        }

        retakeButton.setOnClickListener {
            previewViewImageLay.visibility = View.GONE
            cameraPreviewViewLay.visibility = View.VISIBLE
            checkPermissionAndStartCamera()
            fileAbsPath = ""
        }

        checkPermissionAndStartCamera()
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

    private fun startCamera() {
        cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraExecutor = Executors.newSingleThreadExecutor()

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            val imageCapture = ImageCapture.Builder()
                .build()

            try {
                cameraProvider.unbindAll()

                val camera = cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture
                )

                val cameraControl = camera.cameraControl
                val cameraInfo = camera.cameraInfo

                flashButton.setOnClickListener {

                    val flashOnDrawable = context?.getDrawable(com.google.android.fhir.datacapture.contrib.views.barcode.R.drawable.ic_flash_on_vd_white_24)
                    val flashOffDrawable = context?.getDrawable(com.google.android.fhir.datacapture.contrib.views.barcode.R.drawable.ic_flash_off_vd_white_24)

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
                        cameraExecutor.shutdown()
                        lifecycleScope.launch {
                            cameraPreviewViewLay.visibility = View.GONE
                            previewViewImageLay.visibility = View.VISIBLE
                            context?.let { with(it).load(file).into(previewImage) }
                            fileAbsPath = file.absolutePath
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
