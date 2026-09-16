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

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

/**
 * The failure detail a stuck device needs is inside a retrofit [HttpException]: the status, the
 * request that was sent and the server's `OperationOutcome` body. [SyncFailureEntry.from] must pull
 * all three out, because the body is a one-shot stream nobody else will get to read.
 */
class SyncFailureEntryTest {

  private val operationOutcome =
    """{"resourceType":"OperationOutcome","issue":[{"severity":"error","code":"processing","diagnostics":"HAPI-0550: Resource Patient/abc is not known"}]}"""

  private fun httpException(code: Int, body: String, url: String = "https://fhir.example.org/fhir"): HttpException {
    val raw =
      okhttp3.Response.Builder()
        .request(Request.Builder().url(url).post("{}".toRequestBody("application/json".toMediaType())).build())
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("Bad Request")
        .build()
    return HttpException(
      Response.error<Any>(body.toResponseBody("application/fhir+json".toMediaType()), raw),
    )
  }

  @Test
  fun `http failure captures status, request and server body`() {
    val entry =
      SyncFailureEntry.from(
        runId = "run-1",
        phase = AppSyncWorker.METADATA_SYNC_PHASE,
        resourceType = "Bundle",
        throwable = httpException(400, operationOutcome, url = "https://fhir.example.org/fhir?_id=patient-123"),
      )

    assertEquals(400, entry.httpStatus)
    assertEquals("POST", entry.requestMethod)
    assertEquals("https://fhir.example.org/fhir", entry.requestUrl)
    assertEquals(operationOutcome, entry.responseBody)
    assertEquals("Bundle", entry.resourceType)
    assertEquals(HttpException::class.java.name, entry.exceptionType)
    assertTrue(entry.summary().contains("HTTP 400"))
    assertTrue(entry.summary().contains("HAPI-0550"))
  }

  @Test
  fun `http failure wrapped in another exception is still found`() {
    val wrapped = RuntimeException("upload failed", httpException(422, operationOutcome))
    val entry = SyncFailureEntry.from("run-1", "metadata_sync", "Bundle", wrapped)

    assertEquals(422, entry.httpStatus)
    assertEquals(operationOutcome, entry.responseBody)
    assertEquals(1, entry.causeChain.size)
    assertTrue(entry.causeChain.first().startsWith(HttpException::class.java.name))
  }

  @Test
  fun `non-http failure has no http fields and keeps its message`() {
    val entry = SyncFailureEntry.from("run-1", "metadata_sync", "Patient", IOException("unexpected end of stream"))

    assertNull(entry.httpStatus)
    assertNull(entry.responseBody)
    assertNull(entry.requestUrl)
    assertEquals("unexpected end of stream", entry.message)
    assertEquals("metadata_sync [Patient]: java.io.IOException - unexpected end of stream", entry.summary())
  }

  @Test
  fun `oversized body is truncated`() {
    val huge = "x".repeat(SyncFailureEntry.MAX_BODY_CHARS * 2)
    val entry = SyncFailureEntry.from("run-1", "metadata_sync", "Bundle", httpException(500, huge))

    assertEquals(SyncFailureEntry.MAX_BODY_CHARS, entry.responseBody?.length)
  }
}
