package org.smartregister.fhircore.quest.ui.login

import android.app.Application
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import com.google.android.fhir.FhirEngine
import com.google.android.fhir.LocalChange
import com.google.android.fhir.SearchResult
import com.google.android.fhir.search.Search
import com.google.gson.Gson
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.hl7.fhir.r4.model.Binary
import org.hl7.fhir.r4.model.Composition
import org.hl7.fhir.r4.model.DocumentReference
import org.hl7.fhir.r4.model.Patient
import org.hl7.fhir.r4.model.Practitioner
import org.hl7.fhir.r4.model.Questionnaire
import org.hl7.fhir.r4.model.QuestionnaireResponse
import org.hl7.fhir.r4.model.Resource
import org.hl7.fhir.r4.model.ResourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.smartregister.fhircore.engine.data.remote.shared.TokenAuthenticator
import org.smartregister.fhircore.engine.util.DispatcherProvider
import org.smartregister.fhircore.engine.util.SecureSharedPreference
import org.smartregister.fhircore.engine.util.SharedPreferenceKey
import org.smartregister.fhircore.engine.util.SharedPreferencesHelper
import org.smartregister.fhircore.quest.ui.register.patients.DocumentReferenceCaseType

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE, sdk = [Build.VERSION_CODES.Q])
class UserSwitchManagerTest {

  private val fhirEngine = mockk<FhirEngine>()
  private val secureSharedPreference = mockk<SecureSharedPreference>(relaxed = true)
  private val tokenAuthenticator = mockk<TokenAuthenticator>(relaxed = true)
  private val workManager = mockk<WorkManager>(relaxed = true)
  private lateinit var sharedPreferencesHelper: SharedPreferencesHelper
  private lateinit var userSwitchManager: UserSwitchManager

  /** The local database, by type; search and purge read and write it. */
  private val database = mutableMapOf<ResourceType, MutableList<Resource>>()
  private var localChanges = emptyList<LocalChange>()

  @Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<Application>()
    sharedPreferencesHelper = SharedPreferencesHelper(context, Gson())
    sharedPreferencesHelper.prefs.edit().clear().commit()

    coEvery { fhirEngine.search<Resource>(any<Search>()) } answers
      {
        val search = firstArg<Search>()
        database[search.type].orEmpty().take(search.count ?: Int.MAX_VALUE).map {
          SearchResult(it, null, null)
        }
      }
    coEvery { fhirEngine.purge(any<ResourceType>(), any<Set<String>>(), any()) } answers
      {
        val ids = secondArg<Set<String>>()
        database[firstArg()]?.removeAll { it.idElement.idPart in ids }
      }
    coEvery { fhirEngine.getUnsyncedLocalChanges() } answers { localChanges }

    userSwitchManager =
      UserSwitchManager(
        context = context,
        fhirEngine = fhirEngine,
        sharedPreferencesHelper = sharedPreferencesHelper,
        secureSharedPreference = secureSharedPreference,
        tokenAuthenticator = tokenAuthenticator,
        workManager = workManager,
        dispatcherProvider = object : DispatcherProvider {},
      )
  }

  @Test
  fun clearPreviousUserDataRemovesTheirRecordsButKeepsAppConfiguration() = runBlocking {
    store(Patient().apply { id = "p1" }, Patient().apply { id = "p2" })
    store(QuestionnaireResponse().apply { id = "qr1" })
    store(Practitioner().apply { id = "prac1" })
    store(Composition().apply { id = "app-composition" })
    store(Binary().apply { id = "app-config" })
    store(Questionnaire().apply { id = "registration" })

    userSwitchManager.clearPreviousUserData("previous-user")

    assertTrue(database[ResourceType.Patient].isNullOrEmpty())
    assertTrue(database[ResourceType.QuestionnaireResponse].isNullOrEmpty())
    assertTrue(database[ResourceType.Practitioner].isNullOrEmpty())
    assertEquals(1, database[ResourceType.Composition]?.size)
    assertEquals(1, database[ResourceType.Binary]?.size)
    assertEquals(1, database[ResourceType.Questionnaire]?.size)
  }

  @Test
  fun clearPreviousUserDataResetsTheirSyncStateAndSessionButKeepsTheAppId() = runBlocking {
    sharedPreferencesHelper.write(SharedPreferenceKey.APP_ID.name, "app")
    sharedPreferencesHelper.write("PATIENT_${SharedPreferenceKey.LAST_SYNC_TIMESTAMP.name}", "ts")
    sharedPreferencesHelper.write(SharedPreferenceKey.LAST_SYNC_TIMESTAMP.name, "ts")
    sharedPreferencesHelper.write(SharedPreferenceKey.DRAFTS.name, "drafts")
    sharedPreferencesHelper.write(SharedPreferenceKey.PRACTITIONER_ID.name, "prac1")

    userSwitchManager.clearPreviousUserData("previous-user")

    assertEquals("app", sharedPreferencesHelper.read(SharedPreferenceKey.APP_ID.name, null))
    assertNull(
      sharedPreferencesHelper.read("PATIENT_${SharedPreferenceKey.LAST_SYNC_TIMESTAMP.name}", null),
    )
    assertNull(sharedPreferencesHelper.read(SharedPreferenceKey.LAST_SYNC_TIMESTAMP.name, null))
    assertNull(sharedPreferencesHelper.read(SharedPreferenceKey.DRAFTS.name, null))
    assertNull(sharedPreferencesHelper.read(SharedPreferenceKey.PRACTITIONER_ID.name, null))
    verify { secureSharedPreference.clearPreviousUserSession() }
    verify { tokenAuthenticator.removeAccount("previous-user") }
    verify(exactly = 0) { secureSharedPreference.deleteCredentials() }
  }

  @Test
  fun pendingSyncSummaryCountsEachCaseOnceAndIgnoresDraftPhotos() = runBlocking {
    localChanges =
      listOf(
        localChange(ResourceType.Patient, "p1"),
        localChange(ResourceType.Patient, "p1"),
        localChange(ResourceType.Patient, "p2"),
        localChange(ResourceType.QuestionnaireResponse, "qr1"),
      )
    store(
      DocumentReference().apply {
        id = "submitted"
        description = DocumentReferenceCaseType.SUBMITTED.name
      },
      DocumentReference().apply {
        id = "draft"
        description = DocumentReferenceCaseType.DRAFT.name
      },
    )

    val summary = userSwitchManager.pendingSyncSummary()

    assertEquals(2, summary.caseCount)
    assertEquals(4, summary.changeCount)
    assertEquals(1, summary.imageCount)
    assertTrue(summary.hasPendingData)
  }

  @Test
  fun canSwitchUserIsFalseWhenDataIsPendingEvenAfterAFullySyncedLogout() = runBlocking {
    every { secureSharedPreference.isUserSwitchAllowed() } returns true
    localChanges = listOf(localChange(ResourceType.Patient, "p1"))

    assertFalse(userSwitchManager.canSwitchUser())
  }

  @Test
  fun canSwitchUserIsTrueOnlyAfterAFullySyncedLogout() = runBlocking {
    every { secureSharedPreference.isUserSwitchAllowed() } returns false
    assertFalse(userSwitchManager.canSwitchUser())

    every { secureSharedPreference.isUserSwitchAllowed() } returns true
    assertTrue(userSwitchManager.canSwitchUser())
  }

  @Test
  fun onLogoutRecordsWhetherTheSessionEndedFullySynced() {
    userSwitchManager.onLogout(fullySynced = false)
    verify { secureSharedPreference.deleteSessionPin() }
    verify { secureSharedPreference.setUserSwitchAllowed(false) }

    userSwitchManager.onLogout(fullySynced = true)
    verify { secureSharedPreference.setUserSwitchAllowed(true) }
  }

  private fun store(vararg resources: Resource) {
    resources.forEach { database.getOrPut(it.resourceType) { mutableListOf() }.add(it) }
  }

  private fun localChange(type: ResourceType, id: String): LocalChange =
    mockk {
      every { resourceType } returns type.name
      every { resourceId } returns id
    }
}
