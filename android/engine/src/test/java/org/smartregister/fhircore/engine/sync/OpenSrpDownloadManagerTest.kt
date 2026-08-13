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

import com.google.android.fhir.sync.download.ResourceParamsBasedDownloadWorkManager
import com.google.android.fhir.sync.download.UrlDownloadRequest
import java.net.URLEncoder
import java.time.Instant
import java.util.Date
import kotlinx.coroutines.test.runTest
import org.hl7.fhir.r4.model.Bundle
import org.hl7.fhir.r4.model.Patient
import org.hl7.fhir.r4.model.ResourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The download watermark — the `_lastUpdated` a resource type resumes from on the next sync.
 *
 * The property under test is that a watermark is only persisted for a resource type that finished
 * downloading. Persisting one mid-pagination is what made an interrupted first-time sync lose data
 * permanently: the next sync asks the server for `_lastUpdated=gt<watermark>` and the pages that
 * were never fetched are older than that, so they are never requested again.
 *
 * The calls below follow the order `DownloaderImpl` uses: `getNextRequest()`, then
 * `processResponse()` with what that request returned, repeated until there is no next request. A
 * request that fails is modelled by skipping its `processResponse` — which is exactly what
 * `DownloaderImpl` does when `dataSource.download()` throws.
 */
class OpenSrpDownloadManagerTest {

  private val syncParams = mapOf(ResourceType.Patient to mapOf("_count" to "2"))

  /** Records what actually reaches persistent storage. */
  private class FakeTimestampContext(initial: Map<ResourceType, String> = emptyMap()) :
    ResourceParamsBasedDownloadWorkManager.TimestampContext {
    val persisted = initial.toMutableMap()
    var writes = 0
      private set

    override suspend fun saveLastUpdatedTimestamp(resourceType: ResourceType, timestamp: String?) {
      writes++
      if (timestamp == null) persisted.remove(resourceType) else persisted[resourceType] = timestamp
    }

    override suspend fun getLasUpdateTimestamp(resourceType: ResourceType): String? =
      persisted[resourceType]
  }

  private fun patientPage(vararg lastUpdated: String?, nextPageUrl: String? = null) =
    Bundle().apply {
      type = Bundle.BundleType.SEARCHSET
      lastUpdated.forEach { timestamp ->
        addEntry(
          Bundle.BundleEntryComponent().apply {
            resource =
              Patient().apply {
                id = "patient-${timestamp ?: "no-timestamp"}"
                if (timestamp != null) meta.lastUpdated = Date.from(Instant.parse(timestamp))
              }
          },
        )
      }
      nextPageUrl?.let { addLink().apply { relation = "next"; url = it } }
    }

  // ── the completed download ───────────────────────────────────────────────────────────────────

  @Test
  fun `a resource type that downloads in one page persists its watermark`() = runTest {
    val timestamps = FakeTimestampContext()
    val manager = OpenSrpDownloadManager(syncParams, timestamps)

    manager.getNextRequest()
    manager.processResponse(patientPage("2026-08-01T10:00:00Z", "2026-08-01T11:00:00Z"))

    assertEquals("the newest resource on the page", 1, timestamps.persisted.size)
    assertTrue(timestamps.persisted.getValue(ResourceType.Patient).startsWith("2026-08-01T"))
  }

  @Test
  fun `a paginated resource type persists nothing until its last page arrives`() = runTest {
    val timestamps = FakeTimestampContext()
    val manager = OpenSrpDownloadManager(syncParams, timestamps)

    manager.getNextRequest()
    manager.processResponse(patientPage("2026-08-01T10:00:00Z", nextPageUrl = "Patient?page=2"))
    assertTrue("page 1 is not the whole type", timestamps.persisted.isEmpty())

    manager.getNextRequest()
    manager.processResponse(patientPage("2026-08-01T12:00:00Z", nextPageUrl = "Patient?page=3"))
    assertTrue("page 2 is still not the whole type", timestamps.persisted.isEmpty())

    manager.getNextRequest()
    manager.processResponse(patientPage("2026-08-01T14:00:00Z"))
    assertEquals(
      "the newest resource across every page",
      Instant.parse("2026-08-01T14:00:00Z"),
      Instant.parse(timestamps.persisted.getValue(ResourceType.Patient)),
    )
  }

  // ── the interrupted download ─────────────────────────────────────────────────────────────────

  @Test
  fun `a download stopped mid-pagination leaves no watermark behind`() = runTest {
    val timestamps = FakeTimestampContext()
    val manager = OpenSrpDownloadManager(syncParams, timestamps)

    // The process is force-stopped here: page 2 is requested but its response never comes back.
    manager.getNextRequest()
    manager.processResponse(patientPage("2026-08-01T10:00:00Z", nextPageUrl = "Patient?page=2"))
    manager.getNextRequest()

    assertNull(timestamps.persisted[ResourceType.Patient])
    assertEquals("nothing was written", 0, timestamps.writes)
  }

  @Test
  fun `a page request that fails discards the watermark staged by the pages before it`() = runTest {
    val timestamps = FakeTimestampContext()
    val manager = OpenSrpDownloadManager(syncParams, timestamps)

    manager.getNextRequest()
    manager.processResponse(patientPage("2026-08-01T10:00:00Z", nextPageUrl = "Patient?page=2"))
    manager.getNextRequest() // page 2 …
    // … whose download throws, so DownloaderImpl skips processResponse and asks for the next thing.
    manager.getNextRequest()

    assertNull(
      "page 1's watermark would skip every row page 2 onwards was going to carry",
      timestamps.persisted[ResourceType.Patient],
    )
  }

  @Test
  fun `an interrupted type resumes from the watermark of its last complete run`() = runTest {
    val previousRun = "2026-07-01T09:00:00Z"
    val timestamps = FakeTimestampContext(mapOf(ResourceType.Patient to previousRun))
    val manager = OpenSrpDownloadManager(syncParams, timestamps)

    val request = manager.getNextRequest() as UrlDownloadRequest
    assertTrue(
      "the request resumes from the last complete run, but was ${request.url}",
      request.url.contains("_lastUpdated=gt${URLEncoder.encode(previousRun, "UTF-8")}"),
    )

    manager.processResponse(patientPage("2026-08-01T10:00:00Z", nextPageUrl = "Patient?page=2"))
    manager.getNextRequest()

    assertEquals(
      "an interrupted run must not move the resume point forward",
      previousRun,
      timestamps.persisted.getValue(ResourceType.Patient),
    )
  }

  // ── watermarks that would cause a re-download or skip rows ───────────────────────────────────

  @Test
  fun `a page carrying no lastUpdated cannot clear an existing watermark`() = runTest {
    val previousRun = "2026-07-01T09:00:00Z"
    val timestamps = FakeTimestampContext(mapOf(ResourceType.Patient to previousRun))
    val manager = OpenSrpDownloadManager(syncParams, timestamps)

    manager.getNextRequest()
    manager.processResponse(patientPage(null))

    assertEquals(
      "clearing it would force a full re-download of the resource type",
      previousRun,
      timestamps.persisted.getValue(ResourceType.Patient),
    )
  }

  @Test
  fun `a watermark never moves backwards`() = runTest {
    val newerRun = "2026-08-10T09:00:00Z"
    val timestamps = FakeTimestampContext(mapOf(ResourceType.Patient to newerRun))
    val manager = OpenSrpDownloadManager(syncParams, timestamps)

    manager.getNextRequest()
    manager.processResponse(patientPage("2026-08-01T10:00:00Z"))

    assertEquals(
      newerRun,
      timestamps.persisted.getValue(ResourceType.Patient),
    )
  }

  @Test
  fun `resources are stamped with a local lastUpdated before they are stored`() = runTest {
    val timestamps = FakeTimestampContext()
    val manager = OpenSrpDownloadManager(syncParams, timestamps)
    val serverTimestamp = Instant.parse("2026-08-01T10:00:00Z")

    manager.getNextRequest()
    val resources = manager.processResponse(patientPage(serverTimestamp.toString()))

    assertEquals(1, resources.size)
    assertTrue(
      "the stored copy is stamped locally, while the watermark keeps the server's value",
      resources.first().meta.lastUpdated.toInstant().isAfter(serverTimestamp),
    )
    assertEquals(
      serverTimestamp,
      Instant.parse(timestamps.persisted.getValue(ResourceType.Patient)),
    )
  }
}
