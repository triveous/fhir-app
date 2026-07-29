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

package org.smartregister.fhircore.engine.util

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [UploadedDocumentReferenceLedger]. [SharedPreferencesHelper] is faked with an
 * in-memory string so the ledger's own read / write / eviction behaviour is exercised without
 * Robolectric.
 */
class UploadedDocumentReferenceLedgerTest {

  private val sharedPreferencesHelper: SharedPreferencesHelper = mockk()
  private var stored: String? = null
  private lateinit var ledger: UploadedDocumentReferenceLedger

  private val key = SharedPreferenceKey.UPLOADED_DOCUMENT_REFERENCES.name

  @Before
  fun setUp() {
    stored = null
    val valueSlot = slot<String>()
    every { sharedPreferencesHelper.read(key, null) } answers { stored }
    every { sharedPreferencesHelper.write(key, capture(valueSlot)) } answers
      {
        stored = valueSlot.captured
      }
    every { sharedPreferencesHelper.remove(key) } answers { stored = null }
    ledger = UploadedDocumentReferenceLedger(sharedPreferencesHelper)
  }

  @Test
  fun recordedIdIsReportedAsUploaded() {
    ledger.recordUploaded("doc-1")

    assertTrue(ledger.wasUploaded("doc-1"))
  }

  @Test
  fun unrecordedIdIsNotReportedAsUploaded() {
    ledger.recordUploaded("doc-1")

    assertFalse(ledger.wasUploaded("doc-2"))
  }

  @Test
  fun nullOrBlankIdsAreNeverConsideredUploaded() {
    ledger.recordUploaded("")

    assertFalse(ledger.wasUploaded(null))
    assertFalse(ledger.wasUploaded(""))
  }

  @Test
  fun multipleIdsAreAllRetained() {
    val ids = (1..25).map { "doc-$it" }

    ids.forEach { ledger.recordUploaded(it) }

    ids.forEach { assertTrue("expected $it to be recorded", ledger.wasUploaded(it)) }
  }

  @Test
  fun recordingIsIdempotent() {
    repeat(3) { ledger.recordUploaded("doc-1") }

    assertTrue(ledger.wasUploaded("doc-1"))
    // No duplicate entries accumulate for a repeatedly uploaded id.
    assertTrue(stored == "doc-1")
  }

  @Test
  fun oldestEntriesAreEvictedOnceTheCapIsReached() {
    val overflow = UploadedDocumentReferenceLedger.MAX_ENTRIES + 5
    (1..overflow).forEach { ledger.recordUploaded("doc-$it") }

    // The first five fell out of the window; everything after is still provable.
    assertFalse(ledger.wasUploaded("doc-1"))
    assertFalse(ledger.wasUploaded("doc-5"))
    assertTrue(ledger.wasUploaded("doc-6"))
    assertTrue(ledger.wasUploaded("doc-$overflow"))
  }

  @Test
  fun clearRemovesEverything() {
    ledger.recordUploaded("doc-1")

    ledger.clear()

    assertFalse(ledger.wasUploaded("doc-1"))
  }

  @Test
  fun aFailingPreferenceStoreDegradesToNotUploaded() {
    every { sharedPreferencesHelper.read(key, null) } throws IllegalStateException("prefs gone")

    // Never throws into the sync worker or the submit path...
    ledger.recordUploaded("doc-1")
    // ...and answers conservatively, which keeps the dangling-reference guard active.
    assertFalse(ledger.wasUploaded("doc-1"))
  }
}
