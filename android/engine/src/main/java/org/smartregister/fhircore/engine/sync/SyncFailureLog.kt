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

import android.content.Context
import com.google.gson.GsonBuilder
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton
import retrofit2.HttpException
import timber.log.Timber

/**
 * One sync failure, flattened to what a person debugging a stuck device needs to read.
 *
 * The FHIR SDK reports an upload/download failure as a `ResourceSyncException(resourceType,
 * exception)`; for an HTTP failure the useful part — the status code and the server's
 * `OperationOutcome` body — is buried inside a retrofit [HttpException] whose `message` is just
 * `"HTTP 400 Bad Request"`. This pulls those out once, at the point of failure, because the error
 * body is a one-shot stream that is gone by the time anyone else looks.
 */
data class SyncFailureEntry(
  val recordedAt: String,
  val runId: String?,
  /** `metadata_download`, `metadata_upload`, `worker` — which part of the sync produced it. */
  val phase: String,
  val resourceType: String?,
  val exceptionType: String,
  val message: String?,
  val httpStatus: Int? = null,
  val requestMethod: String? = null,
  val requestUrl: String? = null,
  /**
   * Server response body, usually an `OperationOutcome`; truncated to [MAX_BODY_CHARS]. This is
   * the failure reason a stuck device needs, so it is kept verbatim — HAPI diagnostics name
   * resource ids and validation messages, not patient fields.
   */
  val responseBody: String? = null,
  val causeChain: List<String> = emptyList(),
) {
  /** One line suitable for a log message or an analytics property. */
  fun summary(): String =
    buildString {
      append(phase)
      resourceType?.let { append(" [").append(it).append("]") }
      append(": ").append(exceptionType)
      httpStatus?.let { append(" HTTP ").append(it) }
      message?.takeIf { it.isNotBlank() }?.let { append(" - ").append(it) }
      responseBody?.takeIf { it.isNotBlank() }?.let {
        append(" | server: ").append(it.take(SUMMARY_BODY_CHARS))
      }
    }

  companion object {
    const val MAX_BODY_CHARS = 16_000
    private const val SUMMARY_BODY_CHARS = 1_000
    private const val MAX_CAUSE_DEPTH = 10

    fun from(
      runId: String?,
      phase: String,
      resourceType: String?,
      throwable: Throwable,
    ): SyncFailureEntry {
      val http = throwable.findHttpException()
      val rawRequest = http?.response()?.raw()?.request
      // Read once and keep it: errorBody() is a stream and cannot be re-read later.
      val body =
        runCatching { http?.response()?.errorBody()?.string() }
          .onFailure { Timber.d(it, "Could not read sync failure response body") }
          .getOrNull()
          ?.take(MAX_BODY_CHARS)
      return SyncFailureEntry(
        recordedAt = SyncFailureLog.isoNow(),
        runId = runId,
        phase = phase,
        resourceType = resourceType,
        exceptionType = throwable::class.java.name,
        message = throwable.message ?: throwable.toString(),
        httpStatus = http?.code(),
        requestMethod = rawRequest?.method,
        // Path only: a query string can carry patient identifiers or search terms.
        requestUrl = rawRequest?.url?.let { "${it.scheme}://${it.host}${it.encodedPath}" },
        responseBody = body,
        causeChain = throwable.causeChain(),
      )
    }

    private fun Throwable.findHttpException(): HttpException? {
      var current: Throwable? = this
      repeat(MAX_CAUSE_DEPTH) {
        val t = current ?: return null
        if (t is HttpException) return t
        current = t.cause?.takeIf { it !== t }
      }
      return null
    }

    private fun Throwable.causeChain(): List<String> {
      val chain = mutableListOf<String>()
      var current: Throwable? = this.cause
      repeat(MAX_CAUSE_DEPTH) {
        val t = current ?: return chain
        chain += "${t::class.java.name}: ${t.message}"
        current = t.cause?.takeIf { it !== t }
      }
      return chain
    }
  }
}

/**
 * Append-only, bounded, on-device record of sync failures.
 *
 * Written as JSON lines to `files/sync_diagnostics/sync_failures.jsonl` so it survives process
 * death, can be read back without the app (it ships inside the unsynced-data export), and stays
 * small — only the newest [MAX_ENTRIES] lines are kept. Everything here is best effort: a failure
 * to write the log must never fail the sync it is describing.
 */
@Singleton
class SyncFailureLog @Inject constructor(@ApplicationContext private val context: Context) {

  private val gson = GsonBuilder().disableHtmlEscaping().create()

  val file: File
    get() = File(File(context.filesDir, DIRECTORY), FILE_NAME)

  @Synchronized
  fun record(entry: SyncFailureEntry) {
    runCatching {
      file.parentFile?.mkdirs()
      file.appendText(gson.toJson(entry) + "\n")
      trimIfNeeded()
    }
      .onFailure { Timber.w(it, "Could not record sync failure") }
  }

  /** Newest last. Returns raw JSON lines so a corrupt line never hides the rest. */
  @Synchronized
  fun readAll(): List<String> =
    runCatching { if (file.exists()) file.readLines().filter { it.isNotBlank() } else emptyList() }
      .getOrDefault(emptyList())

  @Synchronized
  fun clear() {
    runCatching { file.delete() }
  }

  private fun trimIfNeeded() {
    if (file.length() < TRIM_CHECK_BYTES) return
    val lines = file.readLines().filter { it.isNotBlank() }
    if (lines.size > MAX_ENTRIES) {
      file.writeText(lines.takeLast(MAX_ENTRIES).joinToString("\n", postfix = "\n"))
    }
  }

  companion object {
    const val DIRECTORY = "sync_diagnostics"
    const val FILE_NAME = "sync_failures.jsonl"
    const val MAX_ENTRIES = 200

    /** Only re-scan the file for trimming once it is big enough to plausibly need it. */
    private const val TRIM_CHECK_BYTES = 512 * 1024L

    fun isoNow(): String =
      SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)
        .apply { timeZone = TimeZone.getDefault() }
        .format(Date())
  }
}
