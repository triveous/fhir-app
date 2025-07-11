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
import androidx.core.os.trace
import androidx.hilt.work.HiltWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import ca.uhn.fhir.context.FhirContext
import com.google.android.fhir.FhirEngine
import com.google.android.fhir.datacapture.extensions.asStringValue
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
import org.hl7.fhir.r4.model.Resource
import org.hl7.fhir.r4.model.ResourceType
import org.hl7.fhir.r4.model.StringType
import org.smartregister.fhircore.engine.R
import org.smartregister.fhircore.engine.data.local.updateDocStatus.DocStatusRequest
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
    val secureSharedPreference: SecureSharedPreference
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
            val fileUri = it.second ?: return@map false

            try {
                uploadImageMutex.withLock {
                    Timber.i("Processing document reference with logicalId: ${docReference.logicalId}")

                    // VERSION-AWARE UPLOAD IMPLEMENTATION
                    val success = uploadDocumentReferenceVersionAware(docReference, fileUri, context)

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

        // Update sync metadata if at least one document was uploaded successfully
        try {
            if (atLeastOneSuccess) {
                updateLastSyncDate(pendingDocuments)
                if ((openSrpFhirEngine.getUnsyncedLocalChanges()
                        .filter { it.resourceType == ResourceType.Basic.name }).isNotEmpty()
                ){
                    Timber.i("Basic resource found, retrying sync")
                    super.doWork()
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to update sync metadata")
        }

        updateNotification(context, notificationManager, notificationBuilder, result)
        Timber.i("Finished version-aware document reference upload for worker: $workerId")
        return result
    }

    private fun documentReferenceHasDataAndFinalOnServer(docReference: DocumentReference?): Boolean {
        return try {
                return (documentReferenceHasImageDataOnServer(docReference)
                        && documentReferenceIsFinalisedOnServer(docReference))
            return false
        } catch (e: Exception) {
            Timber.i("No DocumentReference on server for id ${docReference?.logicalId}: ${e.localizedMessage}")
            false
        }
    }

    private fun documentReferenceAlreadyInsertedOnServer(docReference: DocumentReference?): Boolean {
        return try {
            docReference?.let {
                return (docReference.docStatus == DocumentReference.ReferredDocumentStatus.PRELIMINARY) ||
                        (docReference.docStatus == DocumentReference.ReferredDocumentStatus.FINAL )
            }
            return false
        } catch (e: Exception) {
            Timber.i("No DocumentReference on server for id ${docReference?.logicalId}: ${e.localizedMessage}")
            false
        }
    }

    private fun documentReferenceHasImageDataOnServer(docReference: DocumentReference?): Boolean {
        return try {
            docReference?.let {
                docReference.content.any {
                    return (it.attachment?.size != null && (it.attachment?.size ?: 0) > 0)
                }
            }
            return false
        } catch (e: Exception) {
            Timber.i("No DocumentReference on server for id ${docReference?.logicalId}: ${e.localizedMessage}")
            false
        }
    }

    private fun documentReferenceIsFinalisedOnServer(docReference: DocumentReference?): Boolean {
        return try {
            docReference?.let {
                return (docReference.docStatus == DocumentReference.ReferredDocumentStatus.FINAL)
            }
            return false
        } catch (e: Exception) {
            Timber.i("No DocumentReference on server for id ${docReference?.logicalId}: ${e.localizedMessage}")
            false
        }
    }

    private suspend fun getDocumentReferenceMetaDataFromServer(docReference: DocumentReference): DocumentReference? {
        return try {
            val serverDocRef = fhirResourceService.getDocumentReferenceMeta(docReference.logicalId)
            return serverDocRef
        } catch (e: Exception) {
            Timber.i("No DocumentReference on server for id ${docReference.logicalId}: ${e.localizedMessage}")
            null
        }
    }

    private suspend fun uploadDocumentReferenceVersionAware(
        docReference: DocumentReference,
        fileUri: Uri,
        context: Context
    ): Boolean {
        return try {
            Timber.i("Starting version-aware upload for document: ${docReference.logicalId}")

            val docRefMetaData = getDocumentReferenceMetaDataFromServer(docReference)
            // 1. Avoid uploading if server already has data for this DocumentReference
            if (documentReferenceHasDataAndFinalOnServer(docRefMetaData)) {
                Timber.i("Server already has DocumentReference with data: ${docReference.logicalId}. Skipping upload.")
                return true
            }

            if (docReference.docStatus == DocumentReference.ReferredDocumentStatus.FINAL) {
                if (!documentReferenceIsFinalisedOnServer(docRefMetaData)){
                    Timber.i("DocumentReference already final: ${docReference.logicalId}")

                    val finalStatusUpdate = """[
                        {
                            "op": "replace",
                            "path": "/docStatus",
                            "value": "final"
                        }
                    ]"""

                    fhirResourceService.updateDocumentReferenceResource(
                        docReference.fhirType(),
                        docReference.logicalId,
                        finalStatusUpdate.toRequestBody("application/json-patch+json".toMediaTypeOrNull())
                    )
                    return true
                }
                return true
            }

            if (!documentReferenceAlreadyInsertedOnServer(docRefMetaData)){
                val fileExists = runCatching {
                    context.contentResolver.openInputStream(fileUri)?.use { it.available() > 0 } ?: false
                }.getOrDefault(false)
                if (!fileExists) {
                    Timber.e(Exception("File does not exist or is empty for document: ${docReference.logicalId}"))
                    return false
                }

                // Step 1: Create DocumentReference with metadata only and preliminary status
                val metadataDocReference = docReference.copy().apply {
                    docStatus = DocumentReference.ReferredDocumentStatus.PRELIMINARY
                    // Keep content metadata but ensure no embedded data
                    content.forEach { contentComponent ->
                        contentComponent.attachment?.data = null
                    }
                }

                val docReferenceJson = FhirContext.forR4Cached().newJsonParser()
                    .encodeResourceToString(metadataDocReference as Resource)
                val refBody = docReferenceJson.encodeToByteArray()
                    .toRequestBody(HEADER_APPLICATION_JSON.toMediaType())

                Timber.i("Step 1: Inserting DocumentReference metadata for: ${docReference.logicalId}")

                val initialResource = fhirResourceService.insertResource(
                    docReference.fhirType(),
                    docReference.logicalId,
                    refBody
                )
                Timber.i("Step 1 completed: DocumentReference metadata inserted for: ${docReference.logicalId}")
            }

            if (!documentReferenceHasImageDataOnServer(docRefMetaData)){
                // Step 2: Upload binary file using the exact resource from step 1
                Timber.i("Step 2: Retrieving file bytes for document: ${docReference.logicalId}")
                val bytes = runCatching {
                    context.contentResolver.openInputStream(fileUri)
                        ?.use { it.buffered().readBytes() }
                }.getOrNull() ?: run {
                    Timber.e(Exception("Failed to retrieve file bytes for document: ${docReference.logicalId}"))
                    return false
                }

                val docContentType = docReference.content.first().attachment.contentType
                val body = bytes.toRequestBody(docContentType.toMediaType())

                Timber.i("Step 2: Uploading file for document: ${docReference.logicalId}")
                val uploadResponse = fhirResourceService.uploadFile(
                    docReference.fhirType(),
                    docReference.logicalId,
                    "DocumentReference.content.attachment",
                    body
                )

                if (!uploadResponse.isSuccessful) {
                    val customException = ImageUploadAPIException(
                        documentId = docReference.logicalId,
                        responseCode = uploadResponse.code(),
                        responseMessage = uploadResponse.message(),
                        pendingDocuments = 0 // Will be updated by caller
                    )
                    Timber.e(customException)

                    // Handle specific error codes
                    if (uploadResponse.code() == 422 || uploadResponse.code() == 410) {
                        // Clean up if resource is invalid or gone
                        openSrpFhirEngine.purge(
                            docReference.resourceType,
                            docReference.logicalId,
                            true
                        )
                        context.contentResolver.delete(fileUri, null, null)
                    }
                    return false
                }

                Timber.i("Step 2 completed: File uploaded successfully for: ${docReference.logicalId}")

                //Update docStatus to final locally
                docReference.docStatus = DocumentReference.ReferredDocumentStatus.FINAL
                openSrpFhirEngine.update(docReference)
            }

            if (!documentReferenceIsFinalisedOnServer(docRefMetaData)){
                // Step 3: Update status to final using JSON Patch to avoid overwriting binary data
                Timber.i("Step 3: Updating status to final for document: ${docReference.logicalId}")
                    val finalStatusUpdate = """[
                    {
                        "op": "replace",
                        "path": "/docStatus",
                        "value": "final"
                    }
                ]"""

                val statusUpdateResponse = fhirResourceService.updateDocumentReferenceResource(
                    docReference.fhirType(),
                    docReference.logicalId,
                    finalStatusUpdate.toRequestBody("application/json-patch+json".toMediaTypeOrNull())
                )

                Timber.i("Step 3 completed: Status updated to final for: ${docReference.logicalId}")
                Timber.i("Version-aware upload completed successfully for: ${docReference.logicalId}")

                return true
            }

            return true
        } catch (e: Exception) {
            Timber.e(e, "Version-aware upload failed for document: ${docReference.logicalId}")
            return false
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

            val syncMetadata = Basic().apply {
                id = "sync-metadata-$deviceId"
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

            // Update or create resource on FHIR server
            try {
                openSrpFhirEngine.update(syncMetadata)
                Timber.i("Successfully updated sync metadata for device: $deviceId")
            } catch (e: Exception) {
                Timber.w("Sync metadata not found, creating new resource")
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