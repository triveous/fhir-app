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

package org.smartregister.fhircore.quest.ui.register.customui

import com.google.android.fhir.FhirEngine
import com.google.android.fhir.db.ResourceNotFoundException
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.hl7.fhir.r4.model.DocumentReference
import org.hl7.fhir.r4.model.ResourceType
import org.hl7.fhir.r4.model.StringType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.smartregister.fhircore.quest.ui.register.patients.DocumentReferenceCaseType

/**
 * Tests for the DocumentReference-handling logic of `CustomAttachmentViewHolderFactory`.
 *
 * The view-holder delegate itself is an anonymous object inside a singleton and cannot be
 * constructed in a unit test, so the destructive part — the one that purges rows and deletes JPEGs
 * — was extracted to [purgeDraftDocumentReferenceIfSafe] specifically so it could be covered here.
 *
 * The guard under test is the reason the retake/delete cleanup is safe at all: **only DRAFT
 * documents may be purged.** A non-DRAFT document may already be referenced by a submitted
 * QuestionnaireResponse, and purging it is exactly how an id ends up pointing at nothing.
 */
class CustomAttachmentDocumentReferenceTest {

  private val fhirEngine: FhirEngine = mockk()
  private val deleted = mutableListOf<String>()
  private val deleteFile: (String) -> Unit = { deleted.add(it) }

  private val docId = "3f2b1a90-0000-4000-8000-000000000001"
  private val binaryUrl =
    "https://example.org/fhir/DocumentReference/$docId/\$binary-access-read" +
      "?path=DocumentReference.content.attachment"

  private fun documentReference(
    id: String = docId,
    caseType: String = DocumentReferenceCaseType.DRAFT.name,
    fileLocation: String? = "content://pkg.fileprovider/files/IMG_1.jpeg",
  ) =
    DocumentReference().apply {
      this.id = id
      description = caseType
      fileLocation?.let { addExtension(EXTENSION_FILE_LOCATION, StringType(it)) }
    }

  private fun purge(url: String?) = runBlocking {
    purgeDraftDocumentReferenceIfSafe(fhirEngine, url, deleteFile)
  }

  // ── extractDocumentReferenceId ───────────────────────────────────────────────────────────────

  @Test
  fun `the document id is extracted from a binary-access-read url`() {
    assertEquals(docId, extractDocumentReferenceId(binaryUrl))
  }

  @Test
  fun `a relative url still yields the id`() {
    // getUrl() falls back to an empty base when no FHIR base URL is stored yet.
    assertEquals(
      docId,
      extractDocumentReferenceId("DocumentReference/$docId/\$binary-access-read?path=x"),
    )
  }

  @Test
  fun `unparseable and null urls yield no id`() {
    assertNull(extractDocumentReferenceId(null))
    assertNull(extractDocumentReferenceId(""))
    assertNull(extractDocumentReferenceId("https://example.org/fhir/Patient/123/\$everything"))
    // No trailing slash after the id ⇒ deliberately not matched rather than mis-parsed.
    assertNull(extractDocumentReferenceId("https://example.org/fhir/DocumentReference/$docId"))
  }

  // ── the DRAFT guard: the whole safety property ───────────────────────────────────────────────

  @Test
  fun `a DRAFT document is purged and its file deleted`() {
    coEvery { fhirEngine.get(ResourceType.DocumentReference, docId) } returns documentReference()
    coEvery { fhirEngine.purge(ResourceType.DocumentReference, docId, true) } just runs

    assertEquals(PurgeDraftOutcome.PURGED, purge(binaryUrl))

    assertEquals(listOf("content://pkg.fileprovider/files/IMG_1.jpeg"), deleted)
    coVerify(exactly = 1) { fhirEngine.purge(ResourceType.DocumentReference, docId, true) }
  }

  @Test
  fun `a SUBMITTED document is never purged and its file never deleted`() {
    // The document may already be referenced by an uploaded QuestionnaireResponse.
    coEvery { fhirEngine.get(ResourceType.DocumentReference, docId) } returns
      documentReference(caseType = DocumentReferenceCaseType.SUBMITTED.name)

    assertEquals(PurgeDraftOutcome.NOT_DRAFT, purge(binaryUrl))

    assertTrue(deleted.isEmpty())
    coVerify(exactly = 0) { fhirEngine.purge(any(), any<String>(), any()) }
  }

  @Test
  fun `a document with an unexpected description is treated as not-draft`() {
    coEvery { fhirEngine.get(ResourceType.DocumentReference, docId) } returns
      documentReference(caseType = "SOMETHING_ELSE")

    assertEquals(PurgeDraftOutcome.NOT_DRAFT, purge(binaryUrl))

    coVerify(exactly = 0) { fhirEngine.purge(any(), any<String>(), any()) }
  }

  @Test
  fun `a document with no description is treated as not-draft`() {
    coEvery { fhirEngine.get(ResourceType.DocumentReference, docId) } returns
      DocumentReference().apply { id = docId }

    assertEquals(PurgeDraftOutcome.NOT_DRAFT, purge(binaryUrl))

    coVerify(exactly = 0) { fhirEngine.purge(any(), any<String>(), any()) }
  }

  // ── no-ops ───────────────────────────────────────────────────────────────────────────────────

  @Test
  fun `a null or unparseable url purges nothing`() {
    assertEquals(PurgeDraftOutcome.NO_ID, purge(null))
    assertEquals(PurgeDraftOutcome.NO_ID, purge("not-a-document-reference-url"))

    coVerify(exactly = 0) { fhirEngine.purge(any(), any<String>(), any()) }
  }

  @Test
  fun `an already-purged row is a no-op`() {
    coEvery { fhirEngine.get(ResourceType.DocumentReference, docId) } throws
      ResourceNotFoundException("DocumentReference", docId)

    assertEquals(PurgeDraftOutcome.NOT_FOUND, purge(binaryUrl))

    assertTrue(deleted.isEmpty())
  }

  @Test
  fun `a DRAFT with no file-location extension still purges the row`() {
    coEvery { fhirEngine.get(ResourceType.DocumentReference, docId) } returns
      documentReference(fileLocation = null)
    coEvery { fhirEngine.purge(ResourceType.DocumentReference, docId, true) } just runs

    assertEquals(PurgeDraftOutcome.PURGED, purge(binaryUrl))

    assertTrue(deleted.isEmpty())
    coVerify(exactly = 1) { fhirEngine.purge(ResourceType.DocumentReference, docId, true) }
  }

  @Test
  fun `a failing file delete does not prevent the row from being purged`() {
    // An orphan JPEG is a storage leak; a stale row is a correctness problem. Prefer the leak.
    coEvery { fhirEngine.get(ResourceType.DocumentReference, docId) } returns documentReference()
    coEvery { fhirEngine.purge(ResourceType.DocumentReference, docId, true) } just runs

    val outcome = runBlocking {
      purgeDraftDocumentReferenceIfSafe(fhirEngine, binaryUrl) { error("no permission") }
    }

    assertEquals(PurgeDraftOutcome.PURGED, outcome)
    coVerify(exactly = 1) { fhirEngine.purge(ResourceType.DocumentReference, docId, true) }
  }

  @Test
  fun `a failing purge is swallowed rather than crashing the capture flow`() {
    coEvery { fhirEngine.get(ResourceType.DocumentReference, docId) } returns documentReference()
    coEvery { fhirEngine.purge(ResourceType.DocumentReference, docId, true) } throws
      IllegalStateException("db locked")

    assertEquals(PurgeDraftOutcome.NOT_FOUND, purge(binaryUrl))
  }

  @Test
  fun `cancellation propagates instead of being reported as not-found`() {
    coEvery { fhirEngine.get(ResourceType.DocumentReference, docId) } throws
      CancellationException("scope died")

    var thrown: Throwable? = null
    try {
      purge(binaryUrl)
    } catch (e: Throwable) {
      thrown = e
    }

    assertTrue("cancellation must not be swallowed, was $thrown", thrown is CancellationException)
  }

  // ── createDocumentReference ──────────────────────────────────────────────────────────────────

  @Test
  fun `a newly created DocumentReference is a DRAFT that points at its file`() {
    val uri = mockk<android.net.Uri>()
    coEvery { uri.toString() } returns "content://pkg.fileprovider/files/IMG_9.jpeg"

    val doc = CustomAttachmentViewHolderFactory.createDocumentReference(uri, "image/jpeg")

    // DRAFT keeps it out of AppSyncWorker until the case is actually submitted.
    assertEquals(DocumentReferenceCaseType.DRAFT.name, doc.description)
    assertEquals(
      DocumentReference.ReferredDocumentStatus.PRELIMINARY,
      doc.docStatus,
    )
    assertEquals("image/jpeg", doc.contentFirstRep.attachment.contentType)
    assertEquals(
      "content://pkg.fileprovider/files/IMG_9.jpeg",
      (doc.getExtensionByUrl(EXTENSION_FILE_LOCATION).value as StringType).value,
    )
    // An id must exist before the resource is persisted — the URL is derived from it.
    assertTrue(doc.id.isNotBlank())
  }

  @Test
  fun `each created DocumentReference gets a distinct id`() {
    val uri = mockk<android.net.Uri>()
    coEvery { uri.toString() } returns "content://pkg.fileprovider/files/IMG_9.jpeg"

    val first = CustomAttachmentViewHolderFactory.createDocumentReference(uri, "image/jpeg")
    val second = CustomAttachmentViewHolderFactory.createDocumentReference(uri, "image/jpeg")

    assertTrue(first.id != second.id)
  }
}
