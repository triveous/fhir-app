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

import com.google.android.fhir.LocalChange
import org.hl7.fhir.r4.model.DocumentReference

/**
 * What to do with one DocumentReference this run — the result of [decideDocumentAction].
 *
 * A separate type so the decision can be tested on its own, without a WorkManager, a Hilt entry
 * point or a FHIR server behind it.
 */
internal sealed interface DocumentAction {
    /** Server unreachable. Change nothing; ask again next run. */
    data object SkipServerStateUnknown : DocumentAction

    /** The local image could not be read. Change nothing; try again next run. */
    data object SkipFileStateUnknown : DocumentAction

    /**
     * The image is gone from the device, but the server holds the resource. The URL in the submitted
     * response still resolves, so mark the image permanently failed up there and stop carrying the
     * local row.
     */
    data object FinalizeAsImageLostAndPurge : DocumentAction

    /**
     * The image is gone from the device *and* the server has no resource for this id. Keep the local
     * row: it is the only thing that can still create that resource, and purging it would leave the
     * id in an already-submitted response pointing at nothing forever.
     */
    data object ReportImageLostKeepRow : DocumentAction

    /** A queued whole-resource write would land after the bytes and erase them. Wait for it. */
    data object DeferPendingResourceWrite : DocumentAction

    /** Nothing in the way. Run the upload. */
    data object Upload : DocumentAction
}

/**
 * Picks what to do with one document.
 *
 * The order of these checks *is* the safety property: anything we are not sure about returns before
 * the branches that write, patch or purge. So an offline device with a missing file is a skip, never
 * a "photo lost".
 *
 * @param lookup what `GET /DocumentReference/{id}` came back with.
 * @param fileState whether the local JPEG is readable.
 * @param pendingChangeTypes local changes still queued for this resource, in order.
 */
internal fun decideDocumentAction(
    lookup: ServerDocumentLookup,
    fileState: LocalFileState,
    pendingChangeTypes: List<LocalChange.Type>,
): DocumentAction {
    if (lookup is ServerDocumentLookup.Unavailable) {
        return DocumentAction.SkipServerStateUnknown
    }
    if (fileState is LocalFileState.Unreadable) {
        return DocumentAction.SkipFileStateUnknown
    }
    val serverDocRef = (lookup as? ServerDocumentLookup.Found)?.documentReference
    if (fileState is LocalFileState.Absent && !serverDocRef.hasImageDataOnServer()) {
        // Note this only catches "no image anywhere".
        return if (serverDocRef != null) {
            DocumentAction.FinalizeAsImageLostAndPurge
        } else {
            DocumentAction.ReportImageLostKeepRow
        }
    }
    if (wouldOverwriteResourceOnUpload(pendingChangeTypes)) {
        return DocumentAction.DeferPendingResourceWrite
    }
    return DocumentAction.Upload
}

/**
 * Whether the changes queued for a resource would go up as a write that replaces the whole resource —
 * and would therefore erase an image `$binary-access-write` stored just before.
 *
 * This mirrors how the FHIR SDK squashes queued changes into requests:
 * - a run starting with `INSERT` becomes a single **PUT of the full resource** ⇒ unsafe;
 * - any `DELETE` becomes `DELETE /DocumentReference/{id}` ⇒ unsafe;
 * - `UPDATE`-only becomes a **JSON PATCH** of just the changed paths. Ours only ever touch
 *   `/description`, `/docStatus` and `/extension`, never `content` ⇒ safe.
 *
 * That last case has to stay safe. Flipping a case from DRAFT to SUBMITTED always leaves an UPDATE
 * behind, so treating every pending change as unsafe would hold back the photos of every freshly
 * submitted case for a whole sync cycle.
 */
internal fun wouldOverwriteResourceOnUpload(changeTypes: List<LocalChange.Type>): Boolean =
    changeTypes.firstOrNull() == LocalChange.Type.INSERT ||
        changeTypes.contains(LocalChange.Type.DELETE)

/** Whether the server's copy is marked final. Null-safe — no copy on the server means not final. */
internal fun DocumentReference?.isFinalOnServer(): Boolean =
    this?.docStatus == DocumentReference.ReferredDocumentStatus.FINAL

/**
 * Whether the server's copy actually holds image bytes.
 *
 * Strict on purpose: a non-zero attachment size, never `hasData()` or `hasUrl()`. A false positive
 * here purges a photo the server never received; a false negative only costs a redundant re-upload.
 */
internal fun DocumentReference?.hasImageDataOnServer(): Boolean =
    this?.content?.any { (it.attachment?.size ?: 0) > 0 } == true

/** Whether the server's copy has both the image bytes and a final status — nothing left to do. */
internal fun DocumentReference?.isCompleteOnServer(): Boolean =
    isFinalOnServer() && hasImageDataOnServer()
