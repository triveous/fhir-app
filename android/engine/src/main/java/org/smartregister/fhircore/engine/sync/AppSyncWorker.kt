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

package org.smartregister.fhircore.engine.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE
import android.net.Uri
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import androidx.hilt.work.HiltWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import ca.uhn.fhir.context.FhirContext
import com.google.android.fhir.FhirEngine
import com.google.android.fhir.datacapture.extensions.asStringValue
import com.google.android.fhir.get
import com.google.android.fhir.search.search
import com.google.android.fhir.sync.AcceptLocalConflictResolver
import com.google.android.fhir.sync.ConflictResolver
import com.google.android.fhir.sync.DownloadWorkManager
import com.google.android.fhir.sync.FhirSyncWorker
import com.google.android.fhir.sync.upload.UploadStrategy
import com.google.gson.Gson
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.hl7.fhir.r4.model.Basic
import org.hl7.fhir.r4.model.DateTimeType
import org.hl7.fhir.r4.model.DocumentReference
import org.hl7.fhir.r4.model.OperationOutcome
import org.hl7.fhir.r4.model.StringType
import org.smartregister.fhircore.engine.R
import org.smartregister.fhircore.engine.data.local.updateDocStatus.DocStatusRequest
import org.smartregister.fhircore.engine.data.local.updateDocStatus.ExtensionValue
import org.smartregister.fhircore.engine.data.remote.fhir.resource.FhirResourceService
import org.smartregister.fhircore.engine.domain.networkUtils.DocumentReferenceCaseType
import org.smartregister.fhircore.engine.domain.networkUtils.HttpConstants.HEADER_APPLICATION_JSON
import org.smartregister.fhircore.engine.domain.networkUtils.HttpConstants.UPLOAD_IMAGE_URL
import org.smartregister.fhircore.engine.domain.networkUtils.WorkerConstants.CONTENT_TYPE
import org.smartregister.fhircore.engine.domain.networkUtils.WorkerConstants.DOC_STATUS
import org.smartregister.fhircore.engine.domain.networkUtils.WorkerConstants.REPLACE
import org.smartregister.fhircore.engine.util.SecureSharedPreference
import org.smartregister.fhircore.engine.util.extension.logicalId
import org.smartregister.fhircore.engine.util.notificationHelper.CHANNEL_ID
import org.smartregister.fhircore.engine.util.notificationHelper.NOTIFICATION_ID
import org.smartregister.fhircore.engine.util.notificationHelper.createNotification
import timber.log.Timber
import java.util.Date

@HiltWorker
class AppSyncWorker
@AssistedInject
constructor(
    @Assisted val appContext: Context,
    @Assisted workerParams: WorkerParameters,
    val syncListenerManager: SyncListenerManager,
    private val openSrpFhirEngine: FhirEngine,
    private val appTimeStampContext: AppTimeStampContext,
    private val fhirResourceService: FhirResourceService,
    val secureSharedPreference: SecureSharedPreference,
    private val gson: Gson
) : FhirSyncWorker(appContext, workerParams) {

    companion object {
        val mutex = Mutex()
        val uploadImageMutex = Mutex()
        const val SYNC_METADATA_SYSTEM = "http://hl7.org/fhir/codes"
        const val SYNC_METADATA_CODE = "sync-metadata"
        const val LAST_SYNC_TIME_EXTENSION = "https://midas.iisc.ac.in/fhir/StructureDefinition/last-sync-date"
        const val DEVICE_ID_EXTENSION = "https://midas.iisc.ac.in/fhir/StructureDefinition/device-id"
        const val FLW_ID_EXTENSION = "https://midas.iisc.ac.in/fhir/StructureDefinition/flw-id"
        const val PENDING_IMAGES_EXTENSION = "https://midas.iisc.ac.in/fhir/StructureDefinition/pending-images"
        const val IMG_UPLOAD_ERROR_EXTENSION = "https://midas.iisc.ac.in/fhir/StructureDefinition/img-upload-error"
        const val IMG_UPLOAD_FAILED_PERMANENTLY_EXTENSION = "https://midas.iisc.ac.in/fhir/StructureDefinition/img-upload-failed-permanently"
    }

    override fun getConflictResolver(): ConflictResolver = AcceptLocalConflictResolver

    override fun getDownloadWorkManager(): DownloadWorkManager =
        OpenSrpDownloadManager(
            syncParams = syncListenerManager.loadSyncParams(),
            context = appTimeStampContext,
        )

    override fun getFhirEngine(): FhirEngine = openSrpFhirEngine

    override fun getUploadStrategy(): UploadStrategy = UploadStrategy.AllChangesSquashedBundlePut

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = createNotification(applicationContext)
        return ForegroundInfo(
            NOTIFICATION_ID,
            notification,
            FOREGROUND_SERVICE_TYPE_SHORT_SERVICE
        )
    }

    override suspend fun doWork(): Result {
        Timber.i("AppSyncWorker Running sync worker")
        if (mutex.isLocked) {
            Timber.e("AppSyncWorker is locked. Returning failure")
            return Result.failure()
        }
        val metaSyncResult = super.doWork()
        mutex.withLock {
            Timber.i("AppSyncWorker Running within lock sync worker")
            try {
                setForeground(getForegroundInfo())
                val allDocUploaded = performDocumentReferenceUpload(applicationContext, id.toString())

                val retries = inputData.getInt("max_retires", 0)
                if (metaSyncResult.javaClass === Result.success().javaClass) {
                    return when (allDocUploaded) {
                        true -> Result.success()
                        false -> if (retries > runAttemptCount) Result.retry() else Result.failure(
                            workDataOf(
                                "error" to Exception::class.java.name,
                                "reason" to "Failed to upload all files"
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Appsync worker")
            }
        }
        return metaSyncResult
    }

    private suspend fun performDocumentReferenceUpload(context: Context, workerId: String): Boolean {
        Timber.i("Starting version-aware document reference upload for worker: $workerId")

        val docReferences = openSrpFhirEngine.search<DocumentReference> {}.filter {
            it.resource.description != DocumentReferenceCaseType.DRAFT
        }.shuffled()
        val totalDocuments = docReferences.size
        var pendingDocuments = totalDocuments
        var atLeastOneSuccess = false


        Timber.i("Found $totalDocuments document(s) to upload")

        val notificationManager = createNotificationChannel(context)
        val notificationBuilder = createNotificationBuilder(context, totalDocuments, pendingDocuments)

        val result = docReferences.map {
            val uriString = it.resource.getExtensionByUrl(UPLOAD_IMAGE_URL)?.value?.asStringValue()
            if (uriString.isNullOrBlank()) {
                Timber.e(Exception("Empty or null URI string for document: ${it.resource.logicalId} - $pendingDocuments pending"))
                return@map it.resource to null
            }
            it.resource to uriString.toUri()
        }.filter { it.second !== null }.map {
            val docReference = it.first
            val serverDocRef = getDocumentReferenceMetaDataFromServer(docReference)

            //Image is not found on the device & server
            if (!filesExists(it.second) && !serverDocRef.hasImageDataOnServer()){
                if(imageNotPresentOnDeviceFinalizeDocumentOnServer(docReference)){
                    openSrpFhirEngine.purge(docReference.resourceType, docReference.logicalId, true)
                }
            }
            val fileUri = it.second ?: return@map false

            try {
                uploadImageMutex.withLock {
                    Timber.i("Processing document reference with logicalId: ${docReference.logicalId}")

                    val success = uploadDocumentReferenceVersionAware(docReference, fileUri, context, serverDocRef)

                    if (success) {
                        atLeastOneSuccess = true

                        // Clean up local resources after successful upload
                        openSrpFhirEngine.purge(
                            docReference.resourceType,
                            docReference.logicalId,
                            true
                        )
                        applicationContext.contentResolver.delete(fileUri, null, null)

                        pendingDocuments--
                        val progress = ((totalDocuments - pendingDocuments) * 100 / totalDocuments).toInt()
                        updateProgress(context, notificationBuilder, totalDocuments, pendingDocuments)
                        notificationManager.notify(NOTIFICATION_ID, notificationBuilder.build())
                        setProgressAsync(workDataOf("progress" to progress))

                        Timber.i("Successfully completed version-aware upload for document: ${docReference.logicalId}")
                        true
                    } else {
                        Timber.e("Failed version-aware upload for document: ${docReference.logicalId}")
                        false
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Exception during version-aware upload for document: ${docReference.logicalId} - $pendingDocuments pending")
                false
            }
        }.all { it }

        updateLastSyncDate(pendingDocuments)
        super.doWork()

        updateNotification(context, notificationManager, notificationBuilder, result)
        Timber.i("Finished version-aware document reference upload for worker: $workerId")
        return result
    }

private fun filesExists(uri: Uri?): Boolean {
    if (uri == null) return false
    return try {
        applicationContext.contentResolver.openInputStream(uri)?.use { it.available() > 0 } ?: false
    } catch (e: Exception) {
        Timber.e(e, "Error checking file existence for uri: $uri")
        false
    }
}

    /**
     * Checks if the DocumentReference on the server has been marked as 'final'.
     */
    private fun DocumentReference?.isFinalOnServer(): Boolean =
        this?.docStatus == DocumentReference.ReferredDocumentStatus.FINAL

    /**
     * Checks if the DocumentReference on the server has an attachment with a non-zero size.
     */
    private fun DocumentReference?.hasImageDataOnServer(): Boolean =
        this?.content?.any { (it.attachment?.size ?: 0) > 0 } == true

    /**
     * Checks if a record for this DocumentReference (preliminary or final) already exists on the server.
     */
    private fun DocumentReference?.hasRecordOnServer(): Boolean =
        this?.docStatus in setOf(
            DocumentReference.ReferredDocumentStatus.PRELIMINARY,
            DocumentReference.ReferredDocumentStatus.FINAL
        )

    /**
     * Checks if the DocumentReference on the server is complete (has data and is final).
     */
    private fun DocumentReference?.isCompleteOnServer(): Boolean =
        isFinalOnServer() && hasImageDataOnServer()

    private suspend fun uploadDocumentReferenceVersionAware(
        docReference: DocumentReference,
        fileUri: Uri,
        context: Context,
        serverDocRef: DocumentReference?
    ): Boolean {
        return runCatching {
            Timber.i("Starting version-aware upload for document: ${docReference.logicalId}")

            // 1. If the document is already fully uploaded and finalized, we're done.
            if (serverDocRef.isCompleteOnServer()) {
                Timber.i("Server already has complete DocumentReference: ${docReference.logicalId}. Skipping.")
                return@runCatching true
            }

            // 2. State correction: If local is 'final' but server is not, just patch the server status.
            // This handles cases where the app was closed after uploading the image but before finalizing.
            if (docReference.docStatus == DocumentReference.ReferredDocumentStatus.FINAL && !serverDocRef.isFinalOnServer()) {
                Timber.i("Local DocumentReference is final, updating server status for ${docReference.logicalId}")
                finalizeDocumentOnServer(docReference)
                return@runCatching true
            }

            // 3. Main Upload Flow: Execute steps based on server state.

            // Step 3a: Create the preliminary metadata record if it doesn't exist.
            if (!serverDocRef.hasRecordOnServer()) {
                createMetadataRecordOnServer(docReference, fileUri, context)
            }

            // Step 3b: Upload the file's binary content if it's missing.
            if (!serverDocRef.hasImageDataOnServer()) {
                uploadFileContent(docReference, fileUri, context)
                // After upload, update local status to FINAL to track progress
                docReference.docStatus = DocumentReference.ReferredDocumentStatus.FINAL
                openSrpFhirEngine.update(docReference)
            }

            // Step 3c: Finalize the document status on the server.
            if (!serverDocRef.isFinalOnServer()) {
                finalizeDocumentOnServer(docReference)
            }

            Timber.i("Version-aware upload completed successfully for: ${docReference.logicalId}")
            true // Success
        }.onFailure { e ->
            Timber.e(e, "Version-aware upload failed for document: ${docReference.logicalId}")
        }.getOrDefault(false)
    }

    /**
     * Step 1: Creates the DocumentReference resource on the server with a 'preliminary' status.
     */
    private suspend fun createMetadataRecordOnServer(docReference: DocumentReference, fileUri: Uri, context: Context) {
        Timber.i("Step 1: Creating metadata record for ${docReference.logicalId}")

        // Ensure the local file exists before creating a server record for it.
        val fileExists = context.contentResolver.openInputStream(fileUri)?.use { it.available() > 0 } ?: false
        if (!fileExists) {
            throw IllegalStateException("File does not exist or is empty for document: ${docReference.logicalId}")
        }

        val metadataDocReference = docReference.copy().apply {
            docStatus = DocumentReference.ReferredDocumentStatus.PRELIMINARY
            content.forEach { it.attachment?.data = null } // Ensure no data is embedded
        }

        val docReferenceJson = FhirContext.forR4Cached().newJsonParser().encodeResourceToString(metadataDocReference)
        val requestBody = docReferenceJson.encodeToByteArray().toRequestBody(HEADER_APPLICATION_JSON.toMediaType())

        fhirResourceService.insertResource(docReference.fhirType(), docReference.logicalId, requestBody)
        Timber.i("Step 1 completed: Metadata record created for ${docReference.logicalId}")
    }

    /**
     * Step 2: Uploads the binary file content to the existing DocumentReference.
     */
    private suspend fun uploadFileContent(docReference: DocumentReference, fileUri: Uri, context: Context) {
        Timber.i("Step 2: Uploading file content for ${docReference.logicalId}")

        val bytes = context.contentResolver.openInputStream(fileUri)
            ?.use { it.buffered().readBytes() }
            ?: throw IllegalStateException("Failed to read file bytes for document: ${docReference.logicalId}")

        val contentType = docReference.content.firstOrNull()?.attachment?.contentType
        val body = bytes.toRequestBody(contentType?.toMediaType())

        val response = fhirResourceService.uploadFile(
            docReference.fhirType(),
            docReference.logicalId,
            "DocumentReference.content.attachment",
            body
        )

        if (!response.isSuccessful) {
            docReference.addExtension().apply {
                url = IMG_UPLOAD_ERROR_EXTENSION
                setValue(StringType("Upload failed: ${response.code()} - ${response.message()}"))
            }
            openSrpFhirEngine.update(docReference)
            
            // Handle specific cleanup logic for failed uploads
            if (response.code() in listOf(422, 410)) {
                openSrpFhirEngine.purge(docReference.resourceType, docReference.logicalId, true)
                context.contentResolver.delete(fileUri, null, null)
            }
            // Throw a specific exception to be caught by the top-level handler
            throw ImageUploadAPIException(
                documentId = docReference.logicalId,
                responseCode = response.code(),
                responseMessage = response.message(),
                pendingDocuments = 0 // Caller can update this if needed
            )
        }
        Timber.i("Step 2 completed: File content uploaded for ${docReference.logicalId}")
    }

    /**
     * Step 3: Updates the DocumentReference status to 'final' using a JSON Patch.
     */
    private suspend fun finalizeDocumentOnServer(docReference: DocumentReference) {
        Timber.i("Step 3: Finalizing document status for ${docReference.logicalId}")
        fhirResourceService.updateResource(
            docReference.fhirType(),
            docReference.logicalId,
            gson.toJson(listOf(DocStatusRequest(REPLACE, DOC_STATUS, DocumentReference.ReferredDocumentStatus.FINAL.name.lowercase()))).toRequestBody(
                CONTENT_TYPE.toMediaTypeOrNull()
            )
        )
        Timber.i("Step 3 completed: Document status finalized for ${docReference.logicalId}")
    }

    /**
     * Update the DocumentReference with image permanent failure ext & status to 'final'.
     */
    private suspend fun imageNotPresentOnDeviceFinalizeDocumentOnServer(docReference: DocumentReference) : Boolean {
        Timber.i("Finalizing image FAILED for ${docReference.logicalId}")

        val extensionValue = ExtensionValue(
            url = IMG_UPLOAD_FAILED_PERMANENTLY_EXTENSION,
            valueString = "IMG_UPLOAD_FAILED_PERMANENTLY"
        )

        // Define the JSON Patch operations
        val patchOperations = listOf(
            mapOf(
                "op" to "add",
                "path" to "/extension",
                "value" to listOf(extensionValue)
            ),
            mapOf(
                "op" to "replace",
                "path" to "/docStatus",
                "value" to DocumentReference.ReferredDocumentStatus.FINAL.name.lowercase()
            )
        )
        val patchJson = gson.toJson(patchOperations)

        Timber.d("Sending JSON Patch for ${docReference.logicalId}: $patchJson")

        val result = fhirResourceService.updateDocResource(
            docReference.fhirType(),
            docReference.logicalId,
            patchJson.toRequestBody(
                CONTENT_TYPE.toMediaTypeOrNull()
            )
        )

        // Check if the update was successful
        return result.docStatus.name.lowercase() == DocumentReference.ReferredDocumentStatus.FINAL.name.lowercase()

        Timber.i("Step 3 completed: Document status finalized for ${docReference.logicalId}")
    }

    // The metadata fetch function remains the same
    private suspend fun getDocumentReferenceMetaDataFromServer(docReference: DocumentReference): DocumentReference? {
        return try {
            fhirResourceService.getDocumentReferenceMeta(docReference.logicalId)
        } catch (e: Exception) {
            Timber.i("No DocumentReference on server for id ${docReference.logicalId}: ${e.localizedMessage}")
            null
        }
    }

    // Custom exception class for tracking upload errors
    data class ImageUploadAPIException(
        val documentId: String,
        val responseCode: Int,
        val responseMessage: String,
        val pendingDocuments: Int
    ) : Exception("Image upload failed for document $documentId: $responseCode $responseMessage ($pendingDocuments pending)")

    private fun getDeviceId(): String {
        return Settings.Secure.getString(
            getApplicationContext().getContentResolver(),
            Settings.Secure.ANDROID_ID
        )
    }

    private suspend fun updateLastSyncDate(pendingDocuments: Int) {
        try {
            secureSharedPreference.updateLastSyncDataTime(System.currentTimeMillis())

            val deviceId = getDeviceId()
            val flw = secureSharedPreference.getPractitionerUserId()
            val resourceId = "sync-metadata-$deviceId"

            val syncMetadata = Basic().apply {
                id = resourceId
                code.addCoding()
                    .setSystem(SYNC_METADATA_SYSTEM)
                    .setCode(SYNC_METADATA_CODE)
                addExtension().apply {
                    url = LAST_SYNC_TIME_EXTENSION
                    setValue(DateTimeType(Date()))
                }
                addExtension().apply {
                    url = DEVICE_ID_EXTENSION
                    setValue(StringType(deviceId))
                }
                addExtension().apply {
                    url = FLW_ID_EXTENSION
                    setValue(StringType(flw))
                }
                addExtension().apply {
                    url = PENDING_IMAGES_EXTENSION
                    setValue(StringType("$pendingDocuments"))
                }
            }

            // Check if resource exists, then update or create accordingly
            val existingResource = try {
                openSrpFhirEngine.get<Basic>(resourceId)
            } catch (e: Exception) {
                null
            }

            if (existingResource != null) {
                openSrpFhirEngine.update(syncMetadata)
                Timber.i("Successfully updated sync metadata for device: $deviceId")
            } else {
                openSrpFhirEngine.create(syncMetadata)
                Timber.i("Successfully created sync metadata for device: $deviceId")
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to update sync metadata")
        }
    }

    private fun updateProgress(context: Context,notificationBuilder: NotificationCompat.Builder, totalDocuments: Int, pendingDocuments: Int) {
        notificationBuilder.setProgress(totalDocuments, totalDocuments - pendingDocuments, false)
            .setContentText(context.getString(R.string.images_pending_text, pendingDocuments))

    }

    private fun createNotificationChannel(context: Context): NotificationManager {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(CHANNEL_ID, context.getString(R.string.notification_title_document_upload), NotificationManager.IMPORTANCE_HIGH)
        notificationManager.createNotificationChannel(channel)
        return notificationManager
    }

    private fun createNotificationBuilder(context: Context, totalDocuments: Int, pendingDocuments: Int): NotificationCompat.Builder {
        val notificationBuilder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.uploading_images_title))
            .setSmallIcon(R.drawable.ic_quest_logo)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setProgress(totalDocuments, 0, false)
            .setContentText(context.getString(R.string.images_pending_text, pendingDocuments))
        return notificationBuilder
    }

    private fun updateNotification(context: Context, notificationManager: NotificationManager, notificationBuilder: NotificationCompat.Builder, result: Boolean) {
        if (result) {
            notificationBuilder.setContentText(context.getString(R.string.upload_success))
                .setProgress(0, 0, false)
                .setOngoing(false)
        } else {
            notificationBuilder.setContentText(context.getString(R.string.upload_failure))
                .setProgress(0, 0, false)
                .setOngoing(false)
        }
        notificationManager.notify(NOTIFICATION_ID, notificationBuilder.build())
    }
}