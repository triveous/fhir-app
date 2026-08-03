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

import com.google.android.fhir.sync.DownloadWorkManager
import com.google.android.fhir.sync.download.DownloadRequest
import com.google.android.fhir.sync.download.ResourceParamsBasedDownloadWorkManager
import com.google.android.fhir.sync.download.ResourceSearchParams
import java.time.OffsetDateTime
import org.hl7.fhir.r4.model.Bundle
import org.hl7.fhir.r4.model.Resource
import org.hl7.fhir.r4.model.ResourceType
import org.smartregister.fhircore.engine.util.extension.updateLastUpdated
import timber.log.Timber

/**
 * Downloads resources per the configured sync params, and — crucially — decides when it is safe to
 * remember how far the download got.
 *
 * ### Why this class does not simply delegate the timestamps
 *
 * Each resource type is downloaded page by page, and the next sync resumes a type with
 * `_lastUpdated=gt<watermark>`. The SDK's [ResourceParamsBasedDownloadWorkManager] persists that
 * watermark **after every page**, moving it to the newest `meta.lastUpdated` it has seen so far.
 *
 * That is only sound once a resource type has been downloaded *completely*. If a run stops in the
 * middle of one — the app is force-stopped, WorkManager cancels the worker, the connection drops
 * (`DownloaderImpl` catches the error and moves on to the next type) — the watermark has already
 * advanced past rows that were never fetched. Those rows are still on the server with an equal or
 * older `lastUpdated`, so every later sync asks for `gt<watermark>` and the server correctly answers
 * "nothing new". They are skipped **permanently**: re-syncing does not recover them, only a
 * reinstall does. A first-time sync interrupted part-way through `Patient` therefore leaves the
 * register empty forever.
 *
 * So the watermark is staged in memory here and only written to [context] when the resource type it
 * belongs to has genuinely finished — the page that carries no `next` link. An interrupted type
 * keeps the watermark of its last *complete* run and is re-requested from there next time. Re-
 * downloading a partially-fetched type costs bandwidth; skipping records loses data, so the trade is
 * made in favour of completeness.
 *
 * The staged value is also kept monotonic and never cleared by a null, so a page whose resources
 * carry no `meta.lastUpdated` cannot reset a resource type back to a full download.
 */
class OpenSrpDownloadManager(
  syncParams: ResourceSearchParams,
  val context: ResourceParamsBasedDownloadWorkManager.TimestampContext,
) : DownloadWorkManager {

  private val stagedTimestamps = StagedTimestampContext(context)

  private val downloadWorkManager =
    ResourceParamsBasedDownloadWorkManager(syncParams, stagedTimestamps)

  /**
   * Whether the response just processed advertised a `next` link, i.e. the resource type in flight
   * has more pages and the request that follows continues it. When it does not, the request that
   * follows starts a different resource type — and anything still staged belongs to a type that
   * ended without reaching its last page, which is exactly the partial state that must not be
   * persisted.
   */
  private var awaitingNextPage = false

  override suspend fun getNextRequest(): DownloadRequest? {
    if (!awaitingNextPage) stagedTimestamps.discardStaged()
    awaitingNextPage = false
    return downloadWorkManager.getNextRequest()
  }

  override suspend fun getSummaryRequestUrls(): Map<ResourceType, String> =
    downloadWorkManager.getSummaryRequestUrls()

  override suspend fun processResponse(response: Resource): Collection<Resource> {
    // Delegate first: this is what stages the watermark for the resources on this page. It also
    // throws on an OperationOutcome, in which case nothing is staged or committed.
    val resources = downloadWorkManager.processResponse(response).onEach { it.updateLastUpdated() }

    if (response.isSearchSetWithMorePages()) {
      awaitingNextPage = true
    } else {
      // No more pages for this resource type: everything the server has, we now have.
      stagedTimestamps.commitStaged()
    }
    return resources
  }

  private fun Resource.isSearchSetWithMorePages(): Boolean =
    this is Bundle &&
      type == Bundle.BundleType.SEARCHSET &&
      link.any { it.relation == NEXT_PAGE_RELATION }

  /**
   * Buffers the per-page watermarks the SDK writes and passes them on to [delegate] only when
   * [commitStaged] says the resource type finished. See [OpenSrpDownloadManager] for why.
   */
  internal class StagedTimestampContext(
    private val delegate: ResourceParamsBasedDownloadWorkManager.TimestampContext,
  ) : ResourceParamsBasedDownloadWorkManager.TimestampContext {

    private val staged = mutableMapOf<ResourceType, String>()

    override suspend fun saveLastUpdatedTimestamp(resourceType: ResourceType, timestamp: String?) {
      // A page whose resources have no meta.lastUpdated yields null. Persisting it would wipe the
      // resource type's watermark and force a full re-download on the next sync.
      if (timestamp.isNullOrEmpty()) return
      val current = staged[resourceType]
      staged[resourceType] = if (current == null) timestamp else latestOf(current, timestamp)
    }

    override suspend fun getLasUpdateTimestamp(resourceType: ResourceType): String? =
      staged[resourceType] ?: delegate.getLasUpdateTimestamp(resourceType)

    /** Persists every staged watermark, never moving a resource type backwards in time. */
    suspend fun commitStaged() {
      if (staged.isEmpty()) return
      staged.forEach { (resourceType, timestamp) ->
        val persisted = delegate.getLasUpdateTimestamp(resourceType)
        val newest = if (persisted.isNullOrEmpty()) timestamp else latestOf(persisted, timestamp)
        if (newest != persisted) delegate.saveLastUpdatedTimestamp(resourceType, newest)
      }
      staged.clear()
    }

    /** Drops watermarks belonging to a resource type that never reached its last page. */
    fun discardStaged() {
      if (staged.isEmpty()) return
      Timber.w(
        "Discarding un-committed sync watermarks for %s; those resource types did not finish downloading and will be re-requested from their last complete point",
        staged.keys.joinToString(),
      )
      staged.clear()
    }

    /**
     * The later of two timestamps. They are ISO-8601 with an offset
     * (`ResourceParamsBasedDownloadWorkManager` writes `yyyy-MM-dd'T'HH:mm:ss.SSSXXX`), so they are
     * compared as instants rather than as strings — two equal instants written in different
     * time zones do not compare correctly as text. An unparseable value falls back to [candidate],
     * matching what the SDK would have stored on its own.
     */
    private fun latestOf(current: String, candidate: String): String =
      runCatching {
          if (OffsetDateTime.parse(candidate).isAfter(OffsetDateTime.parse(current))) {
            candidate
          } else {
            current
          }
        }
        .getOrElse {
          Timber.w(it, "Could not compare sync timestamps '%s' and '%s'", current, candidate)
          candidate
        }
  }

  companion object {
    private const val NEXT_PAGE_RELATION = "next"
  }
}
