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
import timber.log.Timber
import java.util.Date

/**
 * The app's one and only sync worker. A run does two things, in this order:
 *
 * 1. **Metadata sync** — [FhirSyncWorker.doWork] uploads every pending local change and downloads
 *    the practitioner's data. Screening photos are not part of this. A DocumentReference travels
 *    here as ordinary FHIR, carrying no image bytes.
 * 2. **Image upload** — [performDocumentReferenceUpload] then walks the DocumentReferences of
 *    submitted cases and pushes each photo up with HAPI's `$binary-access-write` operation.
 *
 * Knowing the split explains most of the care in this file. A submitted QuestionnaireResponse
 * already contains the image URL, built from the DocumentReference id at capture time. Phase 1
 * publishes that URL; phase 2 is what makes it resolve. In between, the id points at nothing — which
 * is fine and temporary, *provided the local row survives to be retried*. So the rule throughout is:
 * never delete the local row or the local JPEG until the server is known to hold the image.
 *
 * Only one run happens at a time; a second worker sees [mutex] held and returns success immediately.
 */
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
         * Observable mirror of [mutex]'s state, for UI that reacts to a sync starting or finishing
         * (a [Mutex] cannot be collected). [mutex] is still the authority on whether to start work;
         * this only reports. It is flipped alongside every lock and unlock in [doWork], so a killed
         * process simply comes back with it `false`.
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
        Timber.d("AppSyncWorker Running sync worker")
        if (!mutex.tryLock()) {
            Timber.d("AppSyncWorker sync already running; skipping duplicate worker")
            return Result.success()
        }
        _isSyncRunning.value = true

        return try {
            Timber.d("AppSyncWorker Running within lock sync worker")
            // A worker can start in a fresh process where the AppSettingActivity bootstrap never
            // ran, leaving the config map empty. Load configs first, or loadSyncParams() (called via
            // getDownloadWorkManager) fails with "Key application is missing in the map".
            syncListenerManager.configurationRegistry.loadConfigurationsIfNotLoaded(applicationContext)
            promoteToForegroundIfAllowed()
            val metaSyncResult = super.doWork()
            // Feature flags live in an app-config Basic that the normal sync params never download,
            // so refresh them here on every run (this never throws). A flag changed on the server is
            // then live after one sync instead of waiting for the next login.
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
            // This catch spans config loading, the metadata sync, the flag refresh and the image
            // pass, so name the exception type — otherwise the report says a sync failed without
            // saying which part of it.
            Timber.e(e, "AppSyncWorker run failed before completing: ${e::class.java.simpleName}")
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

    /**
     * Shows the sync notification if the OS will allow it, and carries on quietly if it will not.
     * Background restrictions are a device setting, not an error worth failing the sync over.
     */
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

    /**
     * Whether this is the OS refusing a foreground service start. Matched by name and message
     * because the exception class only exists from API 31 and can arrive wrapped.
     */
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

    /**
     * Phase 2 of a sync: get the photo of every submitted DocumentReference onto the server.
     *
     * Drafts are left out — their photos belong to a case the FLW has not finished yet. For each of
     * the rest, newest first:
     *
     * 1. ask the server what it already has ([getDocumentReferenceMetaDataFromServer]),
     * 2. check whether the local JPEG is readable ([localFileState]),
     * 3. read any local changes still queued for the resource,
     * 4. hand all three to [decideDocumentAction] and carry out the outcome it picks.
     *
     * Every document ends up in exactly one [DocumentUploadTally] counter, and the run publishes a
     * single summary event before returning.
     *
     * @return true only if every document is now on the server. The caller turns false into a retry.
     */
    private suspend fun performDocumentReferenceUpload(context: Context, workerId: String): Boolean {
        Timber.d("Starting version-aware document reference upload for worker: $workerId")

        val docReferences = openSrpFhirEngine.search<DocumentReference> {}.filter {
            it.resource.description != DocumentReferenceCaseType.DRAFT
        }.sortedByDescending { it.resource.date }
        val totalDocuments = docReferences.size
        var pendingDocuments = totalDocuments
        val tally = DocumentUploadTally()

        Timber.d("Found $totalDocuments document(s) to upload")

        // Report image upload as real sync progress. The SDK only relays worker progress that is
        // serialized as a SyncJobStatus (the "StateType"/"State" keys), so a custom progress key is
        // dropped and progress UIs freeze wherever the metadata sync left them. The in-app bar only
        // shows for a first-time sync, but a first sync can still carry photos — cases registered
        // before it ever succeeded — so emit a real InProgress(UPLOAD) to keep it moving.
        if (totalDocuments > 0) {
            setProgress(
                buildImageUploadProgressData(uploaded = 0, total = totalDocuments),
            )
        }

        val notificationManager = createNotificationChannel(context)
        val notificationBuilder = createNotificationBuilder(context, totalDocuments, pendingDocuments)

        // try/finally so the summary is published even when the worker is stopped half way through —
        // those runs are the interesting ones. `passCompleted` records which of the two happened, so
        // a partial tally is never mistaken for a complete one.
        var passCompleted = false
        try {
        val result = docReferences.map {
            val uriString = it.resource.getExtensionByUrl(UPLOAD_IMAGE_URL)?.value?.asStringValue()
            if (uriString.isNullOrBlank()) {
                // Nothing to upload and never will be. Report it, because it would otherwise drop
                // out of the fold below without leaving any trace.
                tally.missingFileLocation++
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

            // Stop at a document boundary. WorkManager sets isStopped before it cancels the scope,
            // so noticing it here avoids being killed mid-upload or mid-purge — and leaves the tally
            // intact, which a thrown CancellationException would not.
            if (isStopped) {
                tally.stoppedBeforeAttempt++
                tally.recordStuck(docReference.logicalId)
                return@map false
            }

            try {
                val lookup = getDocumentReferenceMetaDataFromServer(docReference)
                val fileState = applicationContext.contentResolver.localFileState(fileUri)
                val pendingChanges =
                    openSrpFhirEngine.getLocalChanges(docReference.resourceType, docReference.logicalId)

                // decideDocumentAction owns the whole branch table so it can be unit-tested on its
                // own; this block only carries out whichever outcome it returned.
                //
                // Written as an expression, not a statement, so the compiler enforces
                // exhaustiveness: a new DocumentAction then breaks the build here instead of
                // quietly falling through to the upload path. (A `when` statement would only warn.)
                val proceedToUpload: Boolean =
                    when (decideDocumentAction(lookup, fileState, pendingChanges.map { it.type })) {
                        // Skips and defers are routine — an offline device, or one mid-submission,
                        // hits them for every pending photo. Debug level, counted in the summary,
                        // rather than a log line per document per sync.
                        DocumentAction.SkipServerStateUnknown -> {
                            tally.skippedServerUnknown++
                            tally.recordStuck(docReference.logicalId)
                            Timber.d(
                                (lookup as? ServerDocumentLookup.Unavailable)?.error,
                                "Skipping DocumentReference ${docReference.logicalId} this run; server state unknown",
                            )
                            false
                        }

                        DocumentAction.SkipFileStateUnknown -> {
                            tally.skippedFileUnknown++
                            tally.recordStuck(docReference.logicalId)
                            Timber.d(
                                (fileState as? LocalFileState.Unreadable)?.error,
                                "Skipping DocumentReference ${docReference.logicalId} this run; image file state unknown",
                            )
                            false
                        }

                        DocumentAction.FinalizeAsImageLostAndPurge -> {
                            // The image is gone but the server holds the resource, so the URL in the
                            // submitted response still resolves. Note the id in the ledger before
                            // purging: a process death between the two would otherwise leave that id
                            // looking dangling at the next submission.
                            tally.finalizedImageLost++
                            if (imageNotPresentOnDeviceFinalizeDocumentOnServer(docReference)) {
                                uploadedDocumentReferenceLedger.recordUploaded(docReference.logicalId)
                                openSrpFhirEngine.purge(docReference.resourceType, docReference.logicalId, true)
                            }
                            false
                        }

                        DocumentAction.ReportImageLostKeepRow -> {
                            // A photo that can never be recovered. Reported twice on purpose: an
                            // exception so it reaches error tracking and can be alerted on, and an
                            // event so it can be looked up by document id.
                            tally.imageLostNoServerRecord++
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
                            tally.deferredPendingWrite++
                            tally.recordStuck(docReference.logicalId)
                            Timber.d(
                                "Deferring DocumentReference ${docReference.logicalId}: unsynced ${pendingChanges.map { it.type }} would be sent as a whole-resource write over the uploaded image",
                            )
                            false
                        }

                        DocumentAction.Upload -> true
                    }
                if (!proceedToUpload) return@map false

                val serverDocRef = (lookup as? ServerDocumentLookup.Found)?.documentReference

                uploadImageMutex.withLock {
                    Timber.d("Processing document reference with logicalId: ${docReference.logicalId}")

                    val success =
                        uploadDocumentReferenceVersionAware(
                            docReference,
                            fileUri,
                            context,
                            serverDocRef,
                            pendingDocuments,
                        )

                    if (success) {
                        // Read the document back before touching anything local. A write that
                        // reported success but cannot be confirmed is not good enough to delete the
                        // only remaining copy of the photo on.
                        val verified = getDocumentReferenceMetaDataFromServer(docReference)
                        val verifiedServerDoc = (verified as? ServerDocumentLookup.Found)?.documentReference
                        if (!verifiedServerDoc.hasImageDataOnServer()) {
                            // Either the server did not keep the bytes, or this second GET failed.
                            // Both mean the same thing: keep the local image and try again. Not data
                            // loss — but a document that lands here on every run is stuck, so record
                            // the id somewhere it can be queried.
                            tally.failed++
                            tally.recordStuck(docReference.logicalId)
                            Timber.e(Exception("SAFETY CHECK FAILED: Upload reported success but image NOT found on server for ${docReference.logicalId}. Keeping local image."))
                            analyticsLogger.capture(
                                AnalyticsLogger.Events.DOCUMENT_REFERENCE_UPLOAD_UNVERIFIED,
                                mapOf(
                                    AnalyticsLogger.Props.DOCUMENT_ID to docReference.logicalId,
                                    AnalyticsLogger.Props.PENDING_DOCUMENTS to pendingDocuments,
                                    AnalyticsLogger.Props.ERROR_MESSAGE to
                                        "Upload reported success but no image data on server; keeping local copy",
                                ),
                            )
                            return@withLock false
                        }

                        // Confirmed on the server, so the local copies can go. Ledger first: once
                        // the row is purged, a later submission carrying this id can only tell
                        // "uploaded" from "lost" by looking it up there.
                        uploadedDocumentReferenceLedger.recordUploaded(docReference.logicalId)
                        openSrpFhirEngine.purge(
                            docReference.resourceType,
                            docReference.logicalId,
                            true
                        )
                        // A JPEG we fail to delete is wasted space, not lost data. It must not mark
                        // this document failed and send the whole sync back round to retry it.
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

                        tally.uploaded++
                        Timber.d("Successfully completed version-aware upload for document: ${docReference.logicalId}")
                        true
                    } else {
                        // uploadDocumentReferenceVersionAware already reported the cause, with its
                        // stack trace and the step it failed at. Nothing to add here.
                        tally.failed++
                        tally.recordStuck(docReference.logicalId)
                        Timber.d("Failed version-aware upload for document: ${docReference.logicalId}")
                        false
                    }
                }
            } catch (e: CancellationException) {
                // The worker is being stopped. Let it stop: the remaining documents must not be
                // processed on a dead scope, where a purge could interleave with a half-finished
                // upload.
                throw e
            } catch (e: Exception) {
                tally.failed++
                tally.recordStuck(docReference.logicalId)
                Timber.e(e, "Exception during version-aware upload for document: ${docReference.logicalId} - $pendingDocuments pending")
                false
            }
        }.all { it }
        // Every document now has an outcome, so the tally is complete and its sum can be checked.
        // Whatever fails below cannot make it wrong.
        passCompleted = true

        updateLastSyncDate(pendingDocuments)
        super.doWork()

        updateNotification(context, notificationManager, notificationBuilder, result)
        Timber.d("Finished version-aware document reference upload for worker: $workerId")
        return result
        } finally {
            reportUploadRun(tally, totalDocuments, passCompleted)
        }
    }

    /**
     * Publishes the single event describing this pass: how many photos went up, and what happened to
     * the ones that did not.
     *
     * Called from a `finally`, which sets two constraints. It runs on a possibly-cancelled coroutine
     * — safe, because every call it makes is non-suspending — and it must never throw, or a
     * telemetry failure would replace whatever actually stopped the run.
     *
     * Sends nothing when the device had no photos to upload; otherwise every idle sync would publish
     * a row of zeroes.
     *
     * @param passCompleted false when the pass was cut short. Those runs are still reported — a
     *   worker that keeps getting stopped is exactly what you want to see — but flagged, so a
     *   partial tally is never read as a whole one.
     */
    private fun reportUploadRun(
        tally: DocumentUploadTally,
        totalDocuments: Int,
        passCompleted: Boolean,
    ) {
        if (totalDocuments == 0) return

        runCatching {
            val unprocessed = (totalDocuments - tally.accountedFor()).coerceAtLeast(0)
            analyticsLogger.capture(
                AnalyticsLogger.Events.DOCUMENT_UPLOAD_RUN_COMPLETED,
                mapOf(
                    AnalyticsLogger.Props.TOTAL_DOCUMENTS to totalDocuments,
                    AnalyticsLogger.Props.UPLOADED_DOCUMENTS to tally.uploaded,
                    AnalyticsLogger.Props.FAILED_DOCUMENTS to tally.failed,
                    AnalyticsLogger.Props.SKIPPED_SERVER_UNKNOWN to tally.skippedServerUnknown,
                    AnalyticsLogger.Props.SKIPPED_FILE_UNKNOWN to tally.skippedFileUnknown,
                    AnalyticsLogger.Props.DEFERRED_PENDING_WRITE to tally.deferredPendingWrite,
                    AnalyticsLogger.Props.FINALIZED_IMAGE_LOST to tally.finalizedImageLost,
                    AnalyticsLogger.Props.IMAGE_LOST_NO_SERVER_RECORD to tally.imageLostNoServerRecord,
                    AnalyticsLogger.Props.MISSING_FILE_LOCATION to tally.missingFileLocation,
                    AnalyticsLogger.Props.STOPPED_BEFORE_ATTEMPT to tally.stoppedBeforeAttempt,
                    AnalyticsLogger.Props.RUN_COMPLETED to passCompleted,
                    AnalyticsLogger.Props.UNPROCESSED_DOCUMENTS to unprocessed,
                    AnalyticsLogger.Props.STUCK_DOCUMENT_IDS to tally.stuckDocumentIds(),
                ),
            )

            // The counters should partition the documents. If they stop doing so, some outcome is no
            // longer being counted and every query built on this event quietly under-reports — so
            // say so loudly. Only meaningful on a completed pass; an interrupted one leaves
            // documents uncounted by design.
            if (passCompleted && tally.accountedFor() != totalDocuments) {
                Timber.e(
                    Exception(
                        "Document upload tally mismatch: counted ${tally.accountedFor()} of $totalDocuments documents",
                    ),
                )
            }
        }.onFailure {
            // Swallowed, and debug only: this runs while unwinding, where anything thrown would
            // hide the real cause.
            Timber.d(it, "Could not report document upload run")
        }
    }

    /**
     * Brings one DocumentReference fully up to date on the server: resource created, bytes uploaded,
     * status final.
     *
     * Each step is skipped when [serverDocRef] shows the server already has that part, which is what
     * makes the upload resumable — an attempt interrupted after the bytes landed but before the
     * status was patched picks up at step 3 next run instead of sending the photo again.
     *
     * @param serverDocRef the server's copy of the document, or null if the server answered 404.
     * @return true if the document is complete on the server by the time this returns.
     */
    private suspend fun uploadDocumentReferenceVersionAware(
        docReference: DocumentReference,
        fileUri: Uri,
        context: Context,
        serverDocRef: DocumentReference?,
        pendingDocuments: Int,
    ): Boolean {
        // Set immediately before each call that can throw, so a failure names the step that broke
        // rather than the one we were about to try.
        var stage = UploadStage.STARTING
        return runCatching {
            Timber.d("Starting version-aware upload for document: ${docReference.logicalId}")

            // 1. Already complete on the server — nothing to do.
            if (serverDocRef.isCompleteOnServer()) {
                Timber.d("Server already has complete DocumentReference: ${docReference.logicalId}. Skipping.")
                return@runCatching true
            }

            // 2. Local says final, server does not. If the image is confirmed up there, all that is
            // missing is the status, so patch it and skip the upload entirely.
            if (docReference.docStatus == DocumentReference.ReferredDocumentStatus.FINAL && !serverDocRef.isFinalOnServer()) {
                if (serverDocRef.hasImageDataOnServer()) {
                    Timber.d("Local DocumentReference is final and image exists on server, updating server status for ${docReference.logicalId}")
                    stage = UploadStage.FINALIZING
                    finalizeDocumentOnServer(docReference)
                    return@runCatching true
                } else {
                    Timber.w("Local DocumentReference is final but image is MISSING on server for ${docReference.logicalId}. Proceeding with upload.")
                }
            }

            // 3. Otherwise work through the steps the server is still missing.

            // Step 3a: create the resource if the server does not have it.
            //
            // Keyed on `serverDocRef == null`, which means the server answered 404 — deliberately
            // not on docStatus. If the server handed us the resource then it exists, whatever its
            // status says, and re-creating it with a metadata-only PUT would wipe the image it
            // already holds. Its status is step 3c's problem.
            if (serverDocRef == null) {
                stage = UploadStage.CREATING_METADATA
                createMetadataRecordOnServer(docReference, fileUri, context)
            }

            // Step 3b: send the bytes if the server has no image yet.
            if (!serverDocRef.hasImageDataOnServer()) {
                stage = UploadStage.UPLOADING_BINARY
                val timeTaken = uploadFileContent(docReference, fileUri, context, pendingDocuments)
                stage = UploadStage.RECORDING_DURATION
                addUploadTimeTaken(docReference, timeTaken)
                // In memory only. Do NOT persist this with openSrpFhirEngine.update(): that queues a
                // local change, and the metadata sync at the end of the run would then PUT the whole
                // resource — attachment empty — straight over the image just uploaded. Not saving it
                // costs nothing, because isCompleteOnServer() reads the status back from the server
                // on the next run.
                docReference.docStatus = DocumentReference.ReferredDocumentStatus.FINAL
            }

            // Step 3c: mark the document final on the server.
            if (!serverDocRef.isFinalOnServer()) {
                stage = UploadStage.FINALIZING
                finalizeDocumentOnServer(docReference)
            }

            Timber.d("Version-aware upload completed successfully for: ${docReference.logicalId}")
            true // Success
        }.onFailure { e ->
            // runCatching catches cancellation too, so rethrow it — a stopped worker should stop,
            // not record a failed upload and move on to the next document.
            if (e is CancellationException) throw e
            Timber.e(e, "Version-aware upload failed for document: ${docReference.logicalId} at stage ${stage.label}")
            // Reported twice, for two different jobs: the exception carries the stack trace that
            // error tracking groups on, the event carries the dimensions you can query — which step
            // is failing, for whom, and whether it is getting worse.
            analyticsLogger.capture(
                AnalyticsLogger.Events.DOCUMENT_UPLOAD_ATTEMPT_FAILED,
                mapOf(
                    AnalyticsLogger.Props.DOCUMENT_ID to docReference.logicalId,
                    AnalyticsLogger.Props.UPLOAD_STAGE to stage.label,
                    AnalyticsLogger.Props.ERROR_TYPE to e::class.java.simpleName,
                    AnalyticsLogger.Props.ERROR_MESSAGE to e.message,
                    AnalyticsLogger.Props.RESPONSE_CODE to
                        (e as? ImageUploadAPIException)?.responseCode,
                    AnalyticsLogger.Props.PENDING_DOCUMENTS to pendingDocuments,
                ),
            )
        }.getOrDefault(false)
    }

    /**
     * Step 1: create the resource on the server, preliminary and carrying no image bytes.
     *
     * Throws if the local file is unreadable, so we never publish a record for a photo we cannot
     * actually send.
     */
    private suspend fun createMetadataRecordOnServer(docReference: DocumentReference, fileUri: Uri, context: Context) {
        Timber.d("Step 1: Creating metadata record for ${docReference.logicalId}")

        // Ensure the local file exists before creating a server record for it.
        val fileExists = context.contentResolver.openInputStream(fileUri)?.use { it.available() > 0 } ?: false
        if (!fileExists) {
            throw IllegalStateException("File does not exist or is empty for document: ${docReference.logicalId}")
        }

        val metadataDocReference = docReference.copy().apply {
            docStatus = DocumentReference.ReferredDocumentStatus.PRELIMINARY
            content.forEach { it.attachment?.data = null } // The bytes go up separately, in step 2.
        }

        val docReferenceJson = FhirContext.forR4Cached().newJsonParser().encodeResourceToString(metadataDocReference)
        val requestBody = docReferenceJson.encodeToByteArray().toRequestBody(HEADER_APPLICATION_JSON.toMediaType())

        fhirResourceService.insertResource(docReference.fhirType(), docReference.logicalId, requestBody)
        Timber.d("Step 1 completed: Metadata record created for ${docReference.logicalId}")
    }

    /**
     * Step 2: send the image bytes to `$binary-access-write`.
     *
     * @return how long the upload took, in milliseconds.
     * @throws ImageUploadAPIException if the server answered with a non-2xx status.
     */
    private suspend fun uploadFileContent(
        docReference: DocumentReference,
        fileUri: Uri,
        context: Context,
        pendingDocuments: Int,
    ): Long {
        Timber.d("Step 2: Uploading file content for ${docReference.logicalId}")

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
            // In memory only, for the same reason as the docStatus in step 3b: persisting this would
            // queue a local change that the next metadata sync PUTs over the image. The failure is
            // already carried by the event above.
            docReference.addExtension().apply {
                url = IMG_UPLOAD_ERROR_EXTENSION
                setValue(StringType("Upload failed: ${response.code()} - ${response.message()}"))
            }

            // 410 Gone is the server saying the resource was deleted, so this id will never resolve
            // again. Retrying is pointless — drop the local copy and record the loss.
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
            // Carries the response code out to the caller, which reports it as the failed stage.
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
        Timber.d("Step 2 completed: File content uploaded for ${docReference.logicalId}")
        return timeTaken
    }

    /**
     * Records the outcome of one byte upload, successful or not. This is the per-photo timing and
     * size data — how slow uploads are in the field, and how big the photos being sent are.
     */
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

    /** Step 3: patch docStatus to final, marking the document complete on the server. */
    private suspend fun finalizeDocumentOnServer(docReference: DocumentReference) {
        Timber.d("Step 3: Finalizing document status for ${docReference.logicalId}")
        fhirResourceService.updateResource(
            docReference.fhirType(),
            docReference.logicalId,
            gson.toJson(listOf(DocStatusRequest(REPLACE, DOC_STATUS, DocumentReference.ReferredDocumentStatus.FINAL.name.lowercase()))).toRequestBody(
                CONTENT_TYPE.toMediaTypeOrNull()
            )
        )
        Timber.d("Step 3 completed: Document status finalized for ${docReference.logicalId}")
    }

    /** Records how long the upload took, as an extension on the server's copy. */
    private suspend fun addUploadTimeTaken(docReference: DocumentReference, timeTaken: Long) {
        Timber.d("addUploadTimeTaken ${docReference.logicalId}")

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

        Timber.d("Added Upload Time Taken: ${docReference.logicalId} status:${result.docStatus.name}")
    }

    /**
     * Marks the server's copy as permanently missing its image, and final. Used when the photo is
     * gone from the device and cannot be recovered — the reference stays resolvable, it just says
     * the image never arrived.
     *
     * @return true if the server confirmed the status change. The caller only purges the local row
     *   when it did, so a failed patch leaves the document to be retried.
     */
    private suspend fun imageNotPresentOnDeviceFinalizeDocumentOnServer(docReference: DocumentReference) : Boolean {
        Timber.d("Finalizing image FAILED for ${docReference.logicalId}")

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

        Timber.d("Step 3 updateDocResource: ${docReference.logicalId} status:${result.docStatus.name}")
        return result.docStatus.name.lowercase() == DocumentReference.ReferredDocumentStatus.FINAL.name.lowercase()

    }

    /** Fetches the server's copy of a document, or says why it could not. */
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


    private fun getDeviceId(): String {
        return Settings.Secure.getString(
            getApplicationContext().getContentResolver(),
            Settings.Secure.ANDROID_ID
        )
    }

    /**
     * Writes a per-device Basic recording when this device last synced, who was logged in, and how
     * many photos are still waiting. It is how pending backlogs can be seen server-side, per device,
     * without asking the FLW.
     *
     * Failures are logged and swallowed: this is bookkeeping, and losing it must not fail a sync that
     * otherwise worked.
     */
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
                Timber.d("Successfully updated sync metadata for device: $deviceId")
            } else {
                openSrpFhirEngine.create(syncMetadata)
                Timber.d("Successfully created sync metadata for device: $deviceId")
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to update sync metadata")
        }
    }

    /**
     * Packs image-upload progress into the WorkManager [Data] shape the FHIR SDK understands, so the
     * emission reaches the registered sync listeners and drives the in-app progress bar. The keys
     * and serialization have to match `FhirSyncWorker.buildWorkData` — the SDK ignores anything else.
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
