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

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material.ExperimentalMaterialApi
import androidx.core.os.bundleOf
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.NavHostFragment
import com.google.android.fhir.FhirEngine
import com.google.android.fhir.sync.CurrentSyncJobStatus
import com.google.android.material.bottomnavigation.BottomNavigationView
import dagger.hilt.android.AndroidEntryPoint
import io.sentry.android.navigation.SentryNavigationListener
import kotlinx.coroutines.launch
import org.hl7.fhir.r4.model.IdType
import org.hl7.fhir.r4.model.QuestionnaireResponse
import org.smartregister.fhircore.engine.configuration.QuestionnaireConfig
import org.smartregister.fhircore.engine.configuration.app.ConfigService
import org.smartregister.fhircore.engine.configuration.workflow.ActionTrigger
import org.smartregister.fhircore.engine.sync.OnSyncListener
import org.smartregister.fhircore.engine.sync.SyncBroadcaster
import org.smartregister.fhircore.engine.sync.SyncListenerManager
import org.smartregister.fhircore.engine.ui.base.BaseMultiLanguageActivity
import org.smartregister.fhircore.engine.util.DefaultDispatcherProvider
import org.smartregister.fhircore.engine.util.extension.isDeviceOnline
import org.smartregister.fhircore.engine.util.extension.parcelable
import org.smartregister.fhircore.engine.util.extension.serializable
import org.smartregister.fhircore.engine.util.extension.showToast
import org.smartregister.fhircore.geowidget.model.GeoWidgetEvent
import org.smartregister.fhircore.geowidget.screens.GeoWidgetViewModel
import org.smartregister.fhircore.quest.R
import org.smartregister.fhircore.quest.event.AppEvent
import org.smartregister.fhircore.quest.event.EventBus
import org.smartregister.fhircore.quest.navigation.NavigationArg
import org.smartregister.fhircore.quest.ui.questionnaire.QuestionnaireActivity
import org.smartregister.fhircore.quest.ui.shared.QuestionnaireHandler
import org.smartregister.fhircore.quest.ui.shared.models.QuestionnaireSubmission
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
@ExperimentalMaterialApi
open class AppMainActivity : BaseMultiLanguageActivity(), QuestionnaireHandler, OnSyncListener {

  @Inject lateinit var dispatcherProvider: DefaultDispatcherProvider

  @Inject lateinit var configService: ConfigService

  @Inject lateinit var syncListenerManager: SyncListenerManager

  @Inject lateinit var syncBroadcaster: SyncBroadcaster

  @Inject lateinit var fhirEngine: FhirEngine

  @Inject lateinit var eventBus: EventBus
  lateinit var navHostFragment: NavHostFragment
  val appMainViewModel by viewModels<AppMainViewModel>()
  private val geoWidgetViewModel by viewModels<GeoWidgetViewModel>()
  private val sentryNavListener =
    SentryNavigationListener(enableNavigationBreadcrumbs = true, enableNavigationTracing = true)

  override val startForResult =
    registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { activityResult ->
      if (activityResult.resultCode == Activity.RESULT_OK) {
        lifecycleScope.launch { onSubmitQuestionnaire(activityResult) }
      }
    }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    //setContentView(FragmentContainerView(this).apply { id = R.id.nav_host })
    setContentView(R.layout.activity_main)
    val topMenuConfig = appMainViewModel.navigationConfiguration.clientRegisters.firstOrNull()
    val topMenuConfigId =
      topMenuConfig?.actions?.firstOrNull { it.trigger == ActionTrigger.ON_CLICK }?.id ?: topMenuConfig?.id

    val fragmentManager = supportFragmentManager
    navHostFragment = fragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment?
      ?: NavHostFragment.create(R.navigation.application_nav_graph, bundleOf(
        NavigationArg.SCREEN_TITLE to topMenuConfig?.display,
        NavigationArg.REGISTER_ID to topMenuConfigId,
      )).also {
        fragmentManager.beginTransaction()
          .replace(R.id.nav_host_fragment, it)
          .setPrimaryNavigationFragment(it)
          .commitNow()
      }

    val bottomNavigationView = findViewById<BottomNavigationView>(R.id.bottom_navigation)
    bottomNavigationView?.setOnNavigationItemSelectedListener { item ->
      val navController = navHostFragment.navController
      when (item.itemId) {
        R.id.navigation_register -> {
          navController.popBackStack(R.id.registerFragment, true)
          navController.navigate(R.id.registerFragment, bundleOf(
            NavigationArg.SCREEN_TITLE to topMenuConfig?.display,
            NavigationArg.REGISTER_ID to topMenuConfigId,
          ))
          true
        }
        R.id.navigation_tasks -> {
          navController.popBackStack(R.id.tasksFragment, true)
          navController.navigate(R.id.tasksFragment)
          true
        }

        /*R.id.navigation_profile -> {
          appMainViewModel.appMainUiState.value.username
          navController.popBackStack(R.id.profileSectionFragment, true)

          *//*val args = bundleOf(
            NavigationArg.PROFILE_ID to "",
            NavigationArg.RESOURCE_ID to "",
            NavigationArg.RESOURCE_CONFIG to "",
          )
          navController.navigate(MainNavigationScreen.Profile.route, args = args)*//*

          navController.navigate(R.id.profileSectionFragment, bundleOf(
            NavigationArg.USER_NAME to appMainViewModel.appMainUiState.value.username
          ))
          true
        }*/

        R.id.navigation_dashboard -> {
//          appMainViewModel.appMainUiState.value.username
          navController.popBackStack(R.id.dashboardFragment, true)
          navController.navigate(R.id.dashboardFragment)
          true
        }
        else -> false
      }
    }

/*    supportFragmentManager
      .beginTransaction()
      .replace(R.id.nav_host_fragment, navHostFragment)
      .setPrimaryNavigationFragment(navHostFragment)
      .commit()*/

    geoWidgetViewModel.geoWidgetEventLiveData.observe(this) { geoWidgetEvent ->
      when (geoWidgetEvent) {
        is GeoWidgetEvent.OpenProfile ->
          appMainViewModel.launchProfileFromGeoWidget(
            navHostFragment.navController,
            geoWidgetEvent.geoWidgetConfiguration.id,
            geoWidgetEvent.data,
          )
        is GeoWidgetEvent.RegisterClient ->
          appMainViewModel.launchFamilyRegistrationWithLocationId(
            context = this,
            locationId = geoWidgetEvent.data,
            questionnaireConfig = geoWidgetEvent.questionnaire,
          )
      }
    }

    // Register sync listener then run sync in that order
    syncListenerManager.registerSyncListener(this, lifecycle)

    // Setup the drawer and schedule jobs
    appMainViewModel.run {
      lifecycleScope.launch {
//        retrieveAppMainUiState()
        if (isDeviceOnline()) {
          appMainViewModel.setSentryUserProperties()
          syncBroadcaster.schedulePeriodicSync(applicationConfiguration.syncInterval)
        } else {
          showToast(
            getString(org.smartregister.fhircore.engine.R.string.sync_failed),
            Toast.LENGTH_LONG,
          )
        }
      }
      schedulePeriodicJobs()
    }
  }

  override fun onResume() {
    super.onResume()
    navHostFragment.navController.addOnDestinationChangedListener(sentryNavListener)
    syncListenerManager.registerSyncListener(this, lifecycle)
  }

  override fun onPause() {
    super.onPause()
    navHostFragment.navController.removeOnDestinationChangedListener(sentryNavListener)
  }

  override suspend fun onSubmitQuestionnaire(activityResult: ActivityResult) {
    if (activityResult.resultCode == RESULT_OK) {
      val questionnaireResponse: QuestionnaireResponse? =
        activityResult.data?.serializable(QuestionnaireActivity.QUESTIONNAIRE_RESPONSE)
          as QuestionnaireResponse?
      val extractedResourceIds =
        activityResult.data?.serializable(
          QuestionnaireActivity.QUESTIONNAIRE_SUBMISSION_EXTRACTED_RESOURCE_IDS,
        ) as List<IdType>? ?: emptyList()
      val questionnaireConfig =
        activityResult.data?.parcelable(QuestionnaireActivity.QUESTIONNAIRE_CONFIG)
          as QuestionnaireConfig?

      if (questionnaireConfig != null && questionnaireResponse != null) {
        eventBus.triggerEvent(
          AppEvent.OnSubmitQuestionnaire(
            QuestionnaireSubmission(
              questionnaireConfig = questionnaireConfig,
              questionnaireResponse = questionnaireResponse,
              extractedResourceIds = extractedResourceIds,
            ),
          ),
        )
      } else Timber.e("QuestionnaireConfig & QuestionnaireResponse are both null")
    }
  }

  override fun onSync(syncJobStatus: CurrentSyncJobStatus) {
//    Timber.e("App Main Activity TAG onSync --> $syncJobStatus")
    when (syncJobStatus) {
      is CurrentSyncJobStatus.Succeeded -> {
        appMainViewModel.run {
          onEvent(
            AppMainEvent.UpdateSyncState(
              state = syncJobStatus,
              lastSyncTime = formatLastSyncTimestamp(syncJobStatus.timestamp),
            ),
          )
        }
      }
      is CurrentSyncJobStatus.Failed -> {
        appMainViewModel.run {
          onEvent(
            AppMainEvent.UpdateSyncState(
              state = syncJobStatus,
              lastSyncTime = formatLastSyncTimestamp(syncJobStatus.timestamp),
            ),
          )
        }
      }
      else -> {
        // Do nothing
      }
    }
  }
}
