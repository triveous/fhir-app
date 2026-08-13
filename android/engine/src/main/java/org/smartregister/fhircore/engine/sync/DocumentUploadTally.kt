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

/**
 * Counters for one image-upload pass — one for each outcome a document can reach — plus a sample of
 * the ids left over for next time.
 *
 * Counting is what lets the per-document paths stay quiet. Skips and defers are normal and
 * high-volume; what actually matters is whether they keep happening to the same device run after run,
 * and totals show that where individual log lines do not.
 *
 * One [DocumentAction] maps to one counter, and every document lands in exactly one, so the counters
 * always sum to the number of documents the pass started with. `AppSyncWorker.reportUploadRun`
 * checks that and complains if it stops holding.
 */
internal class DocumentUploadTally {
    var uploaded = 0
    var failed = 0
    var skippedServerUnknown = 0
    var skippedFileUnknown = 0
    var deferredPendingWrite = 0
    var finalizedImageLost = 0
    var imageLostNoServerRecord = 0
    var missingFileLocation = 0

    /** Reached, but deliberately not attempted because the worker had been told to stop. */
    var stoppedBeforeAttempt = 0

    private val stuckIds = ArrayDeque<String>()

    /**
     * Notes a document being carried over to a future run.
     *
     * Call this for the outcomes that leave the photo un-uploaded and report nothing themselves: the
     * two skips, the defer, a stop, and a failure. The terminal outcomes already publish their own
     * id, and an uploaded document is finished.
     *
     * Holds at most [MAX_STUCK_IDS]. Documents arrive newest-first, so dropping from the front keeps
     * the **oldest** — the ones stuck longest, which are the ones worth chasing. Flipping that is a
     * behaviour change, not a cleanup.
     */
    fun recordStuck(documentId: String) {
        if (stuckIds.size == MAX_STUCK_IDS) stuckIds.removeFirst()
        stuckIds.addLast(documentId)
    }

    /** The retained stuck ids, oldest first. */
    fun stuckDocumentIds(): List<String> = stuckIds.reversed()

    fun accountedFor(): Int =
        uploaded + failed + skippedServerUnknown + skippedFileUnknown + deferredPendingWrite +
            finalizedImageLost + imageLostNoServerRecord + missingFileLocation +
            stoppedBeforeAttempt

    companion object {
        /**
         * A backlog can run to thousands of documents, and one event cannot usefully carry them all.
         * Twenty is enough to spot the persistently stuck ones, and the counters still give the exact
         * totals, so truncating the list costs no measurement.
         */
        const val MAX_STUCK_IDS = 20
    }
}
