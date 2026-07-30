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

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The run-summary tally.
 *
 * Two properties are worth pinning. The counters must partition the documents, because
 * `reportUploadRun` raises an exception when they do not and every fleet-health query depends on the
 * sum. And the stuck-id buffer must retain the **oldest** ids under pressure — the worker iterates
 * documents newest-first, so a buffer that dropped the tail would report the images captured
 * seconds ago and silently discard the ones that have been stuck for weeks, which are the only ones
 * anybody wants to chase.
 */
class DocumentUploadTallyTest {

  private val cap = AppSyncWorker.DocumentUploadTally.MAX_STUCK_IDS

  // ── the stuck-id buffer ──────────────────────────────────────────────────────────────────────

  @Test
  fun `an empty tally reports no stuck ids`() {
    assertEquals(emptyList<String>(), AppSyncWorker.DocumentUploadTally().stuckDocumentIds())
  }

  @Test
  fun `ids below the cap are all retained, oldest first`() {
    val tally = AppSyncWorker.DocumentUploadTally()
    // The worker records newest-first, so "doc-1" here is the most recently captured image.
    listOf("doc-1", "doc-2", "doc-3").forEach(tally::recordStuck)

    assertEquals(listOf("doc-3", "doc-2", "doc-1"), tally.stuckDocumentIds())
  }

  @Test
  fun `over the cap the oldest ids are kept and the newest dropped`() {
    val tally = AppSyncWorker.DocumentUploadTally()
    // 30 documents, recorded newest-first: "doc-0" is newest, "doc-29" is oldest.
    (0 until 30).forEach { tally.recordStuck("doc-$it") }

    val retained = tally.stuckDocumentIds()

    assertEquals(cap, retained.size)
    // Oldest first, and the newest 10 are gone — not the other way round.
    assertEquals("doc-29", retained.first())
    assertEquals("doc-${30 - cap}", retained.last())
    assertEquals(emptyList<String>(), retained.filter { it in setOf("doc-0", "doc-9") })
  }

  @Test
  fun `exactly the cap keeps everything`() {
    val tally = AppSyncWorker.DocumentUploadTally()
    (0 until cap).forEach { tally.recordStuck("doc-$it") }

    assertEquals(cap, tally.stuckDocumentIds().size)
    assertEquals("doc-${cap - 1}", tally.stuckDocumentIds().first())
  }

  @Test
  fun `the same id recorded twice is not de-duplicated`() {
    // Deliberate: a document can only reach one outcome per run, so a repeat means the caller
    // double-counted. Silently collapsing it would hide that from the sum check.
    val tally = AppSyncWorker.DocumentUploadTally()
    tally.recordStuck("doc-1")
    tally.recordStuck("doc-1")

    assertEquals(listOf("doc-1", "doc-1"), tally.stuckDocumentIds())
  }

  // ── the partition invariant ──────────────────────────────────────────────────────────────────

  @Test
  fun `a fresh tally accounts for nothing`() {
    assertEquals(0, AppSyncWorker.DocumentUploadTally().accountedFor())
  }

  @Test
  fun `every counter contributes exactly one to the total`() {
    // Each field is set to a distinct value so a field omitted from accountedFor(), or added twice,
    // changes the sum rather than cancelling out.
    val tally =
      AppSyncWorker.DocumentUploadTally().apply {
        uploaded = 1
        failed = 2
        skippedServerUnknown = 4
        skippedFileUnknown = 8
        deferredPendingWrite = 16
        finalizedImageLost = 32
        imageLostNoServerRecord = 64
        missingFileLocation = 128
        stoppedBeforeAttempt = 256
      }

    assertEquals(511, tally.accountedFor())
  }

  @Test
  fun `recording stuck ids does not affect the counters`() {
    val tally = AppSyncWorker.DocumentUploadTally().apply { skippedServerUnknown = 3 }
    repeat(50) { tally.recordStuck("doc-$it") }

    assertEquals(3, tally.accountedFor())
  }
}
