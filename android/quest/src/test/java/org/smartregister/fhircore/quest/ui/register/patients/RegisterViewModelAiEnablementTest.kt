package org.smartregister.fhircore.quest.ui.register.patients

import android.app.Application
import android.os.Build
import androidx.lifecycle.viewModelScope
import ca.uhn.fhir.context.FhirContext
import com.google.android.fhir.FhirEngine
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.hl7.fhir.r4.model.CanonicalType
import org.hl7.fhir.r4.model.Questionnaire
import org.hl7.fhir.r4.model.ResourceType
import org.hl7.fhir.r4.model.StructureMap
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.smartregister.fhircore.engine.configuration.ConfigurationRegistry
import org.smartregister.fhircore.engine.data.local.register.RegisterRepository
import org.smartregister.fhircore.engine.rulesengine.ResourceDataRulesExecutor
import org.smartregister.fhircore.engine.util.DispatcherProvider
import org.smartregister.fhircore.engine.util.FeatureFlagUtil
import org.smartregister.fhircore.engine.util.SecureSharedPreference
import org.smartregister.fhircore.engine.util.SharedPreferencesHelper
import org.smartregister.fhircore.quest.ui.login.UserSwitchManager

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE, sdk = [Build.VERSION_CODES.Q])
class RegisterViewModelAiEnablementTest {

  private val testDispatcher = StandardTestDispatcher()
  private val fhirEngine = mockk<FhirEngine>()
  private val featureFlagUtil = mockk<FeatureFlagUtil>()
  private val viewModels = mutableListOf<RegisterViewModel>()

  private var flagEnabled = true
  private var localStructureMap: StructureMap = structureMapFixture(MAP_WITH_AI_RESULT)
  private var now = 10_000L
  private var launches = 0

  @Before
  fun setUp() {
    Dispatchers.setMain(testDispatcher)
    coEvery { featureFlagUtil.isAiInferenceEnabled() } answers { flagEnabled }
    coEvery { fhirEngine.get(ResourceType.Questionnaire, QUESTIONNAIRE_ID) } returns
      Questionnaire().apply {
        id = QUESTIONNAIRE_ID
        addExtension(TARGET_STRUCTURE_MAP_EXTENSION, CanonicalType(STRUCTURE_MAP_URL))
      }
    coEvery { fhirEngine.get(ResourceType.StructureMap, QUESTIONNAIRE_ID) } answers
      { localStructureMap }
  }

  @After
  fun tearDown() {
    viewModels.forEach { it.viewModelScope.cancel() }
    viewModels.clear()
    Dispatchers.resetMain()
  }

  @Test
  fun `add case opens the form when the flag is on and the map extracts the AI result`() =
    runTest(testDispatcher) {
      val viewModel = createViewModel()

      viewModel.launchIfAiUpToDate(QUESTIONNAIRE_ID) { launches++ }

      assertEquals(1, launches)
      assertFalse(viewModel.showAiEnableFailedDialog.value)
    }

  @Test
  fun `a site with AI off adds cases without the dialog even with a stale map`() =
    runTest(testDispatcher) {
      flagEnabled = false
      localStructureMap = structureMapFixture(MAP_WITHOUT_AI_RESULT)
      val viewModel = createViewModel()

      viewModel.launchIfAiUpToDate(QUESTIONNAIRE_ID) { launches++ }

      assertEquals(1, launches)
      assertFalse(viewModel.showAiEnableFailedDialog.value)
    }

  @Test
  fun `a site with AI off adds cases without the dialog with a current map`() =
    runTest(testDispatcher) {
      flagEnabled = false
      val viewModel = createViewModel()

      viewModel.launchIfAiUpToDate(QUESTIONNAIRE_ID) { launches++ }

      assertEquals(1, launches)
      assertFalse(viewModel.showAiEnableFailedDialog.value)
    }

  @Test
  fun `add case is blocked when AI is on but the map is stale`() =
    runTest(testDispatcher) {
      localStructureMap = structureMapFixture(MAP_WITHOUT_AI_RESULT)
      val viewModel = createViewModel()

      viewModel.launchIfAiUpToDate(QUESTIONNAIRE_ID) { launches++ }

      assertEquals(0, launches)
      assertTrue(viewModel.showAiEnableFailedDialog.value)
    }

  @Test
  fun `a second tap right after a launch does not open the form twice`() =
    runTest(testDispatcher) {
      val viewModel = createViewModel()

      viewModel.launchIfAiUpToDate(QUESTIONNAIRE_ID) { launches++ }
      now += 300
      viewModel.launchIfAiUpToDate(QUESTIONNAIRE_ID) { launches++ }
      assertEquals(1, launches)

      now += 1_000
      viewModel.launchIfAiUpToDate(QUESTIONNAIRE_ID) { launches++ }
      assertEquals(2, launches)
    }

  @Test
  fun `retry while offline keeps the dialog open and does not ask for a sync`() =
    runTest(testDispatcher) {
      val viewModel = blockedViewModel()

      assertFalse(viewModel.onAiEnableRetry(isOnline = false))
      assertTrue(viewModel.showAiEnableFailedDialog.value)
    }

  @Test
  fun `retry online closes the dialog once and ignores a double tap`() =
    runTest(testDispatcher) {
      val viewModel = blockedViewModel()

      assertTrue(viewModel.onAiEnableRetry(isOnline = true))
      assertFalse(viewModel.showAiEnableFailedDialog.value)
      now += 200
      assertFalse(viewModel.onAiEnableRetry(isOnline = true))
    }

  @Test
  fun `a sync that delivers the extraction after retry is announced once`() =
    runTest(testDispatcher) {
      val viewModel = blockedViewModel()
      viewModel.onAiEnableRetry(isOnline = true)

      localStructureMap = structureMapFixture(MAP_WITH_AI_RESULT) // what the sync downloaded

      assertTrue(viewModel.reconcileAiAfterSync(QUESTIONNAIRE_ID))
      assertFalse(viewModel.showAiEnableFailedDialog.value)
      assertFalse(viewModel.reconcileAiAfterSync(QUESTIONNAIRE_ID))
    }

  @Test
  fun `a sync after retry that leaves the map stale brings the dialog back`() =
    runTest(testDispatcher) {
      val viewModel = blockedViewModel()
      viewModel.onAiEnableRetry(isOnline = true)

      assertFalse(viewModel.reconcileAiAfterSync(QUESTIONNAIRE_ID))
      assertTrue(viewModel.showAiEnableFailedDialog.value)
    }

  @Test
  fun `a sync after retry that turns the site's AI off settles it without dialog or announcement`() =
    runTest(testDispatcher) {
      val viewModel = blockedViewModel()
      viewModel.onAiEnableRetry(isOnline = true)

      flagEnabled = false // the server switched AI off for the site

      assertFalse(viewModel.reconcileAiAfterSync(QUESTIONNAIRE_ID))
      assertFalse(viewModel.showAiEnableFailedDialog.value)
      viewModel.launchIfAiUpToDate(QUESTIONNAIRE_ID) { launches++ }
      assertEquals(1, launches)
    }

  @Test
  fun `a background sync that leaves the map stale does not pop the dialog`() =
    runTest(testDispatcher) {
      localStructureMap = structureMapFixture(MAP_WITHOUT_AI_RESULT)
      val viewModel = createViewModel()
      viewModel.refreshAiEnabledState(QUESTIONNAIRE_ID)

      assertFalse(viewModel.reconcileAiAfterSync(QUESTIONNAIRE_ID))
      assertFalse(viewModel.showAiEnableFailedDialog.value)
    }

  @Test
  fun `a sync that delivers the extraction is announced even without a failed add case`() =
    runTest(testDispatcher) {
      localStructureMap = structureMapFixture(MAP_WITHOUT_AI_RESULT)
      val viewModel = createViewModel()
      viewModel.refreshAiEnabledState(QUESTIONNAIRE_ID)

      localStructureMap = structureMapFixture(MAP_WITH_AI_RESULT)

      assertTrue(viewModel.reconcileAiAfterSync(QUESTIONNAIRE_ID))
    }

  @Test
  fun `syncs on a site with AI off are never announced`() =
    runTest(testDispatcher) {
      flagEnabled = false
      localStructureMap = structureMapFixture(MAP_WITHOUT_AI_RESULT)
      val viewModel = createViewModel()
      viewModel.refreshAiEnabledState(QUESTIONNAIRE_ID)

      localStructureMap = structureMapFixture(MAP_WITH_AI_RESULT)

      assertFalse(viewModel.reconcileAiAfterSync(QUESTIONNAIRE_ID))
      assertFalse(viewModel.showAiEnableFailedDialog.value)
    }

  @Test
  fun `a sync while AI was already enabled is not announced`() =
    runTest(testDispatcher) {
      val viewModel = createViewModel()
      viewModel.refreshAiEnabledState(QUESTIONNAIRE_ID)

      assertFalse(viewModel.reconcileAiAfterSync(QUESTIONNAIRE_ID))
    }

  /** AI on, stale map, and the user has just been blocked from adding a case. */
  private suspend fun blockedViewModel(): RegisterViewModel {
    localStructureMap = structureMapFixture(MAP_WITHOUT_AI_RESULT)
    return createViewModel().also { viewModel ->
      viewModel.launchIfAiUpToDate(QUESTIONNAIRE_ID) { launches++ }
      assertTrue(viewModel.showAiEnableFailedDialog.value)
    }
  }

  private fun createViewModel() =
    RegisterViewModel(
        registerRepository = mockk<RegisterRepository>(relaxed = true),
        configurationRegistry = mockk<ConfigurationRegistry>(relaxed = true),
        sharedPreferencesHelper = mockk<SharedPreferencesHelper>(relaxed = true),
        dispatcherProvider =
          object : DispatcherProvider {
            override fun io() = testDispatcher
          },
        secureSharedPreference = mockk<SecureSharedPreference>(relaxed = true),
        resourceDataRulesExecutor = mockk<ResourceDataRulesExecutor>(relaxed = true),
        fhirEngine = fhirEngine,
        featureFlagUtil = featureFlagUtil,
        userSwitchManager = mockk<UserSwitchManager>(relaxed = true),
      )
      .also {
        it.elapsedRealtime = { now }
        viewModels.add(it)
      }

  private fun structureMapFixture(name: String): StructureMap =
    FhirContext.forR4Cached()
      .newJsonParser()
      .parseResource(
        StructureMap::class.java,
        javaClass.classLoader!!.getResource("structuremap/$name.json")!!.readText(),
      )

  private companion object {
    const val QUESTIONNAIRE_ID = "OralCancerPatientRegistration"
    const val STRUCTURE_MAP_URL = "https://midas.iisc.ac.in/fhir/StructureMap/$QUESTIONNAIRE_ID"
    const val TARGET_STRUCTURE_MAP_EXTENSION =
      "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-targetStructureMap"
    const val MAP_WITH_AI_RESULT = "oral_cancer_registration_v3_with_ai_result"
    const val MAP_WITHOUT_AI_RESULT = "oral_cancer_registration_v2_without_ai_result"
  }
}
