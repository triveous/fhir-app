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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.util.fastDistinctBy
import androidx.compose.ui.util.fastFilter
import androidx.compose.ui.util.fastForEach
import androidx.compose.ui.util.fastMap
import androidx.compose.ui.util.fastMapIndexedNotNull
import androidx.compose.ui.util.fastMapNotNull
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.fhir.FhirEngine
import com.google.android.fhir.search.search
import com.google.gson.Gson
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import org.hl7.fhir.r4.model.Coding
import org.hl7.fhir.r4.model.Enumerations.DataType
import org.hl7.fhir.r4.model.Meta
import org.hl7.fhir.r4.model.Patient
import org.hl7.fhir.r4.model.QuestionnaireResponse
import org.hl7.fhir.r4.model.Task
import org.hl7.fhir.r4.model.Task.TaskStatus
import org.smartregister.fhircore.engine.domain.model.Code
import org.smartregister.fhircore.engine.domain.model.DataQuery
import org.smartregister.fhircore.engine.domain.model.FilterCriterionConfig
import org.smartregister.fhircore.engine.domain.model.SnackBarMessageConfig
import org.smartregister.fhircore.engine.util.DispatcherProvider
import org.smartregister.fhircore.engine.util.SharedPreferenceKey
import org.smartregister.fhircore.engine.util.SharedPreferencesHelper
import org.smartregister.fhircore.engine.util.extension.logicalId
import org.smartregister.fhircore.engine.util.extension.valueToString
import org.smartregister.fhircore.quest.ui.register.patients.RegisterUiState
import org.smartregister.fhircore.quest.ui.register.patients.RegisterViewModel.TaskItem
import org.smartregister.fhircore.quest.util.TaskProgressState
import org.smartregister.model.practitioner.FhirPractitionerDetails
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
//  val paginatedRegisterData: MutableStateFlow<Flow<PagingData<ResourceData>>> =
//    MutableStateFlow(emptyFlow())
//  val pagesDataCache = mutableMapOf<Int, Flow<PagingData<ResourceData>>>()
//  val registerFilterState = mutableStateOf(RegisterFilterState())
//  private val _totalRecordsCount = mutableLongStateOf(0L)
//  private val _filteredRecordsCount = mutableLongStateOf(-1L)
//  private lateinit var registerConfiguration: RegisterConfiguration
//  private var allPatientRegisterData: Flow<PagingData<ResourceData>>? = null
//  private val _percentageProgress: MutableSharedFlow<Int> = MutableSharedFlow(0)
//  private val _isUploadSync: MutableSharedFlow<Boolean> = MutableSharedFlow(0)

  private val _allLatestTasksStateFlow = MutableStateFlow<List<TaskItem>>(emptyList())
  val allLatestTasksStateFlow: StateFlow<List<TaskItem>> = _allLatestTasksStateFlow

  private val _isFetching = MutableStateFlow<Boolean>(false)
  val isFetching: StateFlow<Boolean> = _isFetching

  private val _filteredTasksStateFlow = MutableStateFlow<List<TaskItem>>(emptyList())
  val filteredTasksStateFlow: StateFlow<List<TaskItem>> = _filteredTasksStateFlow

  private val _allTaskCodeWithValues = MutableStateFlow<List<Pair<String,String>>>(emptyList())
  val allTaskCodeWithValues: StateFlow<List<Pair<String,String>>> = _allTaskCodeWithValues

  private val _selectedFilter = MutableStateFlow<Pair<String,String>>(Pair(TaskCode.URGENT_REFER_TO_HOSPITAL.code, ""))
  val selectedFilter: StateFlow<Pair<String,String>> = _selectedFilter

  private var previousCode = ""

  private fun getPractitionerDetails() : FhirPractitionerDetails? {
      return sharedPreferencesHelper.read<FhirPractitionerDetails>(
        key = SharedPreferenceKey.PRACTITIONER_DETAILS.name,
        decodeWithGson = true,
      )
  }

  fun setSelectedFilter(filter: Pair<String, String>){
    _selectedFilter.value = filter
  }

  fun getAllLatestTasks() {
    viewModelScope.launch {
      _isFetching.value = true
      val practitionerDetails = getPractitionerDetails()
      val practitionerId = practitionerDetails?.id.toString().substringAfterLast("/")

      // Fetch tasks and patients in parallel
      val tasksDeferred = async { fhirEngine.search<Task> { } }
      val patientsDeferred = async { fhirEngine.search<Patient> { } }

      val allTasks = tasksDeferred.await().fastMap { it.resource }
      val patients = patientsDeferred.await().fastMap { it.resource.toResourceData() }
        .associateBy { it.patient?.logicalId } // Use associateBy to create map with ID as key

      val tasksWithPatient = allTasks.fastMapNotNull { task ->

        val taskOwnerId = task.owner?.reference?.toString()?.substringAfterLast("/") ?: ""
        val patientId = task?.`for`?.reference?.toString()?.substringAfter("/") ?: return@fastMapNotNull null
        if (taskOwnerId == practitionerId && task.status != TaskStatus.REJECTED && patients.containsKey(patientId)) {
          TaskItem(task = task, patient = patients[patientId]?.patient)
        } else {
          null
        }
      }.fastDistinctBy { it.task.logicalId }

      val allCodeAndDisplay = tasksWithPatient.fastMapNotNull { taskWithPatient ->
        getTaskCodeWithValue(taskWithPatient)
      }.flatten().fastDistinctBy { it.first }.sortedByDescending { it.second }
      _allTaskCodeWithValues.value = allCodeAndDisplay
      _selectedFilter.value = allCodeAndDisplay.first()
      println("allCodeAndDisplay --> ${Gson().toJson(allCodeAndDisplay)}")

      _allLatestTasksStateFlow.value = tasksWithPatient.fastDistinctBy { it.task.logicalId }
      _isFetching.value = false
    }
  }

  fun getFilteredTasks(filter:Pair<String,String>,status: TaskStatus, priority: TaskProgressState){
    val tasks = _allLatestTasksStateFlow.value
    var newTasks : List<TaskItem> = emptyList<TaskItem>()
    _isFetching.value = true
    newTasks = tasks.fastFilter { task ->
      // Get the list of pairs for the task (assuming this returns a list of pairs or null)
      val list = getTaskCodeWithValue(task)

      // Filter the list to check if any element's 'first' matches 'filter.first'
      val filteredList = list?.fastFilter { it.first == filter.first } ?: emptyList()

      // Keep the task if the filtered list is not empty
      filteredList.isNotEmpty()
    }

    newTasks = newTasks.fastFilter {
      if (status == TaskStatus.REQUESTED){
        if (priority == TaskProgressState.NOT_CONTACTED){
          it.task.output.isNullOrEmpty()
        }else{
          it.task.output.takeIf { it.isNotEmpty() }?.get(0)?.value.valueToString() == priority.text
        }
      }else if (status == TaskStatus.INPROGRESS){
        it.task.output.takeIf { it.isNotEmpty() }?.firstOrNull()?.value.valueToString() == priority.text
      }else{
        it.task.output.takeIf { it.isNotEmpty() }?.firstOrNull()?.value.valueToString() == priority.text
      }
    }

    newTasks = newTasks.fastFilter {
      it.task.status != TaskStatus.REJECTED
    }.fastDistinctBy { it.task.logicalId
    }.sortedByDescending { it.task.meta?.lastUpdated }

    _filteredTasksStateFlow.value = newTasks
    _isFetching.value = false

  }

  internal fun getTaskCodeWithValue(taskWithPatient: TaskItem): List<Pair<String, String>>? {
    return taskWithPatient.task.input?.fastMapIndexedNotNull { index, input ->
      var codes = input.value?.getNamedProperty("code")?.values?.fastMapNotNull { code ->
        code.valueToString()
      }

      var displays = input.value?.getNamedProperty("display")?.values?.fastMapNotNull { display ->
        display.valueToString()
      }

      if (codes == null && displays == null) {
        val data = input.value.valueToString()
        if (previousCode.isEmpty()) {
          val taskCode = TaskCodes.fromCode(data)
          val code = taskCode?.codes?.firstOrNull()
          previousCode = code?.lowercase()?:""
        }
        if (!data.contains("_") && previousCode.isNotEmpty()) {
          codes = listOf(previousCode)
          displays = listOf(data)
        }
      }

      // Combine codes and displays into pairs
      if (codes != null && displays != null && codes.size == displays.size) {
        previousCode=""
        codes.zip(displays)  // This will create a list of (code, display) pairs
      } else {
        null  // Handle any mismatch or null values
      }
    }?.flatten()?.fastDistinctBy { it.first }
  }



  private fun createQueriesForRegisterFilter(
    dataQueries: List<DataQuery>?,
    qrItemMap: Map<String, QuestionnaireResponse.QuestionnaireResponseItemComponent>,
  ) =
    dataQueries?.fastMap {
      val newFilterCriteria = mutableListOf<FilterCriterionConfig>()
      it.filterCriteria.fastForEach { filterCriterionConfig ->
        val answerComponent = qrItemMap[filterCriterionConfig.dataFilterLinkId]
        answerComponent?.answer?.fastForEach { itemAnswerComponent ->
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

  // Helper function to convert Patient to ResourceData
  private fun Patient.toResourceData(): AllPatientsResourceData {
    return AllPatientsResourceData(
      id = id,
      meta = meta,
      resourceType = AllPatientsResourceType.Patient,
      patient = this
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
