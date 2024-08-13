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

package org.smartregister.fhircore.quest.ui.register.patients

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
import ca.uhn.fhir.context.FhirContext
import com.google.android.fhir.FhirEngine
import com.google.android.fhir.datacapture.extensions.asStringValue
import org.smartregister.fhircore.engine.util.extension.logicalId
import com.google.android.fhir.search.search
import com.google.gson.Gson
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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
import org.hl7.fhir.r4.model.Bundle
import org.hl7.fhir.r4.model.CodeableConcept
import org.hl7.fhir.r4.model.Coding
import org.hl7.fhir.r4.model.Enumerations.DataType
import org.hl7.fhir.r4.model.Meta
import org.hl7.fhir.r4.model.Patient
import org.hl7.fhir.r4.model.QuestionnaireResponse
import org.hl7.fhir.r4.model.ResourceType
import org.hl7.fhir.r4.model.Task
import org.hl7.fhir.r4.model.Task.TaskStatus
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
import org.smartregister.fhircore.engine.util.SecureSharedPreference
import org.smartregister.fhircore.engine.util.SharedPreferenceKey
import org.smartregister.fhircore.engine.util.SharedPreferencesHelper
import org.smartregister.fhircore.engine.util.extension.daysPassed
import org.smartregister.fhircore.engine.util.extension.encodeJson
import org.smartregister.fhircore.engine.util.extension.extractLogicalIdUuid
import org.smartregister.fhircore.engine.util.extension.isToday
import org.smartregister.fhircore.engine.util.extension.monthsPassed
import org.smartregister.fhircore.engine.util.extension.valueToString
import org.smartregister.fhircore.quest.data.register.RegisterPagingSource
import org.smartregister.fhircore.quest.data.register.model.RegisterPagingSourceState
import org.smartregister.fhircore.quest.util.OpensrpDateUtils.convertToDateStringToDate
import org.smartregister.fhircore.quest.util.TaskProgressState
import org.smartregister.fhircore.quest.util.extensions.toParamDataMap
import org.smartregister.model.practitioner.FhirPractitionerDetails
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
  val secureSharedPreference: SecureSharedPreference,
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

  private val _dashboardDataStateFlow = MutableStateFlow<DashboardData>(DashboardData("", "", "", ""))
  val dashboardDataStateFlow: StateFlow<DashboardData> = _dashboardDataStateFlow

  private val _allLatestTasksStateFlow = MutableStateFlow<List<TaskItem>>(emptyList())
  val allLatestTasksStateFlow: StateFlow<List<TaskItem>> = _allLatestTasksStateFlow

  private val _filteredTasksStateFlow = MutableStateFlow<List<TaskItem>>(emptyList())
  val filteredTasksStateFlow: StateFlow<List<TaskItem>> = _filteredTasksStateFlow

  private val _searchedTasksStateFlow = MutableStateFlow<List<TaskItem>>(emptyList())
  val searchedTasksStateFlow: StateFlow<List<TaskItem>> = _searchedTasksStateFlow


  private val _newTasksStateFlow = MutableStateFlow<List<TaskItem>>(emptyList())
  val newTasksStateFlow: StateFlow<List<TaskItem>> = _newTasksStateFlow

  private val _pendingTasksStateFlow = MutableStateFlow<List<TaskItem>>(emptyList())
  val pendingTasksStateFlow: StateFlow<List<TaskItem>> = _pendingTasksStateFlow

  private val _completedTasksStateFlow = MutableStateFlow<List<TaskItem>>(emptyList())
  val completedTasksStateFlow: StateFlow<List<TaskItem>> = _completedTasksStateFlow

  private val _allPatientsStateFlow = MutableStateFlow<List<AllPatientsResourceData>>(emptyList())
  val allPatientsStateFlow: StateFlow<List<AllPatientsResourceData>> = _allPatientsStateFlow

  private val _isFetching = MutableStateFlow<Boolean>(false)
  val isFetching: StateFlow<Boolean> = _isFetching


  private val _isFetchingTasks = MutableStateFlow<Boolean>(false)
  val isFetchingTasks: StateFlow<Boolean> = _isFetchingTasks


  private val _isLogout = MutableStateFlow<Boolean>(false)
  val isLogout: StateFlow<Boolean> = _isLogout

  private val _allSyncedPatientsStateFlow = MutableStateFlow<List<AllPatientsResourceData>>(emptyList())
  val allSyncedPatientsStateFlow: StateFlow<List<AllPatientsResourceData>> = _allSyncedPatientsStateFlow

  private val _allSavedDraftResponseStateFlow = MutableStateFlow<List<QuestionnaireResponse>>(emptyList())
  val allSavedDraftResponse: StateFlow<List<QuestionnaireResponse>> = _allSavedDraftResponseStateFlow

  private val _allUnSyncedStateFlow = MutableStateFlow<List<Patient2>>(emptyList())
  val allUnSyncedStateFlow: StateFlow<List<Patient2>> = _allUnSyncedStateFlow

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

  fun logout(){
    secureSharedPreference.deleteSessionPin()
    _isLogout.value = true
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

  fun updateTask(task : Task, status: TaskStatus, taskOutput: TaskProgressState){
    viewModelScope.launch {

      val value = CodeableConcept()
      value.text = taskOutput.text

      task.status = status
      task.output = listOf(
        Task.TaskOutputComponent(
          CodeableConcept(),
          value,
        ),
      )
      fhirEngine.update(task)
      getAllTasks()
    }
  }

  private fun getPractitionerDetails() : FhirPractitionerDetails? {
    return sharedPreferencesHelper.read<FhirPractitionerDetails>(
      key = SharedPreferenceKey.PRACTITIONER_DETAILS.name,
      decodeWithGson = true,
    )
  }


  fun getAllTasks() {
    _isFetchingTasks.value = true
    viewModelScope.launch {
      val practitionerDetails = getPractitionerDetails()
      val practitionerId = practitionerDetails?.id.toString().substringAfterLast("/")

      // Fetch tasks and patients in parallel
      val tasksDeferred = async { fhirEngine.search<Task> { } }
      val patientsDeferred = async { fhirEngine.search<Patient> { } }

      val allTasks = tasksDeferred.await().map { it.resource }
      val patients = patientsDeferred.await().map { it.resource.toResourceData() }
        .associateBy { it.patient?.logicalId } // Use associateBy to create map with ID as key

      val tasksWithPatient = allTasks.mapNotNull { task ->

        val taskOwnerId = task.owner?.reference?.toString()?.substringAfterLast("/") ?: ""
        val patientId = task?.`for`?.reference?.toString()?.substringAfter("/") ?: return@mapNotNull null
        if (taskOwnerId == practitionerId && task.status != TaskStatus.REJECTED && patients.containsKey(patientId)) {
          TaskItem(task = task, patient = patients[patientId]?.patient)
        } else {
          null
        }
      }.distinctBy { it.task.logicalId }

      _newTasksStateFlow.value = tasksWithPatient.filter { it.task.status == TaskStatus.REQUESTED }
        .sortedByDescending { it.task.meta.lastUpdated }

      _pendingTasksStateFlow.value = tasksWithPatient.filter { it.task.status == TaskStatus.INPROGRESS }
        .sortedByDescending { it.task.meta.lastUpdated }

      _completedTasksStateFlow.value = tasksWithPatient.filter { it.task.status == TaskStatus.COMPLETED }
        .sortedByDescending { it.task.meta.lastUpdated }

      _isFetchingTasks.value = false
    }
  }

  fun getAllLatestTasks() {
    viewModelScope.launch {
      val practitionerDetails = getPractitionerDetails()
      val practitionerId = practitionerDetails?.id.toString().substringAfterLast("/")

      // Fetch tasks and patients in parallel
      val tasksDeferred = async { fhirEngine.search<Task> { } }
      val patientsDeferred = async { fhirEngine.search<Patient> { } }

      val allTasks = tasksDeferred.await().map { it.resource }
      val patients = patientsDeferred.await().map { it.resource.toResourceData() }
        .associateBy { it.patient?.logicalId } // Use associateBy to create map with ID as key

      val tasksWithPatient = allTasks.mapNotNull { task ->

        val taskOwnerId = task.owner?.reference?.toString()?.substringAfterLast("/") ?: ""
        val patientId = task?.`for`?.reference?.toString()?.substringAfter("/") ?: return@mapNotNull null
        if (taskOwnerId == practitionerId && task.status != TaskStatus.REJECTED && patients.containsKey(patientId)) {
          TaskItem(task = task, patient = patients[patientId]?.patient)
        } else {
          null
        }
      }.distinctBy { it.task.logicalId }

      _allLatestTasksStateFlow.value = tasksWithPatient.distinctBy { it.task.logicalId }
    }
  }

  fun searchTasks(searchText: String) {
    _searchedTasksStateFlow.value = emptyList()
    val isPhoneNumber = searchText.toIntOrNull() != null
    val allTasksWithPatients = _allLatestTasksStateFlow.value
    val matchedTasksWithPatientList = mutableListOf<TaskItem>()

    viewModelScope.launch {
      allTasksWithPatients.forEach { taskItem ->
        val patient = taskItem.patient
        patient?.let {
          if (isPhoneNumber){
            val phone = patient?.telecom?.get(0)?.value.toString()
            if (taskItem.task.status != TaskStatus.REJECTED && searchText.contains(phone)){
              matchedTasksWithPatientList.add(taskItem)
            }
          }else{
            val name = patient?.name?.get(0)?.given?.get(0)?.value.toString() ?: ""
            if (taskItem.task.status != TaskStatus.REJECTED && name.contains(searchText, true)){
              matchedTasksWithPatientList.add(taskItem)
            }
          }
        }
      }
      _searchedTasksStateFlow.value = matchedTasksWithPatientList.distinctBy { it.task.logicalId }
    }
  }

  fun clearSearch(){
    _searchedTasksStateFlow.value = emptyList()
  }

  fun getNotContactedNewTasks(tasks: List<TaskItem>, status: TaskStatus): List<TaskItem> {

    return tasks.filter {
      (it.task.status == status && it.task.output.takeIf { it.isNotEmpty() }?.get(0)?.value.valueToString() != TaskProgressState.NOT_RESPONDED.text)

      //(it.task.priority != TaskPriority.ASAP && it.task.status == status)
    }.sortedByDescending { it.task.meta.lastUpdated }.distinctBy { it.task.logicalId }
  }

  fun getNotRespondedNewTasks(tasks: List<TaskItem>, status: TaskStatus): List<TaskItem> {
    return tasks.filter { it ->
      (it.task.status == status && it.task.output.takeIf { it.isNotEmpty() }?.get(0)?.value.valueToString() == TaskProgressState.NOT_RESPONDED.text)


      //(it.task.priority == TaskPriority.ASAP && it.task.status == status)
    }.sortedByDescending { it.task.meta.lastUpdated }.distinctBy { it.task.logicalId }
  }

  fun getPendingAgreedButNotDoneTasks(newTasks: List<TaskItem>, status: TaskStatus): List<TaskItem> {
    return newTasks.filter {
      (it.task.status == status && it.task.output.takeIf { it.isNotEmpty() }?.get(0)?.value.valueToString() == TaskProgressState.AGREED_FOLLOWUP_NOT_DONE.text)


      //(it.task.priority == TaskPriority.STAT && it.task.status == status)
    }.sortedByDescending { it.task.meta.lastUpdated }.distinctBy { it.task.logicalId }
  }

  fun getPendingNotAgreedTasks(newTasks: List<TaskItem>, status: TaskStatus): List<TaskItem> {
    return newTasks.filter {
      (it.task.status == status && it.task.output.takeIf { it.isNotEmpty() }?.get(0)?.value.valueToString() == TaskProgressState.NOT_AGREED_FOR_FOLLOWUP.text)


      //(it.task.priority == TaskPriority.URGENT && it.task.status == status)
    }.sortedByDescending { it.task.meta.lastUpdated }.distinctBy { it.task.logicalId }
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
    val newBaseResourceDataQueries =
      createQueriesForRegisterFilter(
        registerDataFilterFieldsMap?.get(baseResource.filterId)?.dataQueries,
        qrItemMap,
      )

    Timber.i(
      "New data queries for filtering Base Resources: ${newBaseResourceDataQueries.encodeJson()}",
    )

    val newRelatedResources =
      createFilterRelatedResources(
        registerDataFilterFieldsMap = registerDataFilterFieldsMap,
        relatedResources = resourceConfig.relatedResources,
        qrItemMap = qrItemMap,
      )

    Timber.i(
      "New configurations for filtering related resource data: ${newRelatedResources.encodeJson()}",
    )

    registerFilterState.value =
      RegisterFilterState(
        questionnaireResponse = questionnaireResponse,
        fhirResourceConfig =
          FhirResourceConfig(
            baseResource = baseResource.copy(dataQueries = newBaseResourceDataQueries),
            relatedResources = newRelatedResources,
          ),
      )
  }

  private fun createFilterRelatedResources(
    registerDataFilterFieldsMap: Map<String, RegisterFilterField>?,
    relatedResources: List<ResourceConfig>,
    qrItemMap: Map<String, QuestionnaireResponse.QuestionnaireResponseItemComponent>,
  ): List<ResourceConfig> {
    val newRelatedResources =
      relatedResources.map {
        val newDataQueries =
          createQueriesForRegisterFilter(
            registerDataFilterFieldsMap?.get(it.filterId)?.dataQueries,
            qrItemMap,
          )
        it.copy(
          dataQueries = newDataQueries,
          relatedResources =
            createFilterRelatedResources(
              registerDataFilterFieldsMap = registerDataFilterFieldsMap,
              relatedResources = it.relatedResources,
              qrItemMap = qrItemMap,
            ),
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
                .read(SharedPreferenceKey.LAST_SYNC_TIMESTAMP.name, null)
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

  fun getDashboardCasedData(){

    viewModelScope.launch {
      val patients = fhirEngine.search<Patient> {
        // ... your search criteria
      }.map {
        it.resource
      }.filter { patient ->
        (patient?.generalPractitioner?.firstOrNull()?.reference?.toString()?.substringAfter("/").orEmpty()).equals(getUserName(), true)
      }

      val todayCases = patients.filter {

        if(it.extension?.get(0)?.value?.asStringValue()?.isNotEmpty() == true){
          val date = convertToDateStringToDate(it.extension?.get(0)?.value?.asStringValue() ?: "")
          date.isToday()
        }else{
          it.meta?.lastUpdated?.isToday() == true
        }
        /*it.extension?.get(0)?.value?.asStringValue()?.let { it1 ->
        }*/
      }.size

      val thisWeek = patients.filter {

        if(it.extension?.get(0)?.value?.asStringValue()?.isNotEmpty() == true){
          val date = convertToDateStringToDate(it.extension?.get(0)?.value?.asStringValue() ?: "")
          date.daysPassed() <= 7
        }else{
          (it?.meta?.lastUpdated?.daysPassed() ?: 0) <= 7
        }
      }.size

      val thisMonth = patients.filter {

        if(it.extension?.get(0)?.value?.asStringValue()?.isNotEmpty() == true){
          val date = convertToDateStringToDate(it.extension?.get(0)?.value?.asStringValue() ?: "")
          date.monthsPassed() <= 7
        }else{
          (it.meta?.lastUpdated?.monthsPassed() ?: 0) < 1
        }
      }.size

      val data = DashboardData("$todayCases", "$thisWeek", "$thisMonth", "${patients.size}")
      // Update UI or perform further actions with the counts
      _dashboardDataStateFlow.value = data
    }
  }

  fun getAllPatients() {

    _isFetching.value = true
    viewModelScope.launch {
      val userName = getUserName()

      // Fetching patients
      val patients = fhirEngine.search<Patient> {
      }.map {
        it.resource.toResourceData()
      }
        .filter {
        (it.patient?.generalPractitioner?.firstOrNull()?.reference?.toString()?.substringAfter("/") ?: "").equals(userName, true)

      }.sortedByDescending {
          it.meta.lastUpdated
          /*if(it.patient?.extension?.get(0)?.value?.asStringValue()?.isNotEmpty() == true){
            val date = convertToDateStringToDate(it.patient?.extension?.get(0)?.value?.asStringValue() ?: "")
            date
          }else{
            it.meta.lastUpdated
          }*/
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
      //val combinedList = (patients + drafts)

/*
      val combinedList = (patients + unsyncedPatients)
        .sortedByDescending { it.meta.lastUpdated }
*/


      // Updating the state flow
      _allPatientsStateFlow.value = patients
      _isFetching.value = false
    }
  }

  fun getAllSyncedPatients(){
    _isFetching.value = true
    viewModelScope.launch {
      val userName = getUserName()

      // Fetching patients
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

      val patients = fhirEngine.search<Patient> {
      }.map {
        it.resource.toResourceData()
      }.filter {
        (it.patient?.generalPractitioner?.firstOrNull()?.reference?.toString()?.substringAfter("/") ?: "").equals(userName, true)
      }

      //Removing the unsynced Patient present in the patientsList
      val filteredPatients = patients.filterNot { patient ->
        unsyncedPatients.any { unsyncedPatient ->
          patient.patient?.logicalId == unsyncedPatient.patient2?.id
        }
      }.sortedByDescending {
        it.meta.lastUpdated
        /*if(it.patient?.extension?.get(0)?.value?.asStringValue()?.isNotEmpty() == true){
          val date = convertToDateStringToDate(it.patient?.extension?.get(0)?.value?.asStringValue() ?: "")
          date
        }else{
          it.meta.lastUpdated
        }*/
      }

      _allSyncedPatientsStateFlow.value = filteredPatients
      _isFetching.value = false
    }
  }

  fun getAllDraftResponses() {
    viewModelScope.launch {
      val allResponses = mutableListOf<QuestionnaireResponse>()
      try {
        val parser = FhirContext.forR4Cached().newJsonParser()
        val draftResponsesJson = sharedPreferencesHelper.read<String>(SharedPreferenceKey.DRAFTS.name, false)
        if (!draftResponsesJson.isNullOrEmpty()){
          val allDrafts = parser?.parseResource(draftResponsesJson) as Bundle

          allDrafts?.entry?.forEach {
            allResponses.add(it.resource as QuestionnaireResponse)
          }
        }
      }catch (exception: Exception){
        Timber.e(exception, "An error occurred while getting all drafts")
      }

      val userName = getUserName()
      val responses = fhirEngine.search<QuestionnaireResponse> {
      }.map {
        it.resource
      }.filter {
        (it.status == QuestionnaireResponse.QuestionnaireResponseStatus.INPROGRESS) &&
                (it.author?.reference?.toString() ?: "").contains(userName, true)
      }
        .sortedByDescending { it.meta.lastUpdated }

      _allSavedDraftResponseStateFlow.value = allResponses + responses
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
        _allUnSyncedStateFlow.value = patients
      }
    }
  }

  fun softDeleteDraft(resourceId: String){
    viewModelScope.launch {
      try {
        val parser = FhirContext.forR4Cached().newJsonParser()

        val draftResponsesJson = sharedPreferencesHelper.read<String>(SharedPreferenceKey.DRAFTS.name, false)
        val allDrafts = parser?.parseResource(draftResponsesJson) as Bundle

        if (allDrafts?.entry?.any { it.resource?.id == resourceId } == true){
          if (!draftResponsesJson.isNullOrEmpty()) {
            val entriesToRemove = mutableListOf<Int>()

            allDrafts?.entry?.let {
              for (i in 0 until it.size) {
                val entry = allDrafts.entry[i]
                if (entry.resource.id == resourceId) {
                  entriesToRemove.add(i)
                }
              }
              entriesToRemove.forEach { allDrafts.entry.removeAt(it) }
            }
          }

          // Save the updated bundle
          val bundleJson = parser.encodeResourceToString(allDrafts)
          sharedPreferencesHelper.write<String>(SharedPreferenceKey.DRAFTS.name, bundleJson)
        }else{
          registerRepository.delete(
            resourceType = ResourceType.QuestionnaireResponse,
            resourceId = resourceId.extractLogicalIdUuid(),
            softDelete = false
          )
        }
      }catch (exception: Exception){
        Timber.e(exception, "An error occurred while deleting a draft")
      }
    }
    getAllDraftResponses()
    getAllPatients()
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


  data class TaskItem(
    val task: Task,
    val patient: Patient?
  )

    data class Patient2(
      val id: String,
      val name: String,
      val lastUpdated: String,
      val gender: String,
      val primaryContact: String?,
      val age: Int?
    )

  data class DraftPatient(
    val name: String,
    val payloadJson: String
  )

  data class DashboardData constructor(
    val todayCases: String,
    val thisWeekCases: String,
    val thisMonthCases: String,
    val totalCases: String
  ){

  }

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

        val id = patientData["id"] as String? ?: ""
        val lastUpdated = (patientData["meta"] as Map<*, *>) ["lastUpdated"] as String? ?: ""
        val gender = patientData["gender"] as String?
        val telecomData = patientData["telecom"] as List<*>?
        val mobile = (telecomData?.get(0) as Map<*, *>)["value"].toString()

        return Patient2(id = id, name ?: "", lastUpdated = lastUpdated, gender ?: "", mobile, 0)
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

  fun getUserName(): String {
    return secureSharedPreference.retrieveSessionUsername() ?: "Guest"
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
