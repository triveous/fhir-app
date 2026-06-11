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

package org.smartregister.fhircore.quest.ui.main

import android.content.Context
import android.os.SystemClock
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.google.android.fhir.FhirEngine
import com.google.android.fhir.search.search
import com.google.android.fhir.sync.CurrentSyncJobStatus
import com.google.android.fhir.sync.SyncJobStatus
import com.google.android.fhir.sync.SyncOperation
import dagger.hilt.android.lifecycle.HiltViewModel
import org.smartregister.fhircore.quest.util.PostHogAnalytics
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.hl7.fhir.r4.model.Binary
import org.hl7.fhir.r4.model.DocumentReference
import org.hl7.fhir.r4.model.Enumerations
import org.hl7.fhir.r4.model.QuestionnaireResponse
import org.hl7.fhir.r4.model.ResourceType
import org.hl7.fhir.r4.model.Task
import org.smartregister.fhircore.engine.R
import org.smartregister.fhircore.engine.configuration.ConfigType
import org.smartregister.fhircore.engine.configuration.ConfigurationRegistry
import org.smartregister.fhircore.engine.configuration.QuestionnaireConfig
import org.smartregister.fhircore.engine.configuration.app.ApplicationConfiguration
import org.smartregister.fhircore.engine.configuration.geowidget.GeoWidgetConfiguration
import org.smartregister.fhircore.engine.configuration.navigation.ICON_TYPE_REMOTE
import org.smartregister.fhircore.engine.configuration.navigation.NavigationConfiguration
import org.smartregister.fhircore.engine.configuration.report.measure.MeasureReportConfiguration
import org.smartregister.fhircore.engine.data.local.register.RegisterRepository
import org.smartregister.fhircore.engine.domain.networkUtils.DocumentReferenceCaseType
import org.smartregister.fhircore.engine.domain.model.ActionParameter
import org.smartregister.fhircore.engine.domain.model.ActionParameterType
import org.smartregister.fhircore.engine.sync.SyncBroadcaster
import org.smartregister.fhircore.engine.task.FhirCarePlanGenerator
import org.smartregister.fhircore.engine.task.FhirCompleteCarePlanWorker
import org.smartregister.fhircore.engine.task.FhirResourceExpireWorker
import org.smartregister.fhircore.engine.task.FhirTaskStatusUpdateWorker
import org.smartregister.fhircore.engine.ui.bottomsheet.RegisterBottomSheetFragment
import org.smartregister.fhircore.engine.util.DispatcherProvider
import org.smartregister.fhircore.engine.util.SecureSharedPreference
import org.smartregister.fhircore.engine.util.SharedPreferenceKey
import org.smartregister.fhircore.engine.util.SharedPreferencesHelper
import org.smartregister.fhircore.engine.util.extension.decodeToBitmap
import org.smartregister.fhircore.engine.util.extension.extractLogicalIdUuid
import org.smartregister.fhircore.engine.util.extension.getActivity
import org.smartregister.fhircore.engine.util.extension.isDeviceOnline
import org.smartregister.fhircore.engine.util.extension.setAppLocale
import org.smartregister.fhircore.engine.util.extension.showToast
import org.smartregister.fhircore.engine.util.extension.tryParse
import org.smartregister.fhircore.quest.BuildConfig
import org.smartregister.fhircore.quest.navigation.MainNavigationScreen
import org.smartregister.fhircore.quest.navigation.NavigationArg
import org.smartregister.fhircore.quest.ui.report.measure.worker.MeasureReportMonthPeriodWorker
import org.smartregister.fhircore.quest.ui.shared.QuestionnaireHandler
import org.smartregister.fhircore.quest.ui.shared.models.QuestionnaireSubmission
import org.smartregister.fhircore.quest.util.extensions.handleClickEvent
import org.smartregister.fhircore.quest.util.extensions.schedulePeriodically
import timber.log.Timber
import java.text.SimpleDateFormat
import java.time.OffsetDateTime
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import kotlin.time.Duration

@HiltViewModel
class AppMainViewModel
@Inject
constructor(
  val secureSharedPreference: SecureSharedPreference,
  val syncBroadcaster: SyncBroadcaster,
  val sharedPreferencesHelper: SharedPreferencesHelper,
  val configurationRegistry: ConfigurationRegistry,
  val registerRepository: RegisterRepository,
  val dispatcherProvider: DispatcherProvider,
  val workManager: WorkManager,
  val fhirCarePlanGenerator: FhirCarePlanGenerator,
  val fhirEngine: FhirEngine,
) : ViewModel() {

  private val simpleDateFormat = SimpleDateFormat(SYNC_TIMESTAMP_OUTPUT_FORMAT, Locale.getDefault())
  private var syncStartedAtMs: Long? = null

  /**
   * Identifies the current determinate progress phase as an (operation, total) pair. A single sync
   * runs several sequential phases (metadata download, metadata upload, image upload), each with its
   * own total. We track the active phase so the bar can restart its baseline when a genuinely new
   * phase begins — otherwise the monotonic guard in [showSyncProgress] would pin the bar at the
   * previous phase's percentage (e.g. holding ~99% from the metadata sync while images upload).
   */
  private var currentSyncPhaseKey: Pair<SyncOperation, Int>? = null

  /**
   * Drives the non-blocking floating sync progress bar. Lives in the (activity-scoped) view model so
   * it survives fragment recreation and is shared across all screens/tabs while a sync is running.
   */
  private val _syncProgressStateFlow = MutableStateFlow(SyncProgressUiState())
  val syncProgressStateFlow: StateFlow<SyncProgressUiState> = _syncProgressStateFlow.asStateFlow()

  val applicationConfiguration: ApplicationConfiguration by lazy {
    configurationRegistry.retrieveConfiguration(ConfigType.Application, paramsMap = emptyMap())
  }

  val navigationConfiguration: NavigationConfiguration by lazy {
    configurationRegistry.retrieveConfiguration(ConfigType.Navigation)
  }

  private val measureReportConfigurations: List<MeasureReportConfiguration> by lazy {
    configurationRegistry.retrieveConfigurations(ConfigType.MeasureReport)
  }

  fun retrieveIconsAsBitmap() {
    navigationConfiguration.clientRegisters
      .asSequence()
      .filter {
        it.menuIconConfig != null &&
          it.menuIconConfig?.type == ICON_TYPE_REMOTE &&
          !it.menuIconConfig!!.reference.isNullOrEmpty()
      }
      .forEach {
        val resourceId = it.menuIconConfig!!.reference!!.extractLogicalIdUuid()
        viewModelScope.launch(dispatcherProvider.io()) {
          registerRepository.loadResource<Binary>(resourceId)?.let { binary ->
            it.menuIconConfig!!.decodedBitmap = binary.data.decodeToBitmap()
          }
        }
      }
  }

  fun setPostHogUserProperties() {
    viewModelScope.launch(dispatcherProvider.io()) {
      try {
        updatePostHogUserProperties(
          pendingSyncImages = pendingSyncImages(),
          pendingSyncCases = pendingSyncCases(),
        )
      } catch (e: Exception) {
        Timber.e(e)
      }
    }
  }

  fun onEvent(event: AppMainEvent, isForeground: Boolean = false) {
    when (event) {
      is AppMainEvent.SwitchLanguage -> {
        sharedPreferencesHelper.write(SharedPreferenceKey.LANG.name, event.language.tag)
        event.context.run {
          setAppLocale(event.language.tag)
//          getActivity()?.refresh()
        }
      }
      is AppMainEvent.SyncData -> {
        Timber.d("SyncData event received. isForeground=$isForeground")
        if (event.context.isDeviceOnline()) {
          syncStartedAtMs = SystemClock.elapsedRealtime()
          PostHogAnalytics.capture(PostHogAnalytics.Events.SYNC_INITIATED)
          setPostHogUserProperties()
          if (!isForeground) {
            viewModelScope.launch { syncBroadcaster.runOneTimeSync() }
          } else {
            Timber.d("Starting user-initiated one-time sync")
            viewModelScope.launch {
              syncBroadcaster.runOneTimeSync(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            }
          }
        } else {
          event.context.showToast(event.context.getString(R.string.sync_failed), Toast.LENGTH_LONG)
        }
      }
      is AppMainEvent.OpenRegistersBottomSheet -> displayRegisterBottomSheet(event)
      is AppMainEvent.UpdateSyncState -> {
        if (event.state is CurrentSyncJobStatus.Succeeded) {
          sharedPreferencesHelper.write(
            SharedPreferenceKey.LAST_SYNC_TIMESTAMP.name,
            formatLastSyncTimestamp(event.state.timestamp),
          )
        }
        if (
          event.state is CurrentSyncJobStatus.Succeeded ||
            event.state is CurrentSyncJobStatus.Failed
        ) {
          captureSyncCompleted(event.state)
        }
      }
      is AppMainEvent.TriggerWorkflow ->
        event.navMenu.actions?.handleClickEvent(
          navController = event.navController,
          resourceData = null,
          navMenu = event.navMenu,
        )
      is AppMainEvent.OpenProfile -> {
        val args =
          bundleOf(
            NavigationArg.PROFILE_ID to event.profileId,
            NavigationArg.RESOURCE_ID to event.resourceId,
            NavigationArg.RESOURCE_CONFIG to event.resourceConfig,
          )
        event.navController.navigate(MainNavigationScreen.Profile.route, args)
      }
    }
  }

  private fun displayRegisterBottomSheet(event: AppMainEvent.OpenRegistersBottomSheet) {
    (event.navController.context.getActivity())?.let { activity ->
      RegisterBottomSheetFragment(
          navigationMenuConfigs = event.registersList,
          menuClickListener = {
            onEvent(AppMainEvent.TriggerWorkflow(navController = event.navController, navMenu = it))
          },
          title = event.title,
        )
        .run { show(activity.supportFragmentManager, RegisterBottomSheetFragment.TAG) }
    }
  }

  private fun captureSyncCompleted(state: CurrentSyncJobStatus) {
    viewModelScope.launch(dispatcherProvider.io()) {
      val pendingImages = pendingSyncImages()
      val pendingCases = pendingSyncCases()
      val syncDuration = syncStartedAtMs?.let { SystemClock.elapsedRealtime() - it }
      val syncStatus =
        when (state) {
          is CurrentSyncJobStatus.Succeeded -> "succeeded"
          is CurrentSyncJobStatus.Failed -> "failed"
          else -> return@launch
        }

      PostHogAnalytics.capture(
        PostHogAnalytics.Events.SYNC_COMPLETED,
        mapOf(
          PostHogAnalytics.Props.SYNC_STATUS to syncStatus,
          PostHogAnalytics.Props.SYNC_DURATION_MS to syncDuration,
          PostHogAnalytics.Props.PENDING_IMAGES_AFTER to pendingImages,
          PostHogAnalytics.Props.PENDING_CASES_AFTER to pendingCases,
          PostHogAnalytics.Props.ERROR_MESSAGE to
            (state as? CurrentSyncJobStatus.Failed)?.toString(),
        ),
      )
      updatePostHogUserProperties(
        pendingSyncImages = pendingImages,
        pendingSyncCases = pendingCases,
      )
      syncStartedAtMs = null
    }
  }

  /**
   * Updates [syncProgressStateFlow] from the raw sync status emitted by the FHIR sync worker. The
   * floating progress bar stays visible for the whole [CurrentSyncJobStatus.Running] phase and is
   * only dismissed on a terminal Succeeded/Failed status — this prevents the bar from flickering
   * away when the per-resource progress momentarily hits 100% mid-sync.
   */
  fun updateSyncProgress(syncJobStatus: CurrentSyncJobStatus) {
    when (syncJobStatus) {
      is CurrentSyncJobStatus.Running -> {
        when (val inProgress = syncJobStatus.inProgressSyncJob) {
          is SyncJobStatus.Started -> showSyncProgressStarted()
          is SyncJobStatus.InProgress -> showSyncProgress(inProgress)
          else -> {
            // Enqueued or other intermediate states: ensure the bar is visible.
            _syncProgressStateFlow.update { it.copy(isSyncing = true) }
          }
        }
      }
      is CurrentSyncJobStatus.Succeeded,
      is CurrentSyncJobStatus.Failed, -> {
        currentSyncPhaseKey = null
        _syncProgressStateFlow.value = SyncProgressUiState(isSyncing = false)
      }
      else -> {
        // Enqueued / Cancelled: leave the current state untouched.
      }
    }
  }

  private fun showSyncProgressStarted() {
    val firstTimeSync = isFirstTimeSync()
    val currentState = _syncProgressStateFlow.value

    if (!currentState.isSyncing) {
      currentSyncPhaseKey = null
      _syncProgressStateFlow.value =
        SyncProgressUiState(
          isSyncing = true,
          progressPercentage = 0,
          isUploadSync = false,
          isFirstTimeSync = firstTimeSync,
        )
    } else {
      _syncProgressStateFlow.update {
        it.copy(isSyncing = true, isFirstTimeSync = it.isFirstTimeSync || firstTimeSync)
      }
    }
  }

  private fun showSyncProgress(inProgress: SyncJobStatus.InProgress) {
    val isUpload = inProgress.syncOperation == SyncOperation.UPLOAD
    val firstTimeSync = isFirstTimeSync()
    val percentage = calculateActualPercentageProgress(inProgress)

    // A determinate emission (total > 0) that either belongs to a new (operation, total) phase or is
    // the first tick of a phase (completed == 0) restarts the bar from 0. This lets the image-upload
    // phase show real 0..100 progress instead of inheriting the ~99% left by the metadata sync.
    // Indeterminate emissions (percentage == null, i.e. total <= 0) never reset the baseline so a
    // no-op upload phase cannot blank out an in-progress download.
    val phaseKey = inProgress.syncOperation to inProgress.total
    val isNewPhase =
      percentage != null && (phaseKey != currentSyncPhaseKey || inProgress.completed == 0)
    if (percentage != null) currentSyncPhaseKey = phaseKey

    _syncProgressStateFlow.update {
      // Never move backwards within a phase. When the total is unknown (percentage == null) we
      // cannot compute a value, so we hold the previous one and let the bar stay indeterminate.
      val baseline = if (it.isSyncing && !isNewPhase) it.progressPercentage else 0
      it.copy(
        isSyncing = true,
        progressPercentage = maxOf(baseline, percentage ?: baseline),
        isUploadSync = isUpload,
        isFirstTimeSync = it.isFirstTimeSync || firstTimeSync,
      )
    }
  }

  private fun isFirstTimeSync(): Boolean =
    sharedPreferencesHelper.read(SharedPreferenceKey.LAST_SYNC_TIMESTAMP.name, null).isNullOrEmpty()

  /**
   * Computes the sync progress percentage directly from the raw SDK status.
   *
   * The FHIR SDK ([com.google.android.fhir.sync.FhirSynchronizer]) reports a fixed [total] for the
   * whole operation and a [completed] count that grows monotonically from `0..total`. We therefore
   * just compute `completed / total`, capped below 100 so the bar never reads "complete" before the
   * sync actually reaches a terminal Succeeded/Failed status.
   *
   * When the server does not report resource counts the SDK emits `total == 0`. In that case a
   * percentage is meaningless (the previous implementation incorrectly rendered this as 99%), so we
   * return `null` and let the bar stay indeterminate.
   */
  private fun calculateActualPercentageProgress(
    progressSyncJobStatus: SyncJobStatus.InProgress,
  ): Int? {
    if (progressSyncJobStatus.total <= 0) return null
    return (progressSyncJobStatus.completed * 100 / progressSyncJobStatus.total).coerceIn(0, 99)
  }

  private suspend fun pendingSyncImages(): Int =
    fhirEngine.search<DocumentReference> {}
      .count { it.resource.description != DocumentReferenceCaseType.DRAFT }

  private suspend fun pendingSyncCases(): Int = fhirEngine.getUnsyncedLocalChanges().size

  private fun updatePostHogUserProperties(pendingSyncImages: Int, pendingSyncCases: Int) {
    val flwId = secureSharedPreference.getPractitionerUserId()
    PostHogAnalytics.identifyUser(
      flwId = flwId,
      pendingSyncImages = pendingSyncImages,
      pendingSyncCases = pendingSyncCases,
    )
  }

  fun launchFamilyRegistrationWithLocationId(
    context: Context,
    locationId: String,
    questionnaireConfig: QuestionnaireConfig,
  ) {
    viewModelScope.launch {
      val prePopulateLocationIdParameter =
        ActionParameter(
          key = "locationId",
          paramType = ActionParameterType.PREPOPULATE,
          dataType = Enumerations.DataType.STRING,
          resourceType = ResourceType.Location,
          value = locationId,
          linkId = "household-location-reference",
        )
      if (context is QuestionnaireHandler) {
        context.launchQuestionnaire(
          context = context,
          questionnaireConfig = questionnaireConfig,
          actionParams = listOf(prePopulateLocationIdParameter),
        )
      }
    }
  }

  private fun loadCurrentLanguage() =
    Locale.forLanguageTag(
        sharedPreferencesHelper.read(SharedPreferenceKey.KEY_LANGUAGE_CODE.name, Locale.ENGLISH.toLanguageTag())
          ?: Locale.ENGLISH.toLanguageTag(),
      )
      .displayName

  fun formatLastSyncTimestamp(timestamp: OffsetDateTime): String {
    val syncTimestampFormatter =
      SimpleDateFormat(SYNC_TIMESTAMP_INPUT_FORMAT, Locale.getDefault()).apply {
        timeZone = TimeZone.getDefault()
      }
    val parse: Date? = syncTimestampFormatter.parse(timestamp.toString())
    return if (parse == null) "" else simpleDateFormat.format(parse)
  }

  fun retrieveLastSyncTimestamp(): String? =
    sharedPreferencesHelper.read(SharedPreferenceKey.LAST_SYNC_TIMESTAMP.name, null)

  fun launchProfileFromGeoWidget(
    navController: NavController,
    geoWidgetConfigId: String,
    resourceId: String,
  ) {
    val geoWidgetConfiguration =
      configurationRegistry.retrieveConfiguration<GeoWidgetConfiguration>(
        ConfigType.GeoWidget,
        geoWidgetConfigId,
      )
    onEvent(
      AppMainEvent.OpenProfile(
        navController = navController,
        profileId = geoWidgetConfiguration.profileId,
        resourceId = resourceId,
        resourceConfig = geoWidgetConfiguration.resourceConfig,
      ),
    )
  }

  /** This function is used to schedule tasks that are intended to run periodically */
  fun schedulePeriodicJobs() {
    workManager.run {
      schedulePeriodically<FhirTaskStatusUpdateWorker>(
        workId = FhirTaskStatusUpdateWorker.WORK_ID,
        duration = Duration.tryParse(applicationConfiguration.taskStatusUpdateJobDuration),
        requiresNetwork = false,
      )

      schedulePeriodically<FhirResourceExpireWorker>(
        workId = FhirResourceExpireWorker.WORK_ID,
        duration = Duration.tryParse(applicationConfiguration.taskExpireJobDuration),
        requiresNetwork = false,
      )

      schedulePeriodically<FhirCompleteCarePlanWorker>(
        workId = FhirCompleteCarePlanWorker.WORK_ID,
        duration = Duration.tryParse(applicationConfiguration.taskCompleteCarePlanJobDuration),
        requiresNetwork = false,
      )

      measureReportConfigurations.forEach { measureReportConfig ->
        measureReportConfig.scheduledGenerationDuration?.let { scheduledGenerationDuration ->
          schedulePeriodically<MeasureReportMonthPeriodWorker>(
            workId = "${MeasureReportMonthPeriodWorker.WORK_ID}-${measureReportConfig.id}",
            duration = Duration.tryParse(scheduledGenerationDuration),
            requiresNetwork = false,
            inputData =
              workDataOf(
                MeasureReportMonthPeriodWorker.MEASURE_REPORT_CONFIG_ID to measureReportConfig.id,
              ),
          )
        }
      }
    }
  }

  suspend fun onQuestionnaireSubmission(questionnaireSubmission: QuestionnaireSubmission) {
    questionnaireSubmission.questionnaireConfig.taskId?.let { taskId ->
      val status: Task.TaskStatus =
        when (questionnaireSubmission.questionnaireResponse.status) {
          QuestionnaireResponse.QuestionnaireResponseStatus.INPROGRESS -> Task.TaskStatus.INPROGRESS
          QuestionnaireResponse.QuestionnaireResponseStatus.COMPLETED -> Task.TaskStatus.COMPLETED
          else -> Task.TaskStatus.COMPLETED
        }

      withContext(dispatcherProvider.io()) {
        fhirCarePlanGenerator.updateTaskDetailsByResourceId(
          id = taskId.extractLogicalIdUuid(),
          status = status,
        )
      }
    }
  }

  companion object {
    const val SYNC_TIMESTAMP_INPUT_FORMAT = "yyyy-MM-dd'T'HH:mm:ss"
    const val SYNC_TIMESTAMP_OUTPUT_FORMAT = "MMM d, hh:mm aa"
  }
}
