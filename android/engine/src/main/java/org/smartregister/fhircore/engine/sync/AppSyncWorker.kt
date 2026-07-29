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
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
import android.net.Uri
import android.provider.Settings
import androidx.annotation.VisibleForTesting
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import androidx.hilt.work.HiltWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import ca.uhn.fhir.context.FhirContext
import com.google.android.fhir.FhirEngine
import com.google.android.fhir.LocalChange
import com.google.android.fhir.datacapture.extensions.asStringValue
import com.google.android.fhir.get
import com.google.android.fhir.search.search
import com.google.android.fhir.sync.AcceptLocalConflictResolver
import com.google.android.fhir.sync.ConflictResolver
import com.google.android.fhir.sync.DownloadWorkManager
import com.google.android.fhir.sync.FhirSyncWorker
import com.google.android.fhir.sync.SyncJobStatus
import com.google.android.fhir.sync.SyncOperation
import com.google.android.fhir.sync.upload.UploadStrategy
import com.google.gson.Gson
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.hl7.fhir.r4.model.Basic
import org.hl7.fhir.r4.model.DateTimeType
import org.hl7.fhir.r4.model.DocumentReference
import org.hl7.fhir.r4.model.StringType
import org.smartregister.fhircore.engine.R
import org.smartregister.fhircore.engine.data.local.updateDocStatus.DocStatusRequest
import org.smartregister.fhircore.engine.data.local.updateDocStatus.ExtensionValue
import org.smartregister.fhircore.engine.data.local.updateDocStatus.JsonPatchOperation
import org.smartregister.fhircore.engine.data.remote.fhir.resource.FhirResourceService
import org.smartregister.fhircore.engine.domain.networkUtils.DocumentReferenceCaseType
import org.smartregister.fhircore.engine.domain.networkUtils.HttpConstants.HEADER_APPLICATION_JSON
import org.smartregister.fhircore.engine.domain.networkUtils.HttpConstants.UPLOAD_IMAGE_URL
import org.smartregister.fhircore.engine.domain.networkUtils.WorkerConstants.ADD_EXTENSION
import org.smartregister.fhircore.engine.domain.networkUtils.WorkerConstants.CONTENT_TYPE
import org.smartregister.fhircore.engine.domain.networkUtils.WorkerConstants.DOC_EXTENSION
import org.smartregister.fhircore.engine.domain.networkUtils.WorkerConstants.DOC_STATUS
import org.smartregister.fhircore.engine.domain.networkUtils.WorkerConstants.REPLACE
import org.smartregister.fhircore.engine.util.FeatureFlagUtil
import org.smartregister.fhircore.engine.util.SecureSharedPreference
import org.smartregister.fhircore.engine.util.SharedPreferencesHelper
import org.smartregister.fhircore.engine.util.UploadedDocumentReferenceLedger
import org.smartregister.fhircore.engine.util.analytics.AnalyticsLogger
import org.smartregister.fhircore.engine.util.analytics.AnalyticsLoggerEntryPoint
import org.smartregister.fhircore.engine.util.extension.logicalId
import org.smartregister.fhircore.engine.util.notificationHelper.CHANNEL_ID
import org.smartregister.fhircore.engine.util.notificationHelper.NOTIFICATION_ID
import org.smartregister.fhircore.engine.util.notificationHelper.createNotification
import retrofit2.HttpException
import timber.log.Timber
import java.io.FileNotFoundException
import java.net.HttpURLConnection.HTTP_NOT_FOUND
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
    private val sharedPreferencesHelper: SharedPreferencesHelper,
    private val gson: Gson,
    private val featureFlagUtil: FeatureFlagUtil,
    private val uploadedDocumentReferenceLedger: UploadedDocumentReferenceLedger,
) : FhirSyncWorker(appContext, workerParams) {
    private val analyticsLogger: AnalyticsLogger by lazy {
        EntryPointAccessors.fromApplication(
            applicationContext,
            AnalyticsLoggerEntryPoint::class.java,
        ).analyticsLogger()
    }

    companion object {
        val mutex = Mutex()
        val uploadImageMutex = Mutex()

        private val _isSyncRunning = MutableStateFlow(false)

        /**
         * Observable mirror of [mutex]'s locked state, for UI that must react to a sync starting or
         * finishing (a [Mutex] cannot be collected). [mutex] remains the authority for deciding
         * whether to start work; this flow only reports. It is flipped alongside every lock/unlock
         * in [doWork], so a killed process simply restarts it at `false`.
         */
        val isSyncRunning: StateFlow<Boolean> = _isSyncRunning.asStateFlow()

        /** Drives [isSyncRunning] without running a real worker. */
        @VisibleForTesting
        fun setSyncRunningForTest(running: Boolean) {
            _isSyncRunning.value = running
        }
        const val SYNC_METADATA_SYSTEM = "http://hl7.org/fhir/codes"
        const val SYNC_METADATA_CODE = "sync-metadata"
        const val LAST_SYNC_TIME_EXTENSION = "https://midas.iisc.ac.in/fhir/StructureDefinition/last-sync-date"
        const val DEVICE_ID_EXTENSION = "https://midas.iisc.ac.in/fhir/StructureDefinition/device-id"
        const val FLW_ID_EXTENSION = "https://midas.iisc.ac.in/fhir/StructureDefinition/flw-id"
        const val PENDING_IMAGES_EXTENSION = "https://midas.iisc.ac.in/fhir/StructureDefinition/pending-images"
        const val IMG_UPLOAD_ERROR_EXTENSION = "https://midas.iisc.ac.in/fhir/StructureDefinition/img-upload-error"
        const val IMG_UPLOAD_FAILED_PERMANENTLY_EXTENSION = "https://midas.iisc.ac.in/fhir/StructureDefinition/img-upload-failed-permanently"
        const val TIME_TAKEN_TOUPLOAD_IMG_EXTENSION = "https://midas.iisc.ac.in/fhir/StructureDefinition/time-taken-to-upload-img"

        /**
         * Whether the queued [changeTypes] for one resource would be uploaded as a write that
         * replaces the whole resource — and would therefore erase a binary written by
         * `$binary-access-write` before it.
         *
         * Mirrors `PerResourcePatchGenerator.mergeLocalChangesForSingleResource` +
         * `TransactionBundleGenerator.getGenerator(PUT, PATCH)`:
         * - a set beginning with `INSERT` squashes to one **PUT of the full resource** ⇒ unsafe;
         * - any `DELETE` uploads as `DELETE /DocumentReference/{id}` ⇒ unsafe;
         * - `UPDATE`-only squashes to a **JSON PATCH** over the changed paths. Ours only ever touch
         *   `/description`, `/docStatus` and `/extension`, never `content` ⇒ safe.
         *
         * Being precise here matters: treating every pending change as unsafe would defer the
         * images of every freshly submitted case until the next successful metadata sync, because
         * the DRAFT -> SUBMITTED flip always leaves an UPDATE behind.
         */
        @VisibleForTesting
        internal fun wouldOverwriteResourceOnUpload(changeTypes: List<LocalChange.Type>): Boolean =
            changeTypes.firstOrNull() == LocalChange.Type.INSERT ||
                changeTypes.contains(LocalChange.Type.DELETE)

        /**
         * Maps a failed metadata lookup onto [ServerDocumentLookup].
         *
         * The single rule: **only an HTTP 404 proves the server does not have the resource.**
         * Everything else — real 5xx, 401, and the synthetic 900/901/902/903 codes the app's own
         * OkHttp interceptors manufacture for offline, DNS and timeout conditions
         * (`NetworkModule.buildErrorResponse`) — leaves the server's state unknown, and every
         * caller must then do nothing rather than guess.
         */
        @VisibleForTesting
        internal fun classifyServerLookupFailure(
            documentId: String,
            error: Throwable,
        ): ServerDocumentLookup =
            if (error is HttpException && error.code() == HTTP_NOT_FOUND) {
                Timber.i("No DocumentReference on server for id $documentId: HTTP 404")
                ServerDocumentLookup.NotFound
            } else {
                Timber.i("DocumentReference $documentId state unknown: ${error.localizedMessage}")
                ServerDocumentLookup.Unavailable(error)
            }
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
            FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }

    override suspend fun doWork(): Result {
        Timber.i("AppSyncWorker Running sync worker")
        if (!mutex.tryLock()) {
            Timber.i("AppSyncWorker sync already running; skipping duplicate worker")
            return Result.success()
        }
        _isSyncRunning.value = true

        return try {
            Timber.i("AppSyncWorker Running within lock sync worker")
            // A worker can run in a fresh process where the AppSettingActivity bootstrap never ran,
            // leaving the config map empty. Reload configs first so loadSyncParams() (via
            // getDownloadWorkManager) does not fail with "Key application is missing in the map".
            syncListenerManager.configurationRegistry.loadConfigurationsIfNotLoaded(applicationContext)
            promoteToForegroundIfAllowed()
            val metaSyncResult = super.doWork()
            // Feature flags are an app-config Basic that regular sync params never download, so
            // refresh them here on every sync (never throws). This guarantees a server-side flag
            // change is visible after exactly one sync instead of waiting for the next login.
            featureFlagUtil.refreshFromServer()
            val allDocUploaded = performDocumentReferenceUpload(applicationContext, id.toString())

            val retries = inputData.getInt("max_retires", 0)
            if (metaSyncResult.javaClass === Result.success().javaClass) {
                when (allDocUploaded) {
                    true -> Result.success()
                    false -> if (retries > runAttemptCount) {
                        Result.retry()
                    } else {
                        Result.failure(
                            workDataOf(
                                "error" to Exception::class.java.name,
                                "reason" to "Failed to upload all files",
                            ),
                        )
                    }
                }
            } else {
                metaSyncResult
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Appsync worker")
            Result.failure(
                workDataOf(
                    "error" to e::class.java.name,
                    "reason" to e.message,
                ),
            )
        } finally {
            _isSyncRunning.value = false
            mutex.unlock()
        }
    }

    private suspend fun promoteToForegroundIfAllowed() {
        try {
            setForeground(getForegroundInfo())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e.isForegroundServiceStartRestriction()) {
                Timber.w(
                    e,
                    "Foreground sync notification could not be started; continuing sync as background work",
                )
            } else {
                throw e
            }
        }
    }

    private fun Throwable.isForegroundServiceStartRestriction(): Boolean {
        var throwable: Throwable? = this
        while (throwable != null) {
            if (throwable::class.java.name == "android.app.ForegroundServiceStartNotAllowedException" ||
                throwable.message?.contains("startForegroundService() not allowed", ignoreCase = true) == true ||
                throwable.message?.contains("Foreground service start not allowed", ignoreCase = true) == true
            ) {
                return true
            }
            throwable = throwable.cause
        }
        return false
    }

    private suspend fun performDocumentReferenceUpload(context: Context, workerId: String): Boolean {
        Timber.i("Starting version-aware document reference upload for worker: $workerId")

        val docReferences = openSrpFhirEngine.search<DocumentReference> {}.filter {
            it.resource.description != DocumentReferenceCaseType.DRAFT
        }.sortedByDescending { it.resource.date }
        val totalDocuments = docReferences.size
        var pendingDocuments = totalDocuments

        Timber.i("Found $totalDocuments document(s) to upload")

        // Surface the image-upload phase as real sync progress. The FHIR SDK only relays worker
        // progress that is serialized as a SyncJobStatus (keys "StateType"/"State"); the earlier
        // custom "progress" key was silently dropped by the SDK, which is why downstream progress
        // UIs froze at the ~99% left by the preceding metadata sync. The in-app bar is shown only
        // for the first-time sync, but a first-time sync can still include images (cases registered
        // before the initial sync ever succeeded), so emitting a real InProgress(UPLOAD) keeps that
        // bar tracking uploaded/total instead of freezing.
        if (totalDocuments > 0) {
            setProgress(
                buildImageUploadProgressData(uploaded = 0, total = totalDocuments),
            )
        }

        val notificationManager = createNotificationChannel(context)
        val notificationBuilder = createNotificationBuilder(context, totalDocuments, pendingDocuments)

        val result = docReferences.map {
            val uriString = it.resource.getExtensionByUrl(UPLOAD_IMAGE_URL)?.value?.asStringValue()
            if (uriString.isNullOrBlank()) {
                // S5: a DocumentReference with no file-location extension can never be uploaded and
                // is otherwise silently dropped from the success fold below. Surface it loudly so
                // these malformed drafts are visible instead of vanishing without a trace.
                Timber.e(Exception("Empty or null URI string for document: ${it.resource.logicalId} - $pendingDocuments pending"))
                analyticsLogger.capture(
                    AnalyticsLogger.Events.DOCUMENT_REFERENCE_MISSING_FILE_LOCATION,
                    mapOf(
                        AnalyticsLogger.Props.DOCUMENT_ID to it.resource.logicalId,
                        AnalyticsLogger.Props.PENDING_DOCUMENTS to pendingDocuments,
                        AnalyticsLogger.Props.ERROR_MESSAGE to
                            "DocumentReference has no file-location extension; cannot upload",
                    ),
                )
                return@map it.resource to null
            }
            it.resource to uriString.toUri()
        }.filter { it.second !== null }.map {
            val docReference = it.first
            val fileUri = it.second ?: return@map false

            try {
                val lookup = getDocumentReferenceMetaDataFromServer(docReference)
                val fileState = localFileState(fileUri)
                val pendingChanges =
                    openSrpFhirEngine.getLocalChanges(docReference.resourceType, docReference.logicalId)

                // The whole safety decision lives in decideDocumentAction (see its KDoc for the
                // invariants). This block only carries it out, so the branch table can be tested
                // without a WorkManager, a Hilt entry point or a FHIR server.
                //
                // Assigned to a val on purpose: as an *expression* the compiler enforces
                // exhaustiveness, so adding a DocumentAction is a build error here rather than a
                // silent fall-through into the upload path. (`when` used as a statement would only
                // warn, and this project does not build with -Werror.)
                val proceedToUpload: Boolean =
                    when (decideDocumentAction(lookup, fileState, pendingChanges.map { it.type })) {
                        DocumentAction.SkipServerStateUnknown -> {
                            Timber.w(
                                (lookup as? ServerDocumentLookup.Unavailable)?.error,
                                "Skipping DocumentReference ${docReference.logicalId} this run; server state unknown",
                            )
                            false
                        }

                        DocumentAction.SkipFileStateUnknown -> {
                            Timber.w(
                                (fileState as? LocalFileState.Unreadable)?.error,
                                "Skipping DocumentReference ${docReference.logicalId} this run; image file state unknown",
                            )
                            false
                        }

                        DocumentAction.FinalizeAsImageLostAndPurge -> {
                            // The server has the resource, so the QuestionnaireResponse URL resolves
                            // even though the image itself is lost. Record before purging: a process
                            // death between the two would leave an id that looks dangling at submit.
                            if (imageNotPresentOnDeviceFinalizeDocumentOnServer(docReference)) {
                                uploadedDocumentReferenceLedger.recordUploaded(docReference.logicalId)
                                openSrpFhirEngine.purge(docReference.resourceType, docReference.logicalId, true)
                            }
                            false
                        }

                        DocumentAction.ReportImageLostKeepRow -> {
                            Timber.e(
                                Exception(
                                    "Image file missing and DocumentReference ${docReference.logicalId} absent from server",
                                ),
                            )
                            analyticsLogger.capture(
                                AnalyticsLogger.Events.DOCUMENT_REFERENCE_IMAGE_FILE_LOST,
                                mapOf(
                                    AnalyticsLogger.Props.DOCUMENT_ID to docReference.logicalId,
                                    AnalyticsLogger.Props.PENDING_DOCUMENTS to pendingDocuments,
                                    AnalyticsLogger.Props.ERROR_MESSAGE to
                                        "Local image file is gone and the server has no DocumentReference for this id",
                                ),
                            )
                            false
                        }

                        DocumentAction.DeferPendingResourceWrite -> {
                            Timber.w(
                                "Deferring DocumentReference ${docReference.logicalId}: unsynced ${pendingChanges.map { it.type }} would be sent as a whole-resource write over the uploaded image",
                            )
                            false
                        }

                        DocumentAction.Upload -> true
                    }
                if (!proceedToUpload) return@map false

                val serverDocRef = (lookup as? ServerDocumentLookup.Found)?.documentReference

                uploadImageMutex.withLock {
                    Timber.i("Processing document reference with logicalId: ${docReference.logicalId}")

                    val success =
                        uploadDocumentReferenceVersionAware(
                            docReference,
                            fileUri,
                            context,
                            serverDocRef,
                            pendingDocuments,
                        )

                    if (success) {
                        // CRITICAL: Re-verify from server that image data exists before deleting locally.
                        // This prevents data loss if the upload appeared successful but data didn't persist.
                        val verified = getDocumentReferenceMetaDataFromServer(docReference)
                        val verifiedServerDoc = (verified as? ServerDocumentLookup.Found)?.documentReference
                        if (!verifiedServerDoc.hasImageDataOnServer()) {
                            Timber.e(Exception("SAFETY CHECK FAILED: Upload reported success but image NOT found on server for ${docReference.logicalId}. Keeping local image."))
                            return@withLock false
                        }

                        // Clean up local resources — server confirmed to have image data.
                        // Record the id first: once the row is purged, a later submission of a
                        // response carrying this id can only tell "uploaded" from "lost" via the
                        // ledger, and losing the row without the ledger entry is what makes a good
                        // attachment look like a dangling reference.
                        uploadedDocumentReferenceLedger.recordUploaded(docReference.logicalId)
                        openSrpFhirEngine.purge(
                            docReference.resourceType,
                            docReference.logicalId,
                            true
                        )
                        // The image is safely on the server; failing to delete the local JPEG is a
                        // storage leak, not data loss, and must not flip this document to "failed"
                        // and keep the whole sync retrying forever.
                        runCatching { applicationContext.contentResolver.delete(fileUri, null, null) }
                            .onFailure { Timber.w(it, "Could not delete uploaded image file $fileUri") }

                        pendingDocuments--
                        updateProgress(context, notificationBuilder, totalDocuments, pendingDocuments)
                        notificationManager.notify(NOTIFICATION_ID, notificationBuilder.build())
                        setProgress(
                            buildImageUploadProgressData(
                                uploaded = totalDocuments - pendingDocuments,
                                total = totalDocuments,
                            ),
                        )

                        Timber.i("Successfully completed version-aware upload for document: ${docReference.logicalId}")
                        true
                    } else {
                        Timber.e(Exception("Failed version-aware upload for document: ${docReference.logicalId}"))
                        false
                    }
                }
            } catch (e: CancellationException) {
                // WorkManager stopped us. Do not swallow this: the remaining documents must not be
                // processed on a cancelled scope, where a purge could still interleave with a
                // half-finished upload.
                throw e
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

    /**
     * Whether the captured image is still readable on this device.
     *
     * Tri-state for the same reason the server lookup is: "I could not read the file" is not "the
     * file is gone". Only a [FileNotFoundException] (or a resolver that returns no stream at all)
     * proves absence. Every other failure — a `SecurityException` after the process was recreated
     * and lost its URI grant, a transient I/O error, storage briefly unavailable — used to be
     * reported as absence, which sends the document down the "image permanently lost" path and
     * purges a row whose JPEG was sitting on disk the whole time.
     */
    internal sealed interface LocalFileState {
        /** The file is present and non-empty. */
        data object Present : LocalFileState

        /** The file definitively does not exist, or exists but is empty. */
        data object Absent : LocalFileState

        /** The file could not be read. Its existence is unknown; assume nothing. */
        data class Unreadable(val error: Throwable) : LocalFileState
    }

    private fun localFileState(uri: Uri?): LocalFileState {
        if (uri == null) return LocalFileState.Absent
        return try {
            val stream = applicationContext.contentResolver.openInputStream(uri)
                ?: return LocalFileState.Absent
            stream.use { if (it.available() > 0) LocalFileState.Present else LocalFileState.Absent }
        } catch (e: FileNotFoundException) {
            LocalFileState.Absent
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Could not determine whether the image file exists for uri: $uri")
            LocalFileState.Unreadable(e)
        }
    }

    private fun DocumentReference?.isCompleteOnServer(): Boolean =
        isFinalOnServer() && hasImageDataOnServer()

    private suspend fun uploadDocumentReferenceVersionAware(
        docReference: DocumentReference,
        fileUri: Uri,
        context: Context,
        serverDocRef: DocumentReference?,
        pendingDocuments: Int,
    ): Boolean {
        return runCatching {
            Timber.i("Starting version-aware upload for document: ${docReference.logicalId}")

            // 1. If the document is already fully uploaded and finalized, we're done.
            if (serverDocRef.isCompleteOnServer()) {
                Timber.i("Server already has complete DocumentReference: ${docReference.logicalId}. Skipping.")
                return@runCatching true
            }

            // 2. State correction: If local is 'final' but server is not, and image IS on server,
            // just patch the server status. Only safe to skip upload if image is confirmed on server.
            if (docReference.docStatus == DocumentReference.ReferredDocumentStatus.FINAL && !serverDocRef.isFinalOnServer()) {
                if (serverDocRef.hasImageDataOnServer()) {
                    Timber.i("Local DocumentReference is final and image exists on server, updating server status for ${docReference.logicalId}")
                    finalizeDocumentOnServer(docReference)
                    return@runCatching true
                } else {
                    Timber.w("Local DocumentReference is final but image is MISSING on server for ${docReference.logicalId}. Proceeding with upload.")
                }
            }

            // 3. Main Upload Flow: Execute steps based on server state.

            // Step 3a: Create the preliminary metadata record if it doesn't exist.
            //
            // Keyed on `serverDocRef == null` — which now means exactly "the server answered 404" —
            // and NOT on hasRecordOnServer(). That helper infers existence from `docStatus`, so a
            // resource the server returned without a readable docStatus (an `_elements` projection
            // that omits it, a resource written by an older client) would be treated as absent and
            // re-created by a metadata-only PUT that erases whatever binary it already held. If the
            // server handed us the resource, it exists; docStatus is Step 3c's problem.
            if (serverDocRef == null) {
                createMetadataRecordOnServer(docReference, fileUri, context)
            }

            // Step 3b: Upload the file's binary content if it's missing.
            if (!serverDocRef.hasImageDataOnServer()) {
                val timeTaken = uploadFileContent(docReference, fileUri, context, pendingDocuments)
                addUploadTimeTaken(docReference, timeTaken)
                // Track progress in-memory only. Do NOT call openSrpFhirEngine.update()
                // here — that queues a local change, and the subsequent super.doWork()
                // would PUT the full DocumentReference (without binary data) to the server,
                // potentially overwriting the image we just uploaded.
                //
                // The update() call this comment forbids was present until now, and it is how a
                // successfully uploaded image ended up unreadable: the queued change squashes with
                // the document's still-pending INSERT into a single PUT of the whole resource whose
                // content.attachment carries no data, wiping what $binary-access-write just stored.
                // Losing the local FINAL marker costs nothing — isCompleteOnServer() re-derives it
                // from the server on the next run.
                docReference.docStatus = DocumentReference.ReferredDocumentStatus.FINAL
            }

            // Step 3c: Finalize the document status on the server.
            if (!serverDocRef.isFinalOnServer()) {
                finalizeDocumentOnServer(docReference)
            }

            Timber.i("Version-aware upload completed successfully for: ${docReference.logicalId}")
            true // Success
        }.onFailure { e ->
            // runCatching swallows cancellation too; rethrow so a stopped worker actually stops
            // instead of reporting a "failed upload" and marching on to the next document.
            if (e is CancellationException) throw e
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
    private suspend fun uploadFileContent(
        docReference: DocumentReference,
        fileUri: Uri,
        context: Context,
        pendingDocuments: Int,
    ): Long {
        Timber.i("Step 2: Uploading file content for ${docReference.logicalId}")

        val bytes = context.contentResolver.openInputStream(fileUri)
            ?.use { it.buffered().readBytes() }
            ?: throw IllegalStateException("Failed to read file bytes for document: ${docReference.logicalId}")

        val contentType = docReference.content.firstOrNull()?.attachment?.contentType
        val body = bytes.toRequestBody(contentType?.toMediaType())

        val startTime = System.currentTimeMillis()
        val response = fhirResourceService.uploadFile(
            docReference.fhirType(),
            docReference.logicalId,
            "DocumentReference.content.attachment",
            body
        )
        val timeTaken = System.currentTimeMillis() - startTime

        if (!response.isSuccessful) {
            captureImageUploadCompleted(
                docReference = docReference,
                uploadDurationMs = timeTaken,
                responseCode = response.code(),
                pendingDocuments = pendingDocuments,
                bytesUploaded = bytes.size,
                errorMessage = response.message(),
            )
            // Annotate in memory only. Persisting this via openSrpFhirEngine.update() queued a
            // local change on the DocumentReference, which the trailing super.doWork() then PUT to
            // the server as a full resource with an empty attachment — erasing any image already
            // stored there. The failure is already carried by the analytics event above.
            docReference.addExtension().apply {
                url = IMG_UPLOAD_ERROR_EXTENSION
                setValue(StringType("Upload failed: ${response.code()} - ${response.message()}"))
            }

            // Handle specific cleanup logic for failed uploads. 410 Gone is the server stating the
            // resource was deleted, so the id can never resolve again; drop the local copy rather
            // than retrying forever, and make the permanent loss visible.
            if (response.code() in listOf(410)) {
                analyticsLogger.capture(
                    AnalyticsLogger.Events.DOCUMENT_REFERENCE_GONE_ON_SERVER,
                    mapOf(
                        AnalyticsLogger.Props.DOCUMENT_ID to docReference.logicalId,
                        AnalyticsLogger.Props.RESPONSE_CODE to response.code(),
                        AnalyticsLogger.Props.ERROR_MESSAGE to
                            "Server returned 410 Gone; purging local DocumentReference and image",
                    ),
                )
                openSrpFhirEngine.purge(docReference.resourceType, docReference.logicalId, true)
                context.contentResolver.delete(fileUri, null, null)
            }
            // Throw a specific exception to be caught by the top-level handler
            throw ImageUploadAPIException(
                documentId = docReference.logicalId,
                responseCode = response.code(),
                responseMessage = response.message(),
                pendingDocuments = pendingDocuments
            )
        }
        captureImageUploadCompleted(
            docReference = docReference,
            uploadDurationMs = timeTaken,
            responseCode = response.code(),
            pendingDocuments = pendingDocuments,
            bytesUploaded = bytes.size,
        )
        Timber.i("Step 2 completed: File content uploaded for ${docReference.logicalId}")
        return timeTaken
    }

    private fun captureImageUploadCompleted(
        docReference: DocumentReference,
        uploadDurationMs: Long,
        responseCode: Int,
        pendingDocuments: Int,
        bytesUploaded: Int,
        errorMessage: String? = null,
    ) {
        analyticsLogger.capture(
            AnalyticsLogger.Events.IMAGE_UPLOAD_COMPLETED,
            mapOf(
                AnalyticsLogger.Props.DOCUMENT_ID to docReference.logicalId,
                AnalyticsLogger.Props.UPLOAD_DURATION_MS to uploadDurationMs,
                AnalyticsLogger.Props.RESPONSE_CODE to responseCode,
                AnalyticsLogger.Props.PENDING_DOCUMENTS to pendingDocuments,
                AnalyticsLogger.Props.BYTES_UPLOADED to bytesUploaded,
                AnalyticsLogger.Props.ERROR_MESSAGE to errorMessage,
            ),
        )
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
     * Updates the DocumentReference status time taken to upload image using a JSON Patch.
     */
    private suspend fun addUploadTimeTaken(docReference: DocumentReference, timeTaken: Long) {
        Timber.i("addUploadTimeTaken ${docReference.logicalId}")

        val extensionValue = ExtensionValue(
            url = TIME_TAKEN_TOUPLOAD_IMG_EXTENSION,
            valueString = timeTaken.toString()
        )

        val patchOperations = listOf(JsonPatchOperation(ADD_EXTENSION, DOC_EXTENSION, listOf(extensionValue)))
        val patchJson = gson.toJson(patchOperations)

        Timber.d("Sending JSON Patch for ${docReference.logicalId}: $patchJson")

        val result = fhirResourceService.updateDocResource(
            docReference.fhirType(),
            docReference.logicalId,
            patchJson.toRequestBody(
                CONTENT_TYPE.toMediaTypeOrNull()
            )
        )

        Timber.i("Added Upload Time Taken: ${docReference.logicalId} status:${result.docStatus.name}")
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

        val patchOperations = listOf(JsonPatchOperation(ADD_EXTENSION, DOC_EXTENSION, listOf(extensionValue)),
            JsonPatchOperation(REPLACE, DOC_STATUS, DocumentReference.ReferredDocumentStatus.FINAL.name.lowercase()))
        val patchJson = gson.toJson(patchOperations)

        Timber.d("Sending JSON Patch for ${docReference.logicalId}: $patchJson")

        val result = fhirResourceService.updateDocResource(
            docReference.fhirType(),
            docReference.logicalId,
            patchJson.toRequestBody(
                CONTENT_TYPE.toMediaTypeOrNull()
            )
        )

        Timber.i("Step 3 updateDocResource: ${docReference.logicalId} status:${result.docStatus.name}")
        return result.docStatus.name.lowercase() == DocumentReference.ReferredDocumentStatus.FINAL.name.lowercase()

    }

    /**
     * The result of asking the server about a [DocumentReference].
     *
     * The distinction between [NotFound] and [Unavailable] is load-bearing. This lookup used to
     * collapse every failure to `null`, so a DNS failure, a 401 or a timeout all read as "the
     * server does not have this document". Everything downstream branches on that answer, and each
     * branch does damage when the answer is wrong:
     * - `createMetadataRecordOnServer` PUTs a metadata-only copy of the resource, erasing an image
     *   the server may already hold (`$binary-access-read` then 404s on a resource that exists);
     * - the missing-file path PATCHes and then force-purges the local row, discarding a pending
     *   INSERT local change and stranding the id in the QuestionnaireResponse forever.
     *
     * When the server cannot be reached the only correct action is to do nothing and retry.
     */
    internal sealed interface ServerDocumentLookup {
        /** The server returned the resource. */
        data class Found(val documentReference: DocumentReference) : ServerDocumentLookup

        /** The server answered, authoritatively, that no such resource exists (HTTP 404). */
        data object NotFound : ServerDocumentLookup

        /** The server could not be asked. Its state is unknown; assume nothing. */
        data class Unavailable(val error: Throwable) : ServerDocumentLookup
    }

    private suspend fun getDocumentReferenceMetaDataFromServer(
        docReference: DocumentReference,
    ): ServerDocumentLookup {
        return try {
            ServerDocumentLookup.Found(
                fhirResourceService.getDocumentReferenceMeta(docReference.logicalId),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            classifyServerLookupFailure(docReference.logicalId, e)
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
            val resourceId = sharedPreferencesHelper.getSyncMetadataResourceId(deviceId)

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

    /**
     * Serializes an image-upload [SyncJobStatus.InProgress] into the WorkManager progress [Data]
     * format that the FHIR SDK ([com.google.android.fhir.sync.Sync.getWorkerInfo]) understands, so
     * the emission is relayed to the registered [OnSyncListener]s as
     * [com.google.android.fhir.sync.CurrentSyncJobStatus.Running] and drives the in-app sync
     * progress bar. The keys/serialization mirror `FhirSyncWorker.buildWorkData`.
     */
    private fun buildImageUploadProgressData(uploaded: Int, total: Int): Data {
        val status = SyncJobStatus.InProgress(SyncOperation.UPLOAD, total = total, completed = uploaded)
        return workDataOf(
            "StateType" to status::class.java.name,
            "State" to gson.toJson(status),
        )
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

/**
 * Checks if the DocumentReference on the server has been marked as 'final'.
 *
 * File-level and `internal` so the per-document decision table below can be unit tested without
 * constructing a worker.
 */
internal fun DocumentReference?.isFinalOnServer(): Boolean =
    this?.docStatus == DocumentReference.ReferredDocumentStatus.FINAL

/**
 * Checks if the DocumentReference on the server has an attachment with a non-zero size.
 *
 * Deliberately strict — `size > 0` only, never `hasData() || hasUrl()`. A false positive here
 * causes a purge with no image on the server, which is permanent loss; a false negative only costs
 * a redundant re-upload.
 */
internal fun DocumentReference?.hasImageDataOnServer(): Boolean =
    this?.content?.any { (it.attachment?.size ?: 0) > 0 } == true

/**
 * What the sync worker should do with one DocumentReference this run.
 *
 * Extracted from `performDocumentReferenceUpload` so the safety invariants I1-I3 are expressed as
 * one exhaustively testable decision rather than as nested branches inside a coroutine that needs a
 * WorkManager, a Hilt entry point and a FHIR server to exercise.
 */
internal sealed interface DocumentAction {
    /** The server could not be asked. Touch nothing; retry next run. (I1) */
    data object SkipServerStateUnknown : DocumentAction

    /** The local JPEG could not be read. Touch nothing; retry next run. (I1) */
    data object SkipFileStateUnknown : DocumentAction

    /**
     * The JPEG is gone and the server holds the resource with no image. The reference still
     * resolves, so mark the image permanently failed, record it, and stop carrying the row. (I2/I4)
     */
    data object FinalizeAsImageLostAndPurge : DocumentAction

    /**
     * The JPEG is gone and the server has no resource for this id. Purging would strand the id in
     * an already-submitted response forever, so keep the row and report the loss.
     */
    data object ReportImageLostKeepRow : DocumentAction

    /** A queued whole-resource write would land after the binary and erase it. Defer. (I3) */
    data object DeferPendingResourceWrite : DocumentAction

    /** Safe to run the upload state machine. */
    data object Upload : DocumentAction
}

/**
 * The per-document decision. Order is load-bearing: an unknown answer must short-circuit before any
 * branch that writes, patches or purges.
 *
 * @param lookup what `GET /DocumentReference/{id}` told us — [ServerDocumentLookup.NotFound] only
 *   when the server actually answered 404.
 * @param fileState whether the backing JPEG is readable on this device.
 * @param pendingChangeTypes queued `LocalChange` types for this resource, in order.
 */
internal fun decideDocumentAction(
    lookup: AppSyncWorker.ServerDocumentLookup,
    fileState: AppSyncWorker.LocalFileState,
    pendingChangeTypes: List<LocalChange.Type>,
): DocumentAction {
    if (lookup is AppSyncWorker.ServerDocumentLookup.Unavailable) {
        return DocumentAction.SkipServerStateUnknown
    }
    if (fileState is AppSyncWorker.LocalFileState.Unreadable) {
        return DocumentAction.SkipFileStateUnknown
    }
    val serverDocRef = (lookup as? AppSyncWorker.ServerDocumentLookup.Found)?.documentReference
    if (fileState is AppSyncWorker.LocalFileState.Absent && !serverDocRef.hasImageDataOnServer()) {
        // A file-less document whose image IS already on the server is not handled here: it falls
        // through to Upload, where the state machine finalizes and the caller purges. Only the
        // "no image anywhere" case is terminal.
        return if (serverDocRef != null) {
            DocumentAction.FinalizeAsImageLostAndPurge
        } else {
            DocumentAction.ReportImageLostKeepRow
        }
    }
    if (AppSyncWorker.wouldOverwriteResourceOnUpload(pendingChangeTypes)) {
        return DocumentAction.DeferPendingResourceWrite
    }
    return DocumentAction.Upload
}
