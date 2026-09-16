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

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import androidx.core.net.toUri
import ca.uhn.fhir.context.FhirContext
import ca.uhn.fhir.parser.IParser
import com.google.android.fhir.FhirEngine
import com.google.android.fhir.LocalChange
import com.google.android.fhir.datacapture.extensions.asStringValue
import com.google.android.fhir.search.search
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import org.hl7.fhir.r4.model.Bundle
import org.hl7.fhir.r4.model.DocumentReference
import org.hl7.fhir.r4.model.Resource
import org.hl7.fhir.r4.model.ResourceType
import org.smartregister.fhircore.engine.domain.networkUtils.DocumentReferenceCaseType
import org.smartregister.fhircore.engine.domain.networkUtils.HttpConstants.UPLOAD_IMAGE_URL
import org.smartregister.fhircore.engine.sync.SyncFailureLog
import org.smartregister.fhircore.engine.util.DispatcherProvider
import org.smartregister.fhircore.engine.util.SecureSharedPreference
import org.smartregister.fhircore.engine.util.SharedPreferenceKey
import org.smartregister.fhircore.engine.util.SharedPreferencesHelper
import org.smartregister.fhircore.engine.util.extension.getCustomJsonParser
import org.smartregister.fhircore.engine.util.extension.logicalId
import timber.log.Timber

/** What [UnsyncedDataExporter.export] produced. */
data class UnsyncedExportResult(
  /** The export as a directory tree inside app-private storage. */
  val privateDirectory: File,
  /** The same tree zipped, inside app-private storage; share it through the app's `FileProvider`. */
  val privateZip: File,
  /**
   * Where the public copy of the tree landed, e.g. `Download/AarogyaAarohan/unsynced_export_…/`,
   * or null when MediaStore refused every file (the private copies still exist).
   */
  val downloadsDisplayPath: String?,
  /** Files that could not be copied into Downloads, relative to the export root. */
  val downloadsCopyFailures: List<String>,
  val localChangeCount: Int,
  val resourceCount: Int,
  val imageCount: Int,
  val syncFailureCount: Int,
  val sizeBytes: Long,
)

/**
 * Where an export currently is. [completed]/[total] are within the [phase]; [overallFraction] folds
 * the phases into one 0..1 number for a single progress bar. `total == 0` means the phase's size is
 * not known yet (render as indeterminate).
 */
data class ExportProgress(val phase: Phase, val completed: Int = 0, val total: Int = 0) {
  /**
   * Phases in order. The weights are a rough share of wall-clock time on a device with a large
   * photo backlog, so the bar moves at a believable pace rather than jumping.
   */
  enum class Phase(val weight: Float) {
    READING_CHANGES(0.05f),
    WRITING_RESOURCES(0.15f),
    COPYING_IMAGES(0.35f),
    ZIPPING(0.15f),
    COPYING_TO_DOWNLOADS(0.30f),
  }

  fun overallFraction(): Float {
    val before = Phase.values().takeWhile { it != phase }.sumOf { it.weight.toDouble() }.toFloat()
    val within = if (total > 0) (completed.toFloat() / total).coerceIn(0f, 1f) else 0f
    return (before + phase.weight * within).coerceIn(0f, 1f)
  }
}

/**
 * Writes everything on the device that has not reached the server out as **restorable artifacts**:
 * one FHIR JSON file per resource, one image file per pending DocumentReference, and a transaction
 * `Bundle` that can be POSTed to the server by hand. The point is that a stuck phone's data can be
 * put on the server without the app if the app never manages to.
 *
 * Layout of the export directory (`<root>/`):
 * - `README.md` — what each file is and the exact HTTP calls that restore the data.
 * - `manifest.json` — device id, app/user/server identifiers and counts.
 * - `restore_bundle.json` — FHIR `transaction` Bundle: a `PUT <Type>/<id>` entry holding the
 *   current on-device resource for every resource with a pending change (a `DELETE` entry for
 *   deletes). `DocumentReference` entries carry no attachment bytes; the images go up separately.
 * - `resources/<Type>/<id>.json` — the same resources as individual FHIR JSON files, parseable
 *   with any FHIR R4 library.
 * - `images/<DocumentReference id>.<ext>` — the pending screening photos, named by the
 *   DocumentReference they belong to so they can be uploaded with `$binary-access-write`.
 * - `local_changes.json` — the raw `LocalChange` queue (INSERT payloads are whole resources,
 *   UPDATE payloads are JSON patches, DELETE payloads are empty), for diagnosing *why* it is stuck.
 * - `document_references.json` — every DocumentReference on the device with its image path and
 *   whether that file was readable.
 * - `drafts.json` — the unsubmitted-drafts Bundle from shared preferences.
 * - `sync_failures.jsonl` — the [SyncFailureLog]: one failed sync request per line.
 *
 * The tree is written to app-private `files/exports/<name>/`, zipped next to it for the share
 * sheet, and every file is copied into the public `Download/AarogyaAarohan/<name>/` folder through
 * `MediaStore.Downloads` (no storage permission needed on API 29+) so it can be picked up over USB
 * or from the Files app. **Everything is unencrypted patient data** — this exists for support to
 * recover a stuck device, and should be deleted from the phone once collected.
 *
 * Nothing here mutates the engine or the preferences — an export is read-only.
 */
@Singleton
class UnsyncedDataExporter
@Inject
constructor(
  @ApplicationContext private val context: Context,
  private val fhirEngine: FhirEngine,
  private val sharedPreferencesHelper: SharedPreferencesHelper,
  private val secureSharedPreference: SecureSharedPreference,
  private val syncFailureLog: SyncFailureLog,
  private val dispatcherProvider: DispatcherProvider,
) {

  private val gson = GsonBuilder().setPrettyPrinting().serializeNulls().disableHtmlEscaping().create()
  private val fhirParser: IParser by lazy {
    FhirContext.forR4Cached().getCustomJsonParser().setPrettyPrint(true)
  }

  /**
   * @param onProgress called from the IO dispatcher as the export moves along; it is invoked often
   *   (once per image, once per file copied), so keep it cheap.
   */
  suspend fun export(
    appVersion: String,
    onProgress: (ExportProgress) -> Unit = {},
  ): UnsyncedExportResult =
    withContext(dispatcherProvider.io()) {
      val exportsDir = File(context.filesDir, EXPORT_DIRECTORY).apply { mkdirs() }
      val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
      val deviceId = deviceId()
      val exportName = "${FILE_PREFIX}_${deviceId.take(8)}_$stamp"
      val root = File(exportsDir, exportName)
      val zipFile = File(exportsDir, "$exportName.zip")

      val counts = Counts()
      try {
        root.mkdirs()
        val fhirBaseUrl = sharedPreferencesHelper.getFhirBaseUrlWithoutDefaultValue()
        onProgress(ExportProgress(ExportProgress.Phase.READING_CHANGES))
        val resources = writeLocalChangesAndResources(root, counts, onProgress)
        writeRestoreBundle(root, resources)
        writeDocumentReferencesAndImages(root, counts, onProgress)
        writeDrafts(root)
        writeSyncFailures(root, counts)
        writeManifest(root, deviceId, appVersion, fhirBaseUrl, counts)
        writeReadme(root, exportName, fhirBaseUrl)
        zipDirectory(root, zipFile, onProgress)
      } catch (e: CancellationException) {
        root.deleteRecursively()
        zipFile.delete()
        throw e
      } catch (e: Exception) {
        root.deleteRecursively()
        zipFile.delete()
        throw e
      }

      val (displayPath, failures) = copyTreeToDownloads(root, exportName, onProgress)
      UnsyncedExportResult(
        privateDirectory = root,
        privateZip = zipFile,
        downloadsDisplayPath = displayPath,
        downloadsCopyFailures = failures,
        localChangeCount = counts.localChanges,
        resourceCount = counts.resources,
        imageCount = counts.images,
        syncFailureCount = counts.syncFailures,
        sizeBytes = zipFile.length(),
      )
    }

  /** Removes exports older than the newest [KEEP_PRIVATE_EXPORTS] from private storage; public copies stay. */
  fun pruneOldExports() {
    runCatching {
      File(context.filesDir, EXPORT_DIRECTORY)
        .listFiles { f -> f.name.startsWith(FILE_PREFIX) }
        ?.groupBy { it.nameWithoutExtension }
        ?.entries
        ?.sortedByDescending { (_, files) -> files.maxOf { it.lastModified() } }
        ?.drop(KEEP_PRIVATE_EXPORTS)
        ?.forEach { (_, files) -> files.forEach { it.deleteRecursively() } }
    }
      .onFailure { Timber.w(it, "Could not prune old exports") }
  }

  /** One resource with pending changes, as it stands on the device now. */
  private data class PendingResource(
    val type: ResourceType,
    val id: String,
    /** Null when the resource is gone from the DB, i.e. the pending change is a DELETE. */
    val resource: Resource?,
    val changeTypes: List<LocalChange.Type>,
  )

  private suspend fun writeLocalChangesAndResources(
    root: File,
    counts: Counts,
    onProgress: (ExportProgress) -> Unit,
  ): List<PendingResource> {
    val localChanges = fhirEngine.getUnsyncedLocalChanges()
    counts.localChanges = localChanges.size
    root.writeText("local_changes.json", gson.toJson(localChanges.map { it.toJson() }))

    // Current DB state of each changed resource. An UPDATE's payload is a JSON patch, which is only
    // readable next to the resource it patches; an INSERT's payload is the resource but may be
    // older than what is in the DB now if there were later local edits.
    val pending = mutableListOf<PendingResource>()
    val grouped = localChanges.groupBy { it.resourceType to it.resourceId }
    onProgress(ExportProgress(ExportProgress.Phase.WRITING_RESOURCES, 0, grouped.size))
    grouped.entries.forEachIndexed { index, (key, changes) ->
        val (typeName, id) = key
        val type = runCatching { ResourceType.fromCode(typeName) }.getOrNull() ?: return@forEachIndexed
        val resource =
          runCatching { fhirEngine.get(type, id) }
            .onFailure { Timber.d(it, "Could not read $typeName/$id for export") }
            .getOrNull()
        val changeTypes = changes.map { it.type }
        pending += PendingResource(type, id, resource, changeTypes)

        val json =
          resource?.let { fhirParser.encodeResourceToString(it) }
            ?: gson.toJson(
              mapOf(
                "resourceType" to typeName,
                "id" to id,
                "exportNote" to "Resource is not in the local database; pending change types: $changeTypes",
              ),
            )
        root.writeText("resources/$typeName/$id.json", json)
        counts.resources++
        onProgress(ExportProgress(ExportProgress.Phase.WRITING_RESOURCES, index + 1, grouped.size))
      }
    return pending
  }

  /**
   * A FHIR `transaction` Bundle that puts every pending resource on the server as-is. This mirrors
   * what the app's own sync sends (`UploadStrategy.AllChangesSquashedBundlePut`): a PUT of the whole
   * current resource per id, so it can be replayed by hand with one POST.
   */
  private fun writeRestoreBundle(root: File, pending: List<PendingResource>) {
    val bundle =
      Bundle().apply {
        type = Bundle.BundleType.TRANSACTION
        pending.forEach { p ->
          val entry = addEntry()
          if (p.resource == null || p.changeTypes.lastOrNull() == LocalChange.Type.DELETE) {
            entry.request.method = Bundle.HTTPVerb.DELETE
            entry.request.url = "${p.type.name}/${p.id}"
          } else {
            val resource = p.resource.copy()
            // Same as the worker's metadata step: attachment bytes never travel in the resource,
            // they are uploaded with $binary-access-write afterwards (see README).
            if (resource is DocumentReference) {
              resource.content.forEach { it.attachment?.data = null }
            }
            entry.resource = resource
            entry.fullUrl = "${p.type.name}/${p.id}"
            entry.request.method = Bundle.HTTPVerb.PUT
            entry.request.url = "${p.type.name}/${p.id}"
          }
        }
      }
    root.writeText("restore_bundle.json", fhirParser.encodeResourceToString(bundle))
  }

  private suspend fun writeDocumentReferencesAndImages(
    root: File,
    counts: Counts,
    onProgress: (ExportProgress) -> Unit,
  ) {
    val docRefs = fhirEngine.search<DocumentReference> {}.map { it.resource }
    val summaries = mutableListOf<JsonObject>()
    onProgress(ExportProgress(ExportProgress.Phase.COPYING_IMAGES, 0, docRefs.size))
    docRefs.forEachIndexed { index, docRef ->
      val uriString = docRef.getExtensionByUrl(UPLOAD_IMAGE_URL)?.value?.asStringValue()
      val uri = uriString?.takeIf { it.isNotBlank() }?.toUri()
      val isDraft = docRef.description == DocumentReferenceCaseType.DRAFT
      val contentType = docRef.content.firstOrNull()?.attachment?.contentType
      var imageBytes = -1L
      var imageEntry: String? = null
      var readError: String? = null

      if (uri != null) {
        try {
          val target = File(root, "images/${docRef.logicalId}${imageExtension(uri, contentType)}")
          context.contentResolver.openInputStream(uri)?.use { input ->
            target.parentFile?.mkdirs()
            imageBytes = target.outputStream().use { input.copyTo(it) }
            imageEntry = "images/${target.name}"
            counts.images++
          } ?: run { readError = "openInputStream returned null" }
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          readError = "${e::class.java.simpleName}: ${e.message}"
          Timber.d(e, "Could not export image for DocumentReference/${docRef.logicalId}")
        }
      }

      summaries +=
        JsonObject().apply {
          addProperty("id", docRef.logicalId)
          addProperty("isDraft", isDraft)
          addProperty("status", docRef.status?.toCode())
          addProperty("docStatus", docRef.docStatus?.toCode())
          addProperty("date", docRef.date?.let { isoDate(it) })
          addProperty("subject", docRef.subject?.reference)
          addProperty("contentType", contentType)
          addProperty("localFileUri", uriString)
          addProperty("imageEntry", imageEntry)
          addProperty("imageBytes", imageBytes)
          addProperty("imageReadError", readError)
          add("resource", JsonParser.parseString(fhirParser.encodeResourceToString(docRef)))
        }
      onProgress(ExportProgress(ExportProgress.Phase.COPYING_IMAGES, index + 1, docRefs.size))
    }
    root.writeText("document_references.json", gson.toJson(summaries))
  }

  private fun writeDrafts(root: File) {
    val drafts =
      runCatching { sharedPreferencesHelper.read<String>(SharedPreferenceKey.DRAFTS.name, true) }
        .getOrNull()
    root.writeText("drafts.json", drafts?.takeIf { it.isNotBlank() }?.prettyJson() ?: "{}")
  }

  private fun writeSyncFailures(root: File, counts: Counts) {
    val lines = syncFailureLog.readAll()
    counts.syncFailures = lines.size
    root.writeText(SyncFailureLog.FILE_NAME, lines.joinToString("\n", postfix = "\n"))
  }

  private fun writeManifest(
    root: File,
    deviceId: String,
    appVersion: String,
    fhirBaseUrl: String?,
    counts: Counts,
  ) {
    val manifest =
      linkedMapOf<String, Any?>(
        "exportedAt" to SyncFailureLog.isoNow(),
        "appVersion" to appVersion,
        "deviceId" to deviceId,
        "deviceModel" to "${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, SDK ${Build.VERSION.SDK_INT})",
        "username" to secureSharedPreference.retrieveSessionUsername(),
        "practitionerId" to sharedPreferencesHelper.read(SharedPreferenceKey.PRACTITIONER_ID.name, null),
        "appId" to sharedPreferencesHelper.retrieveApplicationId(),
        "fhirBaseUrl" to fhirBaseUrl,
        "tenantCode" to sharedPreferencesHelper.getTenantCode(),
        "isMultiTenant" to sharedPreferencesHelper.isMultiTenant(),
        "siteName" to sharedPreferencesHelper.getSiteName(),
        "lastSyncTimestamp" to
          sharedPreferencesHelper.read(SharedPreferenceKey.LAST_SYNC_TIMESTAMP.name, null),
        "lastSyncDateTime" to
          sharedPreferencesHelper.read(SharedPreferenceKey.LAST_SYNC_DATE_TIME.name, null),
        "counts" to
          mapOf(
            "localChanges" to counts.localChanges,
            "resources" to counts.resources,
            "images" to counts.images,
            "syncFailures" to counts.syncFailures,
          ),
      )
    root.writeText("manifest.json", gson.toJson(manifest))
  }

  private fun writeReadme(root: File, exportName: String, fhirBaseUrl: String?) {
    val base = fhirBaseUrl?.trimEnd('/') ?: "<fhir base url>"
    root.writeText(
      "README.md",
      """
      |# $exportName
      |
      |Unsynced data exported from the app. **Unencrypted patient data — delete from the phone once collected.**
      |
      |## Files
      |
      || File | What it is |
      ||---|---|
      || `restore_bundle.json` | FHIR `transaction` Bundle with a `PUT <Type>/<id>` entry for every resource that has a pending local change (a `DELETE` entry for pending deletes). Ready to POST. |
      || `resources/<Type>/<id>.json` | The same resources as individual FHIR R4 JSON files (current on-device state). |
      || `images/<DocumentReference id>.<ext>` | The screening photos still waiting to be uploaded, named by their DocumentReference. |
      || `local_changes.json` | The raw local-change queue the app would upload next. INSERT payload = whole resource, UPDATE payload = JSON patch, DELETE = empty. |
      || `document_references.json` | Every DocumentReference on the device, with its local image path and whether the file was readable. |
      || `drafts.json` | Unsubmitted drafts Bundle from shared preferences. |
      || `sync_failures.jsonl` | One JSON object per failed sync request: HTTP status, request URL, server OperationOutcome. Read this first to see why sync is failing. |
      || `manifest.json` | Device, app version, user, server and counts. |
      |
      |## Manual restore
      |
      |The app's own sync does the same three things; replay them with an authenticated client (the same bearer token the app uses).
      |
      |1. **Metadata** — one transaction:
      |
      |   ```
      |   POST $base
      |   Content-Type: application/fhir+json
      |   <body: restore_bundle.json>
      |   ```
      |   If the server rejects the whole bundle, the OperationOutcome names the failing entry; fix that
      |   one file under `resources/` and PUT it on its own (`PUT $base/<Type>/<id>`), then re-run.
      |
      |2. **Images** — for each file in `images/`:
      |
      |   ```
      |   POST $base/DocumentReference/<id>/${'$'}binary-access-write?path=DocumentReference.content.attachment
      |   Content-Type: image/jpeg
      |   <body: the image bytes>
      |   ```
      |
      |3. **Finalize** each uploaded DocumentReference:
      |
      |   ```
      |   PATCH $base/DocumentReference/<id>
      |   Content-Type: application/json-patch+json
      |   [{"op":"replace","path":"/docStatus","value":"final"}]
      |   ```
      |
      |Drafts (`drafts.json`) are not on the server by design; they are QuestionnaireResponses the FLW never submitted.
      """.trimMargin(),
    )
  }

  private fun zipDirectory(root: File, zipFile: File, onProgress: (ExportProgress) -> Unit) {
    val files = root.walkTopDown().filter { it.isFile }.toList()
    onProgress(ExportProgress(ExportProgress.Phase.ZIPPING, 0, files.size))
    ZipOutputStream(zipFile.outputStream().buffered()).use { zip ->
      files.forEachIndexed { index, file ->
        zip.putNextEntry(ZipEntry(file.relativeTo(root).path.replace(File.separatorChar, '/')))
        file.inputStream().use { it.copyTo(zip) }
        zip.closeEntry()
        onProgress(ExportProgress(ExportProgress.Phase.ZIPPING, index + 1, files.size))
      }
    }
  }

  /**
   * Copies the whole export tree into `Download/AarogyaAarohan/<exportName>/…` through MediaStore,
   * preserving subfolders, so the JSON and image files can be browsed and restored individually.
   * Needs no storage permission on API 29+. Files MediaStore refuses are reported, not fatal.
   */
  private fun copyTreeToDownloads(
    root: File,
    exportName: String,
    onProgress: (ExportProgress) -> Unit,
  ): Pair<String?, List<String>> {
    val failures = mutableListOf<String>()
    var copied = 0
    val baseRelative = "${Environment.DIRECTORY_DOWNLOADS}/$PUBLIC_SUBFOLDER/$exportName"
    val files = root.walkTopDown().filter { it.isFile }.toList()
    onProgress(ExportProgress(ExportProgress.Phase.COPYING_TO_DOWNLOADS, 0, files.size))
    files.forEachIndexed { index, file ->
      val relative = file.relativeTo(root).path.replace(File.separatorChar, '/')
      val subDir = relative.substringBeforeLast('/', "")
      val relativePath = if (subDir.isEmpty()) baseRelative else "$baseRelative/$subDir"
      if (copyFileToDownloads(file, relativePath)) copied++ else failures += relative
      onProgress(ExportProgress(ExportProgress.Phase.COPYING_TO_DOWNLOADS, index + 1, files.size))
    }
    return (if (copied > 0) "$baseRelative/" else null) to failures
  }

  private fun copyFileToDownloads(file: File, relativePath: String): Boolean {
    val values =
      ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, file.name)
        put(MediaStore.Downloads.MIME_TYPE, mimeTypeOf(file))
        put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
        put(MediaStore.Downloads.IS_PENDING, 1)
      }
    val resolver = context.contentResolver
    return try {
      val uri: Uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
      val written =
        resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } } != null
      if (!written) {
        resolver.delete(uri, null, null)
        return false
      }
      resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
      true
    } catch (e: Exception) {
      Timber.w(e, "Could not copy ${file.name} to Downloads/$relativePath; private copy kept")
      false
    }
  }

  private fun mimeTypeOf(file: File): String =
    when (file.extension.lowercase(Locale.US)) {
      "json" -> "application/json"
      "jsonl" -> "application/x-ndjson"
      "md" -> "text/markdown"
      "jpg", "jpeg" -> "image/jpeg"
      "png" -> "image/png"
      "webp" -> "image/webp"
      "zip" -> "application/zip"
      else -> "application/octet-stream"
    }

  private fun LocalChange.toJson(): JsonObject =
    JsonObject().apply {
      addProperty("resourceType", resourceType)
      addProperty("resourceId", resourceId)
      addProperty("versionId", versionId)
      addProperty("timestamp", timestamp.toString())
      addProperty("type", type.name)
      add("localChangeIds", gson.toJsonTree(token.ids))
      // The payload is itself JSON (resource or patch); embed it as JSON where it parses so the
      // file is readable, and fall back to the raw string where it does not.
      add("payload", payload.toJsonElementOrString())
    }

  private fun String.toJsonElementOrString(): JsonElement =
    if (isBlank()) JsonPrimitive("") else runCatching { JsonParser.parseString(this) }.getOrElse { JsonPrimitive(this) }

  private fun String.prettyJson(): String =
    runCatching { gson.toJson(JsonParser.parseString(this)) }.getOrDefault(this)

  private fun File.writeText(relativePath: String, text: String) {
    File(this, relativePath).apply { parentFile?.mkdirs() }.writeText(text, Charsets.UTF_8)
  }

  private fun imageExtension(uri: Uri, contentType: String?): String {
    val name = uri.lastPathSegment.orEmpty().lowercase(Locale.US)
    return when {
      name.endsWith(".png") || contentType == "image/png" -> ".png"
      name.endsWith(".webp") || contentType == "image/webp" -> ".webp"
      name.endsWith(".jpeg") -> ".jpeg"
      else -> ".jpg"
    }
  }

  private fun isoDate(date: Date): String =
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(date)

  private fun deviceId(): String =
    runCatching { Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) }
      .getOrNull()
      ?.takeIf { it.isNotBlank() } ?: "unknown"

  private class Counts {
    var localChanges = 0
    var resources = 0
    var images = 0
    var syncFailures = 0
  }

  companion object {
    /** Under `files/`; already covered by the app's `FileProvider` `files-path` entry. */
    const val EXPORT_DIRECTORY = "exports"
    const val FILE_PREFIX = "unsynced_export"
    const val PUBLIC_SUBFOLDER = "AarogyaAarohan"
    private const val KEEP_PRIVATE_EXPORTS = 2
  }
}
