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

package org.smartregister.fhircore.quest.ui.register

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.filter
import com.google.android.fhir.FhirEngine
import com.google.android.fhir.search.search
import com.google.gson.Gson
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import javax.inject.Inject
import kotlin.math.ceil
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.hl7.fhir.r4.model.Coding
import org.hl7.fhir.r4.model.Enumerations.DataType
import org.hl7.fhir.r4.model.Meta
import org.hl7.fhir.r4.model.Patient
import org.hl7.fhir.r4.model.QuestionnaireResponse
import org.hl7.fhir.r4.model.ResourceType
import org.smartregister.fhircore.engine.configuration.ConfigType
import org.smartregister.fhircore.engine.configuration.ConfigurationRegistry
import org.smartregister.fhircore.engine.configuration.register.RegisterConfiguration
import org.smartregister.fhircore.engine.configuration.register.RegisterFilterField
import org.smartregister.fhircore.engine.data.local.register.RegisterRepository
import org.smartregister.fhircore.engine.domain.model.ActionParameter
import org.smartregister.fhircore.engine.domain.model.Code
import org.smartregister.fhircore.engine.domain.model.DataQuery
import org.smartregister.fhircore.engine.domain.model.FhirResourceConfig
import org.smartregister.fhircore.engine.domain.model.FilterCriterionConfig
import org.smartregister.fhircore.engine.domain.model.ResourceConfig
import org.smartregister.fhircore.engine.domain.model.ResourceData
import org.smartregister.fhircore.engine.domain.model.SnackBarMessageConfig
import org.smartregister.fhircore.engine.rulesengine.ResourceDataRulesExecutor
import org.smartregister.fhircore.engine.util.DispatcherProvider
import org.smartregister.fhircore.engine.util.SharedPreferenceKey
import org.smartregister.fhircore.engine.util.SharedPreferencesHelper
import org.smartregister.fhircore.engine.util.extension.encodeJson
import org.smartregister.fhircore.quest.data.register.RegisterPagingSource
import org.smartregister.fhircore.quest.data.register.model.RegisterPagingSourceState
import org.smartregister.fhircore.quest.util.extensions.toParamDataMap
import timber.log.Timber
import java.time.LocalDate
import java.util.Date
import java.util.UUID

@HiltViewModel
class RegisterViewModel
@Inject
constructor(
  val registerRepository: RegisterRepository,
  val configurationRegistry: ConfigurationRegistry,
  val sharedPreferencesHelper: SharedPreferencesHelper,
  val dispatcherProvider: DispatcherProvider,
  val resourceDataRulesExecutor: ResourceDataRulesExecutor,
  val fhirEngine: FhirEngine,
) : ViewModel() {

  private val _snackBarStateFlow = MutableSharedFlow<SnackBarMessageConfig>()
  val snackBarStateFlow = _snackBarStateFlow.asSharedFlow()
  val registerUiState = mutableStateOf(RegisterUiState())
  val currentPage: MutableState<Int> = mutableIntStateOf(0)
  val searchText = mutableStateOf("")
  val paginatedRegisterData: MutableStateFlow<Flow<PagingData<ResourceData>>> =
    MutableStateFlow(emptyFlow())
  val pagesDataCache = mutableMapOf<Int, Flow<PagingData<ResourceData>>>()
  val registerFilterState = mutableStateOf(RegisterFilterState())
  private val _totalRecordsCount = mutableLongStateOf(0L)
  private val _filteredRecordsCount = mutableLongStateOf(-1L)
  private lateinit var registerConfiguration: RegisterConfiguration
  private var allPatientRegisterData: Flow<PagingData<ResourceData>>? = null
  private val _percentageProgress: MutableSharedFlow<Int> = MutableSharedFlow(0)
  private val _isUploadSync: MutableSharedFlow<Boolean> = MutableSharedFlow(0)


  private val _patientsStateFlow = MutableStateFlow<List<AllPatientsResourceData>>(emptyList())
  val patientsStateFlow: StateFlow<List<AllPatientsResourceData>> = _patientsStateFlow

  private val _isFetching = MutableStateFlow<Boolean>(false)
  val isFetching: StateFlow<Boolean> = _isFetching


  private val _savedDraftResponseStateFlow = MutableStateFlow<List<QuestionnaireResponse>>(emptyList())
  val savedDraftResponse: StateFlow<List<QuestionnaireResponse>> = _savedDraftResponseStateFlow

  private val _unSyncedStateFlow = MutableStateFlow<List<Patient2>>(emptyList())
  val unSyncedStateFlow: StateFlow<List<Patient2>> = _unSyncedStateFlow

  /**
   * This function paginates the register data. An optional [clearCache] resets the data in the
   * cache (this is necessary after a questionnaire has been submitted to refresh the register with
   * new/updated data).
   */
  fun paginateRegisterData(
    registerId: String,
    loadAll: Boolean = false,
    clearCache: Boolean = false,
  ) {
    if (clearCache) {
      pagesDataCache.clear()
      allPatientRegisterData = null
    }
    paginatedRegisterData.value =
      pagesDataCache.getOrPut(currentPage.value) {
        getPager(registerId, loadAll).flow.cachedIn(viewModelScope)
      }
  }

  private fun getPager(registerId: String, loadAll: Boolean = false): Pager<Int, ResourceData> {
    val currentRegisterConfigs = retrieveRegisterConfiguration(registerId)
    val ruleConfigs = currentRegisterConfigs.registerCard.rules
    val pageSize = currentRegisterConfigs.pageSize // Default 10

    return Pager(
      config = PagingConfig(pageSize = pageSize, enablePlaceholders = false),
      pagingSourceFactory = {
        RegisterPagingSource(
            registerRepository = registerRepository,
            resourceDataRulesExecutor = resourceDataRulesExecutor,
            ruleConfigs = ruleConfigs,
            fhirResourceConfig = registerFilterState.value.fhirResourceConfig,
            actionParameters = registerUiState.value.params,
          )
          .apply {
            setPatientPagingSourceState(
              RegisterPagingSourceState(
                registerId = registerId,
                loadAll = loadAll,
                currentPage = if (loadAll) 0 else currentPage.value,
              ),
            )
          }
      },
    )
  }

  fun retrieveRegisterConfiguration(
    registerId: String,
    paramMap: Map<String, String>? = emptyMap(),
  ): RegisterConfiguration {
    // Ensures register configuration is initialized once
    if (!::registerConfiguration.isInitialized) {
      registerConfiguration =
        configurationRegistry.retrieveConfiguration(ConfigType.Register, registerId, paramMap)
    }
    return registerConfiguration
  }

  private fun retrieveAllPatientRegisterData(registerId: String): Flow<PagingData<ResourceData>> {
    // Ensure that we only initialize this flow once
    if (allPatientRegisterData == null) {
      allPatientRegisterData = getPager(registerId, true).flow.cachedIn(viewModelScope)
    }
    return allPatientRegisterData!!
  }

  fun onEvent(event: RegisterEvent) =
    when (event) {
      // Search using name or patient logicalId or identifier. Modify to add more search params
      is RegisterEvent.SearchRegister -> {
        searchText.value = event.searchText
        if (event.searchText.isEmpty()) {
          paginateRegisterData(registerUiState.value.registerId)
        } else {
          filterRegisterData(event)
        }
      }
      is RegisterEvent.MoveToNextPage -> {
        currentPage.value = currentPage.value.plus(1)
        paginateRegisterData(registerUiState.value.registerId)
      }
      is RegisterEvent.MoveToPreviousPage -> {
        currentPage.value.let { if (it > 0) currentPage.value = it.minus(1) }
        paginateRegisterData(registerUiState.value.registerId)
      }
      RegisterEvent.ResetFilterRecordsCount -> _filteredRecordsCount.longValue = -1
    }

  fun filterRegisterData(event: RegisterEvent.SearchRegister) {
    val searchBar = registerUiState.value.registerConfiguration?.searchBar
    // computedRules (names of pre-computed rules) must be provided for search to work.
    if (searchBar?.computedRules != null) {
      paginatedRegisterData.value =
        retrieveAllPatientRegisterData(registerUiState.value.registerId).map {
          pagingData: PagingData<ResourceData> ->
          pagingData.filter { resourceData: ResourceData ->
            searchBar.computedRules!!.any { ruleName ->
              // if ruleName not found in map return {-1}; check always return false hence no data
              val value = resourceData.computedValuesMap[ruleName]?.toString() ?: "{-1}"
              value.contains(other = event.searchText, ignoreCase = true)
            }
          }
        }
    }
  }

  fun updateRegisterFilterState(registerId: String, questionnaireResponse: QuestionnaireResponse) {
    // Reset filter state if no answer is provided for all the fields
    if (questionnaireResponse.item.all { !it.hasAnswer() }) {
      registerFilterState.value =
        RegisterFilterState(
          questionnaireResponse = null,
          fhirResourceConfig = null,
        )
      return
    }

    val registerConfiguration = retrieveRegisterConfiguration(registerId)
    val resourceConfig = registerConfiguration.fhirResource
    val baseResource = resourceConfig.baseResource
    val qrItemMap = questionnaireResponse.item.groupBy { it.linkId }.mapValues { it.value.first() }

    val registerDataFilterFieldsMap =
      registerConfiguration.registerFilter
        ?.dataFilterFields
        ?.groupBy { it.filterId }
        ?.mapValues { it.value.first() }

    // Get filter queries from the map. NOTE: filterId MUST be unique for all resources
    val baseResourceRegisterFilterField = registerDataFilterFieldsMap?.get(baseResource.filterId)
    val newBaseResourceDataQueries =
      createQueriesForRegisterFilter(
        dataQueries = baseResourceRegisterFilterField?.dataQueries,
        qrItemMap = qrItemMap,
      )

    val newRelatedResources =
      createFilterRelatedResources(
        registerDataFilterFieldsMap = registerDataFilterFieldsMap,
        relatedResources = resourceConfig.relatedResources,
        qrItemMap = qrItemMap,
      )

    val fhirResourceConfig =
      FhirResourceConfig(
        baseResource =
          baseResource.copy(
            dataQueries = newBaseResourceDataQueries ?: baseResource.dataQueries,
            nestedSearchResources =
              baseResourceRegisterFilterField?.nestedSearchResources?.map { nestedSearchConfig ->
                nestedSearchConfig.copy(
                  dataQueries =
                    createQueriesForRegisterFilter(
                      dataQueries = nestedSearchConfig.dataQueries,
                      qrItemMap = qrItemMap,
                    ),
                )
              } ?: baseResource.nestedSearchResources,
          ),
        relatedResources = newRelatedResources,
      )
    registerFilterState.value =
      RegisterFilterState(
        questionnaireResponse = questionnaireResponse,
        fhirResourceConfig = fhirResourceConfig,
      )
    Timber.i("New ResourceConfig for register data filter: ${fhirResourceConfig.encodeJson()}")
  }

  private fun createFilterRelatedResources(
    registerDataFilterFieldsMap: Map<String, RegisterFilterField>?,
    relatedResources: List<ResourceConfig>,
    qrItemMap: Map<String, QuestionnaireResponse.QuestionnaireResponseItemComponent>,
  ): List<ResourceConfig> {
    val newRelatedResources =
      relatedResources.map { resourceConfig: ResourceConfig ->
        val registerFilterField = registerDataFilterFieldsMap?.get(resourceConfig.filterId)
        val newDataQueries =
          createQueriesForRegisterFilter(
            dataQueries = registerFilterField?.dataQueries,
            qrItemMap = qrItemMap,
          )
        resourceConfig.copy(
          dataQueries = newDataQueries ?: resourceConfig.dataQueries,
          relatedResources =
            createFilterRelatedResources(
              registerDataFilterFieldsMap = registerDataFilterFieldsMap,
              relatedResources = resourceConfig.relatedResources,
              qrItemMap = qrItemMap,
            ),
          nestedSearchResources =
            registerFilterField?.nestedSearchResources?.map { nestedSearchConfig ->
              nestedSearchConfig.copy(
                dataQueries =
                  createQueriesForRegisterFilter(
                    dataQueries = nestedSearchConfig.dataQueries,
                    qrItemMap = qrItemMap,
                  ),
              )
            } ?: resourceConfig.nestedSearchResources,
        )
      }
    return newRelatedResources
  }

  private fun createQueriesForRegisterFilter(
    dataQueries: List<DataQuery>?,
    qrItemMap: Map<String, QuestionnaireResponse.QuestionnaireResponseItemComponent>,
  ) =
    dataQueries?.map {
      val newFilterCriteria = mutableListOf<FilterCriterionConfig>()
      it.filterCriteria.forEach { filterCriterionConfig ->
        val answerComponent = qrItemMap[filterCriterionConfig.dataFilterLinkId]
        answerComponent?.answer?.forEach { itemAnswerComponent ->
          val criterion = convertAnswerToFilterCriterion(itemAnswerComponent, filterCriterionConfig)
          if (criterion != null) newFilterCriteria.add(criterion)
        }
      }
      it.copy(
        filterCriteria = if (newFilterCriteria.isEmpty()) it.filterCriteria else newFilterCriteria,
      )
    }

  private fun convertAnswerToFilterCriterion(
    answerComponent: QuestionnaireResponse.QuestionnaireResponseItemAnswerComponent,
    oldFilterCriterion: FilterCriterionConfig,
  ): FilterCriterionConfig? =
    when {
      answerComponent.hasValueCoding() -> {
        val valueCoding: Coding = answerComponent.valueCoding
        FilterCriterionConfig.TokenFilterCriterionConfig(
          dataType = DataType.CODE,
          computedRule = oldFilterCriterion.computedRule,
          value = Code(valueCoding.system, valueCoding.code, valueCoding.display),
        )
      }
      answerComponent.hasValueStringType() -> {
        val stringFilterCriterion =
          oldFilterCriterion as FilterCriterionConfig.StringFilterCriterionConfig
        FilterCriterionConfig.StringFilterCriterionConfig(
          dataType = DataType.STRING,
          computedRule = stringFilterCriterion.computedRule,
          modifier = stringFilterCriterion.modifier,
          value = answerComponent.valueStringType.value,
        )
      }
      answerComponent.hasValueQuantity() -> {
        val quantityCriteria =
          oldFilterCriterion as FilterCriterionConfig.QuantityFilterCriterionConfig
        FilterCriterionConfig.QuantityFilterCriterionConfig(
          dataType = DataType.QUANTITY,
          computedRule = quantityCriteria.computedRule,
          prefix = quantityCriteria.prefix,
          system = quantityCriteria.system,
          unit = quantityCriteria.unit,
          value = answerComponent.valueDecimalType.value,
        )
      }
      answerComponent.hasValueIntegerType() -> {
        val numberFilterCriterion =
          oldFilterCriterion as FilterCriterionConfig.NumberFilterCriterionConfig
        FilterCriterionConfig.NumberFilterCriterionConfig(
          dataType = DataType.DECIMAL,
          computedRule = numberFilterCriterion.computedRule,
          prefix = numberFilterCriterion.prefix,
          value = answerComponent.valueIntegerType.value.toBigDecimal(),
        )
      }
      answerComponent.hasValueDecimalType() -> {
        val numberFilterCriterion =
          oldFilterCriterion as FilterCriterionConfig.NumberFilterCriterionConfig
        FilterCriterionConfig.NumberFilterCriterionConfig(
          dataType = DataType.DECIMAL,
          computedRule = numberFilterCriterion.computedRule,
          prefix = numberFilterCriterion.prefix,
          value = answerComponent.valueDecimalType.value,
        )
      }
      answerComponent.hasValueDateTimeType() -> {
        val dateFilterCriterion =
          oldFilterCriterion as FilterCriterionConfig.DateFilterCriterionConfig
        FilterCriterionConfig.DateFilterCriterionConfig(
          dataType = DataType.DATETIME,
          computedRule = dateFilterCriterion.computedRule,
          prefix = dateFilterCriterion.prefix,
          valueAsDateTime = true,
          value = answerComponent.valueDecimalType.asStringValue(),
        )
      }
      answerComponent.hasValueDateType() -> {
        val dateFilterCriterion =
          oldFilterCriterion as FilterCriterionConfig.DateFilterCriterionConfig
        FilterCriterionConfig.DateFilterCriterionConfig(
          dataType = DataType.DATE,
          computedRule = dateFilterCriterion.computedRule,
          prefix = dateFilterCriterion.prefix,
          valueAsDateTime = false,
          value = answerComponent.valueDateType.asStringValue(),
        )
      }
      answerComponent.hasValueUriType() -> {
        val uriCriterion = oldFilterCriterion as FilterCriterionConfig.UriFilterCriterionConfig
        FilterCriterionConfig.UriFilterCriterionConfig(
          dataType = DataType.URI,
          computedRule = uriCriterion.computedRule,
          value = answerComponent.valueUriType.valueAsString,
        )
      }
      answerComponent.hasValueReference() -> {
        val referenceCriterion =
          oldFilterCriterion as FilterCriterionConfig.ReferenceFilterCriterionConfig
        FilterCriterionConfig.ReferenceFilterCriterionConfig(
          dataType = DataType.REFERENCE,
          computedRule = referenceCriterion.computedRule,
          value = answerComponent.valueReference.reference,
        )
      }
      else -> {
        null
      }
    }

  fun retrieveRegisterUiState(
    registerId: String,
    screenTitle: String,
    params: Array<ActionParameter>? = emptyArray(),
    clearCache: Boolean,
  ) {
    if (registerId.isNotEmpty()) {
      val paramsMap: Map<String, String> = params.toParamDataMap()
      viewModelScope.launch(dispatcherProvider.io()) {
        val currentRegisterConfiguration = retrieveRegisterConfiguration(registerId, paramsMap)

        _totalRecordsCount.longValue =
          registerRepository.countRegisterData(registerId = registerId, paramsMap = paramsMap)

        // Only count filtered data when queries are updated
        if (registerFilterState.value.fhirResourceConfig != null) {
          _filteredRecordsCount.longValue =
            registerRepository.countRegisterData(
              registerId = registerId,
              paramsMap = paramsMap,
              fhirResourceConfig = registerFilterState.value.fhirResourceConfig,
            )
        }

        paginateRegisterData(registerId, loadAll = false, clearCache = clearCache)

        registerUiState.value =
          RegisterUiState(
            screenTitle = currentRegisterConfiguration.registerTitle ?: screenTitle,
            isFirstTimeSync =
              sharedPreferencesHelper
                .read(
                  SharedPreferenceKey.LAST_SYNC_TIMESTAMP.name,
                  null,
                )
                .isNullOrEmpty() && _totalRecordsCount.longValue == 0L,
            registerConfiguration = currentRegisterConfiguration,
            registerId = registerId,
            totalRecordsCount = _totalRecordsCount.longValue,
            filteredRecordsCount = _filteredRecordsCount.longValue,
            pagesCount =
              ceil(
                  (if (registerFilterState.value.fhirResourceConfig != null) {
                      _filteredRecordsCount.longValue
                    } else _totalRecordsCount.longValue)
                    .toDouble()
                    .div(currentRegisterConfiguration.pageSize.toLong()),
                )
                .toInt(),
            progressPercentage = _percentageProgress,
            isSyncUpload = _isUploadSync,
            params = paramsMap,
          )
      }
    }
  }

  suspend fun emitSnackBarState(snackBarMessageConfig: SnackBarMessageConfig) {
    _snackBarStateFlow.emit(snackBarMessageConfig)
  }

  suspend fun emitPercentageProgressState(progress: Int, isUploadSync: Boolean) {
    _percentageProgress.emit(progress)
    _isUploadSync.emit(isUploadSync)
  }

  fun getAllPatients() {
    /*viewModelScope.launch {
      val patients = fhirEngine.search<Patient> {
      }.map {
        it.resource
      }.sortedByDescending { it.meta.lastUpdated }
      _patientsStateFlow.value = patients
    }*/

    _isFetching.value = true
    viewModelScope.launch {
      // Fetching patients
      val patients = fhirEngine.search<Patient> {
      }.map {
        it.resource.toResourceData()
      }

      // Fetching drafts
      val drafts = fhirEngine.search<QuestionnaireResponse> {
      }.filter { it.resource.status == QuestionnaireResponse.QuestionnaireResponseStatus.INPROGRESS }
        .map { response ->
          response.resource.toResourceData()
        }

      // Fetching unsynced patients
      val unsyncedPatients = mutableListOf<AllPatientsResourceData>()
      val data = fhirEngine.getUnsyncedLocalChanges()
      data.forEach { localChange ->
        val patient = parsePatientJson(localChange.payload)
        patient?.let {
          patient?.let {
            if (patient.name.isNotEmpty()){
              unsyncedPatients.add(it.toResourceData())
            }
          }
        }
      }

      // Combining and sorting by last updated time
      //val combinedList = (patients + drafts + unsyncedPatients)
      val combinedList = (patients + unsyncedPatients)
        .sortedByDescending { it.meta.lastUpdated }


      // Updating the state flow
      _patientsStateFlow.value = combinedList
      _isFetching.value = false
    }


  }

  fun getAllUnSyncedPatients() {
    val patients = mutableListOf<Patient2>()
    viewModelScope.launch {
      val data = fhirEngine.getUnsyncedLocalChanges()
      data.forEachIndexed { index, localChange ->
        val patient = parsePatientJson(localChange.payload)
        patient?.let {
          if (patient.name.isNotEmpty()){
            patients.add(patient)
          }
        }
      }
      patients.reverse()
      CoroutineScope(Dispatchers.Main).launch {
        _unSyncedStateFlow.value = patients
      }
    }
  }

  fun softDeleteDraft(resourceId: String){
    viewModelScope.launch {
      registerRepository.delete(
        resourceType = ResourceType.QuestionnaireResponse,
        resourceId = resourceId,
        softDelete = false
      )
      getAllDraftResponses()
      getAllPatients()
    }
  }

  fun getAllDraftResponses() {
    viewModelScope.launch {
      val responses = fhirEngine.search<QuestionnaireResponse> {
      }.map {
        it.resource
      }.filter { it.status == QuestionnaireResponse.QuestionnaireResponseStatus.INPROGRESS }
        .sortedByDescending { it.meta.lastUpdated }
      _savedDraftResponseStateFlow.value = responses
    }
  }


    /** The Patient's details for display purposes. */
    data class PatientItem(
      val id: String,
      val resourceId: String,
      val name: String,
      val gender: String,
      val dob: LocalDate? = null,
      val phone: String,
      val city: String,
      val country: String,
      val isActive: Boolean,
      val html: String,
      var risk: String? = ""
    ) {
      override fun toString(): String = name
    }

    /** The Observation's details for display purposes. */
    data class ObservationItem(
      val id: String,
      val code: String,
      val effective: String,
      val value: String,
    ) {
      override fun toString(): String = code
    }

    data class ConditionItem(
      val id: String,
      val code: String,
      val effective: String,
      val value: String,
    ) {
      override fun toString(): String = code
    }

    data class Patient2(
      val name: String,
      val gender: String,
      val primaryContact: String?,
      val age: Int?
    )

  data class DraftPatient(
    val name: String,
    val payloadJson: String
  )

    fun parsePatientJson(json: String): Patient2? {
      val gson = Gson()
      try {
        val patientData = gson.fromJson(json, Map::class.java)

        val nameList = patientData["name"] as List<*>?
        val name = if (nameList != null && nameList.isNotEmpty()) {
          val firstName = (nameList[0] as Map<*, *>)["given"] as List<*>?
          if (firstName != null && firstName.isNotEmpty()) {
            firstName[0] as String
          } else {
            null
          }
        } else {
          null
        }

        val gender = patientData["gender"] as String?
        val telecomData = patientData["telecom"] as List<*>?

        return Patient2(name ?: "", gender ?: "", "", 0)
      } catch (e: Exception) {
        e.printStackTrace()
        return null
      }
    }

  fun parseQuestionnaireResponseJson(json: String): DraftPatient? {
    val gson = Gson()
    try {
      val patientData = gson.fromJson(json, Map::class.java)

      val nameList = patientData["name"] as List<*>?
      val name = if (nameList != null && nameList.isNotEmpty()) {
        val firstName = (nameList[0] as Map<*, *>)["given"] as List<*>?
        if (firstName != null && firstName.isNotEmpty()) {
          firstName[0] as String
        } else {
          null
        }
      } else {
        null
      }

      val gender = patientData["gender"] as String?
      val telecomData = patientData["telecom"] as List<*>?

      return DraftPatient(name ?: "", gender ?: "")
    } catch (e: Exception) {
      e.printStackTrace()
      return null
    }
  }

  // Helper function to convert Patient to ResourceData
  private fun Patient.toResourceData(): AllPatientsResourceData {
    return AllPatientsResourceData(
      id = id,
      meta = meta,
      resourceType = AllPatientsResourceType.Patient,
      patient = this
    )
  }

  // Helper function to convert QuestionnaireResponse to ResourceData
  private fun QuestionnaireResponse.toResourceData(): AllPatientsResourceData {
    return AllPatientsResourceData(
      id = id,
      meta = meta,
      resourceType = AllPatientsResourceType.QuestionnaireResponse,
      questionnaireResponse = this
    )
  }

  // Helper function to convert Patient2 to ResourceData
  private fun Patient2.toResourceData(): AllPatientsResourceData {
    return AllPatientsResourceData(
      id = UUID.randomUUID().toString(),
      meta = Meta().apply { lastUpdated = Date() },
      resourceType = AllPatientsResourceType.Patient2,
      patient2 = this
    )
  }

  // ResourceData class with all three types and meta information
  data class AllPatientsResourceData(
    val id: String,
    val meta: Meta,
    val resourceType: AllPatientsResourceType,
    val patient: Patient? = null,
    val questionnaireResponse: QuestionnaireResponse? = null,
    val patient2: Patient2? = null
  )

  // Enumeration for resource types
  enum class AllPatientsResourceType {
    Patient,
    QuestionnaireResponse,
    Patient2
  }


}
