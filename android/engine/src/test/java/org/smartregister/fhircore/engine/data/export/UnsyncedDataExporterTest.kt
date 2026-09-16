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

package org.smartregister.fhircore.engine.data.export

import android.content.Context
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import ca.uhn.fhir.context.FhirContext
import com.google.android.fhir.FhirEngine
import com.google.android.fhir.LocalChange
import com.google.android.fhir.LocalChangeToken
import com.google.android.fhir.SearchResult
import com.google.android.fhir.search.Search
import com.google.gson.JsonParser
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.time.Instant
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.hl7.fhir.r4.model.Bundle
import org.hl7.fhir.r4.model.DocumentReference
import org.hl7.fhir.r4.model.Patient
import org.hl7.fhir.r4.model.ResourceType
import org.hl7.fhir.r4.model.StringType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.smartregister.fhircore.engine.domain.networkUtils.HttpConstants.UPLOAD_IMAGE_URL
import org.smartregister.fhircore.engine.robolectric.RobolectricTest
import org.smartregister.fhircore.engine.sync.SyncFailureEntry
import org.smartregister.fhircore.engine.sync.SyncFailureLog
import org.smartregister.fhircore.engine.util.DispatcherProvider
import org.smartregister.fhircore.engine.util.SecureSharedPreference
import org.smartregister.fhircore.engine.util.SharedPreferencesHelper

class UnsyncedDataExporterTest : RobolectricTest() {

  private val context: Context = ApplicationProvider.getApplicationContext()
  private val fhirEngine = mockk<FhirEngine>()
  private val sharedPreferencesHelper = mockk<SharedPreferencesHelper>(relaxed = true)
  private val secureSharedPreference = mockk<SecureSharedPreference>(relaxed = true)
  private lateinit var syncFailureLog: SyncFailureLog
  private lateinit var exporter: UnsyncedDataExporter

  private val patient = Patient().apply { id = "p1"; addName().family = "Test" }
  private val imageFile: File by lazy {
    File(context.filesDir, "img-1.jpg").apply { writeBytes(ByteArray(64) { 7 }) }
  }
  private val docRef =
    DocumentReference().apply {
      id = "d1"
      description = "submitted"
      addExtension(UPLOAD_IMAGE_URL, StringType(imageFile.toUri().toString()))
    }

  @Before
  fun setUp() {
    syncFailureLog = SyncFailureLog(context).also { it.clear() }
    syncFailureLog.record(
      SyncFailureEntry(
        recordedAt = "now",
        runId = "r1",
        phase = "metadata_sync",
        resourceType = "Bundle",
        exceptionType = "retrofit2.HttpException",
        message = "HTTP 400",
        httpStatus = 400,
        responseBody = """{"resourceType":"OperationOutcome"}""",
      ),
    )
    every { sharedPreferencesHelper.read(any<String>(), null) } returns null
    every { secureSharedPreference.retrieveSessionUsername() } returns "flw1"

    coEvery { fhirEngine.getUnsyncedLocalChanges() } returns
      listOf(
        LocalChange(
          resourceType = "Patient",
          resourceId = "p1",
          versionId = null,
          timestamp = Instant.now(),
          type = LocalChange.Type.INSERT,
          payload = """{"resourceType":"Patient","id":"p1"}""",
          token = LocalChangeToken(listOf(1L)),
        ),
        LocalChange(
          resourceType = "DocumentReference",
          resourceId = "d1",
          versionId = "1",
          timestamp = Instant.now(),
          type = LocalChange.Type.UPDATE,
          payload = """[{"op":"replace","path":"/status","value":"current"}]""",
          token = LocalChangeToken(listOf(2L)),
        ),
      )
    coEvery { fhirEngine.get(ResourceType.Patient, "p1") } returns patient
    coEvery { fhirEngine.get(ResourceType.DocumentReference, "d1") } returns docRef
    coEvery { fhirEngine.search<DocumentReference>(any<Search>()) } returns
      listOf(SearchResult(docRef, included = null, revIncluded = null))

    exporter =
      UnsyncedDataExporter(
        context = context,
        fhirEngine = fhirEngine,
        sharedPreferencesHelper = sharedPreferencesHelper,
        secureSharedPreference = secureSharedPreference,
        syncFailureLog = syncFailureLog,
        dispatcherProvider =
          object : DispatcherProvider {
            override fun io() = Dispatchers.Unconfined
          },
      )
  }

  @Test
  fun `export writes local changes, resources, images, drafts and sync failures`() = runTest {
    val result = exporter.export(appVersion = "test-1")

    assertTrue(result.privateDirectory.isDirectory)
    assertTrue(result.privateZip.exists())
    assertEquals(2, result.localChangeCount)
    assertEquals(2, result.resourceCount)
    assertEquals(1, result.imageCount)
    assertEquals(1, result.syncFailureCount)

    // The directory tree is the restorable artifact; the zip mirrors it for the share sheet.
    val tree = result.privateDirectory.walkTopDown().filter { it.isFile }
      .map { it.relativeTo(result.privateDirectory).path }.toSet()
    assertTrue("restore_bundle.json" in tree)
    assertTrue("resources/Patient/p1.json" in tree)
    assertTrue("images/d1.jpg" in tree)
    assertTrue("README.md" in tree)

    // restore_bundle.json is a valid FHIR transaction with a PUT per pending resource, and the
    // per-resource files parse back into the same resources.
    val parser = FhirContext.forR4Cached().newJsonParser()
    val bundle = parser.parseResource(File(result.privateDirectory, "restore_bundle.json").readText()) as Bundle
    assertEquals(Bundle.BundleType.TRANSACTION, bundle.type)
    assertEquals(2, bundle.entry.size)
    assertTrue(bundle.entry.all { it.request.method == Bundle.HTTPVerb.PUT })
    assertEquals(setOf("Patient/p1", "DocumentReference/d1"), bundle.entry.map { it.request.url }.toSet())
    val restoredPatient =
      parser.parseResource(File(result.privateDirectory, "resources/Patient/p1.json").readText()) as Patient
    assertEquals("Test", restoredPatient.nameFirstRep.family)

    ZipFile(result.privateZip).use { zip ->
      val names = zip.entries().asSequence().map { it.name }.toSet()
      assertEquals(tree, names)
      assertTrue("manifest.json" in names)
      assertTrue("local_changes.json" in names)
      assertTrue("resources/Patient/p1.json" in names)
      assertTrue("resources/DocumentReference/d1.json" in names)
      assertTrue("document_references.json" in names)
      assertTrue("images/d1.jpg" in names)
      assertTrue("drafts.json" in names)
      assertTrue(SyncFailureLog.FILE_NAME in names)

      val image = zip.getInputStream(zip.getEntry("images/d1.jpg")).readBytes()
      assertEquals(64, image.size)

      val changes = JsonParser.parseString(zip.readText("local_changes.json")).asJsonArray
      assertEquals(2, changes.size())
      // The UPDATE payload is a JSON patch and must be embedded as JSON, not as an escaped string.
      val patch = changes[1].asJsonObject
      assertEquals("UPDATE", patch["type"].asString)
      assertTrue(patch["payload"].isJsonArray)

      val manifest = JsonParser.parseString(zip.readText("manifest.json")).asJsonObject
      assertEquals("test-1", manifest["appVersion"].asString)
      assertEquals("flw1", manifest["username"].asString)
      assertEquals(2, manifest["counts"].asJsonObject["localChanges"].asInt)

      val failures = zip.readText(SyncFailureLog.FILE_NAME).trim().lines()
      assertEquals(1, failures.size)
      assertEquals(400, JsonParser.parseString(failures[0]).asJsonObject["httpStatus"].asInt)

      val docRefs = JsonParser.parseString(zip.readText("document_references.json")).asJsonArray
      assertEquals("images/d1.jpg", docRefs[0].asJsonObject["imageEntry"].asString)
      assertNotNull(docRefs[0].asJsonObject["resource"])
    }
  }

  @Test
  fun `a missing image file is reported, not fatal`() = runTest {
    val lost =
      DocumentReference().apply {
        id = "d2"
        addExtension(UPLOAD_IMAGE_URL, StringType(File(this@UnsyncedDataExporterTest.context.filesDir, "gone.jpg").toUri().toString()))
      }
    coEvery { fhirEngine.search<DocumentReference>(any<Search>()) } returns
      listOf(SearchResult(lost, included = null, revIncluded = null))

    val result = exporter.export(appVersion = "test-1")

    assertEquals(0, result.imageCount)
    ZipFile(result.privateZip).use { zip ->
      val docRefs = JsonParser.parseString(zip.readText("document_references.json")).asJsonArray
      val entry = docRefs[0].asJsonObject
      assertTrue(entry["imageEntry"].isJsonNull)
      assertTrue(entry["imageReadError"].asString.isNotBlank())
    }
  }

  @Test
  fun `progress walks every phase in order and never goes backwards`() = runTest {
    val seen = mutableListOf<ExportProgress>()
    exporter.export(appVersion = "test-1") { seen += it }

    val phases = seen.map { it.phase }.distinct()
    assertEquals(ExportProgress.Phase.values().toList(), phases)
    // Every phase with work in it reports 0/total first and total/total last.
    val images = seen.filter { it.phase == ExportProgress.Phase.COPYING_IMAGES }
    assertEquals(0, images.first().completed)
    assertEquals(images.last().total, images.last().completed)
    assertTrue(images.last().total >= 1)
    val fractions = seen.map { it.overallFraction() }
    // Monotonic within float rounding (phase boundaries land on e.g. 0.70000005 vs 0.7).
    fractions.zipWithNext().forEach { (a, b) -> assertTrue("$a -> $b", b >= a - 0.0001f) }
    assertEquals(1f, fractions.last(), 0.0001f)
  }

  private fun ZipFile.readText(name: String): String =
    getInputStream(getEntry(name)).bufferedReader().readText()
}
