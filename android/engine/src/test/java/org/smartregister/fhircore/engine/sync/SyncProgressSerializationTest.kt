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

import com.google.android.fhir.sync.SyncJobStatus
import com.google.android.fhir.sync.SyncOperation
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.smartregister.fhircore.engine.util.TimeZoneTypeAdapter

/**
 * The image-upload worker publishes its progress as JSON that the FHIR SDK deserializes with *its*
 * Gson. That makes the encoding a wire contract, not a local choice.
 *
 * [SyncJobStatus] carries `timestamp: OffsetDateTime`. The SDK's adapter writes it as an ISO-8601
 * string and reads it back with `JsonReader.nextString()`, so any writer that reflects the
 * OffsetDateTime into a nested object crashes the SDK's reader with
 * `Expected a string but was BEGIN_OBJECT at path $.timestamp` — on the main thread, in the
 * LiveData-backed status flow.
 */
class SyncProgressSerializationTest {

  /** Mirrors `com.google.android.fhir.Sync.gson`, whose adapter is internal to the SDK. */
  private val sdkGson: Gson =
    GsonBuilder()
      .registerTypeAdapter(
        OffsetDateTime::class.java,
        object : TypeAdapter<OffsetDateTime>() {
          override fun write(out: JsonWriter, value: OffsetDateTime) {
            out.value(DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(value))
          }

          override fun read(input: JsonReader): OffsetDateTime =
            OffsetDateTime.parse(input.nextString())
        }
          .nullSafe(),
      )
      .create()

  /** Exactly what `NetworkModule.provideGson()` builds — the one that caused the crash. */
  private val applicationGson: Gson =
    GsonBuilder()
      .setLenient()
      .registerTypeAdapter(TimeZone::class.java, TimeZoneTypeAdapter().nullSafe())
      .create()

  private val status =
    SyncJobStatus.InProgress(SyncOperation.UPLOAD, total = 30, completed = 7)

  /**
   * The failure *shape* differs by runtime, so this asserts only that the round trip cannot
   * succeed — which is the property that matters and is true on both.
   *
   * On the JVM, Gson refuses to reflect into `java.time.OffsetDateTime` at all and throws while
   * writing (`Failed making field java.time.OffsetDateTime#dateTime accessible`). On Android there
   * is no such module protection, so the write succeeds and produces
   * `"timestamp":{"dateTime":{...},"offset":{...}}`; the SDK's reader then throws
   * `Expected a string but was BEGIN_OBJECT at path $.timestamp`, which is the crash seen in
   * production. Either way the application Gson must never be used for worker progress.
   */
  @Test
  fun `the application Gson cannot produce progress the SDK reader accepts`() {
    val roundTrip =
      runCatching {
        val json = applicationGson.toJson(status)
        sdkGson.fromJson(json, SyncJobStatus.InProgress::class.java)
      }

    assertTrue(
      "the application Gson round-tripped, so this test no longer guards anything",
      roundTrip.isFailure,
    )
  }

  @Test
  fun `the worker's progress Gson round-trips through the SDK reader`() {
    val json = progressGson.toJson(status)

    assertTrue(
      "timestamp must be a quoted ISO-8601 string, was: $json",
      Regex(""""timestamp"\s*:\s*"[^"]+"""").containsMatchIn(json),
    )

    val decoded = sdkGson.fromJson(json, SyncJobStatus.InProgress::class.java)

    assertEquals(SyncOperation.UPLOAD, decoded.syncOperation)
    assertEquals(30, decoded.total)
    assertEquals(7, decoded.completed)
  }

  /** Same construction as `AppSyncWorker.progressGson`; kept here so the contract is asserted. */
  private val progressGson: Gson =
    GsonBuilder()
      .registerTypeAdapter(
        OffsetDateTime::class.java,
        object : TypeAdapter<OffsetDateTime>() {
          override fun write(out: JsonWriter, value: OffsetDateTime) {
            out.value(DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(value))
          }

          override fun read(input: JsonReader): OffsetDateTime =
            OffsetDateTime.parse(input.nextString())
        }
          .nullSafe(),
      )
      .create()
}
