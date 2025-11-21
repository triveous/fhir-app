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

package org.smartregister.fhircore.quest.ui.questionnaire

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.location.Location
import android.os.Bundle
import android.os.Parcelable
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.viewModels
import androidx.core.os.bundleOf
import androidx.fragment.app.commit
import androidx.lifecycle.lifecycleScope
import com.google.android.fhir.datacapture.QuestionnaireFragment
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.hl7.fhir.r4.model.DocumentReference
import org.hl7.fhir.r4.model.Questionnaire
import org.hl7.fhir.r4.model.QuestionnaireResponse
import org.hl7.fhir.r4.model.Reference
import org.hl7.fhir.r4.model.Resource
import org.hl7.fhir.r4.model.ResourceType
import org.hl7.fhir.r4.model.StringType
import org.smartregister.fhircore.engine.configuration.QuestionnaireConfig
import org.smartregister.fhircore.engine.configuration.app.LocationLogOptions
import org.smartregister.fhircore.engine.domain.model.ActionParameter
import org.smartregister.fhircore.engine.domain.model.ActionParameterType
import org.smartregister.fhircore.engine.domain.model.isEditable
import org.smartregister.fhircore.engine.domain.model.isReadOnly
import org.smartregister.fhircore.engine.ui.base.AlertDialogue
import org.smartregister.fhircore.engine.ui.base.BaseMultiLanguageActivity
import org.smartregister.fhircore.engine.util.DispatcherProvider
import org.smartregister.fhircore.engine.util.extension.clearText
import org.smartregister.fhircore.engine.util.extension.encodeResourceToString
import org.smartregister.fhircore.engine.util.extension.logicalId
import org.smartregister.fhircore.engine.util.extension.parcelable
import org.smartregister.fhircore.engine.util.extension.parcelableArrayList
import org.smartregister.fhircore.engine.util.extension.showToast
import org.smartregister.fhircore.quest.R
import org.smartregister.fhircore.quest.databinding.QuestionnaireActivityBinding
import org.smartregister.fhircore.quest.ui.register.patients.DocumentReferenceCaseType
import org.smartregister.fhircore.quest.util.CONFIDENCE_PERCENTAGE_URL
import org.smartregister.fhircore.quest.util.LocationUtils
import org.smartregister.fhircore.quest.util.PermissionUtils
import org.smartregister.fhircore.quest.util.ResourceUtils
import org.smartregister.fhircore.quest.util.SUSPICIOUS_NON_SUSPICIOUS_URL
import timber.log.Timber
import java.io.Serializable
import java.util.LinkedList
import javax.inject.Inject

@AndroidEntryPoint
class QuestionnaireActivity : BaseMultiLanguageActivity() {

  @Inject lateinit var dispatcherProvider: DispatcherProvider
  val viewModel by viewModels<QuestionnaireViewModel>()
  private lateinit var questionnaireConfig: QuestionnaireConfig
  private lateinit var actionParameters: ArrayList<ActionParameter>
  private lateinit var viewBinding: QuestionnaireActivityBinding
  private var questionnaire: Questionnaire? = null
  private var alertDialog: AlertDialog? = null
  private lateinit var fusedLocationClient: FusedLocationProviderClient
  var currentLocation: Location? = null
  private lateinit var locationPermissionLauncher: ActivityResultLauncher<Array<String>>
  private lateinit var activityResultLauncher: ActivityResultLauncher<Intent>
  private var questionnaireResponse: String? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    setTheme(org.smartregister.fhircore.engine.R.style.AppTheme_Questionnaire)
    viewBinding = QuestionnaireActivityBinding.inflate(layoutInflater)
    setContentView(viewBinding.root)

    with(intent) {
      parcelable<QuestionnaireConfig>(QUESTIONNAIRE_CONFIG)?.also { questionnaireConfig = it }
      actionParameters = parcelableArrayList(QUESTIONNAIRE_ACTION_PARAMETERS) ?: arrayListOf()
      questionnaireResponse = getStringExtra(QUESTIONNAIRE_RESPONSE_PREFILL)
    }

    if (!::questionnaireConfig.isInitialized) {
      showToast(getString(R.string.missing_questionnaire_config))
      finish()
      return
    }

    viewModel.questionnaireProgressStateLiveData.observe(this) { progressState ->
      alertDialog =
        if (progressState?.active == false) {
          alertDialog?.dismiss()
          null
        } else {
          when (progressState) {
            is QuestionnaireProgressState.ExtractionInProgress ->
              AlertDialogue.showProgressAlert(this, R.string.extraction_in_progress)
            is QuestionnaireProgressState.QuestionnaireLaunch ->
              AlertDialogue.showProgressAlert(this, R.string.loading_questionnaire)
            else -> null
          }
        }
    }

    if (savedInstanceState == null) renderQuestionnaire()

    setupLocationServices()

    this.onBackPressedDispatcher.addCallback(
      this,
      object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
          handleBackPress()
        }
      },
    )
  }

  fun setupLocationServices() {
    if (
      viewModel.applicationConfiguration.logGpsLocation.contains(LocationLogOptions.QUESTIONNAIRE)
    ) {
      fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

      if (!LocationUtils.isLocationEnabled(this)) {
        openLocationServicesSettings()
      }

      if (!hasLocationPermissions()) {
        showToast(getString(R.string.location_permission_reason))
        launchLocationPermissionsDialog()
      }

      if (LocationUtils.isLocationEnabled(this) && hasLocationPermissions()) {
        fetchLocation(true)
      }
    }
  }

  fun hasLocationPermissions(): Boolean {
    return PermissionUtils.checkPermissions(
      this,
      listOf(
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACCESS_FINE_LOCATION,
      ),
    )
  }

  fun openLocationServicesSettings() {
    activityResultLauncher =
      PermissionUtils.getStartActivityForResultLauncher(this) { resultCode, _ ->
        if (resultCode == RESULT_OK || hasLocationPermissions()) {
          fetchLocation()
        }
      }

    val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
    showLocationSettingsDialog(intent)
  }

  private fun showLocationSettingsDialog(intent: Intent) {
    viewModel.setProgressState(QuestionnaireProgressState.QuestionnaireLaunch(false))
    AlertDialog.Builder(this)
      .setMessage(getString(R.string.location_services_disabled))
      .setCancelable(true)
      .setPositiveButton(getString(R.string.yes)) { _, _ -> activityResultLauncher.launch(intent) }
      .setNegativeButton(getString(R.string.no)) { dialog, _ -> dialog.cancel() }
      .show()
  }

  fun launchLocationPermissionsDialog() {
    locationPermissionLauncher =
      PermissionUtils.getLocationPermissionLauncher(
        this,
        onFineLocationPermissionGranted = { fetchLocation(true) },
        onCoarseLocationPermissionGranted = { fetchLocation(false) },
        onLocationPermissionDenied = {
          Toast.makeText(
              this,
              getString(R.string.location_permissions_denied),
              Toast.LENGTH_SHORT,
            )
            .show()
          Timber.e("Location permissions denied")
        },
      )

    locationPermissionLauncher.launch(
      arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
      ),
    )
  }

  fun fetchLocation(highAccuracy: Boolean = true) {
    lifecycleScope.launch {
      try {
        if (highAccuracy) {
          currentLocation =
            LocationUtils.getAccurateLocation(fusedLocationClient, dispatcherProvider.io())
        } else {
          currentLocation =
            LocationUtils.getApproximateLocation(fusedLocationClient, dispatcherProvider.io())
        }
      } catch (e: Exception) {
        Timber.e(e, "Failed to get GPS location for questionnaire: ${questionnaireConfig.id}")
      } finally {
        if (currentLocation == null) {
          this@QuestionnaireActivity.showToast("Failed to get GPS location", Toast.LENGTH_LONG)
        }
      }
    }
  }

  override fun onSaveInstanceState(outState: Bundle) {
    super.onSaveInstanceState(outState)
    outState.clear()
  }

  private fun renderQuestionnaire() {
    lifecycleScope.launch {
      if (supportFragmentManager.findFragmentByTag(QUESTIONNAIRE_FRAGMENT_TAG) == null) {
        viewModel.setProgressState(QuestionnaireProgressState.QuestionnaireLaunch(true))
        with(viewBinding) {
          questionnaireToolbar.apply {
            setNavigationIcon(R.drawable.ic_arrow_back)
            setNavigationOnClickListener { handleBackPress() }
          }
          questionnaireTitle.apply { text = getString(R.string.add_case) }
          clearAll.apply {
            visibility = if (questionnaireConfig.showClearAll) View.VISIBLE else View.GONE
            setOnClickListener {
              // TODO Clear current QuestionnaireResponse items -> SDK
            }
          }
        }


        questionnaire = viewModel.retrieveQuestionnaire(questionnaireConfig, actionParameters,sharedPreferencesHelper.getLanguageCode())

        try {
          val questionnaireFragmentBuilder = buildQuestionnaireFragment(questionnaire!!)
          supportFragmentManager.commit {
            setReorderingAllowed(true)
            add(R.id.container, questionnaireFragmentBuilder.build(), QUESTIONNAIRE_FRAGMENT_TAG)
          }

          registerFragmentResultListener()
        } catch (nullPointerException: NullPointerException) {
          Timber.e(nullPointerException, "questionnaire_not_found")
          showToast(getString(R.string.questionnaire_not_found))
          finish()
        } finally {
          viewModel.setProgressState(QuestionnaireProgressState.QuestionnaireLaunch(false))
        }
      }
    }
  }

  private suspend fun buildQuestionnaireFragment(
    questionnaire: Questionnaire,
  ): QuestionnaireFragment.Builder {
    if (questionnaire.subjectType.isNullOrEmpty()) {
      showToast(getString(R.string.missing_subject_type))
      Timber.e(
        "Missing subject type on questionnaire. Provide Questionnaire.subjectType to resolve.",
      )
      finish()
    }
    val questionnaireFragmentBuilder =
       QuestionnaireFragment.builder()
        .setQuestionnaire(questionnaire.json())
        .showReviewPageBeforeSubmit(true)
        .setCustomQuestionnaireItemViewHolderFactoryMatchersProvider(
          OPENSRP_ITEM_VIEWHOLDER_FACTORY_MATCHERS_PROVIDER,
        )
        .showAsterisk(questionnaireConfig.showRequiredTextAsterisk)
        .showRequiredText(questionnaireConfig.showRequiredText)

    questionnaireResponse?.let {
      questionnaireFragmentBuilder.setQuestionnaireResponse(questionnaireResponse ?: "")
    }

    /*if (!questionnaireResponse.isNullOrBlank()){
      questionnaireResponse?.let {
        questionnaireFragmentBuilder.setQuestionnaireResponse(questionnaireResponse ?: "1\t31\tQuestionnaireResponse\te5e6661c-f70b-4228-b1ef-7feaf75e0f54\tA0C520CFA006413994DEC9EE9629FAEA\t1716792701797\t1\t{\"resourceType\":\"QuestionnaireResponse\",\"id\":\"e5e6661c-f70b-4228-b1ef-7feaf75e0f54\",\"meta\":{\"lastUpdated\":\"2024-05-27T12:21:41.796+05:30\",\"tag\":[{\"system\":\"https://smartregister.org/care-team-tag-id\",\"code\":\"Not defined\",\"display\":\"Practitioner CareTeam\"},{\"system\":\"https://smartregister.org/location-tag-id\",\"code\":\"2d4fd0b8-44fe-4eb8-ae62-e2c6feba8e8a\",\"display\":\"Practitioner Location\"},{\"system\":\"https://smartregister.org/organisation-tag-id\",\"code\":\"eb456411-0f25-57b4-92d1-bc50ea54364b\",\"display\":\"Practitioner Organization\"},{\"system\":\"https://smartregister.org/practitioner-tag-id\",\"code\":\"b24966ce-f60b-4bd4-b2be-726869228373\",\"display\":\"Practitioner\"},{\"system\":\"https://smartregister.org/app-version\",\"code\":\"1.1.0\",\"display\":\"Application Version\"}]},\"status\":\"in-progress\",\"item\":[{\"linkId\":\"basic-info-group\",\"text\":\"Basic Info\",\"item\":[{\"linkId\":\"patient-name-given\",\"text\":\"First Name\",\"answer\":[{\"valueString\":\"draftoko\"}]},{\"linkId\":\"patient-name-family\",\"text\":\"Last Name\"},{\"linkId\":\"patient-identifier-abha\",\"text\":\"ABHA ID(Optional)\"},{\"linkId\":\"patient-age\",\"text\":\"Age\",\"answer\":[{\"valueCoding\":{\"code\":\"dob\",\"display\":\"By Date of Birth\"}}]},{\"linkId\":\"patient-age-by-dob\",\"text\":\"By Date of Birth\"},{\"linkId\":\"patient-gender\",\"text\":\"Gender\"},{\"linkId\":\"patient-contact-primary\",\"text\":\"Primary Contact\"},{\"linkId\":\"patient-contact-secondary\",\"text\":\"Secondary Contact Number\"},{\"linkId\":\"patient-address-house\",\"text\":\"House Number\"},{\"linkId\":\"patient-address-village\",\"text\":\"Village\"},{\"linkId\":\"patient-address-pincode\",\"text\":\"Pincode\"},{\"linkId\":\"patient-address-district\",\"text\":\"District\"},{\"linkId\":\"patient-address-state\",\"text\":\"State\"}]},{\"linkId\":\"habit-history-group\",\"text\":\"Habit History\",\"item\":[{\"linkId\":\"patient-habit-cigarette\",\"text\":\"Cigarette/Bidi\"},{\"linkId\":\"patient-habit-tobacco\",\"text\":\"Tobacco\"},{\"linkId\":\"patient-habit-areca\",\"text\":\"Areca Nut\"},{\"linkId\":\"patient-habit-alcohol\",\"text\":\"Alcohol\"}]},{\"linkId\":\"screening-group\",\"text\":\"Screening\",\"item\":[{\"linkId\":\"patient-screening-question-group\",\"text\":\"Current Condition\",\"item\":[{\"linkId\":\"patient-screening-mouth-open\",\"text\":\"Open Mouth\"},{\"linkId\":\"patient-screening-lesion\",\"text\":\"Lesion/Patch\"}]},{\"linkId\":\"patient-screening-image-group\",\"text\":\"Image Screening\",\"item\":[{\"linkId\":\"patient-screening-image-1\",\"text\":\"Image 1\"},{\"linkId\":\"patient-screening-image-2\",\"text\":\"Image 2\"},{\"linkId\":\"patient-screening-image-3\",\"text\":\"Image 3\"},{\"linkId\":\"patient-screening-image-4\",\"text\":\"Image 4\"},{\"linkId\":\"patient-screening-image-5\",\"text\":\"Image 5\"},{\"linkId\":\"patient-screening-image-6\",\"text\":\"Image 6\"},{\"linkId\":\"patient-screening-image-7\",\"text\":\"Image 7\"},{\"linkId\":\"patient-screening-image-8\",\"text\":\"Image 8\"},{\"linkId\":\"patient-screening-image-9\",\"text\":\"Image 9\"},{\"linkId\":\"patient-screening-image-10\",\"text\":\"Image 10\"},{\"linkId\":\"patient-screening-image-11\",\"text\":\"Image 11\"},{\"linkId\":\"patient-screening-image-12\",\"text\":\"Image 12\"},{\"linkId\":\"patient-screening-image-13\",\"text\":\"Image 13\"}]}]}]}\t")
      }
    }*/

    val questionnaireSubjectType = questionnaire.subjectType.firstOrNull()?.code
    val resourceType =
      questionnaireConfig.resourceType ?: questionnaireSubjectType?.let { ResourceType.valueOf(it) }
    val resourceIdentifier = questionnaireConfig.resourceIdentifier

    if (resourceType != null && !resourceIdentifier.isNullOrEmpty()) {
      // Add subject and other configured resource to launchContext
      val launchContextResources =
        LinkedList<Resource>().apply {
          viewModel.loadResource(resourceType, resourceIdentifier)?.let { add(it) }
          addAll(
            // Exclude the subject resource its already added
            viewModel.retrievePopulationResources(
              actionParameters.filterNot {
                it.paramType == ActionParameterType.QUESTIONNAIRE_RESPONSE_POPULATION_RESOURCE &&
                  resourceType == it.resourceType &&
                  resourceIdentifier.equals(it.value, ignoreCase = true)
              },
            ),
          )
        }

      if (launchContextResources.isNotEmpty()) {
        questionnaireFragmentBuilder.setQuestionnaireLaunchContextMap(
          launchContextResources.associate {
            Pair(it.resourceType.name.lowercase(), it.encodeResourceToString())
          },
        )
      }

      // Populate questionnaire with latest QuestionnaireResponse
      if (questionnaireConfig.isEditable()) {
        val latestQuestionnaireResponse =
          viewModel.searchLatestQuestionnaireResponse(
            resourceId = resourceIdentifier,
            resourceType = resourceType,
            questionnaireId = questionnaire.logicalId,
          )

        val questionnaireResponse =
          QuestionnaireResponse().apply {
            item = latestQuestionnaireResponse?.item
            // Clearing the text prompts the SDK to re-process the content, which includes HTML
            clearText()
          }

        if (viewModel.validateQuestionnaireResponse(questionnaire, questionnaireResponse, this)) {
          questionnaireFragmentBuilder.setQuestionnaireResponse(questionnaireResponse.json())
        } else {
          showToast(getString(R.string.error_populating_questionnaire))
        }
      }
    }
    return questionnaireFragmentBuilder
  }

  private fun Resource.json(): String = this.encodeResourceToString()

  private fun registerFragmentResultListener() {
    supportFragmentManager.setFragmentResultListener(
      QuestionnaireFragment.SUBMIT_REQUEST_KEY,
      this,
    ) { _, _ ->
      lifecycleScope.launch {
        val questionnaireResponse = retrieveQuestionnaireResponse()
        questionnaireResponse?.language = viewModel.languageCode
        // Close questionnaire if opened in read only mode or if experimental
        if (questionnaireConfig.isReadOnly() || questionnaire?.experimental == true) {
          finish()
        }
        if (questionnaireResponse != null && questionnaire != null) {
          viewModel.run {
            setProgressState(QuestionnaireProgressState.ExtractionInProgress(true))

            if (currentLocation != null) {
              questionnaireResponse.contained.add(
                ResourceUtils.createFhirLocationFromGpsLocation(gpsLocation = currentLocation!!),
              )
            }
            val ref = Reference().apply { reference = "Practitioner/${viewModel.getUserName()}" }
            // set author
            questionnaireResponse.author = ref

            Timber.d("=== Starting DocumentReference update processing ===")
            for (item in questionnaireResponse.item) {
              if (item.linkId == "screening-group"){
                Timber.d("Found screening-group for DocumentReference updates")
                item.item.forEach{ group ->
                  if (group.linkId == "patient-screening-image-group"){
                    Timber.d("Found patient-screening-image-group for DocumentReference updates")
                    group.item.forEach{ image ->
                      // Skip AI result items in this loop too
                      if (image.linkId.endsWith("-ai-result")) {
                        Timber.d("Skipping AI result item in DocumentReference loop: ${image.linkId}")
                        return@forEach
                      }

                      Timber.d("Processing DocumentReference for image: ${image.linkId}")
                      image.answer.forEach{
                        it.valueAttachment?.let { attachment ->
                          val documentReferenceId = extractDocumentReferenceIdFromUrl(attachment.url)
                          if (documentReferenceId != null) {
                            try {
                              val fetchedDocumentReference = fhirEngine.get(ResourceType.DocumentReference, documentReferenceId) as DocumentReference
                              if(fetchedDocumentReference.description == DocumentReferenceCaseType.DRAFT.name){
                                fetchedDocumentReference.description = DocumentReferenceCaseType.SUBMITTED.name
                                fhirEngine.update(fetchedDocumentReference)
                                Timber.i("DocumentReference $documentReferenceId description updated to SUBMITTED")
                              }
                            } catch (e: Exception) {
                              Timber.e(e, "Error updating DocumentReference status for ID: $documentReferenceId")
                            }
                          } else {
                            Timber.w("Could not extract DocumentReference ID from URL: ${attachment.url}")
                          }
                        }
                      }
                    }
                  }
                }
              }
            }
            Timber.d("=== Finished DocumentReference update processing ===")

            var isSuspicious = false
            try {
              Timber.d("=== Starting to set AI results in hidden items ===")
              for (item in questionnaireResponse.item) {
                if (item.linkId == "screening-group") {
                  Timber.d("Found screening-group for AI result processing")
                  item.item.forEach { group ->
                    if (group.linkId == "patient-screening-image-group") {
                      Timber.d("Found patient-screening-image-group, total items: ${group.item.size}")
                      group.item.forEach { imageOrHiddenItem ->
                        // Skip items that are AI result items (ending with -ai-result)
                        if (imageOrHiddenItem.linkId.endsWith("-ai-result")) {
                          Timber.d("Skipping AI result item: ${imageOrHiddenItem.linkId}")
                          return@forEach
                        }

                        try {
                          Timber.d("Processing item: ${imageOrHiddenItem.linkId}, has answers: ${imageOrHiddenItem.answer.isNotEmpty()}")
                          if (imageOrHiddenItem.answer.isNotEmpty()) {
                            val firstAnswer = imageOrHiddenItem.answer.firstOrNull()
                            Timber.d("First answer exists: ${firstAnswer != null}, has extensions: ${firstAnswer?.hasExtension()}, extension count: ${firstAnswer?.extension?.size}")

                            if (firstAnswer != null && firstAnswer.hasExtension() && firstAnswer.extension.size > 1) {
                              val resultExtension = firstAnswer.getExtensionByUrl(SUSPICIOUS_NON_SUSPICIOUS_URL)
                              val confidenceExtension = firstAnswer.getExtensionByUrl(CONFIDENCE_PERCENTAGE_URL)

                              Timber.d("Result extension: ${resultExtension != null}, Confidence extension: ${confidenceExtension != null}")

                              if (resultExtension != null && confidenceExtension != null) {
                                val result = resultExtension.value
                                val confidence = confidenceExtension.value

                                Timber.d("Result value: $result, Confidence value: $confidence")

                                if (result != null && confidence != null) {
                                  val hiddenLinkId = "${imageOrHiddenItem.linkId}-ai-result"
                                  val hiddenItem = group.item.find { it.linkId == hiddenLinkId }

                                  Timber.d("Looking for hidden item: $hiddenLinkId, found: ${hiddenItem != null}")

                                  if (hiddenItem != null) {
                                    Timber.d("BEFORE: Hidden item $hiddenLinkId has ${hiddenItem.answer.size} answers")
 
                                    // Set or update the answer
                                    if (hiddenItem.answer.isEmpty()) {
                                      Timber.d("Adding new answer to hidden item")
                                      hiddenItem.addAnswer()
                                      if(isSuspicious.not()){
                                        isSuspicious = result.toString().equals("suspicious", ignoreCase = true)
                                      }
                                      firstAnswer.extension.remove(firstAnswer.getExtensionByUrl(SUSPICIOUS_NON_SUSPICIOUS_URL))
                                      firstAnswer.extension.remove(firstAnswer.getExtensionByUrl(CONFIDENCE_PERCENTAGE_URL))
                                    }

                                    Timber.d("Setting value on hidden item answer")
                                    hiddenItem.answer[0].value = StringType("$result|$confidence")

                                    Timber.d("AFTER: Successfully set AI result for $hiddenLinkId: $result|$confidence")
                                  } else {
                                    Timber.w("Hidden item not found for linkId: $hiddenLinkId")
                                  }
                                }
                              }
                            }
                          }
                        } catch (e: Exception) {
                          Timber.e(e, "!!! EXCEPTION processing image item: ${imageOrHiddenItem.linkId}")
                        }
                      }
                    }
                  }
                }
              }
              Timber.d("=== Finished setting AI results in hidden items ===")
            } catch (e: Exception) {
              Timber.e(e, "!!! EXCEPTION in outer try-catch for AI result processing")
            }

            Timber.d("=== About to call handleQuestionnaireSubmission ===")

            handleQuestionnaireSubmission(
              questionnaire = questionnaire!!,
              currentQuestionnaireResponse = questionnaireResponse,
              questionnaireConfig = questionnaireConfig,
              actionParameters = actionParameters,
              context = this@QuestionnaireActivity,
            ) { idTypes, questionnaireResponse ->
              Timber.d("=== Inside handleQuestionnaireSubmission callback ===")
              // Dismiss progress indicator dialog, submit result then finish activity
              // TODO Ensure this dialog is dismissed even when an exception is encountered
              setProgressState(QuestionnaireProgressState.ExtractionInProgress(false))
              setResult(
                Activity.RESULT_OK,
                Intent().apply {
                  putExtra(QUESTIONNAIRE_RESPONSE, questionnaireResponse as Serializable)
                  putExtra(QUESTIONNAIRE_SUBMISSION_EXTRACTED_RESOURCE_IDS, idTypes as Serializable)
                  putExtra(QUESTIONNAIRE_CONFIG, questionnaireConfig as Parcelable)
                },
              )
              Timber.d("=== About to call finish() ===")
              //Goto AIResultActivity with a flag isSuspicious
              val intent = Intent(this@QuestionnaireActivity, AIResultActivity::class.java)
              intent.putExtra("isSuspicious", isSuspicious)
              startActivity(intent)
              finish()
              Timber.d("=== Called finish() ===")
            }
          }
        }
      }
    }
  }

  private fun findQuestionnaireItemByLinkId(
    questionnaire: Questionnaire,
    linkId: String
  ): Questionnaire.QuestionnaireItemComponent? {
    fun searchItems(items: List<Questionnaire.QuestionnaireItemComponent>): Questionnaire.QuestionnaireItemComponent? {
      for (item in items) {
        if (item.linkId == linkId) return item
        val found = searchItems(item.item)
        if (found != null) return found
      }
      return null
    }
    return searchItems(questionnaire.item)
  }

  private fun extractDocumentReferenceIdFromUrl(url: String?): String? {
    if (url == null) return null
    // Example URL:  http://your-fhir-server/DocumentReference/123/$binary-access-read...
    val regex = Regex("DocumentReference/([^/]+)/")  // Much more robust regex.
    val matchResult = regex.find(url)
    return matchResult?.groupValues?.get(1) // The ID is the first captured group.
  }

  private fun handleBackPress() {
    if (questionnaireConfig.isReadOnly()) {
      finish()
    } else if (questionnaireConfig.saveDraft) {
      val dialogue = AlertDialogue.showProgressAlert(this, R.string.extraction_in_progress)

      lifecycleScope.launch {
        retrieveQuestionnaireResponse()?.let { questionnaireResponse ->
          viewModel.isDraftSaved.observeForever {
            if (it){
              dialogue.dismiss()
              finish()
            }
          }
          viewModel.saveDraftQuestionnaire(questionnaireResponse)
        }
      }
    } else {
      AlertDialogue.showConfirmAlert(
        context = this,
        message =
          org.smartregister.fhircore.engine.R.string.questionnaire_alert_back_pressed_message,
        title = org.smartregister.fhircore.engine.R.string.questionnaire_alert_back_pressed_title,
        confirmButtonListener = { finish() },
        confirmButtonText =
          org.smartregister.fhircore.engine.R.string.questionnaire_alert_back_pressed_button_title,
      )
    }
  }

  private suspend fun retrieveQuestionnaireResponse(): QuestionnaireResponse? =
    (supportFragmentManager.findFragmentByTag(QUESTIONNAIRE_FRAGMENT_TAG) as QuestionnaireFragment?)
      ?.getQuestionnaireResponse()

  companion object {

    const val QUESTIONNAIRE_FRAGMENT_TAG = "questionnaireFragment"
    const val QUESTIONNAIRE_CONFIG = "questionnaireConfig"
    const val QUESTIONNAIRE_SUBMISSION_EXTRACTED_RESOURCE_IDS = "questionnaireExtractedResourceIds"
    const val QUESTIONNAIRE_RESPONSE = "questionnaireResponse"
    const val QUESTIONNAIRE_ACTION_PARAMETERS = "questionnaireActionParameters"
    const val QUESTIONNAIRE_RESPONSE_PREFILL = "questionnaireResponsePrefill"
    const val QUESTIONNAIRE_POPULATION_RESOURCES = "questionnairePopulationResources"

    fun intentBundle(
      questionnaireConfig: QuestionnaireConfig,
      actionParams: List<ActionParameter>,
    ): Bundle =
      bundleOf(
        Pair(QUESTIONNAIRE_CONFIG, questionnaireConfig),
        Pair(QUESTIONNAIRE_ACTION_PARAMETERS, actionParams),
      )
  }
}
