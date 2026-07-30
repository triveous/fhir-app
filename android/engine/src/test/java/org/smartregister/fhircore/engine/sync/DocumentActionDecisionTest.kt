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
import org.hl7.fhir.r4.model.Attachment
import org.hl7.fhir.r4.model.DocumentReference
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

/**
 * The per-document safety decision inside `performDocumentReferenceUpload`, tested exhaustively.
 *
 * This is where invariants I1-I3 live, and every image-loss defect found in the RCA was a wrong
 * answer in this table:
 * - a failed `GET` read as "the server does not have it" ⇒ metadata PUT erased the binary;
 * - an unreadable file read as "the file is gone" ⇒ a readable image marked permanently failed;
 * - a queued whole-resource write ignored ⇒ the trailing metadata sync erased a confirmed upload;
 * - a file-less document with no server resource purged ⇒ the id stranded in a submitted response.
 */
class DocumentActionDecisionTest {

  // ── fixtures ────────────────────────────────────────────────────────────────────────────────

  private fun serverDoc(
    docStatus: DocumentReference.ReferredDocumentStatus? = null,
    attachmentSize: Int? = null,
  ) =
    DocumentReference().apply {
      docStatus?.let { this.docStatus = it }
      addContent().apply {
        attachment = Attachment().apply { attachmentSize?.let { size = it } }
      }
    }

  private val found = AppSyncWorker.ServerDocumentLookup.Found(serverDoc())
  private val foundWithImage =
    AppSyncWorker.ServerDocumentLookup.Found(
      serverDoc(DocumentReference.ReferredDocumentStatus.FINAL, attachmentSize = 1234),
    )
  private val notFound = AppSyncWorker.ServerDocumentLookup.NotFound
  private val unavailable = AppSyncWorker.ServerDocumentLookup.Unavailable(IOException("offline"))

  private val filePresent = AppSyncWorker.LocalFileState.Present
  private val fileAbsent = AppSyncWorker.LocalFileState.Absent
  private val fileUnreadable = AppSyncWorker.LocalFileState.Unreadable(SecurityException("no grant"))

  private val noChanges = emptyList<LocalChange.Type>()
  private val insertPending = listOf(LocalChange.Type.INSERT)
  private val updatePending = listOf(LocalChange.Type.UPDATE)

  private fun decide(
    lookup: AppSyncWorker.ServerDocumentLookup,
    fileState: AppSyncWorker.LocalFileState,
    pending: List<LocalChange.Type> = noChanges,
  ) = decideDocumentAction(lookup, fileState, pending)

  // ── I1: an unknown answer must short-circuit before anything destructive ─────────────────────

  @Test
  fun `unreachable server short-circuits regardless of everything else`() {
    // Crucially this outranks the file check: an offline device with a missing file must NOT be
    // routed to "image permanently lost".
    listOf(filePresent, fileAbsent, fileUnreadable).forEach { file ->
      listOf(noChanges, insertPending, updatePending).forEach { pending ->
        assertEquals(
          "unavailable + $file + $pending",
          DocumentAction.SkipServerStateUnknown,
          decide(unavailable, file, pending),
        )
      }
    }
  }

  @Test
  fun `unreadable file short-circuits once the server answered`() {
    listOf(found, foundWithImage, notFound).forEach { lookup ->
      assertEquals(
        "$lookup + unreadable",
        DocumentAction.SkipFileStateUnknown,
        decide(lookup, fileUnreadable),
      )
    }
  }

  // ── the terminal image-lost split ────────────────────────────────────────────────────────────

  @Test
  fun `file gone and server holds the resource without an image is finalized and purged`() {
    assertEquals(DocumentAction.FinalizeAsImageLostAndPurge, decide(found, fileAbsent))
  }

  @Test
  fun `file gone and server has no resource keeps the row so Route A can still create it`() {
    // Purging here is what stranded ids in already-submitted responses — the F1 shape.
    assertEquals(DocumentAction.ReportImageLostKeepRow, decide(notFound, fileAbsent))
  }

  @Test
  fun `file gone but the image is already on the server proceeds to upload for finalization`() {
    // Nothing is lost: the state machine finalizes from server state and never reads the file.
    // Both file-touching steps (createMetadataRecordOnServer, uploadFileContent) are gated on the
    // server lacking the resource/image, so neither runs here.
    assertEquals(DocumentAction.Upload, decide(foundWithImage, fileAbsent))
    assertEquals(DocumentAction.Upload, decide(foundWithImage, fileAbsent, updatePending))
  }

  @Test
  fun `file gone with the image on the server still defers behind a queued INSERT`() {
    // The same case as above, but the queued whole-resource write outranks it: the INSERT would be
    // PUT over the image the server already holds. Deferring costs one sync cycle and loses nothing.
    assertEquals(
      DocumentAction.DeferPendingResourceWrite,
      decide(foundWithImage, fileAbsent, insertPending),
    )
  }

  // ── I3: queued whole-resource writes ─────────────────────────────────────────────────────────

  @Test
  fun `a queued INSERT defers the upload`() {
    assertEquals(DocumentAction.DeferPendingResourceWrite, decide(notFound, filePresent, insertPending))
    assertEquals(DocumentAction.DeferPendingResourceWrite, decide(found, filePresent, insertPending))
  }

  @Test
  fun `a queued DELETE defers the upload`() {
    assertEquals(
      DocumentAction.DeferPendingResourceWrite,
      decide(found, filePresent, listOf(LocalChange.Type.UPDATE, LocalChange.Type.DELETE)),
    )
  }

  @Test
  fun `the UPDATE left by the submit-time flip does not defer`() {
    // Every freshly submitted case is in exactly this state. Deferring here would delay all of
    // their images by a whole sync cycle for no safety benefit.
    assertEquals(DocumentAction.Upload, decide(found, filePresent, updatePending))
    assertEquals(DocumentAction.Upload, decide(notFound, filePresent, updatePending))
  }

  // ── the happy paths ──────────────────────────────────────────────────────────────────────────

  @Test
  fun `present file and a clean resource uploads`() {
    assertEquals(DocumentAction.Upload, decide(found, filePresent))
    assertEquals(DocumentAction.Upload, decide(notFound, filePresent))
    assertEquals(DocumentAction.Upload, decide(foundWithImage, filePresent))
  }

  // ── ordering guarantees, stated as tests so a refactor cannot silently reorder them ──────────

  @Test
  fun `server-unknown outranks a queued INSERT`() {
    assertEquals(DocumentAction.SkipServerStateUnknown, decide(unavailable, filePresent, insertPending))
  }

  @Test
  fun `file-unknown outranks a queued INSERT`() {
    assertEquals(DocumentAction.SkipFileStateUnknown, decide(found, fileUnreadable, insertPending))
  }

  @Test
  fun `the terminal image-lost branches outrank a queued INSERT`() {
    // The row is being purged (or deliberately kept); a pending write is irrelevant to that call.
    assertEquals(DocumentAction.FinalizeAsImageLostAndPurge, decide(found, fileAbsent, insertPending))
    assertEquals(DocumentAction.ReportImageLostKeepRow, decide(notFound, fileAbsent, insertPending))
  }

  // ── hasImageDataOnServer: a false positive here is permanent image loss ──────────────────────

  @Test
  fun `only a non-zero attachment size counts as image data on the server`() {
    assertEquals(true, serverDoc(attachmentSize = 1).hasImageDataOnServer())
    assertEquals(true, serverDoc(attachmentSize = 1024).hasImageDataOnServer())
  }

  @Test
  fun `absent, null and zero-size attachments are not image data`() {
    // Any of these returning true would purge the row with no image on the server.
    assertEquals(false, (null as DocumentReference?).hasImageDataOnServer())
    assertEquals(false, DocumentReference().hasImageDataOnServer())
    assertEquals(false, serverDoc(attachmentSize = null).hasImageDataOnServer())
    assertEquals(false, serverDoc(attachmentSize = 0).hasImageDataOnServer())
  }

  @Test
  fun `a data or url attachment without a size is NOT counted`() {
    // Deliberate: the predicate must stay strict. Loosening it to hasData()/hasUrl() would let a
    // resource that merely mentions an attachment be treated as holding the image.
    val doc =
      DocumentReference().apply {
        addContent().apply {
          attachment = Attachment().apply { url = "Binary/abc" }
        }
      }

    assertEquals(false, doc.hasImageDataOnServer())
  }

  @Test
  fun `a document with several content entries counts if any carries data`() {
    val doc =
      DocumentReference().apply {
        addContent().apply { attachment = Attachment() }
        addContent().apply { attachment = Attachment().apply { size = 99 } }
      }

    assertEquals(true, doc.hasImageDataOnServer())
  }

  @Test
  fun `isFinalOnServer only matches the final status`() {
    assertEquals(true, serverDoc(DocumentReference.ReferredDocumentStatus.FINAL).isFinalOnServer())
    assertEquals(
      false,
      serverDoc(DocumentReference.ReferredDocumentStatus.PRELIMINARY).isFinalOnServer(),
    )
    assertEquals(false, serverDoc().isFinalOnServer())
    assertEquals(false, (null as DocumentReference?).isFinalOnServer())
  }

  // ── no combination is destructive when the answer is unknown ─────────────────────────────────

  @Test
  fun `no unknown-state combination ever reaches a purging or uploading action`() {
    val destructive =
      setOf(DocumentAction.FinalizeAsImageLostAndPurge, DocumentAction.Upload)
    val unknownStates =
      listOf(unavailable to filePresent, unavailable to fileAbsent, unavailable to fileUnreadable) +
        listOf(found to fileUnreadable, notFound to fileUnreadable, foundWithImage to fileUnreadable)

    unknownStates.forEach { (lookup, file) ->
      listOf(noChanges, insertPending, updatePending).forEach { pending ->
        val action = decide(lookup, file, pending)
        assert(action !in destructive) {
          "unknown state ($lookup, $file, $pending) must not resolve to $action"
        }
      }
    }
  }
}
