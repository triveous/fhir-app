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

import android.content.Context
import androidx.core.net.toUri
import androidx.hilt.work.HiltWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import ca.uhn.fhir.context.FhirContext
import com.google.android.fhir.FhirEngine
import com.google.android.fhir.datacapture.extensions.asStringValue
import org.smartregister.fhircore.engine.util.extension.logicalId
import com.google.android.fhir.search.search
import com.google.android.fhir.sync.AcceptLocalConflictResolver
import com.google.android.fhir.sync.ConflictResolver
import com.google.android.fhir.sync.DownloadWorkManager
import com.google.android.fhir.sync.FhirSyncWorker
import com.google.android.fhir.sync.upload.UploadStrategy
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.hl7.fhir.r4.model.DocumentReference
import org.hl7.fhir.r4.model.Resource
import org.smartregister.fhircore.engine.data.remote.fhir.resource.FhirResourceService
import timber.log.Timber

@HiltWorker
class AppSyncWorker
@AssistedInject
constructor(
  @Assisted appContext: Context,
  @Assisted workerParams: WorkerParameters,
  val syncListenerManager: SyncListenerManager,
  private val openSrpFhirEngine: FhirEngine,
  private val appTimeStampContext: AppTimeStampContext,
  private val fhirResourceService: FhirResourceService,
) : FhirSyncWorker(appContext, workerParams) {

  override fun getConflictResolver(): ConflictResolver = AcceptLocalConflictResolver

  override fun getDownloadWorkManager(): DownloadWorkManager =
    OpenSrpDownloadManager(
      syncParams = syncListenerManager.loadSyncParams(),
      context = appTimeStampContext,
    )

  override fun getFhirEngine(): FhirEngine = openSrpFhirEngine

  override fun getUploadStrategy(): UploadStrategy = UploadStrategy.AllChangesSquashedBundlePut

  override suspend fun doWork(): Result {
    val metaSyncResult = super.doWork()
    val allDocUploaded = performDocumentReferenceUpload()

    val retries = inputData.getInt("max_retires", 0)
    // In case it has failed or to be retried, we will send the original result
    if (metaSyncResult.javaClass === Result.success().javaClass) {
      return when (allDocUploaded) {
        true -> Result.success()
        false -> if (retries > runAttemptCount) Result.retry() else Result.failure(workDataOf(
          "error" to Exception::class.java.name,
          "reason" to "Failed to upload all files"
        ))
      }
    }

    return metaSyncResult
  }

  private suspend fun performDocumentReferenceUpload(): Boolean {
    val docReferences = openSrpFhirEngine.search<DocumentReference> {}

    Timber.i("Found ${docReferences.size} file to upload")

    return docReferences
      .map {
        val uriString = it.resource.getExtensionByUrl("http://hl7.org/fhir/StructureDefinition/file-location")?.value?.asStringValue()
        if (uriString.isNullOrBlank()) return@map it.resource to null

        it.resource to uriString.toUri()
      }
      .filter { it.second !== null }
      .map {

        val docReference = it.first
        val fileUri = it.second

        if (fileUri == null){
          Timber.w("File URI is null for document : ${docReference.logicalId}")
          return@map false;
        }

        try {
          Timber.i("Serializing document reference with logicalId: ${docReference.logicalId}")
          val docReferenceJson = FhirContext.forR4Cached().newJsonParser().encodeResourceToString(docReference as Resource)
          val docReferenceBytes = docReferenceJson.encodeToByteArray()

          val refBody = docReferenceBytes.toRequestBody("application/json".toMediaType())

          Timber.i("Inserting document reference with logicalId: ${docReference.logicalId}")

          fhirResourceService.insertResource(docReference.fhirType(), docReference.logicalId, refBody)

          Timber.i("Successfully inserted document reference with logicalId: ${docReference.logicalId}")

          val docContentType = docReference.content.first().attachment.contentType

          Timber.i("Retrieving file bytes for document with logicalId: ${docReference.logicalId}")
          // In case the file is missing, we will return true and no do anything
          val bytes = runCatching {
            applicationContext.contentResolver.openInputStream(fileUri)?.use { it.buffered().readBytes() }
          }.getOrNull() ?: run {
            Timber.w("Failed to retrieve file bytes for document with logicalId: ${docReference.logicalId}")
            return@map false
          }

          Timber.i("Successfully retrieved file bytes for document with logicalId: ${docReference.logicalId}")

          val body = bytes.toRequestBody(docContentType.toMediaType())

          Timber.i("Uploading file for document with logicalId: ${docReference.logicalId}")
          val response = fhirResourceService.uploadFile(
            docReference.fhirType(),
            docReference.logicalId,
            "DocumentReference.content.attachment",
            body)

          Timber.i("Received response for upload of document with logicalId: ${docReference.logicalId} - Success: ${response.isSuccessful}")

          if (response.isSuccessful.not()) {
            Timber.e("File upload failed for document with logicalId: ${docReference.logicalId} - Response code: ${response.code()} - Message: ${response.message()}")

            // When it is client error, it cannot be retried successfully ever,
            // so we are going to purge the data
            if (response.code() == 400 || response.code() == 422 || response.code() == 410) {
              Timber.i("Client error encountered. Purging document with logicalId: ${docReference.logicalId}")

              // Save the changes to document reference
              openSrpFhirEngine.purge(docReference.resourceType, docReference.logicalId, true)

              // When the content is uploaded, we will reset the description back to empty so that
              // it won't be attempted the next time
              applicationContext.contentResolver.delete(fileUri, null, null)
            }
            return@map false
          }

          Timber.i("Successfully uploaded document with logicalId: ${docReference.logicalId}")
          // Save the changes to document reference
          openSrpFhirEngine.purge(docReference.resourceType, docReference.logicalId, true)

          // When the content is uploaded, we will reset the description back to empty so that
          // it won't be attempted the next time
          applicationContext.contentResolver.delete(fileUri, null, null)

          true
        } catch (e: Exception) {
          Timber.e(e, "Exception during document upload: ${docReference.logicalId}")
          Timber.e(e, "Exception stackTrace: ${e.printStackTrace()}")
          false
        }

      }.all { it }
  }
}
