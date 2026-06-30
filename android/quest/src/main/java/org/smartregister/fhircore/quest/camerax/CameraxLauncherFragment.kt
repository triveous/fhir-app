package org.smartregister.fhircore.quest.camerax

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.media.MediaActionSound
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
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
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.bumptech.glide.Glide.with
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.target.Target
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.pytorch.IValue
import org.pytorch.Module
import org.pytorch.Tensor
import org.pytorch.torchvision.TensorImageUtils
import org.smartregister.fhircore.engine.util.extension.showToast
import org.smartregister.fhircore.quest.R
import org.smartregister.fhircore.quest.ui.register.customui.ZoomableImageView
import org.smartregister.fhircore.quest.util.OpenCVUtils
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.DecimalFormat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.collections.get
import kotlin.math.exp
import androidx.core.view.isVisible
import com.google.android.fhir.FhirEngine
import dagger.hilt.android.AndroidEntryPoint
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import org.smartregister.fhircore.quest.util.DeviceMetrics
import org.smartregister.fhircore.quest.util.FeatureFlagUtil
import org.smartregister.fhircore.quest.util.ImageQualityAnalyzer
import org.smartregister.fhircore.quest.util.PostHogAnalytics
import org.smartregister.fhircore.quest.util.ScreeningTimer
import javax.inject.Inject
import kotlin.math.ln

@AndroidEntryPoint
class CameraxLauncherFragment : DialogFragment() {

    @Inject
    lateinit var fhirEngine: FhirEngine

    @Inject
    lateinit var featureFlagUtil: FeatureFlagUtil

    private val cameraxViewModel: CameraxLauncherViewModel by viewModels()

    private lateinit var cameraProviderFuture: ListenableFuture<ProcessCameraProvider>
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var captureButton: AppCompatImageView
    private lateinit var captureProgress: ProgressBar
    private lateinit var captureFlashOverlay: View
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
    private lateinit var previewImage: ZoomableImageView
    private lateinit var scaleGestureDetector: ScaleGestureDetector
    private lateinit var tapGestureDetector: GestureDetector
    private lateinit var focusRing: AppCompatImageView
    private val hideFocusRingRunnable = Runnable { hideFocusIndicator() }
    private lateinit var cameraControl: CameraControl
    private lateinit var cameraInfo: CameraInfo
    private lateinit var zoomSeekBar: CustomSeekBar

    // Holds the ImageCapture use case only while it is actually bound to the camera. It is null
    // whenever the camera is mid-(re)bind or unbound, so a stray shutter tap can't call
    // takePicture() on an unbound use case (which throws "Not bound to a valid Camera").
    @Volatile private var boundImageCapture: ImageCapture? = null

    @Volatile var module6 : Module? = null
    @Volatile var module8 : Module? = null
    @Volatile var module82 : Module? = null
    // Init runs concurrently with photo capture; onPhotoSelected awaits this before
    // running inference so the IO-thread forward pass can't race with module load.
    private var initModelJob: Job? = null
    // Retained for the frozen AI pipeline below (ScreeningTimer.markStep + model analytics props).
    // The camera UI state machine keeps its own copy in CameraxLauncherViewModel.
    private var screeningId: String? = null

    // Standard system camera shutter sound, played on capture for audible feedback.
    private var shutterSound: MediaActionSound? = null

    // Path currently shown in the captured-photo preview, so render() only (re)loads the image
    // when it actually changes — not on every state emission.
    private var lastPreviewedPath: String? = null

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            // Permission granted, start camera
            startCamera()
        } else {
            // Permission denied, dismiss fragment
            activity?.let {
                Toast.makeText(
                    it,
                    getString(R.string.camera_permissions_denied),
                    Toast.LENGTH_SHORT,
                ).show()
            }
            dismiss()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        //setStyle(STYLE_NO_FRAME, android.R.style.Theme_Holo_Light)
        setStyle(DialogFragment.STYLE_NORMAL, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        screeningId = arguments?.getString(ARG_SCREENING_ID)
        cameraxViewModel.setScreeningId(screeningId)
        // Warm up the camera provider now (its first initialization is the cold-start cost) so the
        // preview can bind with minimal delay once the view is ready. getInstance is a cached
        // singleton, so the call in startCamera() reuses this same initialization.
        runCatching { ProcessCameraProvider.getInstance(requireContext()) }
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
        captureProgress = view.findViewById(R.id.captureProgress)
        captureFlashOverlay = view.findViewById(R.id.captureFlashOverlay)
        focusRing = view.findViewById(R.id.focusRing)

        // Pre-load the system shutter sound so it plays without latency on the first capture.
        shutterSound = MediaActionSound().apply { load(MediaActionSound.SHUTTER_CLICK) }

        selectButton.setSafeOnClickListener(interval = 6000) {
            val path = cameraxViewModel.uiState.value.capturedFilePath
            if (path.isNullOrEmpty()) return@setSafeOnClickListener
            lifecycleScope.launch {
                onPhotoSelected(path)
            }
        }

        closeCameraIB.setOnClickListener {
            if (::cameraExecutor.isInitialized) {
                cameraExecutor.shutdown()
            }
            dismiss()
        }

        zoomIv.setOnClickListener {
            cameraxViewModel.toggleZoomIndicator()
        }

        flashButton.setOnClickListener {
            cameraxViewModel.toggleFlash()
        }

        captureButton.setOnClickListener {
            // Read the currently-bound use case. If the camera is mid-(re)bind this is null, so we
            // drop the tap instead of throwing "Not bound to a valid Camera".
            val capture = boundImageCapture ?: return@setOnClickListener
            lifecycleScope.launch {
                takePhoto(capture)
            }
        }

        retakeButton.setOnClickListener {
            // onRetake() flips back to capture mode (shutter stays disabled until the camera
            // rebinds, which avoids reopening the retake race) and bumps the retake counter.
            cameraxViewModel.onRetake()
            checkPermissionAndStartCamera()
        }

        scaleGestureDetector = ScaleGestureDetector(requireContext(), object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                // Guard: a pinch can arrive before the camera is bound.
                if (!::cameraControl.isInitialized || !::cameraInfo.isInitialized) return false
                val zoomRatio = cameraInfo.zoomState.value?.zoomRatio ?: 1f
                val scaleFactor = detector.scaleFactor
                cameraControl.setZoomRatio(zoomRatio * scaleFactor)
                return true
            }
        })

        // Single-tap (one finger, no real movement) = tap-to-focus. Kept separate from the pinch
        // detector so zoom and focus no longer fight over previewView's touch listener.
        tapGestureDetector = GestureDetector(requireContext(), object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                focusOnTap(e.x, e.y)
                return true
            }
        })

        previewView.setOnTouchListener { v, event ->
            scaleGestureDetector.onTouchEvent(event)
            // Don't treat the end of a pinch as a focus tap.
            if (!scaleGestureDetector.isInProgress) {
                tapGestureDetector.onTouchEvent(event)
            }
            if (event.action == MotionEvent.ACTION_UP) v.performClick()
            true
        }

        // The ViewModel is the single source of truth for what the dialog shows; render() applies
        // each emitted CameraUiState to the views and camera hardware.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                cameraxViewModel.uiState.collect { render(it) }
            }
        }

        checkPermissionAndStartCamera()

        initModelJob = lifecycleScope.launch {
            if (isAiInferenceEnabled()) {
                initModel()
            }
        }
    }

    /** Applies the single source-of-truth [CameraUiState] to the views and camera hardware. */
    private fun render(state: CameraUiState) {
        // Disabling the shutter also swaps it to the dimmed "busy" drawable (state-list selector),
        // which together with the spinner makes it obvious a capture is already in progress.
        captureButton.isEnabled = state.shutterEnabled

        val inCaptureMode = state.mode == CameraMode.CAPTURE
        cameraPreviewViewLay.visibility = if (inCaptureMode) View.VISIBLE else View.GONE
        cameraControlsll.visibility = if (inCaptureMode) View.VISIBLE else View.GONE
        previewViewImageLay.visibility = if (inCaptureMode) View.GONE else View.VISIBLE

        // Load (or restore) the captured photo into the preview whenever we are in PREVIEW mode.
        // Guarded by lastPreviewedPath so it only decodes when the photo actually changes.
        if (inCaptureMode) {
            lastPreviewedPath = null
        } else {
            val path = state.capturedFilePath
            if (!path.isNullOrEmpty() && path != lastPreviewedPath) {
                lastPreviewedPath = path
                showCapturedPreview(File(path))
            }
        }

        // Spinner over the shutter while the capture is in flight (before the preview appears).
        captureProgress.visibility =
            if (state.isCapturing && inCaptureMode) View.VISIBLE else View.GONE

        zoomIndicatorll.visibility = if (state.zoomIndicatorVisible) View.VISIBLE else View.GONE

        val flashDrawable =
            context?.getDrawable(if (state.flashOn) R.drawable.flash_on else R.drawable.flash_off)
        flashButton.setImageDrawable(flashDrawable)
        if (::cameraControl.isInitialized) {
            cameraControl.enableTorch(state.flashOn)
        }
    }

    /**
     * Loads the captured photo into the zoomable preview at its original resolution. By default
     * Glide downsamples to the view size, which looks soft when the user pinch-zooms (the server
     * copy is the full file, so it looks sharper there). `Target.SIZE_ORIGINAL` + `dontTransform()`
     * load the full, uncropped image; the [ZoomableImageView]'s fitCenter handles on-screen
     * scaling. If the full decode runs out of memory, `error()` falls back to a downsampled load so
     * the user always sees the photo and the app never crashes.
     */
    private fun showCapturedPreview(file: File) {
        val ctx = context ?: return
        with(ctx)
            .load(file)
            .diskCacheStrategy(DiskCacheStrategy.NONE)
            .skipMemoryCache(true)
            .dontTransform()
            .override(Target.SIZE_ORIGINAL)
            .error(
                with(ctx)
                    .load(file)
                    .diskCacheStrategy(DiskCacheStrategy.NONE)
                    .skipMemoryCache(true)
            )
            .into(previewImage)
    }

    /** Plays the standard-camera capture feedback: shutter sound, button bounce and screen flash. */
    private fun playCaptureFeedback() {
        runCatching { shutterSound?.play(MediaActionSound.SHUTTER_CLICK) }
        animateShutterPress()
        animateCaptureFlash()
    }

    /** Quick scale-down/up on the shutter button so the tap feels tactile. */
    private fun animateShutterPress() {
        captureButton.animate()
            .scaleX(0.85f)
            .scaleY(0.85f)
            .setDuration(80)
            .withEndAction {
                captureButton.animate().scaleX(1f).scaleY(1f).setDuration(80).start()
            }
            .start()
    }

    /** Brief white flash over the whole screen, mimicking a standard camera app's capture cue. */
    private fun animateCaptureFlash() {
        captureFlashOverlay.clearAnimation()
        captureFlashOverlay.alpha = 0f
        captureFlashOverlay.visibility = View.VISIBLE
        captureFlashOverlay.animate()
            .alpha(1f)
            .setDuration(70)
            .withEndAction {
                captureFlashOverlay.animate()
                    .alpha(0f)
                    .setDuration(130)
                    .withEndAction { captureFlashOverlay.visibility = View.GONE }
                    .start()
            }
            .start()
    }

    private suspend fun isAiInferenceEnabled(): Boolean =
        featureFlagUtil.isAiInferenceEnabled()

    private fun initModel() {
        try {
            module6 = Module.load(getAssetPath(requireContext(), "model6.pt"))
            module8 = Module.load(getAssetPath(requireContext(), "model8.pt"))
            module82 = Module.load(getAssetPath(requireContext(), "model82.pt"))
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
        requestPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    /**
     * Runs a real auto-focus + auto-exposure metering pass at the tapped point and shows the
     * focus-ring indicator. The tap coordinates are mapped to sensor coordinates via
     * [PreviewView.getMeteringPointFactory], so the camera focuses exactly where the user tapped.
     */
    private fun focusOnTap(tapX: Float, tapY: Float) {
        // Camera may not be bound yet (mid-(re)bind); show nothing rather than crash.
        if (!::cameraControl.isInitialized) return

        showFocusIndicator(tapX, tapY)

        try {
            val point = previewView.meteringPointFactory.createPoint(tapX, tapY)
            // AF + AE so the tapped subject is both sharp and correctly exposed, like a standard
            // camera app. Auto-cancel returns the camera to continuous AF after a few seconds.
            val action =
                FocusMeteringAction.Builder(
                    point,
                    FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE,
                )
                    .setAutoCancelDuration(4, java.util.concurrent.TimeUnit.SECONDS)
                    .build()

            val future = cameraControl.startFocusAndMetering(action)
            future.addListener(
                {
                    val focused =
                        try {
                            future.get().isFocusSuccessful
                        } catch (e: Exception) {
                            false
                        }
                    onFocusResult(focused)
                },
                ContextCompat.getMainExecutor(requireContext()),
            )
        } catch (e: Exception) {
            Timber.e(e, "Tap-to-focus metering failed")
            // Still let the indicator fade out so it doesn't linger on screen.
            onFocusResult(false)
        }
    }

    /** Places the focus ring at the tapped point and plays the appear animation. */
    private fun showFocusIndicator(tapX: Float, tapY: Float) {
        val ringSize = resources.getDimensionPixelSize(R.dimen.focus_ring_size)
        focusRing.removeCallbacks(hideFocusRingRunnable)
        focusRing.animate().cancel()
        // previewView and focusRing share the same parent (camera_preview_fl), so offset the
        // (previewView-relative) tap by previewView's position to centre the ring on the tap.
        focusRing.x = previewView.x + tapX - ringSize / 2f
        focusRing.y = previewView.y + tapY - ringSize / 2f
        focusRing.visibility = View.VISIBLE
        focusRing.alpha = 0f
        focusRing.scaleX = 1.4f
        focusRing.scaleY = 1.4f
        focusRing.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(220)
            .start()
        // Safety net: hide the ring even if the focus future never reports back.
        focusRing.postDelayed(hideFocusRingRunnable, 1500)
    }

    /** Brief "lock" confirmation bump when focus settles, then fade the ring out. */
    private fun onFocusResult(focused: Boolean) {
        if (focusRing.visibility != View.VISIBLE) return
        if (focused) {
            focusRing.animate()
                .scaleX(0.9f)
                .scaleY(0.9f)
                .setDuration(120)
                .withEndAction {
                    focusRing.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                }
                .start()
        }
        focusRing.removeCallbacks(hideFocusRingRunnable)
        focusRing.postDelayed(hideFocusRingRunnable, 700)
    }

    private fun hideFocusIndicator() {
        focusRing.animate()
            .alpha(0f)
            .setDuration(180)
            .withEndAction { focusRing.visibility = View.GONE }
            .start()
    }

    @OptIn(ExperimentalZeroShutterLag::class)
    private fun startCamera() {
        // The capture use case is bound asynchronously below. Until that completes the camera has
        // no valid ImageCapture, so clear the previously bound reference and disable the shutter
        // (via state). This closes the retake race where the stale click listener could fire
        // takePicture() on an already-unbound use case and surface "Not bound to a valid Camera".
        boundImageCapture = null
        cameraxViewModel.onCameraBinding()
        // Shut down the executor from a previous bind so repeated (re)binds don't leak threads.
        if (::cameraExecutor.isInitialized) {
            cameraExecutor.shutdown()
        }
        cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraExecutor = Executors.newSingleThreadExecutor()

        cameraProviderFuture.addListener({
            // The provider future resolves asynchronously. By the time it fires the user may have
            // closed the dialog or backgrounded the app, so binding to a destroyed lifecycle or
            // touching detached views would throw. Bail out cleanly, and wrap the rest so a
            // provider/bind failure can never crash the process on the main executor.
            if (!isAdded || view == null) return@addListener
            try {
                val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

                // Preview only needs to fill the screen. Forcing a 4096x4096 preview target makes
                // the camera configure a huge, slow-to-start stream — that is the "black screen
                // then it loads" hang. Letting CameraX pick a display-sized preview resolution (and
                // keeping it consistent with the capture use case) makes the first frames arrive
                // almost immediately. The CAPTURED image quality is unaffected: ImageCapture below
                // keeps the full 4096x4096 target, so the saved photo resolution is unchanged.
                val preview = Preview.Builder()
                    .build()
                    .also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                val imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .setTargetResolution(Size(4096, 4096))
                    .build()

                cameraProvider.unbindAll()
                val camera = cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture
                )

                cameraControl = camera.cameraControl
                cameraInfo = camera.cameraInfo
                // Publish the live ImageCapture, then flip to the bound state. onCameraBound()
                // re-enables the shutter and turns the torch on; render() applies both. The shutter
                // and flash click listeners are registered once in onViewCreated().
                boundImageCapture = imageCapture
                setZoomLevel(0.0f) //0.0f represents 1x zoom level
                setupZoomControl()
                // Tap-to-focus is wired once in onViewCreated via the gesture detector, so it no
                // longer overwrites the pinch-to-zoom touch listener on every (re)bind.
                cameraxViewModel.onCameraBound()

            } catch (e: Exception) {
                Timber.e(e, "Camera start/bind failed")
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun takePhoto(imageCapture: ImageCapture) {
        // beginCapture() applies the re-entrancy guard, disables the shutter (via state), records
        // the start time and marks the screening step. It returns false if a capture is in flight.
        if (!cameraxViewModel.beginCapture()) return
        // Immediate, standard-camera feedback so the user knows the photo was taken and doesn't
        // tap again: shutter sound, a button "press" bounce and a quick white screen flash. The
        // progress spinner over the shutter (driven by render) covers the rest of the capture.
        playCaptureFeedback()
        try {
            val file = File.createTempFile("IMG_", ".jpeg", requireContext().filesDir)
            val outputOptions = ImageCapture.OutputFileOptions.Builder(file).build()

            imageCapture.takePicture(
                outputOptions, cameraExecutor, object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                        cameraControl.enableTorch(false)
                        lifecycleScope.launch {
                            try {
                                if (::cameraProviderFuture.isInitialized) {
                                    cameraProviderFuture.get().unbindAll()
                                }
                            } catch (e: Exception) {
                                Timber.e(e, "Error unbinding camera")
                            }
                            // Camera is unbound while the captured photo is previewed; clear the
                            // bound reference so a stray shutter tap can't fire on the now-invalid
                            // use case. The switch to preview mode (shutter off, torch off, layout
                            // swap, capture timing) is driven by onCaptureSaved() -> render().
                            boundImageCapture = null
                            // The captured photo is loaded into the preview by render() once the
                            // state flips to PREVIEW (see showCapturedPreview). Driving it from
                            // state means the preview is restored correctly after the fragment is
                            // recreated or the app is resumed, not just on this one callback.
                            cameraxViewModel.onCaptureSaved(file.absolutePath)
                            if (::cameraExecutor.isInitialized) {
                                cameraExecutor.shutdown()
                            }
                        }
                    }

                    override fun onError(exception: ImageCaptureException) {
                        // This callback runs on the cameraExecutor background thread. Touching the
                        // FragmentManager (dismiss()) or views directly from here previously let an
                        // IllegalStateException escape on a background thread, which the global
                        // uncaught-exception handler turned into a full process kill — wiping the
                        // in-memory questionnaire and losing every photo already captured. Marshal
                        // all UI work to the main thread (lifecycleScope cancels if the fragment is
                        // gone) and recover in place instead of tearing the screen down.
                        Timber.e(exception, "Image capture failed: ${exception.message}")
                        lifecycleScope.launch {
                            if (!isAdded) return@launch
                            cameraxViewModel.onCaptureError()
                            try {
                                // Rebind the camera so the user can retry the shot in place rather
                                // than being kicked out of the screen and losing their session.
                                checkPermissionAndStartCamera()
                                activity?.showToast(
                                    getString(R.string.image_capture_failed),
                                    Toast.LENGTH_SHORT,
                                )
                            } catch (e: Exception) {
                                Timber.e(e, "Failed to recover camera after capture error")
                            }
                        }
                    }
                }
            )
        }catch (e: Exception){
            // takePicture() threw before dispatch; re-enable the shutter (via state) so the user
            // can retry, and clear the in-flight flag.
            cameraxViewModel.onCaptureFailedSynchronously()
            e.printStackTrace()
        }
    }

    private fun decodeFileToBitmap(filePath: String): Bitmap? {
        return try {
            val tStart = System.currentTimeMillis()
            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            var bitmap = BitmapFactory.decodeFile(filePath, options)
                ?: throw IOException("Failed to decode file: $filePath")

            val end = System.currentTimeMillis()
            val elapseSec = (end - tStart) / 1000f
            Timber.d("cls6.pt process: bitmap $elapseSec sec")

            Bitmap.createScaledBitmap(bitmap, 256, 256, true)
        } catch (e: Exception) {
            Timber.e(e, "Error decoding file to bitmap: $filePath")
            null
        }
    }
    fun getAssetPath(context: Context, assetName: String): String? {
        val assetManager = context.assets
        val assetInputStream = assetManager.open(assetName) ?: return null
        val internalFile = File(context.filesDir, assetName)

        try {
            internalFile.createNewFile()
            val outputStream = FileOutputStream(internalFile)

            val buffer = ByteArray(1024)
            var bytesRead: Int
            while (assetInputStream.read(buffer).also { bytesRead = it } > 0) {
                outputStream.write(buffer, 0, bytesRead)
            }
            outputStream.close()
            return internalFile.absolutePath
        } catch (e: IOException) {
            e.printStackTrace()
            return null // Indicate failure to get asset path
        } finally {
            assetInputStream.close() // Ensure closing the stream even on exceptions
        }
    }

    private fun convertRGBtoBGR(inputTensor: Tensor): Tensor? {
        return try {
            val shape = inputTensor.shape()
            require(shape.size == 4 && shape[1] == 3L) { "Input tensor must have shape [1, 3, H, W]" }

            val channels = shape[1].toInt()
            val height = shape[2].toInt()
            val width = shape[3].toInt()

            val inputData = inputTensor.dataAsFloatArray
            val outputData = FloatArray(inputData.size)
            val productOfHeightAndWidth = height * width

            for (i in 0 until productOfHeightAndWidth) {
                outputData[i] = inputData[2 * productOfHeightAndWidth + i] // B
                outputData[productOfHeightAndWidth + i] = inputData[productOfHeightAndWidth + i] // G
                outputData[2 * productOfHeightAndWidth + i] = inputData[i] // R
            }

            Tensor.fromBlob(outputData, shape)
        } catch (e: Exception) {
            Timber.e(e, "Failed to convert RGB to BGR")
            null
        }
    }

    private fun processImage(absolutePath: String, runAiInference: Boolean): ImageProcessingResult? {
        val sourceMat = OpenCVUtils.scaleImageMat(absolutePath)
        if (sourceMat == null) {
            PostHogAnalytics.captureError("CameraxLauncherFragment", "Image processing failed: unable to decode image")
            return null
        }

        val rgbMat = Mat()
        return try {
            val qualityProps = ImageQualityAnalyzer.analyze(sourceMat)
            if (!runAiInference) return ImageProcessingResult(emptyMap(), qualityProps, 0L)

            Imgproc.cvtColor(sourceMat, rgbMat, Imgproc.COLOR_BGR2RGB)
            val processedImage = Bitmap.createBitmap(rgbMat.cols(), rgbMat.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(rgbMat, processedImage)

            val mean = floatArrayOf(0.0f, 0.0f, 0.0f)
            val std = floatArrayOf(1f, 1f, 1f)

            val inputTensor = TensorImageUtils.bitmapToFloat32Tensor(processedImage, mean, std)
            val bgrTensor = convertRGBtoBGR(inputTensor) ?: throw IllegalStateException("Failed to convert RGB to BGR")
            val inputIValue = IValue.from(bgrTensor)

            ScreeningTimer.markStep(screeningId, "ai_inference_started")
            val result6 = runInference(inputIValue, module6, "v6")
            val result8 = runInference(inputIValue, module8, "v8")
            val result82 = runInference(inputIValue, module82, "v82")
            val modelResults = listOf(result6, result8, result82)

            modelResults.forEach { captureModelInference(it) }

            val combinedInferenceTimeMs = modelResults.sumOf { it.inferenceTimeMs }
            val allSuspicious = modelResults.all { it.isSuspicious }
            val finalPrediction = if (allSuspicious) 1 else 0
            val finalClassName = classes.diseases[finalPrediction]
            val finalConfidenceValue = modelResults.map { it.confidence }.average().toFloat()
            val finalConfidence = DecimalFormat("#.##").format(finalConfidenceValue)
            val ensemble =
                ModelInferenceResult(
                    modelName = "ensemble",
                    prediction = if (allSuspicious) "suspicious" else "non_suspicious",
                    displayPrediction = finalClassName,
                    confidence = finalConfidenceValue,
                    probability = modelResults.map { it.probability }.average().toFloat(),
                    entropy = modelResults.map { it.entropy }.average(),
                    lowConfidence = finalConfidenceValue < LOW_CONFIDENCE_THRESHOLD,
                    inferenceTimeMs = combinedInferenceTimeMs,
                    combinedInferenceTimeMs = combinedInferenceTimeMs,
                    isSuspicious = allSuspicious,
                )
            captureModelInference(ensemble)
            ScreeningTimer.markStep(screeningId, "ai_inference_completed")

            Timber.d("Final prediction: $finalPrediction, Class: $finalClassName")
            ImageProcessingResult(
                resultMap =
                    mapOf(
                        CAMERA_PREDICTION_KEY to finalClassName,
                        CAMERA_CONFIDENCE_KEY to finalConfidence,
                        "model6_prediction" to result6.displayPrediction,
                        "model6_confidence" to result6.confidence.toString(),
                        "model8_prediction" to result8.displayPrediction,
                        "model8_confidence" to result8.confidence.toString(),
                        "model82_prediction" to result82.displayPrediction,
                        "model82_confidence" to result82.confidence.toString(),
                    ),
                qualityProps = qualityProps,
                combinedInferenceTimeMs = combinedInferenceTimeMs,
            )
        } catch (e: Exception) {
            Log.d("Error", "Error processing image ${e.printStackTrace()}")
            PostHogAnalytics.captureError("CameraxLauncherFragment", "Image processing failed: ${e.message}")
            null
        } finally {
            sourceMat.release()
            rgbMat.release()
        }
    }

    private fun runInference(inputIValue: IValue, module: Module?, modelName: String): ModelInferenceResult {
        val startedMs = SystemClock.elapsedRealtime()
        val outputTensor = module?.forward(inputIValue)?.toTensor()
            ?: throw IllegalStateException("Module $modelName is null or forward operation failed")
        val inferenceTimeMs = SystemClock.elapsedRealtime() - startedMs
        val probability = sigmoid(outputTensor.dataAsFloatArray[0])
        val prediction = if (probability > 0.5f) 1 else 0
        val confidence = if (prediction == 1) probability * 100 else (1 - probability) * 100
        return ModelInferenceResult(
            modelName = modelName,
            prediction = if (prediction == 1) "suspicious" else "non_suspicious",
            displayPrediction = classes.diseases[prediction],
            confidence = confidence,
            probability = probability,
            entropy = binaryEntropy(probability),
            // Low-confidence is evaluated on the predicted side of 50%; 65% is the V1 launch cut.
            lowConfidence = confidence < LOW_CONFIDENCE_THRESHOLD,
            inferenceTimeMs = inferenceTimeMs,
            isSuspicious = prediction == 1,
        )
    }

    private fun captureModelInference(result: ModelInferenceResult) {
        PostHogAnalytics.capture(
            PostHogAnalytics.Events.MODEL_INFERENCE_COMPLETED,
            mapOf(
                PostHogAnalytics.Props.SCREENING_ID to screeningId,
                PostHogAnalytics.Props.MODEL_NAME to result.modelName,
                PostHogAnalytics.Props.MODEL_VERSION to MODEL_VERSION,
                PostHogAnalytics.Props.MODEL_PREDICTION to result.prediction,
                PostHogAnalytics.Props.MODEL_CONFIDENCE to result.confidence,
                PostHogAnalytics.Props.MODEL_ENTROPY to result.entropy,
                PostHogAnalytics.Props.LOW_CONFIDENCE to result.lowConfidence,
                PostHogAnalytics.Props.INFERENCE_TIME_MS to result.inferenceTimeMs,
                PostHogAnalytics.Props.COMBINED_INFERENCE_TIME_MS to result.combinedInferenceTimeMs,
            ),
        )
    }

    private fun sigmoid(value: Float): Float = (1 / (1 + exp(-value))).toFloat()

    private fun binaryEntropy(probability: Float): Double {
        val p = probability.coerceIn(0.000001f, 0.999999f).toDouble()
        return -p * ln(p) - (1 - p) * ln(1 - p)
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
    private suspend fun onPhotoSelected(absolutePath : String){
        val aiEnabled = cameraxViewModel.isAiInferenceEnabled()
        // The AI pipeline (processImage and everything it calls) is frozen and stays here; the
        // ViewModel only consumes its already-computed output to build the result + analytics.
        val processingResult = if (aiEnabled) {
            // Wait for model load to finish before forwarding on Dispatchers.IO.
            // Without this the IO-thread forward pass can race with model load on
            // Main, hit a null Module, throw, and surface as "Error" — which the
            // case-level combine then reads as Non-Suspicious (false negative).
            initModelJob?.join()
            withContext(Dispatchers.IO) {
                processImage(absolutePath, runAiInference = true)
            }
        } else {
            withContext(Dispatchers.IO) {
                processImage(absolutePath, runAiInference = false)
            }
        }
        val resultMap = processingResult?.resultMap?.takeIf { it.isNotEmpty() }

        val captureResult = cameraxViewModel.preparePhotoCapture(
            absolutePath = absolutePath,
            aiEnabled = aiEnabled,
            resultMap = resultMap,
            qualityProps = processingResult?.qualityProps,
            combinedInferenceTimeMs = processingResult?.combinedInferenceTimeMs,
            deviceMetrics = DeviceMetrics.snapshot(requireContext()),
        )

        setFragmentResult(CAMERA_RESULT_KEY, Bundle().apply {
            putString(CAMERA_RESULT_URI_KEY, captureResult.uri)
            captureResult.stringExtras.forEach { (key, value) -> putString(key, value) }
            putBoolean(CAMERA_RESULT_KEY, true)
        })
        activity?.showToast(captureResult.toastMessage, Toast.LENGTH_SHORT)
        dismiss()
    }

    override fun onDestroy() {
        super.onDestroy()
        if(this::cameraExecutor.isInitialized){
            cameraExecutor.shutdown()
        }
        shutterSound?.release()
        shutterSound = null
    }

    private data class ImageProcessingResult(
        val resultMap: Map<String, Any>,
        val qualityProps: Map<String, Any>,
        val combinedInferenceTimeMs: Long,
    )

    private data class ModelInferenceResult(
        val modelName: String,
        val prediction: String,
        val displayPrediction: String,
        val confidence: Float,
        val probability: Float,
        val entropy: Double,
        val lowConfidence: Boolean,
        val inferenceTimeMs: Long,
        val combinedInferenceTimeMs: Long? = null,
        val isSuspicious: Boolean,
    )

    companion object {
        private const val ARG_SAVED_PHOTO_URI = "arg_saved_photo_uri"
        private const val ARG_SCREENING_ID = "arg_screening_id"
        private const val MODEL_VERSION = "v38"
        private const val LOW_CONFIDENCE_THRESHOLD = 65f
        const val CAMERA_RESULT_KEY = "camera_result"
        const val CAMERA_RESULT_URI_KEY = "camera_result_uri"
        const val CAMERA_PREDICTION_KEY = "camera_prediction"
        const val CAMERA_CONFIDENCE_KEY = "camera_confidence"

        const val CAMERA_MODEL6_PREDICTION_KEY = "camera_model6_prediction"
        const val CAMERA_MODEL6_CONFIDENCE_KEY = "camera_model6_confidence"
        const val CAMERA_MODEL8_PREDICTION_KEY = "camera_model8_prediction"
        const val CAMERA_MODEL8_CONFIDENCE_KEY = "camera_model8_confidence"
        const val CAMERA_MODEL82_PREDICTION_KEY = "camera_model82_prediction"
        const val CAMERA_MODEL82_CONFIDENCE_KEY = "camera_model82_confidence"

        fun newInstance(screeningId: String? = null): CameraxLauncherFragment {
            return CameraxLauncherFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_SCREENING_ID, screeningId)
                }
            }
        }
    }
}
