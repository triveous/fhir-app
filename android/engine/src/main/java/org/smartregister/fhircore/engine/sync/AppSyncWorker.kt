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
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
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
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.hl7.fhir.r4.model.DocumentReference
import org.hl7.fhir.r4.model.Resource
import org.smartregister.fhircore.engine.R
import org.smartregister.fhircore.engine.data.remote.fhir.resource.FhirResourceService
import org.smartregister.fhircore.engine.domain.networkUtils.HttpConstants.HEADER_APPLICATION_JSON
import org.smartregister.fhircore.engine.domain.networkUtils.HttpConstants.UPLOAD_IMAGE_URL
import org.smartregister.fhircore.engine.util.extension.logicalId
import org.smartregister.fhircore.engine.util.notificationHelper.CHANNEL_ID
import org.smartregister.fhircore.engine.util.notificationHelper.NOTIFICATION_ID
import org.smartregister.fhircore.engine.util.notificationHelper.createNotification
import timber.log.Timber

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
) : FhirSyncWorker(appContext, workerParams) {

    companion object{
        val mutex = Mutex()
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
        val notification = createNotification(applicationContext) // Your method to create a notification
        return ForegroundInfo(
            NOTIFICATION_ID,
            notification,
            FOREGROUND_SERVICE_TYPE_SHORT_SERVICE // Specify the service type
        )
    }

    override suspend fun doWork(): Result {
        Timber.e("AppSyncWorker Running sync worker")
        if (mutex.isLocked) {
            Timber.e("AppSyncWorker is locked. Returning failure")
            return Result.failure()
        }
        val metaSyncResult = super.doWork()
        mutex.withLock {
            Timber.i("AppSyncWorker Running within lock sync worker")
            try {
                setForeground(getForegroundInfo()) // Set the foreground info
                val allDocUploaded =
                    performDocumentReferenceUpload(applicationContext, id.toString())

                val retries = inputData.getInt("max_retires", 0)
                // In case it has failed or to be retried, we will send the original result
                if (metaSyncResult.javaClass === Result.success().javaClass) {
                    return when (allDocUploaded) {
                        true -> {
                            Result.success()
                        }

                        false -> if (retries > runAttemptCount) Result.retry() else Result.failure(
                            workDataOf(
                                "error" to Exception::class.java.name,
                                "reason" to "Failed to upload all files"
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return metaSyncResult
    }

    private suspend fun performDocumentReferenceUpload(context: Context, workerId: String): Boolean {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Create notification channel (for Android 8.0 and above)
        val channel = NotificationChannel(CHANNEL_ID, "Document Upload", NotificationManager.IMPORTANCE_HIGH)
        notificationManager.createNotificationChannel(channel)

        val notificationBuilder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.uploading_images_title))
            .setSmallIcon(R.drawable.ic_quest_logo)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)

        val docReferences = openSrpFhirEngine.search<DocumentReference> {}
        val totalDocuments = docReferences.size
        var pendingDocuments = totalDocuments

        Timber.i("Found $totalDocuments file(s) to upload")

        notificationBuilder.setProgress(totalDocuments, 0, false)
            .setContentText(context.getString(R.string.images_pending_text,pendingDocuments))
        notificationManager.notify(NOTIFICATION_ID, notificationBuilder.build())

        val result = docReferences.map {
                val uriString = it.resource.getExtensionByUrl(UPLOAD_IMAGE_URL)?.value?.asStringValue()
                if (uriString.isNullOrBlank()) return@map it.resource to null
                it.resource to uriString.toUri()
            }.filter { it.second !== null }.map {
                val docReference = it.first
                val fileUri = it.second ?: return@map false

                try {
//                    Timber.i("Serializing document reference with logicalId: ${docReference.logicalId}")
                    val docReferenceJson = FhirContext.forR4Cached().newJsonParser().encodeResourceToString(docReference as Resource)
                    val docReferenceBytes = docReferenceJson.encodeToByteArray()

                    val refBody = docReferenceBytes.toRequestBody(HEADER_APPLICATION_JSON.toMediaType())

//                    Timber.i("Inserting document reference with logicalId: ${docReference.logicalId}")

                    fhirResourceService.insertResource(docReference.fhirType(), docReference.logicalId, refBody)

//                    Timber.i("Successfully inserted document reference with logicalId: ${docReference.logicalId}")

                    val docContentType = docReference.content.first().attachment.contentType

//                    Timber.i("Retrieving file bytes for document with logicalId: ${docReference.logicalId}")
                    // In case the file is missing, we will return true and no do anything
                    val bytes = runCatching {
                        context.contentResolver.openInputStream(fileUri)?.use { it.buffered().readBytes() }
                    }.getOrNull() ?: run {
//                        Timber.w("Failed to retrieve file bytes for document with logicalId: ${docReference.logicalId}")
                        return@map false
                    }

//                    Timber.i("Successfully retrieved file bytes for document with logicalId: ${docReference.logicalId}")

                    val body = bytes.toRequestBody(docContentType.toMediaType())

//                    Timber.i("Uploading file for document with logicalId: ${docReference.logicalId}")
                    val response = fhirResourceService.uploadFile(
                        docReference.fhirType(),
                        docReference.logicalId,
                        "DocumentReference.content.attachment",
                        body)

//                    Timber.i("Received response for upload of document with logicalId: ${docReference.logicalId} - Success: ${response.isSuccessful}")

                    if (response.isSuccessful.not()) {
//                        Timber.e("File upload failed for document with logicalId: ${docReference.logicalId} - Response code: ${response.code()} - Message: ${response.message()}")

                        // When it is client error, it cannot be retried successfully ever,
                        // so we are going to purge the data
                        if (response.code() == 400 || response.code() == 422 || response.code() == 410) {
//                            Timber.i("Client error encountered. Purging document with logicalId: ${docReference.logicalId}")

                            // Save the changes to document reference
                            openSrpFhirEngine.purge(docReference.resourceType, docReference.logicalId, true)

                            // When the content is uploaded, we will reset the description back to empty so that
                            // it won't be attempted the next time
//                            context.contentResolver.delete(fileUri, null, null)
                            applicationContext.contentResolver.delete(fileUri, null, null)
                        }
                        return@map false
                    }

//                    Timber.i("Successfully uploaded document with logicalId: ${docReference.logicalId}")
                    // Save the changes to document reference
                    openSrpFhirEngine.purge(docReference.resourceType, docReference.logicalId, true)

                    // When the content is uploaded, we will reset the description back to empty so that
                    // it won't be attempted the next time
//                    context.contentResolver.delete(fileUri, null, null)
                    applicationContext.contentResolver.delete(fileUri, null, null)

                    // Update progress
                    pendingDocuments--
                    val progress = ((totalDocuments - pendingDocuments) * 100 / totalDocuments).toInt()
                    notificationBuilder.setProgress(totalDocuments, totalDocuments - pendingDocuments, false)
                        .setContentText(context.getString(R.string.images_pending_text,pendingDocuments))
                    notificationManager.notify(NOTIFICATION_ID, notificationBuilder.build())

                    // Update work progress
                    setProgressAsync(workDataOf("progress" to progress))

                    true
                } catch (e: Exception) {
//                    Timber.e(e, "Exception during document upload: ${docReference.logicalId}")
                    Timber.e(e, "Exception stackTrace: ${e.printStackTrace()}")
                    false
                }
            }.all { it }

        // Update notification on completion
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

        return result
    }
}