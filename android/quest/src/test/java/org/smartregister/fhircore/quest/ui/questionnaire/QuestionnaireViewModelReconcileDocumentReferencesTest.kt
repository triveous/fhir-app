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

package org.smartregister.fhircore.quest.ui.questionnaire

import com.google.android.fhir.FhirEngine
import com.google.android.fhir.db.ResourceNotFoundException
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.runBlocking
import org.hl7.fhir.r4.model.Attachment
import org.hl7.fhir.r4.model.DocumentReference
import org.hl7.fhir.r4.model.QuestionnaireResponse
import org.hl7.fhir.r4.model.ResourceType
import org.hl7.fhir.r4.model.StringType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.smartregister.fhircore.quest.ui.register.patients.DocumentReferenceCaseType

/**
 * Pure unit tests for [QuestionnaireViewModel.reconcileDocumentReferencesForSubmission] — the
 * screening-image DocumentReference reconciliation extracted from
 * `QuestionnaireActivity.registerFragmentResultListener`. No Robolectric / Hilt (the module's
 * [QuestionnaireViewModelTest] is Robolectric-heavy); the ViewModel is constructed directly with
 * MockK doubles and only [FhirEngine] is exercised, so the reconciliation logic is verified in
 * isolation.
 */
class QuestionnaireViewModelReconcileDocumentReferencesTest {

  private val fhirEngine: FhirEngine = mockk()
  private lateinit var viewModel: QuestionnaireViewModel

  @Before
  fun setUp() {
    viewModel =
      QuestionnaireViewModel(
        defaultRepository = mockk(relaxed = true),
        dispatcherProvider = mockk(relaxed = true),
        fhirCarePlanGenerator = mockk(relaxed = true),
        resourceDataRulesExecutor = mockk(relaxed = true),
        transformSupportServices = mockk(relaxed = true),
        sharedPreferencesHelper = mockk(relaxed = true),
        secureSharedPreference = mockk(relaxed = true),
        fhirOperator = mockk(relaxed = true),
        fhirPathDataExtractor = mockk(relaxed = true),
        configurationRegistry = mockk(relaxed = true),
        syncBroadcaster = mockk(relaxed = true),
        fhirEngine = fhirEngine,
      )
    coEvery { fhirEngine.update(resource = anyVararg()) } just runs
  }

  // ───────────────────────────── happy paths ─────────────────────────────

  @Test
  fun draftDocumentReferenceIsFlippedToSubmittedAndAnswerRetained() {
    val id = "doc-draft-1"
    val doc = documentReference(id, DocumentReferenceCaseType.DRAFT.name)
    coEvery { fhirEngine.get(ResourceType.DocumentReference, id) } returns doc
    val response = screeningResponse(imageItem("image-1", attachmentAnswer(id)))

    val result = runBlocking { viewModel.reconcileDocumentReferencesForSubmission(response) }

    assertEquals(listOf(id), result.submittedDraftIds)
    assertTrue(result.missingReferences.isEmpty())
    assertTrue(result.unparseableUrls.isEmpty())
    // The DocumentReference was flipped and persisted.
    assertEquals(DocumentReferenceCaseType.SUBMITTED.name, doc.description)
    coVerify(exactly = 1) { fhirEngine.update(any<DocumentReference>()) }
    // The answer is kept — the image is real and uploadable.
    assertEquals(1, response.imageItem("image-1").answer.size)
  }

  @Test
  fun submittedDocumentReferenceIsNotFlippedAndAnswerRetained() {
    val id = "doc-submitted-1"
    val doc = documentReference(id, DocumentReferenceCaseType.SUBMITTED.name)
    coEvery { fhirEngine.get(ResourceType.DocumentReference, id) } returns doc
    val response = screeningResponse(imageItem("image-1", attachmentAnswer(id)))

    val result = runBlocking { viewModel.reconcileDocumentReferencesForSubmission(response) }

    assertTrue(result.submittedDraftIds.isEmpty())
    assertTrue(result.missingReferences.isEmpty())
    assertEquals(DocumentReferenceCaseType.SUBMITTED.name, doc.description)
    coVerify(exactly = 0) { fhirEngine.update(any<DocumentReference>()) }
    assertEquals(1, response.imageItem("image-1").answer.size)
  }

  // ───────────────────────────── the 404 guard ─────────────────────────────

  @Test
  fun missingDocumentReferenceIsDroppedAndReported() {
    val id = "doc-missing-1"
    coEvery { fhirEngine.get(ResourceType.DocumentReference, id) } throws
      ResourceNotFoundException("DocumentReference", id)
    val response = screeningResponse(imageItem("image-1", attachmentAnswer(id)))

    val result = runBlocking { viewModel.reconcileDocumentReferencesForSubmission(response) }

    assertEquals(listOf(MissingDocumentReference("image-1", id)), result.missingReferences)
    assertTrue(result.submittedDraftIds.isEmpty())
    // The dangling answer was dropped so the uploaded QR does not reference a phantom resource.
    assertEquals(0, response.imageItem("image-1").answer.size)
    coVerify(exactly = 0) { fhirEngine.update(any<DocumentReference>()) }
  }

  @Test
  fun otherFetchExceptionLeavesAnswerUntouched() {
    val id = "doc-boom-1"
    coEvery { fhirEngine.get(ResourceType.DocumentReference, id) } throws
      RuntimeException("transient db error")
    val response = screeningResponse(imageItem("image-1", attachmentAnswer(id)))

    val result = runBlocking { viewModel.reconcileDocumentReferencesForSubmission(response) }

    // A non-ResourceNotFound failure must NOT drop the answer (matches the prior behaviour).
    assertTrue(result.missingReferences.isEmpty())
    assertTrue(result.submittedDraftIds.isEmpty())
    assertEquals(1, response.imageItem("image-1").answer.size)
    coVerify(exactly = 0) { fhirEngine.update(any<DocumentReference>()) }
  }

  // ───────────────────────────── malformed / skipped answers ─────────────────────────────

  @Test
  fun unparseableUrlIsReportedAndKeptWithoutEngineLookup() {
    val response =
      screeningResponse(imageItem("image-1", attachmentAnswerWithUrl("https://example.org/no-id")))

    val result = runBlocking { viewModel.reconcileDocumentReferencesForSubmission(response) }

    assertEquals(listOf("https://example.org/no-id"), result.unparseableUrls)
    assertTrue(result.missingReferences.isEmpty())
    assertTrue(result.submittedDraftIds.isEmpty())
    assertEquals(1, response.imageItem("image-1").answer.size)
    // No id could be parsed, so the engine is never consulted.
    coVerify(exactly = 0) { fhirEngine.get(ResourceType.DocumentReference, any()) }
  }

  @Test
  fun aiResultItemsAreSkipped() {
    // An -ai-result item that (incorrectly) carries an attachment must never be reconciled.
    val id = "doc-ai-1"
    val response =
      screeningResponse(imageItem("image-1-ai-result", attachmentAnswer(id)))

    val result = runBlocking { viewModel.reconcileDocumentReferencesForSubmission(response) }

    assertTrue(result.submittedDraftIds.isEmpty())
    assertTrue(result.missingReferences.isEmpty())
    assertTrue(result.unparseableUrls.isEmpty())
    assertEquals(1, response.imageItem("image-1-ai-result").answer.size)
    coVerify(exactly = 0) { fhirEngine.get(ResourceType.DocumentReference, any()) }
  }

  @Test
  fun nonAttachmentAnswersAreIgnored() {
    val response =
      screeningResponse(
        imageItem(
          "image-1",
          QuestionnaireResponse.QuestionnaireResponseItemAnswerComponent().apply {
            value = StringType("not-an-attachment")
          },
        ),
      )

    val result = runBlocking { viewModel.reconcileDocumentReferencesForSubmission(response) }

    assertTrue(result.submittedDraftIds.isEmpty())
    assertTrue(result.missingReferences.isEmpty())
    assertTrue(result.unparseableUrls.isEmpty())
    assertEquals(1, response.imageItem("image-1").answer.size)
    coVerify(exactly = 0) { fhirEngine.get(ResourceType.DocumentReference, any()) }
  }

  @Test
  fun itemsOutsideScreeningGroupAreIgnored() {
    val id = "doc-outside-1"
    val response =
      QuestionnaireResponse().apply {
        addItem().apply {
          linkId = "some-other-group"
          addItem().apply {
            linkId = "patient-screening-image-group"
            addItem().apply {
              linkId = "image-1"
              answer.add(attachmentAnswer(id))
            }
          }
        }
      }

    val result = runBlocking { viewModel.reconcileDocumentReferencesForSubmission(response) }

    assertTrue(result.submittedDraftIds.isEmpty())
    assertTrue(result.missingReferences.isEmpty())
    coVerify(exactly = 0) { fhirEngine.get(ResourceType.DocumentReference, any()) }
  }

  @Test
  fun emptyResponseReturnsEmptyResult() {
    val result =
      runBlocking { viewModel.reconcileDocumentReferencesForSubmission(QuestionnaireResponse()) }

    assertTrue(result.submittedDraftIds.isEmpty())
    assertTrue(result.missingReferences.isEmpty())
    assertTrue(result.unparseableUrls.isEmpty())
  }

  // ───────────────────────────── combined / real-world shape ─────────────────────────────

  @Test
  fun mixedImagesFlipDraftsDropMissingKeepSubmitted() {
    // The "7 of 10 fine, 3 wrong" production shape: 4 drafts, 3 submitted, 3 missing.
    val draftIds = listOf("d1", "d2", "d3", "d4")
    val submittedIds = listOf("s1", "s2", "s3")
    val missingIds = listOf("m1", "m2", "m3")

    draftIds.forEach { id ->
      coEvery { fhirEngine.get(ResourceType.DocumentReference, id) } returns
        documentReference(id, DocumentReferenceCaseType.DRAFT.name)
    }
    submittedIds.forEach { id ->
      coEvery { fhirEngine.get(ResourceType.DocumentReference, id) } returns
        documentReference(id, DocumentReferenceCaseType.SUBMITTED.name)
    }
    missingIds.forEach { id ->
      coEvery { fhirEngine.get(ResourceType.DocumentReference, id) } throws
        ResourceNotFoundException("DocumentReference", id)
    }

    val allIds = draftIds + submittedIds + missingIds
    val images = allIds.mapIndexed { index, id -> imageItem("image-$index", attachmentAnswer(id)) }
    val response = screeningResponse(*images.toTypedArray())

    val result = runBlocking { viewModel.reconcileDocumentReferencesForSubmission(response) }

    assertEquals(draftIds.toSet(), result.submittedDraftIds.toSet())
    assertEquals(missingIds.toSet(), result.missingReferences.map { it.documentReferenceId }.toSet())
    assertTrue(result.unparseableUrls.isEmpty())
    // Only the missing images lost their answer; drafts + submitted kept theirs.
    allIds.forEachIndexed { index, id ->
      val expected = if (id in missingIds) 0 else 1
      assertEquals("image-$index answer count", expected, response.imageItem("image-$index").answer.size)
    }
    coVerify(exactly = draftIds.size) { fhirEngine.update(any<DocumentReference>()) }
  }

  @Test
  fun onlyTheMissingAnswerIsDroppedWhenAnImageHasMultipleAnswers() {
    // One image item carrying two answers: a valid DRAFT and a missing reference. Only the missing
    // one should be dropped (guards the in-loop removal + copy iteration).
    val draftId = "doc-draft-x"
    val missingId = "doc-missing-x"
    coEvery { fhirEngine.get(ResourceType.DocumentReference, draftId) } returns
      documentReference(draftId, DocumentReferenceCaseType.DRAFT.name)
    coEvery { fhirEngine.get(ResourceType.DocumentReference, missingId) } throws
      ResourceNotFoundException("DocumentReference", missingId)

    val response =
      screeningResponse(
        imageItem("image-1", attachmentAnswer(draftId), attachmentAnswer(missingId)),
      )

    val result = runBlocking { viewModel.reconcileDocumentReferencesForSubmission(response) }

    assertEquals(listOf(draftId), result.submittedDraftIds)
    assertEquals(listOf(missingId), result.missingReferences.map { it.documentReferenceId })
    val remaining = response.imageItem("image-1").answer
    assertEquals(1, remaining.size)
    assertEquals(docRefUrl(draftId), remaining.first().valueAttachment.url)
  }

  // ───────────────────────────── extractDocumentReferenceIdFromUrl ─────────────────────────────

  @Test
  fun extractDocumentReferenceIdParsesBinaryAccessReadUrl() {
    assertEquals(
      "abc-123",
      viewModel.extractDocumentReferenceIdFromUrl(docRefUrl("abc-123")),
    )
  }

  @Test
  fun extractDocumentReferenceIdReturnsNullForNullOrUnmatchedUrl() {
    assertNull(viewModel.extractDocumentReferenceIdFromUrl(null))
    assertNull(viewModel.extractDocumentReferenceIdFromUrl(""))
    assertNull(viewModel.extractDocumentReferenceIdFromUrl("https://example.org/Patient/1/x"))
    // Needs the trailing slash after the id to match.
    assertNull(viewModel.extractDocumentReferenceIdFromUrl("https://example.org/DocumentReference/abc"))
  }

  // ───────────────────────────── helpers ─────────────────────────────

  private fun documentReference(id: String, caseType: String): DocumentReference =
    DocumentReference().apply {
      this.id = id
      description = caseType
    }

  private fun docRefUrl(id: String): String =
    "https://example.org/fhir/DocumentReference/$id/\$binary-access-read?path=DocumentReference.content.attachment"

  private fun attachmentAnswer(documentReferenceId: String) =
    attachmentAnswerWithUrl(docRefUrl(documentReferenceId))

  private fun attachmentAnswerWithUrl(url: String) =
    QuestionnaireResponse.QuestionnaireResponseItemAnswerComponent().apply {
      value =
        Attachment().apply {
          this.url = url
          title = "IMG.jpeg"
          contentType = "image/jpeg"
        }
    }

  private fun imageItem(
    linkId: String,
    vararg answers: QuestionnaireResponse.QuestionnaireResponseItemAnswerComponent,
  ): QuestionnaireResponse.QuestionnaireResponseItemComponent =
    QuestionnaireResponse.QuestionnaireResponseItemComponent().apply {
      this.linkId = linkId
      answers.forEach { answer.add(it) }
    }

  private fun screeningResponse(
    vararg imageItems: QuestionnaireResponse.QuestionnaireResponseItemComponent,
  ): QuestionnaireResponse =
    QuestionnaireResponse().apply {
      addItem().apply {
        linkId = QuestionnaireViewModel.SCREENING_GROUP_LINK_ID
        addItem().apply {
          linkId = QuestionnaireViewModel.PATIENT_SCREENING_IMAGE_GROUP_LINK_ID
          imageItems.forEach { addItem(it) }
        }
      }
    }

  private fun QuestionnaireResponse.imageItem(
    linkId: String,
  ): QuestionnaireResponse.QuestionnaireResponseItemComponent =
    item
      .first { it.linkId == QuestionnaireViewModel.SCREENING_GROUP_LINK_ID }
      .item
      .first { it.linkId == QuestionnaireViewModel.PATIENT_SCREENING_IMAGE_GROUP_LINK_ID }
      .item
      .first { it.linkId == linkId }
}
