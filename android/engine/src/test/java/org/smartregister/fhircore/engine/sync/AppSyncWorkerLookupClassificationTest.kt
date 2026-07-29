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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * The one decision the whole image-loss fix turns on: a failed `GET /DocumentReference/{id}` must
 * only be read as "the server does not have this" when the server actually said 404.
 *
 * Before this rule existed, every failure collapsed to `null`, and production logged 36,207
 * "No DocumentReference on server" lines that were really "the phone had no internet". Downstream
 * that drove a metadata-only PUT over resources that already held their image, and a force-purge of
 * documents the server had never received.
 */
class AppSyncWorkerLookupClassificationTest {

  private fun httpException(code: Int, message: String = "err") =
    HttpException(
      Response.error<Any>(code, message.toResponseBody("text/plain".toMediaType())),
    )

  private fun classify(error: Throwable) =
    AppSyncWorker.classifyServerLookupFailure("doc-1", error)

  @Test
  fun `http 404 is the only response that proves absence`() {
    assertTrue(classify(httpException(404)) is AppSyncWorker.ServerDocumentLookup.NotFound)
  }

  @Test
  fun `synthetic offline and timeout codes are treated as unknown, not absent`() {
    // NetworkModule.buildErrorResponse manufactures these; they never came from the server.
    // 900 FAILED_TO_COMPLETE_REQUEST, 901 FAILED_TO_OVERWRITE_URL, 902 UNKNOWN, 903 NO_INTERNET.
    listOf(900, 901, 902, 903).forEach { code ->
      assertTrue(
        "HTTP $code must not be read as absence",
        classify(httpException(code)) is AppSyncWorker.ServerDocumentLookup.Unavailable,
      )
    }
  }

  @Test
  fun `auth and server errors are treated as unknown, not absent`() {
    listOf(401, 403, 409, 410, 500, 502, 503, 504).forEach { code ->
      assertTrue(
        "HTTP $code must not be read as absence",
        classify(httpException(code)) is AppSyncWorker.ServerDocumentLookup.Unavailable,
      )
    }
  }

  @Test
  fun `transport failures are treated as unknown, not absent`() {
    listOf(
      UnknownHostException("Unable to resolve host \"site-5-production.example\""),
      SocketTimeoutException("timeout"),
      IOException("connection closed"),
    )
      .forEach { error ->
        assertTrue(
          "${error::class.simpleName} must not be read as absence",
          classify(error) is AppSyncWorker.ServerDocumentLookup.Unavailable,
        )
      }
  }

  // ── Which queued local changes make it unsafe to write a binary ─────────────────────────────

  @Test
  fun `a pending INSERT is unsafe - it squashes to a full-resource PUT`() {
    assertTrue(
      AppSyncWorker.wouldOverwriteResourceOnUpload(listOf(LocalChange.Type.INSERT)),
    )
    // INSERT followed by later edits still squashes to a single PUT of the whole resource.
    assertTrue(
      AppSyncWorker.wouldOverwriteResourceOnUpload(
        listOf(LocalChange.Type.INSERT, LocalChange.Type.UPDATE, LocalChange.Type.UPDATE),
      ),
    )
  }

  @Test
  fun `a pending DELETE is unsafe`() {
    assertTrue(
      AppSyncWorker.wouldOverwriteResourceOnUpload(
        listOf(LocalChange.Type.UPDATE, LocalChange.Type.DELETE),
      ),
    )
  }

  @Test
  fun `UPDATE-only is safe - it uploads as a JSON PATCH, not a whole-resource write`() {
    // This is the state every freshly submitted case is in: the DRAFT -> SUBMITTED flip leaves one
    // UPDATE behind. Treating it as unsafe would defer every case's images by a whole sync cycle.
    assertFalse(AppSyncWorker.wouldOverwriteResourceOnUpload(listOf(LocalChange.Type.UPDATE)))
    assertFalse(
      AppSyncWorker.wouldOverwriteResourceOnUpload(
        listOf(LocalChange.Type.UPDATE, LocalChange.Type.UPDATE),
      ),
    )
  }

  @Test
  fun `no pending changes is safe`() {
    assertFalse(AppSyncWorker.wouldOverwriteResourceOnUpload(emptyList()))
  }

  @Test
  fun `unavailable carries the originating error for diagnostics`() {
    val boom = SocketTimeoutException("timeout")

    val result = classify(boom)

    assertTrue(result is AppSyncWorker.ServerDocumentLookup.Unavailable)
    assertTrue((result as AppSyncWorker.ServerDocumentLookup.Unavailable).error === boom)
  }
}
