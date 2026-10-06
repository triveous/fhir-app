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

package org.smartregister.fhircore.quest.ui.login

import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import androidx.work.WorkManager
import com.google.android.fhir.FhirEngine
import com.google.android.fhir.search.Search
import com.google.android.fhir.search.search
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.hl7.fhir.r4.model.DocumentReference
import org.hl7.fhir.r4.model.Resource
import org.hl7.fhir.r4.model.ResourceType
import org.hl7.fhir.r4.model.StringType
import org.smartregister.fhircore.engine.data.remote.shared.TokenAuthenticator
import org.smartregister.fhircore.engine.sync.AppSyncWorker
import org.smartregister.fhircore.engine.util.DispatcherProvider
import org.smartregister.fhircore.engine.util.SecureSharedPreference
import org.smartregister.fhircore.engine.util.SharedPreferenceKey
import org.smartregister.fhircore.engine.util.SharedPreferencesHelper
import org.smartregister.fhircore.engine.util.extension.logicalId
import org.smartregister.fhircore.quest.ui.register.customui.EXTENSION_FILE_LOCATION
import org.smartregister.fhircore.quest.ui.register.patients.DocumentReferenceCaseType
import timber.log.Timber

/** What the current user still has to upload before they can hand the device to someone else. */
data class PendingSyncSummary(
  /** Cases (Patients) with an unsynced change. */
  val caseCount: Int,
  /** Every unsynced local change, cases included. */
  val changeCount: Int,
  /** Screening photos not yet uploaded. */
  val imageCount: Int,
) {
  val hasPendingData: Boolean
    get() = changeCount > 0 || imageCount > 0
}

/**
 * Decides when a different user may log in on this device, and clears the previous user's data when
 * one does.
 *
 * The device only ever holds one user's data. A logout with nothing left to sync marks the device as
 * switchable ([onLogout]); a logout with pending data does not, so only the same user can log back
 * in — the pre-existing behaviour, which keeps their unsynced cases safe. When a different user then
 * logs in, [clearPreviousUserData] removes the previous user's records but keeps the app's
 * configuration resources (Composition, Binary, Questionnaire, StructureMap, ...), which the app
 * needs to start and which are the same for every user of the app id.
 */
@Singleton
class UserSwitchManager
@Inject
constructor(
  @ApplicationContext private val context: Context,
  private val fhirEngine: FhirEngine,
  private val sharedPreferencesHelper: SharedPreferencesHelper,
  private val secureSharedPreference: SecureSharedPreference,
  private val tokenAuthenticator: TokenAuthenticator,
  private val workManager: WorkManager,
  private val dispatcherProvider: DispatcherProvider,
) {

  suspend fun pendingSyncSummary(): PendingSyncSummary =
    withContext(dispatcherProvider.io()) {
      val localChanges = fhirEngine.getUnsyncedLocalChanges()
      val caseCount =
        localChanges
          .filter { it.resourceType == ResourceType.Patient.name }
          .distinctBy { it.resourceId }
          .size
      val imageCount =
        fhirEngine.search<DocumentReference> {}.count {
          it.resource.description != DocumentReferenceCaseType.DRAFT.name
        }
      PendingSyncSummary(
        caseCount = caseCount,
        changeCount = localChanges.size,
        imageCount = imageCount,
      )
    }

  /**
   * Ends the session. [fullySynced] is whether nothing was left to sync at logout; only then may a
   * different user log in next.
   */
  fun onLogout(fullySynced: Boolean) {
    secureSharedPreference.deleteSessionPin()
    secureSharedPreference.setUserSwitchAllowed(fullySynced)
  }

  /** A new session has started, so whatever it records must be synced before another switch. */
  fun onLoginCompleted() {
    secureSharedPreference.setUserSwitchAllowed(false)
  }

  /**
   * Whether a different user may log in now. Re-checks the device rather than trusting the logout
   * flag alone, so data can never be dropped even if something was written after logout.
   */
  suspend fun canSwitchUser(): Boolean =
    secureSharedPreference.isUserSwitchAllowed() && !pendingSyncSummary().hasPendingData

  /**
   * Removes everything that belongs to [previousUsername]: their records in the local database,
   * the sync watermarks (so the new user's first sync is a full download), their drafts, PIN and
   * device account. Call only after [canSwitchUser] and after the new user has authenticated, so a
   * mistyped username never wipes the device.
   */
  suspend fun clearPreviousUserData(previousUsername: String) =
    withContext(dispatcherProvider.io()) {
      Timber.i("Different user logging in; clearing the previous user's data")
      stopSync()
      purgeUserResources()
      clearUserPreferences()
      secureSharedPreference.clearPreviousUserSession()
      tokenAuthenticator.removeAccount(previousUsername)
    }

  /** A sync running under the previous user's session must not write while records are purged. */
  private suspend fun stopSync() {
    workManager.cancelAllWorkByTag(AppSyncWorker::class.java.name)
    withTimeoutOrNull(SYNC_STOP_TIMEOUT_MS) {
      while (AppSyncWorker.isSyncRunning.value) delay(SYNC_STOP_POLL_MS)
    } ?: Timber.w("Sync still running after cancellation; purging anyway")
  }

  private suspend fun purgeUserResources() {
    ResourceType.values()
      .filterNot { it in APP_CONFIG_RESOURCE_TYPES }
      .forEach { resourceType ->
        try {
          purgeAll(resourceType)
        } catch (exception: CancellationException) {
          throw exception
        } catch (exception: Exception) {
          Timber.e(exception, "Could not clear %s resources of the previous user", resourceType)
        }
      }
  }

  private suspend fun purgeAll(resourceType: ResourceType) {
    while (true) {
      val page =
        fhirEngine.search<Resource>(Search(resourceType, PURGE_BATCH_SIZE)).map {
          it.resource
        }
      if (page.isEmpty()) return
      if (resourceType == ResourceType.DocumentReference) {
        page.filterIsInstance<DocumentReference>().forEach { deleteImageFile(it) }
      }
      fhirEngine.purge(resourceType, page.map { it.logicalId }.toSet(), true)
      if (page.size < PURGE_BATCH_SIZE) return
    }
  }

  /** Only draft photos can be left at this point; submitted ones are uploaded before a switch. */
  private fun deleteImageFile(documentReference: DocumentReference) {
    val fileLocation =
      (documentReference.getExtensionByUrl(EXTENSION_FILE_LOCATION)?.value as? StringType)?.value
        ?: return
    runCatching { context.contentResolver.delete(Uri.parse(fileLocation), null, null) }
      .onFailure { Timber.w(it, "Could not delete image of ${documentReference.logicalId}") }
  }

  private fun clearUserPreferences() {
    val prefs = sharedPreferencesHelper.prefs
    val syncWatermarkSuffix = "_${SharedPreferenceKey.LAST_SYNC_TIMESTAMP.name}"
    val keysToRemove =
      USER_PREFERENCE_KEYS +
        prefs.all.keys.filter {
          it.endsWith(syncWatermarkSuffix) ||
            it.startsWith(SharedPreferencesHelper.PREFS_SYNC_PROGRESS_TOTAL)
        }
    prefs.edit(commit = true) { keysToRemove.forEach { remove(it) } }
  }

  companion object {
    private const val PURGE_BATCH_SIZE = 500
    private const val SYNC_STOP_TIMEOUT_MS = 10_000L
    private const val SYNC_STOP_POLL_MS = 200L

    /**
     * The app's configuration: fetched for the app id, not for a user, so it is kept across a user
     * switch. Mirrors the types ConfigurationRegistry loads, plus terminology the forms reference.
     */
    private val APP_CONFIG_RESOURCE_TYPES =
      setOf(
        ResourceType.Composition,
        ResourceType.Binary,
        ResourceType.Parameters,
        ResourceType.Questionnaire,
        ResourceType.StructureMap,
        ResourceType.List,
        ResourceType.PlanDefinition,
        ResourceType.ActivityDefinition,
        ResourceType.Library,
        ResourceType.Measure,
        ResourceType.Basic,
        ResourceType.ValueSet,
        ResourceType.CodeSystem,
        ResourceType.ConceptMap,
        ResourceType.StructureDefinition,
      )

    private val USER_PREFERENCE_KEYS =
      listOf(
        SharedPreferenceKey.LAST_SYNC_TIMESTAMP,
        SharedPreferenceKey.PRACTITIONER_ID,
        SharedPreferenceKey.PRACTITIONER_DETAILS,
        SharedPreferenceKey.PRACTITIONER_LOCATION_HIERARCHIES,
        SharedPreferenceKey.PRACTITIONER_LOCATION,
        SharedPreferenceKey.USER_INFO,
        SharedPreferenceKey.CARE_TEAM,
        SharedPreferenceKey.ORGANIZATION,
        SharedPreferenceKey.FLW_DISTRICT,
        SharedPreferenceKey.FLW_STATE,
        SharedPreferenceKey.DRAFTS,
        SharedPreferenceKey.LAST_OFFSET,
        SharedPreferenceKey.UPLOADED_DOCUMENT_REFERENCES,
      )
        .map { it.name } +
        // Sync parameters written at login, keyed by resource type.
        listOf(ResourceType.CareTeam.name, ResourceType.Organization.name, ResourceType.Location.name)
  }
}
