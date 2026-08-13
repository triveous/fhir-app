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

import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Durable record of the `DocumentReference` ids that the sync worker confirmed on the server
 * immediately before purging them from the local `FhirEngine`.
 *
 * A `DocumentReference` lives in the engine only until its image has been uploaded and verified;
 * the id it was given lives on forever inside the `QuestionnaireResponse` attachment URL. Without
 * this ledger the two states below are indistinguishable at submit time, because both present as
 * `ResourceNotFoundException`:
 * - the document was uploaded and then purged — the server **has** it, the URL is valid;
 * - the document was lost before it ever reached the server — the URL will 404.
 *
 * Treating the first case as the second silently strips good images off a submission; treating the
 * second as the first uploads a response pointing at a resource that does not exist. The ledger is
 * what makes them separable.
 *
 * Entries are kept in insertion order and capped at [MAX_ENTRIES]; the oldest are evicted first. A
 * lost entry only costs us the ability to prove an id was uploaded, so eviction degrades to the
 * conservative behaviour rather than to data loss.
 */
@Singleton
class UploadedDocumentReferenceLedger
@Inject
constructor(private val sharedPreferencesHelper: SharedPreferencesHelper) {

  /** Records that [documentReferenceId] is present on the server. Never throws. */
  @Synchronized
  fun recordUploaded(documentReferenceId: String) {
    if (documentReferenceId.isBlank()) return
    runCatching {
      val ids = readIds()
      // Re-insert so a repeat upload refreshes the entry's position in the eviction order.
      ids.remove(documentReferenceId)
      ids.add(documentReferenceId)
      while (ids.size > MAX_ENTRIES) {
        ids.iterator().let {
          it.next()
          it.remove()
        }
      }
      sharedPreferencesHelper.write(
        SharedPreferenceKey.UPLOADED_DOCUMENT_REFERENCES.name,
        ids.joinToString(SEPARATOR),
      )
    }
      .onFailure { Timber.w(it, "Could not record uploaded DocumentReference $documentReferenceId") }
  }

  /**
   * True when [documentReferenceId] was confirmed on the server before being purged locally, i.e.
   * the attachment URL carrying it still resolves even though the resource is no longer on device.
   */
  @Synchronized
  fun wasUploaded(documentReferenceId: String?): Boolean {
    if (documentReferenceId.isNullOrBlank()) return false
    return runCatching { readIds().contains(documentReferenceId) }.getOrDefault(false)
  }

  @Synchronized
  fun clear() {
    sharedPreferencesHelper.remove(SharedPreferenceKey.UPLOADED_DOCUMENT_REFERENCES.name)
  }

  private fun readIds(): LinkedHashSet<String> {
    val raw =
      sharedPreferencesHelper.read(SharedPreferenceKey.UPLOADED_DOCUMENT_REFERENCES.name, null)
    if (raw.isNullOrEmpty()) return LinkedHashSet()
    return raw.split(SEPARATOR).filter { it.isNotBlank() }.toCollection(LinkedHashSet())
  }

  companion object {
    /** ~1000 ids ≈ 37 KB of preferences; far more than a device accumulates between wipes. */
    const val MAX_ENTRIES = 1000
    private const val SEPARATOR = ","
  }
}
