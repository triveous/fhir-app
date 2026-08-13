package org.smartregister.fhircore.quest.ui.register.customui

import android.app.Dialog
import android.content.Context
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.drawable.Drawable
import android.net.Uri
import android.provider.OpenableColumns
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatImageView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.os.bundleOf
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.Target
import com.google.android.fhir.FhirEngine
import com.google.android.fhir.FhirEngineProvider
import com.google.android.fhir.datacapture.extensions.MimeType
import com.google.android.fhir.datacapture.extensions.hasMimeType
import com.google.android.fhir.datacapture.extensions.hasMimeTypeOnly
import com.google.android.fhir.datacapture.extensions.mimeTypes
import com.google.android.fhir.datacapture.extensions.tryUnwrapContext
import com.google.android.fhir.datacapture.validation.Invalid
import com.google.android.fhir.datacapture.validation.NotValidated
import com.google.android.fhir.datacapture.validation.Valid
import com.google.android.fhir.datacapture.validation.ValidationResult
import com.google.android.fhir.datacapture.views.HeaderView
import com.google.android.fhir.datacapture.views.QuestionnaireViewItem
import com.google.android.fhir.datacapture.views.attachment.OpenDocumentLauncherFragment
import com.google.android.fhir.datacapture.views.factories.QuestionnaireItemViewHolderDelegate
import com.google.android.fhir.datacapture.views.factories.QuestionnaireItemViewHolderFactory
import com.google.android.material.divider.MaterialDivider
import com.google.android.material.snackbar.Snackbar
import com.google.gson.Gson
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.hl7.fhir.r4.model.Attachment
import org.hl7.fhir.r4.model.DecimalType
import org.hl7.fhir.r4.model.DocumentReference
import org.hl7.fhir.r4.model.Enumerations
import org.hl7.fhir.r4.model.Questionnaire
import org.hl7.fhir.r4.model.QuestionnaireResponse
import org.hl7.fhir.r4.model.ResourceType
import org.hl7.fhir.r4.model.StringType
import org.smartregister.fhircore.engine.util.SharedPreferencesHelper
import org.smartregister.fhircore.engine.util.FeatureFlagUtil
import org.smartregister.fhircore.engine.util.FeatureFlagUtilEntryPoint
import kotlinx.coroutines.runBlocking
import org.smartregister.fhircore.engine.util.extension.logicalId
import org.smartregister.fhircore.quest.BuildConfig
import org.smartregister.fhircore.quest.R
import org.smartregister.fhircore.quest.camerax.CameraxLauncherFragment
import org.smartregister.fhircore.quest.ui.questionnaire.QuestionnaireActivity
import org.smartregister.fhircore.quest.ui.register.patients.DocumentReferenceCaseType
import org.smartregister.fhircore.quest.util.CONFIDENCE_PERCENTAGE_URL
import org.smartregister.fhircore.quest.util.SUSPICIOUS_NON_SUSPICIOUS_URL
import timber.log.Timber
import java.io.File
import java.math.BigDecimal
import java.util.Date
import java.util.UUID


internal object CustomAttachmentViewHolderFactory :
    QuestionnaireItemViewHolderFactory(R.layout.custom_attachment_view_item) {

    override fun getQuestionnaireItemViewHolderDelegate() =
        object : QuestionnaireItemViewHolderDelegate {

            override lateinit var questionnaireViewItem: QuestionnaireViewItem
            private lateinit var header: HeaderView
            private lateinit var errorTextView: TextView
            private lateinit var takePhotoButton: Button
            private lateinit var uploadPhotoButton: Button
            private lateinit var uploadAudioButton: Button
            private lateinit var uploadVideoButton: Button
            private lateinit var uploadDocumentButton: Button
            private lateinit var uploadFileButton: Button
            private lateinit var divider: MaterialDivider
            private lateinit var photoPreview: ConstraintLayout
            private lateinit var photoThumbnail: ImageView
            private lateinit var photoTitle: TextView
            private lateinit var photoResult: TextView
            private lateinit var photoConfidence: TextView
            private lateinit var photoDeleteButton: Button
            private lateinit var photoDeleteButton2: ImageView
            private lateinit var photoView: ImageView
            private lateinit var filePreview: ConstraintLayout
            private lateinit var fileIcon: ImageView
            private lateinit var fileTitle: TextView
            private lateinit var fileDeleteButton: Button
            private lateinit var context: AppCompatActivity
            private lateinit var fhirEngine: FhirEngine
            private var sharedPreferencesHelper: SharedPreferencesHelper? = null
            private var aiInferenceEnabled: Boolean = false

            override fun init(itemView: View) {

                header = itemView.findViewById(R.id.header)
                errorTextView = itemView.findViewById(R.id.error)
                takePhotoButton = itemView.findViewById(R.id.take_photo)
                uploadPhotoButton = itemView.findViewById(R.id.upload_photo)
                uploadAudioButton = itemView.findViewById(R.id.upload_audio)
                uploadVideoButton = itemView.findViewById(R.id.upload_video)
                uploadDocumentButton = itemView.findViewById(R.id.upload_document)
                uploadFileButton = itemView.findViewById(R.id.upload_file)
                divider = itemView.findViewById(R.id.divider)
                photoPreview = itemView.findViewById(R.id.photo_preview)
                photoThumbnail = itemView.findViewById(R.id.photo_thumbnail)
                photoResult = itemView.findViewById(R.id.photo_result)
                photoConfidence = itemView.findViewById(R.id.photo_confidence)
                photoTitle = itemView.findViewById(R.id.photo_title)
                photoDeleteButton = itemView.findViewById(R.id.photo_delete)
                photoDeleteButton2 = itemView.findViewById(R.id.photo_delete2)
                photoView = itemView.findViewById(R.id.photo_view)
                filePreview = itemView.findViewById(R.id.file_preview)
                fileIcon = itemView.findViewById(R.id.file_icon)
                fileTitle = itemView.findViewById(R.id.file_title)
                fileDeleteButton = itemView.findViewById(R.id.file_delete)
                context = itemView.context.tryUnwrapContext()!!
                fhirEngine = FhirEngineProvider.getInstance(context.applicationContext)
                sharedPreferencesHelper = SharedPreferencesHelper(itemView.context, Gson())
                val featureFlagUtil = EntryPointAccessors
                    .fromApplication(context.applicationContext, FeatureFlagUtilEntryPoint::class.java)
                    .featureFlagUtil()
                context.lifecycleScope.launch {
                    aiInferenceEnabled = featureFlagUtil.isAiInferenceEnabled()
                }
            }

            private fun isAiInferenceEnabled(): Boolean {
                return aiInferenceEnabled
            }

            override fun bind(questionnaireViewItem: QuestionnaireViewItem) {
                this.questionnaireViewItem = questionnaireViewItem
                header.bind(questionnaireViewItem)
                header.showRequiredOrOptionalTextInHeaderView(questionnaireViewItem)
                val questionnaireItem = questionnaireViewItem.questionnaireItem

                displayOrClearInitialPreview(questionnaireItem)
                displayTakePhotoButton(questionnaireItem)
                displayUploadButton(questionnaireItem)
                takePhotoButton.setOnClickListener { view ->
                    onTakePhotoClicked(
                        view,
                        questionnaireItem
                    )
                }
                uploadPhotoButton.setOnClickListener { view ->
                    onUploadClicked(
                        view,
                        questionnaireItem
                    )
                }
                uploadAudioButton.setOnClickListener { view ->
                    onUploadClicked(
                        view,
                        questionnaireItem
                    )
                }
                uploadVideoButton.setOnClickListener { view ->
                    onUploadClicked(
                        view,
                        questionnaireItem
                    )
                }
                uploadDocumentButton.setOnClickListener { view ->
                    onUploadClicked(
                        view,
                        questionnaireItem
                    )
                }
                uploadFileButton.setOnClickListener { view ->
                    onUploadClicked(
                        view,
                        questionnaireItem
                    )
                }
                photoDeleteButton.setOnClickListener { view -> onDeleteClicked(view) }
                photoDeleteButton2.setOnClickListener { view -> onDeleteClicked(view) }
                photoView.setOnClickListener { view -> onViewPhotoClicked(view) }
                photoThumbnail.setOnClickListener { view -> onViewPhotoClicked(view) }
                fileDeleteButton.setOnClickListener { view -> onDeleteClicked(view) }
                displayValidationResult(questionnaireViewItem.validationResult)

                displayAttachmentPreview(questionnaireViewItem, questionnaireItem)
            }

            private fun displayValidationResult(validationResult: ValidationResult) {
                when (validationResult) {
                    is NotValidated,
                    Valid,
                        -> errorTextView.visibility = View.GONE

                    is Invalid -> {
                        errorTextView.text = validationResult.getSingleStringValidationMessage()
                        errorTextView.visibility = View.VISIBLE
                    }
                }
            }

            override fun setReadOnly(isReadOnly: Boolean) {
                takePhotoButton.isEnabled = !isReadOnly
                uploadPhotoButton.isEnabled = !isReadOnly
                uploadAudioButton.isEnabled = !isReadOnly
                uploadVideoButton.isEnabled = !isReadOnly
                uploadDocumentButton.isEnabled = !isReadOnly
                uploadFileButton.isEnabled = !isReadOnly
                photoDeleteButton.isEnabled = !isReadOnly
                fileDeleteButton.isEnabled = !isReadOnly
            }

            private fun displayOrClearInitialPreview(questionnaireItem: Questionnaire.QuestionnaireItemComponent) {
                val answer = questionnaireViewItem.answers.firstOrNull()

                // Clear preview if there is no answer to prevent showing old previews in views that have
                // been recycled.
                if (answer == null) {
                    clearPhotoPreview()
                    clearFilePreview()
                    return
                }

                answer.valueAttachment?.let { attachment ->
                    displayPreview(
                        attachmentType = getMimeType(attachment.contentType),
                        attachmentTitle = attachment.title,
                        attachmentByteArray = attachment.data,
                        questionnaireItem = questionnaireItem
                    )
                }
            }

            private fun displayTakePhotoButton(questionnaireItem: Questionnaire.QuestionnaireItemComponent) {
                if (questionnaireItem.hasMimeType(MimeType.IMAGE.value)) {
                    takePhotoButton.visibility = View.VISIBLE
                }
            }

            private fun displayUploadButton(questionnaireItem: Questionnaire.QuestionnaireItemComponent) {
                when {
                    questionnaireItem.hasMimeTypeOnly(MimeType.AUDIO.value) -> {
                        uploadAudioButton.visibility = View.VISIBLE
                    }

                    questionnaireItem.hasMimeTypeOnly(MimeType.DOCUMENT.value) -> {
                        uploadDocumentButton.visibility = View.VISIBLE
                    }

                    questionnaireItem.hasMimeTypeOnly(MimeType.IMAGE.value) -> {
                        // NOOP
                    }

                    questionnaireItem.hasMimeTypeOnly(MimeType.VIDEO.value) -> {
                        // NOOP
                    }

                    else -> {
                        uploadFileButton.visibility = View.VISIBLE
                    }
                }
            }

            private fun displayAttachmentPreview(
                questionnaireViewItem: QuestionnaireViewItem,
                questionnaireItem: Questionnaire.QuestionnaireItemComponent
            ) {
                // Check if the answer contains an attachment
                val answer = questionnaireViewItem.answers.firstOrNull()
                answer?.valueAttachment?.let { attachment ->
                    // Determine the attachment type and display preview accordingly
                    when (getMimeType(attachment.contentType)) {
                        MimeType.IMAGE.value -> {
                            // If it's an image attachment, display the preview
                            displayImagePreview(attachment, questionnaireItem)
                        }
                        // Handle other attachment types if needed
                        else -> {
                            // Clear any existing preview for non-image attachments
                            clearAttachmentPreview()
                        }
                    }
                } ?: run {
                    // If there's no attachment, clear any existing preview
                    clearAttachmentPreview()
                }
            }

            private fun displayImagePreview(
                attachment: Attachment,
                questionnaireItem: Questionnaire.QuestionnaireItemComponent
            ) {
                // Display image preview logic
                val attachmentTitle = attachment.title ?: ""
                val attachmentUri = getFileUri(attachment.title ?: "")
                loadPhotoPreview(attachmentUri, attachmentTitle, questionnaireItem)
            }

            fun getFileUri(imageFileName: String): Uri {
                //Existing images in the drafts will be in cache
                //New images will be in files folder
                //Try cache first for better backward compatibility
                if (imageFileName.isNotEmpty()) {
                    val urisToTry = listOf(
                        Uri.parse(IMAGE_CACHE_BASE_URI + imageFileName),
                        Uri.parse(IMAGE_FILES_BASE_URI + imageFileName)
                    )
                    for (uri in urisToTry) {
                        try {
                            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                                if (inputStream.available() > 0) {
                                    Timber.d("Found image at: $uri")
                                    return uri
                                }
                            }
                        } catch (e: Exception) {
                            Timber.d("Failed to open URI: $uri, trying next location...")
                            continue
                        }
                    }
                    Timber.w("Image not found in any location: $imageFileName")
                }
                return Uri.EMPTY
            }

            private fun clearAttachmentPreview() {
                // Clear attachment preview logic
                photoPreview.visibility = View.GONE
                Glide.with(context).clear(photoThumbnail)
                photoTitle.text = ""
            }

            private fun onTakePhotoClicked(
                view: View,
                questionnaireItem: Questionnaire.QuestionnaireItemComponent

            ) {
                context.supportFragmentManager.setFragmentResultListener(
                    CameraxLauncherFragment.CAMERA_RESULT_KEY,
                    context,

                    ) { _, result ->
                    val isSaved = result.getBoolean(CameraxLauncherFragment.CAMERA_RESULT_KEY)
                    if (!isSaved) return@setFragmentResultListener
                    val fileAbsolutePath =
                        result.getString(CameraxLauncherFragment.CAMERA_RESULT_URI_KEY)
                    val predictionResult =
                        result.getString(CameraxLauncherFragment.CAMERA_PREDICTION_KEY)
                    val confidence = result.getString(CameraxLauncherFragment.CAMERA_CONFIDENCE_KEY)
                    val model6Prediction =
                        result.getString(CameraxLauncherFragment.CAMERA_MODEL6_PREDICTION_KEY)
                    val model6Confidence =
                        result.getString(CameraxLauncherFragment.CAMERA_MODEL6_CONFIDENCE_KEY)
                    val model8Prediction =
                        result.getString(CameraxLauncherFragment.CAMERA_MODEL8_PREDICTION_KEY)
                    val model8Confidence =
                        result.getString(CameraxLauncherFragment.CAMERA_MODEL8_CONFIDENCE_KEY)
                    val model82Prediction =
                        result.getString(CameraxLauncherFragment.CAMERA_MODEL82_PREDICTION_KEY)
                    val model82Confidence =
                        result.getString(CameraxLauncherFragment.CAMERA_MODEL82_CONFIDENCE_KEY)
                    val hasAiInferenceResult = isAiInferenceEnabled() && !predictionResult.isNullOrBlank()

                    if (!fileAbsolutePath.isNullOrEmpty()) {
                        try {
                            val capturedFile = File(fileAbsolutePath)
                            val attachmentUri =
                                FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    capturedFile
                                )
                            val attachmentMimeTypeWithSubType =
                                context.getMimeTypeFromUri(attachmentUri)
                            val attachmentMimeType = getMimeType(attachmentMimeTypeWithSubType)
                            if (!questionnaireItem.hasMimeType(attachmentMimeType)) {
                                displayError(R.string.mime_type_wrong_media_format_validation_error_msg)
                                displaySnackbar(view, R.string.upload_failed)
                                capturedFile.delete()
                                return@setFragmentResultListener
                            }

                            // Remove existing extensions before adding new ones
                            questionnaireItem.removeExtension(SUSPICIOUS_NON_SUSPICIOUS_URL)
                            questionnaireItem.removeExtension(CONFIDENCE_PERCENTAGE_URL)
                            questionnaireItem.removeExtension(MODEL6_PREDICTION_URL)
                            questionnaireItem.removeExtension(MODEL6_CONFIDENCE_URL)
                            questionnaireItem.removeExtension(MODEL8_PREDICTION_URL)
                            questionnaireItem.removeExtension(MODEL8_CONFIDENCE_URL)
                            questionnaireItem.removeExtension(MODEL82_PREDICTION_URL)
                            questionnaireItem.removeExtension(MODEL82_CONFIDENCE_URL)

                            // Create a document reference to store the file later and use the document ref
                            // permanent link in attachment url
                            val doc = createDocumentReference(
                                attachmentUri,
                                attachmentMimeTypeWithSubType
                            ).apply {
                                // Add AI Model results to DocumentReference
                                if (hasAiInferenceResult) {
                                    if (!model6Prediction.isNullOrEmpty()) {
                                        addExtension(
                                            MODEL6_PREDICTION_URL,
                                            StringType(model6Prediction)
                                        )
                                        addExtension(
                                            MODEL6_CONFIDENCE_URL,
                                            StringType(model6Confidence)
                                        )
                                    }

                                    if (!model8Prediction.isNullOrEmpty()) {
                                        addExtension(
                                            MODEL8_PREDICTION_URL,
                                            StringType(model8Prediction)
                                        )
                                        addExtension(
                                            MODEL8_CONFIDENCE_URL,
                                            StringType(model8Confidence)
                                        )
                                    }

                                    if (!model82Prediction.isNullOrEmpty()) {
                                        addExtension(
                                            MODEL82_PREDICTION_URL,
                                            StringType(model82Prediction)
                                        )
                                        addExtension(
                                            MODEL82_CONFIDENCE_URL,
                                            StringType(model82Confidence)
                                        )
                                    }
                                }
                            }

                            val answer =
                                QuestionnaireResponse.QuestionnaireResponseItemAnswerComponent()
                                    .apply {
                                        if (hasAiInferenceResult) {
                                            addExtension(
                                                SUSPICIOUS_NON_SUSPICIOUS_URL,
                                                StringType(predictionResult.orEmpty())
                                            )
                                            addExtension(
                                                CONFIDENCE_PERCENTAGE_URL,
                                                StringType(confidence.orEmpty())
                                            )

                                            if (!model6Prediction.isNullOrEmpty()) {
                                                addExtension(
                                                    MODEL6_PREDICTION_URL,
                                                    StringType(model6Prediction)
                                                )
                                                addExtension(
                                                    MODEL6_CONFIDENCE_URL,
                                                    StringType(model6Confidence)
                                                )
                                            }

                                            if (!model8Prediction.isNullOrEmpty()) {
                                                addExtension(
                                                    MODEL8_PREDICTION_URL,
                                                    StringType(model8Prediction)
                                                )
                                                addExtension(
                                                    MODEL8_CONFIDENCE_URL,
                                                    StringType(model8Confidence)
                                                )
                                            }

                                            if (!model82Prediction.isNullOrEmpty()) {
                                                addExtension(
                                                    MODEL82_PREDICTION_URL,
                                                    StringType(model82Prediction)
                                                )
                                                addExtension(
                                                    MODEL82_CONFIDENCE_URL,
                                                    StringType(model82Confidence)
                                                )
                                            }
                                        }

                                        // IMPORTANT: the DocumentReference id (carried in the URL)
                                        // is deliberately NOT baked in here. It is attached below,
                                        // only AFTER create(doc) durably persists the resource (see
                                        // the coroutine). Writing the URL before the resource exists
                                        // is the root cause of "QR references a DocumentReference
                                        // that 404s": the id can outlive a resource that was never
                                        // persisted or synced.
                                        value =
                                            Attachment().apply {
                                                contentType = attachmentMimeTypeWithSubType
                                                title = capturedFile.name
                                                creation = Date()
                                            }
                                    }

                            if (hasAiInferenceResult) {
                                //Suspicious/NonSuspicious
                                questionnaireItem.addExtension(
                                    SUSPICIOUS_NON_SUSPICIOUS_URL,
                                    StringType(predictionResult.orEmpty())
                                )

                                //Confidence percentage
                                questionnaireItem.addExtension(
                                    CONFIDENCE_PERCENTAGE_URL,
                                    StringType(confidence.orEmpty())
                                )

                                // Add individual model results to questionnaire item
                                if (!model6Prediction.isNullOrEmpty()) {
                                    questionnaireItem.addExtension(
                                        MODEL6_PREDICTION_URL,
                                        StringType(model6Prediction)
                                    )
                                    questionnaireItem.addExtension(
                                        MODEL6_CONFIDENCE_URL,
                                        StringType(model6Confidence)
                                    )
                                }

                                if (!model8Prediction.isNullOrEmpty()) {
                                    questionnaireItem.addExtension(
                                        MODEL8_PREDICTION_URL,
                                        StringType(model8Prediction)
                                    )
                                    questionnaireItem.addExtension(
                                        MODEL8_CONFIDENCE_URL,
                                        StringType(model8Confidence)
                                    )
                                }

                                if (!model82Prediction.isNullOrEmpty()) {
                                    questionnaireItem.addExtension(
                                        MODEL82_PREDICTION_URL,
                                        StringType(model82Prediction)
                                    )
                                    questionnaireItem.addExtension(
                                        MODEL82_CONFIDENCE_URL,
                                        StringType(model82Confidence)
                                    )
                                }
                            }

                            // `questionnaireViewItem` is an `override lateinit var` that bind()
                            // reassigns as RecyclerView recycles this delegate across all image
                            // slots. Pin it to a local now: the coroutine below suspends twice, and
                            // reading the field again afterwards can yield a *different* slot — we
                            // would then purge one slot's DocumentReference and delete its JPEG
                            // while writing the answer to another, destroying an image and leaving
                            // the first slot pointing at a resource that no longer exists.
                            val boundViewItem = questionnaireViewItem

                            // The slot may already hold a previous capture (retake / re-shoot).
                            // Capture its URL now so the orphaned DRAFT DocumentReference + its JPEG
                            // can be purged once the new answer is in place.
                            val previousAttachmentUrl =
                                boundViewItem.answers.firstOrNull()?.valueAttachment?.url

                            context.lifecycleScope.launch {
                                // Persist the DocumentReference FIRST. Only once it is durably in the
                                // engine do we bake its id into the QR answer. If create fails we
                                // surface an error and leave the QR untouched, so the response can
                                // never point at a resource that does not exist.
                                try {
                                    fhirEngine.create(doc)
                                } catch (e: Exception) {
                                    Timber.e(e, "Failed to persist DocumentReference ${doc.logicalId}; not attaching to the response")
                                    displaySnackbar(view, R.string.upload_failed)
                                    return@launch
                                }
                                val persistedAttachment = answer.value as? Attachment
                                persistedAttachment?.url = doc.getUrl(sharedPreferencesHelper)

                                // Replace flow: the new capture supersedes the previous one; purge
                                // the now-orphaned DRAFT DocumentReference + file it left behind.
                                // Same slot as `previousAttachmentUrl` came from — see boundViewItem.
                                purgeDraftDocumentReference(previousAttachmentUrl)

                                boundViewItem.setAnswer(answer)
                                divider.visibility = View.VISIBLE
                                displayPreview(
                                    attachmentType = attachmentMimeType,
                                    attachmentTitle = if (hasAiInferenceResult) "RESULT : $predictionResult" else capturedFile.name,
                                    attachmentUri = attachmentUri,
                                    questionnaireItem = questionnaireItem
                                )

                                //setAnswerFromAI(predictionResult,confidence)
                                displaySnackbarOnUpload(view, attachmentMimeType)
                            }

                        } catch (e: Exception) {
                            Timber.e(e, "error --> %s", e.printStackTrace())
                            e.printStackTrace()
                        }

                    } else {
                        displaySnackbar(view, R.string.image_capture_failed)
                    }
                }

                CameraxLauncherFragment.newInstance((context as? QuestionnaireActivity)?.activeScreeningId())
                    .show(
                        context.supportFragmentManager,
                        CustomAttachmentViewHolderFactory::class.java.simpleName
                    )
            }


            private fun onUploadClicked(
                view: View,
                questionnaireItem: Questionnaire.QuestionnaireItemComponent
            ) {
                context.supportFragmentManager.setFragmentResultListener(
                    OpenDocumentLauncherFragment.OPEN_DOCUMENT_RESULT_KEY,
                    context,
                ) { _, result ->
                    val attachmentUri =
                        (result.get(OpenDocumentLauncherFragment.OPEN_DOCUMENT_RESULT_KEY)
                            ?: return@setFragmentResultListener)
                                as Uri
                    val attachmentByteArray = context.readBytesFromUri(attachmentUri)
                    if (questionnaireItem.isGivenSizeOverLimit(attachmentByteArray.size.toBigDecimal())) {
                        displayError(
                            R.string.max_size_file_above_limit_validation_error_msg,
                            questionnaireItem.maxSizeInMiBs,
                        )

                        displaySnackbar(view, R.string.upload_failed)
                        return@setFragmentResultListener
                    }

                    val attachmentMimeTypeWithSubType = context.getMimeTypeFromUri(attachmentUri)
                    val attachmentMimeType = getMimeType(attachmentMimeTypeWithSubType)
                    if (!questionnaireItem.hasMimeType(attachmentMimeType)) {
                        displayError(R.string.mime_type_wrong_media_format_validation_error_msg)
                        displaySnackbar(view, R.string.upload_failed)
                        return@setFragmentResultListener
                    }

                    val attachmentTitle = getFileName(attachmentUri)
                    // Create a document reference to store the file later and use the document ref
                    // permanent link in attachment url
                    val doc = createDocumentReference(attachmentUri, attachmentMimeTypeWithSubType)
                    val answer =
                        QuestionnaireResponse.QuestionnaireResponseItemAnswerComponent().apply {
                            // URL (DocumentReference id) intentionally omitted here; it is attached
                            // below only after create(doc) succeeds. See the take-photo flow.
                            value =
                                Attachment().apply {
                                    contentType = attachmentMimeTypeWithSubType
                                    title = attachmentTitle
                                    creation = Date()
                                    language = ""
                                }
                        }

                    // Pinned for the same reason as the take-photo path: the field is reassigned on
                    // every bind(), and purging one slot while answering another deletes an image.
                    val boundViewItem = questionnaireViewItem
                    val previousAttachmentUrl =
                        boundViewItem.answers.firstOrNull()?.valueAttachment?.url

                    context.lifecycleScope.launch {
                        try {
                            fhirEngine.create(doc)
                        } catch (e: Exception) {
                            Timber.e(e, "Failed to persist DocumentReference ${doc.logicalId}; not attaching to the response")
                            displaySnackbar(view, R.string.upload_failed)
                            return@launch
                        }
                        val persistedAttachment = answer.value as? Attachment
                        persistedAttachment?.url = doc.getUrl(sharedPreferencesHelper)
                        purgeDraftDocumentReference(previousAttachmentUrl)
                        boundViewItem.setAnswer(answer)
                        divider.visibility = View.VISIBLE
                        displayPreview(
                            attachmentType = attachmentMimeType,
                            attachmentTitle = attachmentTitle,
                            attachmentUri = attachmentUri,
                            questionnaireItem = questionnaireItem
                        )
                        displaySnackbarOnUpload(view, attachmentMimeType)
                    }
                }
                OpenDocumentLauncherFragment()
                    .apply {
                        arguments =
                            bundleOf(EXTRA_MIME_TYPE_KEY to questionnaireItem.mimeTypes.toTypedArray())
                    }
                    .show(
                        context.supportFragmentManager,
                        CustomAttachmentViewHolderFactory::class.java.simpleName
                    )
            }


            private fun displayPreview(
                attachmentType: String,
                attachmentTitle: String,
                attachmentByteArray: ByteArray? = null,
                attachmentUri: Uri? = null,
                questionnaireItem: Questionnaire.QuestionnaireItemComponent? = null,
            ) {
                when (attachmentType) {
                    MimeType.AUDIO.value -> {
                        loadFilePreview(
                            com.google.android.fhir.datacapture.R.drawable.ic_audio_file,
                            attachmentTitle
                        )
                        clearPhotoPreview()
                    }

                    MimeType.DOCUMENT.value -> {
                        loadFilePreview(
                            com.google.android.fhir.datacapture.R.drawable.ic_document_file,
                            attachmentTitle
                        )
                        clearPhotoPreview()
                    }

                    MimeType.IMAGE.value -> {
                        if (attachmentByteArray != null) {
                            loadPhotoPreview(attachmentByteArray, attachmentTitle)
                        } else if (attachmentUri != null) {
                            loadPhotoPreview(attachmentUri, attachmentTitle, questionnaireItem)
                        }
                        clearFilePreview()
                    }

                    MimeType.VIDEO.value -> {
                        loadFilePreview(
                            com.google.android.fhir.datacapture.R.drawable.ic_video_file,
                            attachmentTitle
                        )
                        clearPhotoPreview()
                    }
                }
            }


            private fun loadFilePreview(@DrawableRes iconResource: Int, title: String) {

                filePreview.visibility = View.VISIBLE

                Glide.with(context).load(iconResource).into(fileIcon)

                fileTitle.text = title

            }


            private fun clearFilePreview() {

                filePreview.visibility = View.GONE

                Glide.with(context).clear(fileIcon)

                fileTitle.text = ""

            }


            private fun loadPhotoPreview(byteArray: ByteArray, title: String) {

                photoPreview.visibility = View.VISIBLE

                Glide.with(context).load(byteArray).into(photoThumbnail)

                photoTitle.text = title

            }


            private fun loadPhotoPreview(
                uri: Uri,
                photoTitle: String,
                questionnaireItem: Questionnaire.QuestionnaireItemComponent?
            ) {
                try {
                    photoPreview.visibility = View.VISIBLE
                    Glide.with(context).load(uri).into(photoThumbnail)
                    this.photoTitle.text = photoTitle

                    val result = questionnaireItem?.getExtensionString(SUSPICIOUS_NON_SUSPICIOUS_URL)
                    val confidence = questionnaireItem?.getExtensionString(CONFIDENCE_PERCENTAGE_URL)
                    val m6Pred = questionnaireItem?.getExtensionString(MODEL6_PREDICTION_URL)
                    val m6Conf = questionnaireItem?.getExtensionString(MODEL6_CONFIDENCE_URL)
                    val m8Pred = questionnaireItem?.getExtensionString(MODEL8_PREDICTION_URL)
                    val m8Conf = questionnaireItem?.getExtensionString(MODEL8_CONFIDENCE_URL)
                    val m82Pred = questionnaireItem?.getExtensionString(MODEL82_PREDICTION_URL)
                    val m82Conf = questionnaireItem?.getExtensionString(MODEL82_CONFIDENCE_URL)
                    //setAnswerFromAI(result, confidence, m6Pred, m6Conf, m8Pred, m8Conf, m82Pred, m82Conf)
                } catch (exc: Exception) {
                    Timber.e("Failed to load photo preview: ${exc.message}")
                }
            }

            private fun setAnswerFromAI(
                predictionResult: String?, confidence: String?,
                m6Pred: String?, m6Conf: String?,
                m8Pred: String?, m8Conf: String?,
                m82Pred: String?, m82Conf: String?
            ) {
                // Build a comprehensive result string
                val stringBuilder = StringBuilder()
                if (!predictionResult.isNullOrEmpty()) {
                    stringBuilder.append("Final: $predictionResult")

                    if (!confidence.isNullOrEmpty()) {
                        stringBuilder.append(" ($confidence%)")
                    }

                    stringBuilder.append("\n")
                }

//                if (!m6Pred.isNullOrEmpty()) {
//                    stringBuilder.append("M6: $m6Pred")
//                    if (!m6Conf.isNullOrEmpty()) stringBuilder.append(" ($m6Conf%)")
//                    stringBuilder.append("\n")
//                }
//
//                if (!m8Pred.isNullOrEmpty()) {
//                    stringBuilder.append("M8: $m8Pred")
//                    if (!m8Conf.isNullOrEmpty()) stringBuilder.append(" ($m8Conf%)")
//                    stringBuilder.append("\n")
//                }
//
//                if (!m82Pred.isNullOrEmpty()) {
//                    stringBuilder.append("M82: $m82Pred")
//                    if (!m82Conf.isNullOrEmpty()) stringBuilder.append(" ($m82Conf%)")
//                }
                val finalString = stringBuilder.toString().trim()
                if (finalString.isNotEmpty()) {
                    photoResult.text = finalString
                    photoResult.visibility = View.VISIBLE
                    // Hide separate confidence view as it is merged into the main text
                    photoConfidence.visibility = View.GONE
                } else {
                    photoResult.visibility = View.GONE
                    photoConfidence.visibility = View.GONE
                }
            }


            private fun clearPhotoPreview() {
                photoPreview.visibility = View.GONE
                Glide.with(context).clear(photoThumbnail)
                photoTitle.text = ""
            }

            fun showFullScreenImageDialog(context: Context, imageUri: Uri) {
                val dialog = Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
                dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
                dialog.window?.setLayout(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT
                )

                val rootLayout = FrameLayout(context)

                val imageView = ZoomableImageView(context).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                }

                val closeButton = ImageButton(context).apply {
                    setImageDrawable(
                        ContextCompat.getDrawable(
                            context,
                            android.R.drawable.ic_menu_close_clear_cancel
                        )
                    )
                    background = null
                    setPadding(20, 20, 20, 20)
                }

                val closeButtonParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.END
                    topMargin = 20
                    rightMargin = 20
                }

                rootLayout.addView(imageView)
                rootLayout.addView(closeButton, closeButtonParams)

                dialog.setContentView(rootLayout)

                // Full-screen viewer is zoomable, so load the original full-resolution image
                // (no downsample, no crop) to keep it sharp when zoomed. Falls back to a
                // downsampled load if the full decode runs out of memory, so it never crashes.
                Glide.with(context)
                    .load(imageUri)
                    .dontTransform()
                    .override(Target.SIZE_ORIGINAL)
                    .error(Glide.with(context).load(imageUri))
                    .into(imageView)

                closeButton.setOnClickListener {
                    dialog.dismiss()
                }

                dialog.show()
            }


            /**
             * Purges a not-yet-submitted (DRAFT) DocumentReference and deletes its backing JPEG.
             *
             * Called when a captured photo is deleted or superseded by a retake. Only DRAFT
             * documents (never submitted/synced) are removed, so editing a case that already has a
             * submitted image can never delete server-bound data. A missing id, an already-purged
             * row and any non-DRAFT document are all no-ops.
             */
            private suspend fun purgeDraftDocumentReference(attachmentUrl: String?) {
                purgeDraftDocumentReferenceIfSafe(
                    fhirEngine = fhirEngine,
                    attachmentUrl = attachmentUrl,
                    deleteFile = { fileLocation ->
                        context.contentResolver.delete(Uri.parse(fileLocation), null, null)
                    },
                )
            }

            private fun onDeleteClicked(view: View) {
                // Pinned before the coroutine: purgeDraftDocumentReference suspends, and clearing
                // the answer on a slot other than the one whose file we just deleted would leave a
                // live answer pointing at a deleted image.
                val boundViewItem = questionnaireViewItem
                context.lifecycleScope.launch {
                    val deletedAttachment =
                        boundViewItem.answers.firstOrNull()?.valueAttachment ?: return@launch
                    val attachmentType = getMimeType(deletedAttachment.contentType)
                    // Clear the answer FIRST, then purge. If the purge throws, the worst case is an
                    // orphaned DRAFT row and its JPEG (a storage leak); the reverse order risks a
                    // deleted image still referenced by a live answer.
                    boundViewItem.clearAnswer()
                    purgeDraftDocumentReference(deletedAttachment.url)

                    val questionnaireItem = boundViewItem.questionnaireItem
                    questionnaireItem.removeExtension(SUSPICIOUS_NON_SUSPICIOUS_URL)
                    questionnaireItem.removeExtension(CONFIDENCE_PERCENTAGE_URL)
                    questionnaireItem.removeExtension(MODEL6_PREDICTION_URL)
                    questionnaireItem.removeExtension(MODEL6_CONFIDENCE_URL)
                    questionnaireItem.removeExtension(MODEL8_PREDICTION_URL)
                    questionnaireItem.removeExtension(MODEL8_CONFIDENCE_URL)
                    questionnaireItem.removeExtension(MODEL82_PREDICTION_URL)
                    questionnaireItem.removeExtension(MODEL82_CONFIDENCE_URL)

                    divider.visibility = View.GONE

                    clearPhotoPreview()

                    clearFilePreview()

                    displaySnackbarOnDelete(
                        view,
                        attachmentType,
                    )
                }
            }

            private fun onViewPhotoClicked(view: View) {
                context.lifecycleScope.launch {
                    val attachmentUri = getFileUri(
                        questionnaireViewItem.answers.first().valueAttachment.title ?: ""
                    )
                    showFullScreenImageDialog(context, attachmentUri)
                }
            }

            private fun displaySnackbar(view: View, @StringRes textResource: Int) {

                Snackbar.make(view, context.getString(textResource), Snackbar.LENGTH_SHORT).show()

            }


            private fun displaySnackbarOnUpload(view: View, attachmentType: String) {

                when (attachmentType) {

                    MimeType.AUDIO.value -> {

                        displaySnackbar(view, R.string.audio_uploaded)

                    }


                    MimeType.DOCUMENT.value -> {

                        displaySnackbar(

                            view,

                            com.google.android.fhir.datacapture.R.string.file_uploaded

                        )

                    }


                    MimeType.IMAGE.value -> {

                        //            displaySnackbar(view, com.google.android.fhir.datacapture.R.string.image_uploaded)

                        displaySnackbar(view, R.string.image_saved)

                    }


                    MimeType.VIDEO.value -> {

                        displaySnackbar(view, R.string.video_uploaded)

                    }

                }

            }


            private fun displaySnackbarOnDelete(view: View, attachmentType: String) {

                when (attachmentType) {

                    MimeType.AUDIO.value -> {

                        displaySnackbar(view, R.string.audio_deleted)

                    }


                    MimeType.DOCUMENT.value -> {

                        displaySnackbar(

                            view,

                            com.google.android.fhir.datacapture.R.string.file_deleted

                        )

                    }


                    MimeType.IMAGE.value -> {

                        displaySnackbar(

                            view,

                            com.google.android.fhir.datacapture.R.string.image_deleted

                        )

                    }


                    MimeType.VIDEO.value -> {

                        displaySnackbar(view, R.string.video_deleted)

                    }

                }

            }


            private fun displayError(@StringRes textResource: Int) {

                displayValidationResult(

                    Invalid(

                        listOf(

                            context.getString(

                                textResource,

                                ),

                            ),

                        ),

                    )

            }


            private fun displayError(@StringRes textResource: Int, vararg formatArgs: Any?) {

                displayValidationResult(

                    Invalid(

                        listOf(

                            context.getString(

                                textResource,

                                *formatArgs

                            )

                        )

                    )

                )

            }


            private fun getFileName(uri: Uri): String {

                var fileName = ""

                val columns = arrayOf(OpenableColumns.DISPLAY_NAME)

                context.contentResolver.query(uri, columns, null, null, null)?.use { cursor ->

                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)

                    cursor.moveToFirst()

                    fileName = cursor.getString(nameIndex)

                }

                return fileName

            }

        }


    internal fun createDocumentReference(attachmentUri: Uri, mimeType: String): DocumentReference {

        val doc = DocumentReference().apply {

            id = UUID.randomUUID().toString()

            addExtension(EXTENSION_FILE_LOCATION, StringType(attachmentUri.toString()))

            addContent().apply {

                attachment = Attachment().apply { contentType = mimeType }

            }

            date = Date()

            docStatus = DocumentReference.ReferredDocumentStatus.PRELIMINARY

            status = Enumerations.DocumentReferenceStatus.CURRENT

            description = "DRAFT"

        }

        return doc

    }


    private val IMAGE_FILES_BASE_URI: String =

        "content://${BuildConfig.APPLICATION_ID}.fileprovider/files/"

    private val IMAGE_CACHE_BASE_URI: String =

        "content://${BuildConfig.APPLICATION_ID}.fileprovider/cache/"

    val EXTRA_MIME_TYPE_KEY = "mime_type"

    val EXTRA_SAVED_PHOTO_URI_KEY = "saved_photo_uri"


    fun matcher(questionnaireItem: Questionnaire.QuestionnaireItemComponent): Boolean {

        return questionnaireItem.type == Questionnaire.QuestionnaireItemType.ATTACHMENT

    }


}


private fun getMimeType(mimeType: String): String = mimeType.substringBefore("/")


private fun Context.readBytesFromUri(uri: Uri): ByteArray {

    return contentResolver.openInputStream(uri)?.use { it.buffered().readBytes() } ?: ByteArray(0)

}


private fun Context.getMimeTypeFromUri(uri: Uri): String {

    return contentResolver.getType(uri) ?: "*/*"

}


internal const val EXTENSION_MAX_SIZE = "http://hl7.org/fhir/StructureDefinition/maxSize"

internal const val EXTENSION_FILE_LOCATION = "http://hl7.org/fhir/StructureDefinition/file-location"


/** The default maximum size of an attachment is 1 Mebibytes. */

private val DEFAULT_SIZE = BigDecimal(1048576)


/** The maximum size of an attachment in Bytes. */

internal val Questionnaire.QuestionnaireItemComponent.maxSizeInBytes: BigDecimal?
    get() =

        (extension.firstOrNull { it.url == EXTENSION_MAX_SIZE }?.valueAsPrimitive as DecimalType?)

            ?.value


private val BYTES_PER_MIB = BigDecimal(1048576)


/** The maximum size of an attachment in Mebibytes. */

internal val Questionnaire.QuestionnaireItemComponent.maxSizeInMiBs: BigDecimal?
    get() = maxSizeInBytes?.div(BYTES_PER_MIB)


/** Returns true if given size is above maximum size allowed. */

private fun Questionnaire.QuestionnaireItemComponent.isGivenSizeOverLimit(

    size: BigDecimal,

    ): Boolean {

    return size > (maxSizeInBytes ?: DEFAULT_SIZE)

}


//private val DocumentReference.url

//  get() = "${BuildConfig.FHIR_BASE_URL}DocumentReference/${logicalId}/\$binary-access-read?path=DocumentReference.content.attachment"


fun DocumentReference.getUrl(sharedPreferencesHelper: SharedPreferencesHelper?): String {

    return "${sharedPreferencesHelper?.getFhirBaseUrl()}DocumentReference/${logicalId}/\$binary-access-read?path=DocumentReference.content.attachment"

}

/**
 * Extracts the DocumentReference logical id from a binary-access-read URL of the form
 * `.../DocumentReference/{id}/$binary-access-read?...`. Returns null when [url] is null or the id
 * cannot be located.
 */
internal fun extractDocumentReferenceId(url: String?): String? {
    if (url == null) return null
    return Regex("DocumentReference/([^/]+)/").find(url)?.groupValues?.get(1)
}

/** Why [purgeDraftDocumentReferenceIfSafe] did or did not remove anything. */
internal enum class PurgeDraftOutcome {
    /** No DocumentReference id could be read from the URL. */
    NO_ID,

    /** The row is already gone, or the lookup failed. */
    NOT_FOUND,

    /** The document is not a DRAFT — it may be referenced by a submitted response. Left alone. */
    NOT_DRAFT,

    /** The DRAFT row was purged and its JPEG deleted. */
    PURGED,
}

/**
 * Purges a not-yet-submitted (DRAFT) DocumentReference and deletes its backing JPEG.
 *
 * Called when a captured photo is deleted or superseded by a retake. **Only DRAFT documents are
 * removed**, so editing a case whose image was already submitted can never delete server-bound
 * data — that guard is the reason this is destructive-but-safe, and it is the single most important
 * thing to preserve here.
 *
 * A missing id, an already-purged row and any non-DRAFT document are all no-ops. A failure to
 * delete the file is not fatal: an orphan JPEG is a storage leak, whereas skipping the purge would
 * leave a stale row behind.
 *
 * Extracted from the view-holder delegate — which is an anonymous object inside a singleton and
 * cannot be constructed in a test — so this behaviour is verifiable.
 */
internal suspend fun purgeDraftDocumentReferenceIfSafe(
    fhirEngine: FhirEngine,
    attachmentUrl: String?,
    deleteFile: (String) -> Unit,
): PurgeDraftOutcome {
    val documentReferenceId = extractDocumentReferenceId(attachmentUrl) ?: return PurgeDraftOutcome.NO_ID
    return try {
        val doc =
            fhirEngine.get(ResourceType.DocumentReference, documentReferenceId) as? DocumentReference
                ?: return PurgeDraftOutcome.NOT_FOUND
        if (doc.description != DocumentReferenceCaseType.DRAFT.name) return PurgeDraftOutcome.NOT_DRAFT

        // Delete the JPEG the DocumentReference points at (file-location extension).
        (doc.getExtensionByUrl(EXTENSION_FILE_LOCATION)?.value as? StringType)?.value?.let { fileLocation ->
            runCatching { deleteFile(fileLocation) }
                .onFailure { Timber.w(it, "Could not delete file for $documentReferenceId") }
        }

        fhirEngine.purge(ResourceType.DocumentReference, documentReferenceId, true)
        Timber.i("Purged orphaned DRAFT DocumentReference $documentReferenceId and its file")
        PurgeDraftOutcome.PURGED
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (e: Exception) {
        // ResourceNotFoundException (already gone) or any purge failure — nothing to do.
        Timber.w(e, "No DRAFT DocumentReference to purge for id $documentReferenceId")
        PurgeDraftOutcome.NOT_FOUND
    }
}

internal const val MODEL6_PREDICTION_URL =
    "http://smartregister.org/ai-model-result/v38-model-v6-prediction"

internal const val MODEL6_CONFIDENCE_URL =
    "http://smartregister.org/ai-model-result/v38-model-v6-confidence"

internal const val MODEL8_PREDICTION_URL =
    "http://smartregister.org/ai-model-result/v38-model-v8-prediction"

internal const val MODEL8_CONFIDENCE_URL =
    "http://smartregister.org/ai-model-result/v38-model-v8-confidence"

internal const val MODEL82_PREDICTION_URL =
    "http://smartregister.org/ai-model-result/v38-model-v82-prediction"

internal const val MODEL82_CONFIDENCE_URL =
    "http://smartregister.org/ai-model-result/v38-model82-confidence"

internal const val CASE_PREDICTION_RESULT_URL =
    "http://smartregister.org/ai-model-result/case-prediction-result"
