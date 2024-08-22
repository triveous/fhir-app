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

package org.smartregister.fhircore.quest.ui.register.tasks

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import com.google.android.fhir.FhirEngine
import org.smartregister.fhircore.engine.util.extension.logicalId
import com.google.android.fhir.search.search
import com.google.gson.Gson
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import org.hl7.fhir.r4.model.CodeableConcept
import org.hl7.fhir.r4.model.Coding
import org.hl7.fhir.r4.model.Enumerations.DataType
import org.hl7.fhir.r4.model.Meta
import org.hl7.fhir.r4.model.Patient
import org.hl7.fhir.r4.model.QuestionnaireResponse
import org.hl7.fhir.r4.model.Task
import org.hl7.fhir.r4.model.Task.TaskPriority
import org.hl7.fhir.r4.model.Task.TaskStatus
import org.smartregister.fhircore.engine.configuration.register.RegisterConfiguration
import org.smartregister.fhircore.engine.configuration.register.RegisterFilterField
import org.smartregister.fhircore.engine.domain.model.Code
import org.smartregister.fhircore.engine.domain.model.DataQuery
import org.smartregister.fhircore.engine.domain.model.FilterCriterionConfig
import org.smartregister.fhircore.engine.domain.model.ResourceConfig
import org.smartregister.fhircore.engine.domain.model.ResourceData
import org.smartregister.fhircore.engine.domain.model.SnackBarMessageConfig
import org.smartregister.fhircore.engine.util.DispatcherProvider
import org.smartregister.fhircore.engine.util.SharedPreferenceKey
import org.smartregister.fhircore.engine.util.SharedPreferencesHelper
import org.smartregister.fhircore.engine.util.extension.valueToString
import org.smartregister.fhircore.quest.ui.register.patients.RegisterFilterState
import org.smartregister.fhircore.quest.ui.register.patients.RegisterUiState
import org.smartregister.fhircore.quest.util.TaskProgressState
import org.smartregister.model.practitioner.FhirPractitionerDetails
import java.time.LocalDate
import java.util.Date
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class TasksViewModel
@Inject
constructor(
  val dispatcherProvider: DispatcherProvider,
  val sharedPreferencesHelper: SharedPreferencesHelper,
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

  private val _patientsStateFlow = MutableStateFlow<List<AllPatientsResourceData>>(emptyList())
  val patientsStateFlow: StateFlow<List<AllPatientsResourceData>> = _patientsStateFlow

  private val _isFetching = MutableStateFlow<Boolean>(false)
  val isFetching: StateFlow<Boolean> = _isFetching


  private val _isFetchingTasks = MutableStateFlow<Boolean>(false)
  val isFetchingTasks: StateFlow<Boolean> = _isFetchingTasks

  private val _savedDraftResponseStateFlow = MutableStateFlow<List<QuestionnaireResponse>>(emptyList())
  val savedDraftResponse: StateFlow<List<QuestionnaireResponse>> = _savedDraftResponseStateFlow

  private val _unSyncedStateFlow = MutableStateFlow<List<Patient2>>(emptyList())
  val unSyncedStateFlow: StateFlow<List<Patient2>> = _unSyncedStateFlow



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

  fun getAllTasks() {
    _isFetchingTasks.value = true
    viewModelScope.launch {
      val practitionerDetails = getPractitionerDetails()

      val practitionerId = practitionerDetails?.id

      val patients = fhirEngine.search<Patient> {
      }.map {
        it.resource.toResourceData()
      }

      val responses = fhirEngine.search<Task> {
      }.map { it.resource }

        val tasksWithPatient : MutableList<TaskItem> = mutableListOf()
        responses.map { task ->

          if (task?.owner?.identifier?.value == practitionerId){
            patients.size.let { it > 0 }.let { isNotEmpty ->
              if(isNotEmpty){
                val patient = patients.find {
                  task?.`for`?.reference?.toString().let { refId ->
                    (refId.toString().contains(it.patient?.logicalId.toString(), true) && task.status != TaskStatus.REJECTED)
                  }
                }

                patient?.let {
                  val taskItem = TaskItem(
                    task = task,
                    patient = patient?.patient // Use a default patient object if not found
                  )
                  tasksWithPatient.add(taskItem)
                }
              }
            }
          }
        }

      _newTasksStateFlow.value = tasksWithPatient
        .filter { it.task.status == TaskStatus.REQUESTED }
        .distinctBy { it.task.logicalId }
        .sortedByDescending { it.task.meta.lastUpdated }

      _pendingTasksStateFlow.value = tasksWithPatient
        .filter { it.task.status == TaskStatus.INPROGRESS }
        .distinctBy { it.task.logicalId }
        .sortedByDescending { it.task.meta.lastUpdated }

      _completedTasksStateFlow.value = tasksWithPatient
        .filter { it.task.status == TaskStatus.COMPLETED }
        .distinctBy { it.task.logicalId }
        .sortedByDescending {it.task.meta.lastUpdated }

      _isFetchingTasks.value = false

    }
  }

  private fun getPractitionerDetails() : FhirPractitionerDetails? {
      return sharedPreferencesHelper.read<FhirPractitionerDetails>(
        key = SharedPreferenceKey.PRACTITIONER_DETAILS.name,
        decodeWithGson = true,
      )
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

  fun getFilteredTasks(filter: FilterType, status: TaskStatus, priority: TaskProgressState){
    val tasks = _allLatestTasksStateFlow.value

    var newTasks : List<TaskItem> = emptyList<TaskItem>()

    newTasks = tasks.filter {
      it.task.status == status
    }

    newTasks = newTasks.filter {
      if (status == TaskStatus.REQUESTED){
        if (priority == TaskProgressState.NOT_CONTACTED){
          it.task.output.isNullOrEmpty()
        }else{
          it.task.output.takeIf { it.isNotEmpty() }?.get(0)?.value.valueToString() == priority.text
        }
      }else{
        it.task.output.takeIf { it.isNotEmpty() }?.get(0)?.value.valueToString() == priority.text
      }
    }

    when(filter){
      FilterType.URGENT_REFERRAL -> newTasks = newTasks.filter {
        it.task.intent == Task.TaskIntent.ORDER
      }
      FilterType.ADD_INVESTIGATION -> newTasks = newTasks.filter {
        it.task.intent == Task.TaskIntent.PLAN
      }
      FilterType.RETAKE_PHOTO -> newTasks = newTasks.filter {
        it.task.intent == Task.TaskIntent.PROPOSAL
      }
      FilterType.ADVICE_TO_QUIT -> newTasks = newTasks.filter {
        it.task.intent == Task.TaskIntent.OPTION
      }
    }


    newTasks = newTasks.filter {
      it.task.status != TaskStatus.REJECTED
    }.distinctBy { it.task.logicalId
    }.sortedByDescending { it?.task?.meta?.lastUpdated }

    _filteredTasksStateFlow.value = newTasks
  }

  fun searchTasks(searchText: String) {
    val isPhoneNumber = searchText.toIntOrNull() != null

    viewModelScope.launch {

      val patients = fhirEngine.search<Patient> {
      }.map {
        it.resource.toResourceData()
      }

      val responses = fhirEngine.search<Task> {
      }.map { it.resource }

      val matchedTasksWithPatientList = mutableListOf<TaskItem>()

      responses.map { task ->
        val patientId = task.description
        //val patient = patients.find { it.id == patientId }
        val patient = patients.get(0)

        if (isPhoneNumber){
          val phone = patient?.patient?.telecom?.get(0)?.value.toString()
          if (searchText.contains(phone)){
            val taskItem = TaskItem(
              task = task,
              patient = patient?.patient // Use a default patient object if not found
            )
            matchedTasksWithPatientList.add(taskItem)
          }
        }else{
          val name = patient?.patient?.name?.get(0)?.given?.get(0)?.value.toString() ?: ""
          if (name.contains(searchText, true)){
            val taskItem = TaskItem(
              task = task,
              patient = patient?.patient // Use a default patient object if not found
            )
            matchedTasksWithPatientList.add(taskItem)
          }
        }
      }
      _searchedTasksStateFlow.value = matchedTasksWithPatientList
    }
  }

  fun clearSearch(){
    _searchedTasksStateFlow.value = emptyList()
  }

  fun getNotContactedNewTasks(tasks: List<TaskItem>, status: TaskStatus): List<TaskItem> {
    return tasks.filter {
      (it.task.priority != TaskPriority.ASAP && it.task.status == status)
    }.sortedByDescending { it.task.meta.lastUpdated }
  }

  fun getNotRespondedNewTasks(tasks: List<TaskItem>, status: TaskStatus): List<TaskItem> {
    return tasks.filter {
      (it.task.priority == TaskPriority.ASAP && it.task.status == status)
    }.sortedByDescending { it.task.meta.lastUpdated }
  }

  fun getPendingAgreedButNotDoneTasks(newTasks: List<TaskItem>, status: TaskStatus): List<TaskItem> {
    return newTasks.filter {
      (it.task.priority == TaskPriority.STAT && it.task.status == status)
    }.sortedByDescending { it.task.meta.lastUpdated }
  }

  fun getPendingNotAgreedTasks(newTasks: List<TaskItem>, status: TaskStatus): List<TaskItem> {
    return newTasks.filter {
      (it.task.priority == TaskPriority.URGENT && it.task.status == status)
    }.sortedByDescending { it.task.meta.lastUpdated }
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
      val combinedList = (patients + drafts)
      //val combinedList = (patients + unsyncedPatients)
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


  data class TaskItem(
    val task: Task,
    val patient: Patient?
  )

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
