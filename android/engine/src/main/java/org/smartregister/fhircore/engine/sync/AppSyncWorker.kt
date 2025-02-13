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
import com.google.gson.Gson
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.hl7.fhir.r4.model.DocumentReference
import org.hl7.fhir.r4.model.Resource
import org.smartregister.fhircore.engine.R
import org.smartregister.fhircore.engine.data.local.updateDocStatus.DocStatusRequest
import org.smartregister.fhircore.engine.data.remote.fhir.resource.FhirResourceService
import org.smartregister.fhircore.engine.di.CoroutineDispatchers
import org.smartregister.fhircore.engine.di.NetworkModule
import org.smartregister.fhircore.engine.domain.networkUtils.DocumentReferenceCaseType
import org.smartregister.fhircore.engine.domain.networkUtils.HttpConstants.HEADER_APPLICATION_JSON
import org.smartregister.fhircore.engine.domain.networkUtils.HttpConstants.UPLOAD_IMAGE_URL
import org.smartregister.fhircore.engine.domain.networkUtils.WorkerConstants.CONTENT_TYPE
import org.smartregister.fhircore.engine.domain.networkUtils.WorkerConstants.DOC_STATUS
import org.smartregister.fhircore.engine.domain.networkUtils.WorkerConstants.REPLACE
import org.smartregister.fhircore.engine.util.extension.logicalId
import org.smartregister.fhircore.engine.util.notificationHelper.CHANNEL_ID
import org.smartregister.fhircore.engine.util.notificationHelper.NOTIFICATION_ID
import org.smartregister.fhircore.engine.util.notificationHelper.createNotification
import retrofit2.Response
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
    private val dispatchers: CoroutineDispatchers
) : FhirSyncWorker(appContext, workerParams) {

    companion object {
        val mutex = Mutex()
        val uploadImageMutex = Mutex()
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

    override suspend fun doWork(): Result = withContext(dispatchers.io) {
        Timber.i("AppSyncWorker Running sync worker")

        if (mutex.isLocked) {
            Timber.e("AppSyncWorker is locked. Returning failure")
            return@withContext Result.failure()
        }

        mutex.withLock {
            try {
                setForeground(getForegroundInfo())

                val metaSyncResult = super.doWork()
                /*if (metaSyncResult.javaClass === Result.success().javaClass) {
                    return@withLock metaSyncResult
                }*/

                val allDocUploaded = performDocumentReferenceUpload(applicationContext, id.toString())
                val retries = inputData.getInt("max_retires", 0)

                when {
                    allDocUploaded && (metaSyncResult.javaClass === Result.success().javaClass) -> Result.success()
                    retries > runAttemptCount -> Result.retry()
                    else -> Result.failure(
                        workDataOf(
                            "error" to Exception::class.java.name,
                            "reason" to "Failed to upload all files"
                        )
                    )
                }
            } catch (e: Exception) {
                Timber.e(e, "AppSync worker failed")
                Result.failure(workDataOf("error" to e.toString()))
            }
        }
    }

    private suspend fun performDocumentReferenceUpload(context: Context, workerId: String): Boolean {
        Timber.i("Starting document reference upload for worker: $workerId")

        val docReferences = withContext(dispatchers.io) {
            openSrpFhirEngine.search<DocumentReference> {}.filter {
                it.resource.description != DocumentReferenceCaseType.DRAFT
            }
        }

        val totalDocuments = docReferences.size
        var processedDocuments = 0

        val notificationManager = createNotificationChannel(context)
        val notificationBuilder = createNotificationBuilder(context, totalDocuments, totalDocuments - processedDocuments)

        return docReferences.map { docRef ->
            processDocumentReference(
                context,
                docRef.resource,
                notificationManager,
                notificationBuilder,
                totalDocuments,
                processedDocuments
            ).also {
                processedDocuments++
                updateProgress(processedDocuments, totalDocuments)
            }
        }.all { it }
            .also { success ->
                updateNotification(context, notificationManager, notificationBuilder, success)
            }
    }

    private suspend fun processDocumentReference(
        context: Context,
        docReference: DocumentReference,
        notificationManager: NotificationManager,
        notificationBuilder: NotificationCompat.Builder,
        totalDocuments: Int,
        processedDocuments: Int
    ): Boolean = withContext(dispatchers.io) {
        try {
            val uriString = docReference.getExtensionByUrl(UPLOAD_IMAGE_URL)?.value?.asStringValue()
            if (uriString.isNullOrBlank()) {
                Timber.e("Empty or null URI string for document: ${docReference.logicalId}")
                return@withContext false
            }

            val fileUri = uriString.toUri()
            val bytes = context.contentResolver.openInputStream(fileUri)?.use {
                it.buffered().readBytes()
            } ?: run {
                Timber.e("Failed to read file bytes for document: ${docReference.logicalId}")
                return@withContext false
            }

            uploadImageMutex.withLock {
                // Upload document reference
                val docReferenceJson = FhirContext.forR4Cached().newJsonParser()
                    .encodeResourceToString(docReference as Resource)
                val refBody = docReferenceJson.encodeToByteArray()
                    .toRequestBody(HEADER_APPLICATION_JSON.toMediaType())

                fhirResourceService.insertResource(
                    docReference.fhirType(),
                    docReference.logicalId,
                    refBody
                )

                // Upload file
                val docContentType = docReference.content.first().attachment.contentType
                val body = bytes.toRequestBody(docContentType.toMediaType())

                val response = fhirResourceService.uploadFile(
                    docReference.fhirType(),
                    docReference.logicalId,
                    "DocumentReference.content.attachment",
                    body
                )

                handleUploadResponse(response, docReference, fileUri, context)
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to process document: ${docReference.logicalId}")
            false
        }
    }

    private suspend fun handleUploadResponse(
        response: Response<String>,
        docReference: DocumentReference,
        fileUri: Uri,
        context: Context
    ): Boolean {
        if (!response.isSuccessful) {
            if (response.code() in listOf(422, 410)) {
                cleanupFailedUpload(docReference, fileUri, context)
            }
            return false
        }

        // Update document status
        fhirResourceService.updateResource(
            docReference.fhirType(),
            docReference.logicalId,
            Gson().toJson(listOf(
                DocStatusRequest(
                    REPLACE,
                    DOC_STATUS,
                    DocumentReference.ReferredDocumentStatus.FINAL.name.lowercase()
                )
            )).toRequestBody(CONTENT_TYPE.toMediaTypeOrNull())
        )

        cleanupSuccessfulUpload(docReference, fileUri, context)
        return true
    }

    private suspend fun cleanupFailedUpload(
        docReference: DocumentReference,
        fileUri: Uri,
        context: Context
    ) = withContext(dispatchers.io) {
        openSrpFhirEngine.purge(docReference.resourceType, docReference.logicalId, true)
        context.contentResolver.delete(fileUri, null, null)
    }

    private suspend fun cleanupSuccessfulUpload(
        docReference: DocumentReference,
        fileUri: Uri,
        context: Context
    ) = withContext(dispatchers.io) {
        openSrpFhirEngine.purge(docReference.resourceType, docReference.logicalId, true)
        context.contentResolver.delete(fileUri, null, null)
    }

    private suspend fun updateProgress(processedDocuments: Int, totalDocuments: Int) {
        val progress = ((processedDocuments * 100) / totalDocuments).toInt()
        setProgressAsync(workDataOf("progress" to progress))
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