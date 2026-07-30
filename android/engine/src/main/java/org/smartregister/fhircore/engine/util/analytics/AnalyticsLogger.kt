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

package org.smartregister.fhircore.engine.util.analytics

interface AnalyticsLogger {
  fun capture(event: String, properties: Map<String, Any?>? = null)

  object Events {
    const val IMAGE_UPLOAD_COMPLETED = "image_upload_completed"

    /**
     * A DocumentReference reached the upload phase with no file-location extension, so it can never
     * be uploaded. Emitted so these malformed drafts are visible instead of being silently dropped.
     */
    const val DOCUMENT_REFERENCE_MISSING_FILE_LOCATION = "document_reference_missing_file_location"

    /**
     * The image file backing a DocumentReference is gone from the device *and* the server has no
     * resource for that id, so the attachment URL already sitting in a submitted
     * QuestionnaireResponse can never resolve. This is the terminal, unrecoverable form of the
     * "DocumentReference 404" and should be alerted on.
     */
    const val DOCUMENT_REFERENCE_IMAGE_FILE_LOST = "document_reference_image_file_lost"

    /** The server answered 410 Gone for a DocumentReference we still hold locally. */
    const val DOCUMENT_REFERENCE_GONE_ON_SERVER = "document_reference_gone_on_server"

    /**
     * `$binary-access-write` reported success but the follow-up read found no image on the server,
     * so the local copy was kept and the upload will be retried. A steady trickle is a server-side
     * persistence problem; it must never be the last word on a document.
     */
    const val DOCUMENT_REFERENCE_UPLOAD_UNVERIFIED = "document_reference_upload_unverified"

    /**
     * One document's upload attempt failed, tagged with the stage it died at.
     *
     * This is the per-document diagnostic: it replaces the step-by-step info logging that used to
     * cost twelve PostHog events per image and could only be read by string-matching messages.
     * Volume is bounded by *failures*, not by backlog size, because a document that is skipped
     * (server unreachable, file unreadable) never reaches an attempt.
     *
     * Grouping by [Props.UPLOAD_STAGE] is the query that sized the original 404 population:
     * documents whose furthest stage was `creating_metadata` are those that entered Step 1 and
     * never completed it — the resource never reached the server, so its id 404s in a submitted
     * QuestionnaireResponse.
     */
    const val DOCUMENT_UPLOAD_ATTEMPT_FAILED = "document_upload_attempt_failed"

    /**
     * One image-upload pass, summarised. Emitted once per sync run that had at least one document,
     * with every document accounted for in exactly one outcome counter.
     *
     * This is the event to query for fleet health: it answers "how many images are stuck, and why"
     * without the per-document logging that used to make that question unaffordable. The skip and
     * defer outcomes are normal on a poor connection — what matters is whether they *stay* high for
     * the same user across runs.
     */
    const val DOCUMENT_UPLOAD_RUN_COMPLETED = "document_upload_run_completed"

    /**
     * A submitted QuestionnaireResponse referenced a DocumentReference that is absent from the
     * local engine but recorded as uploaded. The reference is valid; emitted for visibility only.
     */
    const val DOCUMENT_REFERENCE_RESOLVED_FROM_LEDGER = "document_reference_resolved_from_ledger"
  }

  object Props {
    const val DOCUMENT_ID = "document_id"
    const val UPLOAD_DURATION_MS = "upload_duration_ms"
    const val RESPONSE_CODE = "response_code"
    const val PENDING_DOCUMENTS = "pending_documents"
    const val BYTES_UPLOADED = "bytes_uploaded"
    const val ERROR_MESSAGE = "error_message"

    /** Simple class name of the throwable, so failures can be grouped without parsing messages. */
    const val ERROR_TYPE = "error_type"

    /**
     * Furthest stage the upload state machine reached before failing. One of the
     * `UploadStage` labels: `starting`, `creating_metadata`, `uploading_binary`,
     * `recording_duration`, `finalizing`.
     */
    const val UPLOAD_STAGE = "upload_stage"

    // Outcome counters for DOCUMENT_UPLOAD_RUN_COMPLETED. Every document lands in exactly one, so
    // the counters below always sum to TOTAL_DOCUMENTS — a run where they do not is a bug in the
    // tally, not a new outcome.
    const val TOTAL_DOCUMENTS = "total_documents"
    const val UPLOADED_DOCUMENTS = "uploaded_documents"

    /** Threw, or the upload state machine returned false. Retried next run. */
    const val FAILED_DOCUMENTS = "failed_documents"

    /** Server unreachable this run, so nothing was assumed. Normal while offline. */
    const val SKIPPED_SERVER_UNKNOWN = "skipped_server_unknown"

    /** The local image could not be read (not proven missing), so nothing was assumed. */
    const val SKIPPED_FILE_UNKNOWN = "skipped_file_unknown"

    /** Held back because a queued whole-resource write would have erased the uploaded image. */
    const val DEFERRED_PENDING_WRITE = "deferred_pending_write"

    /** Image gone locally but the resource exists on the server; marked failed there and purged. */
    const val FINALIZED_IMAGE_LOST = "finalized_image_lost"

    /** Image gone locally *and* absent from the server — the terminal, unrecoverable loss. */
    const val IMAGE_LOST_NO_SERVER_RECORD = "image_lost_no_server_record"

    /** No file-location extension, so the document can never be uploaded. */
    const val MISSING_FILE_LOCATION = "missing_file_location"

    /** Reached but not attempted because the worker had already been told to stop. */
    const val STOPPED_BEFORE_ATTEMPT = "stopped_before_attempt"

    /**
     * False when the pass was cut short (cancelled worker, thrown exception). Filter on this before
     * treating the counters as a complete picture of the run — but do not discard these rows, since
     * a rising share of incomplete runs is itself the signal that images are not draining.
     */
    const val RUN_COMPLETED = "run_completed"

    /**
     * Documents never reached at all, i.e. TOTAL_DOCUMENTS minus every outcome counter. Always 0 on
     * a completed pass; on an interrupted one it measures how much work the stop discarded.
     */
    const val UNPROCESSED_DOCUMENTS = "unprocessed_documents"

    /**
     * Ids of documents carried over to a future run — the skips, defers, stops and failures.
     * **Oldest first, capped at 20**, so a large backlog reports the longest-stuck rather than the
     * most recent. The counters remain the authority on how many there were.
     *
     * Excludes outcomes that publish their own id (`document_reference_image_file_lost`,
     * `document_reference_missing_file_location`) and, of course, successful uploads.
     *
     * Client-generated UUIDs carrying no patient data, and already sent as `document_id` on the
     * per-document events.
     */
    const val STUCK_DOCUMENT_IDS = "stuck_document_ids"
  }
}
