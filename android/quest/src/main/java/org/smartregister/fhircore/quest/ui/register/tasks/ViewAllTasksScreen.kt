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

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.MaterialTheme
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.smartregister.fhircore.engine.ui.theme.LightColors
import org.smartregister.fhircore.engine.ui.theme.SearchHeaderColor
import org.smartregister.fhircore.quest.ui.main.components.FILTER
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.ModalBottomSheetLayout
import androidx.compose.material.ModalBottomSheetValue
import androidx.compose.material.Scaffold
import androidx.compose.material.rememberModalBottomSheetState
import androidx.compose.material3.Icon
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonColors
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import org.hl7.fhir.r4.model.Patient
import org.hl7.fhir.r4.model.Task
import org.hl7.fhir.r4.model.Task.TaskPriority
import org.hl7.fhir.r4.model.Task.TaskStatus
import org.smartregister.fhircore.engine.domain.model.ToolBarHomeNavigation
import org.smartregister.fhircore.quest.R
import org.smartregister.fhircore.quest.util.OpensrpDateUtils


const val URGENT_REFERRAL_TAB = "Urgent Referral"
const val ADD_INVESTIGATION_TAB = "Add Investigation"
const val RETAKE_PHOTO_TAB = "Retake Photo Tab"
const val URGENT_REFERRAL_PATIENTS = 0
const val ADD_INVESTIGATION_PATIENTS = 1
const val RETAKE_PHOTO_PATIENTS = 2

enum class FilterType(val label: String) {
  URGENT_REFERRAL("Urgent Referral"),
  ADD_INVESTIGATION("Add. Investigation"),
  RETAKE_PHOTO("Retake photo")
}

@Composable
fun FilterRow(selectedFilter: FilterType, onFilterSelected: (FilterType) -> Unit) {
  Row(modifier = Modifier
    .fillMaxWidth()
    .horizontalScroll(rememberScrollState())
    .padding(16.dp),
    horizontalArrangement = Arrangement.SpaceBetween
    ) {
    FilterType.entries.forEachIndexed { index, filter ->
      Box(modifier = Modifier
        .border(width = 0.5.dp, color = (if (filter == selectedFilter) LightColors.primary else Color.LightGray ), shape = RoundedCornerShape(8.dp))
        .background(if (filter == selectedFilter) LightColors.primary else SearchHeaderColor)
        .padding(8.dp)) {
        Text(
          text = filter.label,
          modifier = Modifier
            .padding(4.dp)
            .clickable {
              onFilterSelected(filter)
            },
          color = if (filter == selectedFilter) Color.White else Color.Black
        )
      }
      if (index < FilterType.entries.size - 1) {
        Spacer(modifier = Modifier.width(8.dp)) // Horizontal margin
      }
    }
  }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterialApi::class)
@Composable
fun ViewAllTasksScreen(
  modifier: Modifier = Modifier,
  viewModel : TasksViewModel,
  screenTitle : String,
  taskStatus: TaskStatus,
  taskPriority: TaskPriority,
  onBack : () -> Unit
) {
  val lazyListState: LazyListState = rememberLazyListState()
  val coroutineScope = rememberCoroutineScope()
  val bottomSheetState = rememberModalBottomSheetState(initialValue = ModalBottomSheetValue.Hidden, skipHalfExpanded = true)
  var selectedTask by remember { mutableStateOf<TasksViewModel.TaskItem?>(null) }

  Scaffold(
    modifier = modifier.background(SearchHeaderColor),

    topBar = {
      Column(modifier = modifier.background(SearchHeaderColor)) {
        TasksTopScreenSection(
          title = screenTitle,
          toolBarHomeNavigation = ToolBarHomeNavigation.NAVIGATE_BACK
        ) {
          onBack()
        }
      }
    }
  ) { innerPadding ->

    ModalBottomSheetLayout(
      sheetGesturesEnabled = false,
      sheetState = bottomSheetState,
      sheetContent = {
        selectedTask?.let { task ->
          TasksBottomSheetContent(task = task, onStatusUpdate = {
            var status : TaskStatus = TaskStatus.NULL
            when(it){
              TaskPriority.ROUTINE -> {
                status = TaskStatus.COMPLETED
              }
              TaskPriority.URGENT -> {
                status = TaskStatus.INPROGRESS
              }
              TaskPriority.STAT -> {
                status = TaskStatus.INPROGRESS
              }
              TaskPriority.ASAP -> {
                status = TaskStatus.INPROGRESS
              }
              TaskPriority.NULL -> {
                status = TaskStatus.INPROGRESS
              }
            }

            viewModel.updateTask(task.task, status, it)
            coroutineScope.launch {
              bottomSheetState.hide()
            }
          },
            onCancel = {
              coroutineScope.launch {
                bottomSheetState.hide()
              }
            })
        }
      }
    ) {

      Box(modifier = modifier.padding(innerPadding)) {

        Box(
          modifier = modifier
            .background(SearchHeaderColor)
        )
        {
          var selectedFilter by remember { mutableStateOf(FilterType.URGENT_REFERRAL) }
          val filteredTasks by viewModel.filteredTasksStateFlow.collectAsState()
          val allLatestTasksStateFlow by viewModel.allLatestTasksStateFlow.collectAsState()

          Column(
            modifier = modifier
              .fillMaxHeight()
              .background(SearchHeaderColor)
              .fillMaxWidth()
          ){

            LaunchedEffect(key1 = selectedFilter, key2 = allLatestTasksStateFlow) {
              viewModel.getFilteredTasks(selectedFilter, taskStatus, taskPriority)
            }

            Row(modifier = Modifier
              .fillMaxWidth()) {
              FilterRow(selectedFilter) { filter ->
                selectedFilter = filter
              }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Box(
              modifier = modifier
                .fillMaxHeight()
                .background(SearchHeaderColor)
                .fillMaxWidth()
            ) {

              if (filteredTasks.isEmpty()){
                Box(
                  modifier = modifier
                    .background(SearchHeaderColor)
                    .padding(top = 48.dp)
                    .fillMaxWidth(),
                  contentAlignment = Alignment.Center
                ) {

                  Box(
                    modifier = modifier
                      .padding(horizontal = 16.dp)
                      .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                  ) {
                    Text(text = stringResource(id = R.string.no_cases))
                  }
                }
              }else{
                LazyColumn(modifier = modifier
                  .background(SearchHeaderColor)) {
                  items(filteredTasks) { task ->
                    Box(
                      modifier = modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp)
                        .background(SearchHeaderColor)
                    ) {
                      SearchCardItemView(task) {
                        selectedTask = task
                        coroutineScope.launch { bottomSheetState.show() }
                      }
                    }
                  }
                }
              }
            }
          }
        }
      }
    }
  }
}


@Composable
fun TasksBottomSheetContent(task: TasksViewModel.TaskItem, onStatusUpdate: (TaskPriority) -> Unit, onCancel: () -> Unit) {

  var name = ""
  var phone = ""
  var date = ""
  var address = ""
  if (task.patient?.name?.isNotEmpty() == true && task.patient.name?.get(0)?.given?.isNotEmpty() == true){
    name = task.patient.name?.get(0)?.given?.get(0)?.value.orEmpty()
    phone = task.patient.telecom?.get(0)?.value.orEmpty()
    date = task.patient.meta?.lastUpdated?.let { OpensrpDateUtils.convertToDate(it) }.toString()
    address = getPatientAddress(task.patient)
  }

  val context = LocalContext.current
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 36.dp)
  ) {
    val selectedPriority = remember { mutableStateOf(TaskPriority.NULL) } // Initial selected status

    Row(modifier = Modifier.padding(vertical = 4.dp, horizontal = 4.dp)) {

      androidx.compose.material.Icon(
        modifier = Modifier.padding(
          vertical = 4.dp
        ),
        painter = painterResource(id = R.drawable.ic_patient_male),
        contentDescription = FILTER,
        tint = LightColors.primary
      )
      Spacer(modifier = Modifier.width(8.dp))

      Text(
        modifier = Modifier
          .weight(1f)
          .padding(vertical = 4.dp),
        text = name,
        fontSize = 24.sp,
        color = LightColors.primary
      )
    }

    Spacer(modifier = Modifier.height(8.dp))

    Text(text = address,
      modifier = Modifier.padding(horizontal = 4.dp),
      color = colorResource(id = R.color.subTextGrey),
      fontSize = 14.sp
    )

    Spacer(modifier = Modifier.height(8.dp))

    Row(modifier = Modifier
      .fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically) {
      Text(text = "Screened on ",
        color = colorResource(id = R.color.subTextGreyBold),
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold)

      Spacer(modifier = Modifier.width(4.dp))

      Text(text = date,
        color = colorResource(id = R.color.subTextGrey),
        fontSize = 14.sp,
      )
    }

    Spacer(modifier = Modifier.height(8.dp))

    Row(modifier = Modifier
      .fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween) {

      Row {
        Text(
          text = "Phone ",
          color = colorResource(id = R.color.subTextGreyBold),
          fontSize = 14.sp,
          fontWeight = FontWeight.Bold,
        )

        Spacer(modifier = Modifier.width(4.dp))


        Text(text = phone,
          color = colorResource(id = R.color.subTextGrey),
          fontSize = 14.sp,)
      }

      Row(modifier = Modifier.align(Alignment.CenterVertically)
        .clickable {
          context.startActivity(Intent(Intent.ACTION_DIAL).apply { data = Uri.parse("tel:$phone") })
        },
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text(text = "Call", color = LightColors.primary)

        Spacer(modifier = Modifier.width(4.dp))

        Icon(
          modifier = Modifier
            .padding(
              horizontal = 4.dp
            )
            .width(24.dp)
            .height(24.dp)
            .align(Alignment.CenterVertically),
          painter = painterResource(id = R.drawable.ic_call),
          contentDescription = FILTER,
          tint = LightColors.primary
        )
      }
    }

    Spacer(modifier = Modifier.height(8.dp))

    Row(modifier = Modifier.padding(vertical = 4.dp)) {
      var label = ""
      var textColor = Color.Black
      var color = Color.Black

      when(task.task.intent){

        Task.TaskIntent.PLAN -> {
          label = "ADD INVESTIGATION"
          color = Color(0xFFFFF8E0)
          textColor = Color(0xFFFFC800)
        }

        Task.TaskIntent.ORDER -> {
          label = "URGENT REFERRAL"
          color = Color(0xFFFFCDD2)
          textColor = Color(0xFFFF3355)
        }

        Task.TaskIntent.PROPOSAL -> {
          label = "RETAKE PHOTO"
          color = Color.LightGray
          textColor = Color.Gray
        }

        else -> {
          label = ""
          color = Color.LightGray
          textColor = Color.Gray
        }
      }

      Row(modifier = Modifier
        .background(color, shape = MaterialTheme.shapes.small)
        .fillMaxWidth(),
        horizontalArrangement = Arrangement.Center
      ) {
        Text(
          text = label,
          color = textColor,
          fontWeight = FontWeight.Bold,
          textAlign = TextAlign.Center,
          modifier = Modifier
            .padding(horizontal = 8.dp, vertical = 8.dp)
            .fillMaxWidth(),
        )
      }
    }

    Spacer(modifier = Modifier.height(16.dp))
    Spacer(modifier = Modifier
      .fillMaxWidth()
      .height(1.dp)
      .background(Color.LightGray))

    Spacer(modifier = Modifier.height(16.dp))

    Text(text = "CHANGE STATUS", color = colorResource(id = R.color.subTextGrey), fontSize = 14.sp)
    Spacer(modifier = Modifier.height(16.dp))

    //val taskImportance = remember { mutableStateOf(TaskPriority.NULL) }

    var options : List<Pair<TaskPriority, String>> = emptyList<Pair<Task.TaskPriority, String>>()
    when(task.task.status) {
      TaskStatus.REQUESTED -> {
        options = listOf(
          TaskPriority.ASAP to "Not responded",
          TaskPriority.URGENT to "Didn't agree for follow up",
          TaskPriority.STAT to "Agreed, Follow up not done",
          TaskPriority.ROUTINE to "Follow up done"
        )
      }

      TaskStatus.INPROGRESS -> {

        when(task.task.priority){
          TaskPriority.ROUTINE -> {}
          TaskPriority.URGENT -> {
            //Clicked item from Inprogress tab -> Not agreed for follow-up.
            options = listOf(
              TaskPriority.STAT to "Agreed, Follow up not done",
              TaskPriority.NULL to "Not Agreed, Remove case"
            )
          }
          TaskPriority.ASAP -> {

          }
          TaskPriority.STAT -> {
            //Clicked item from Inprogress tab -> Agreed, Follow up not done section.
            options = listOf(
              TaskPriority.ROUTINE to "Follow up done"
            )
          }
          TaskPriority.NULL -> {}
        }
      }
      TaskStatus.COMPLETED -> {
        options = listOf()
      }
      else -> {

      }
    }

    Column { // Arrange radio buttons and labels vertically
      options.forEach { (priority, label) ->
        Row( // Arrange RadioButton and label horizontally
          verticalAlignment = Alignment.CenterVertically
        ) {
          var selectedRadioColor = Color.Gray
          selectedRadioColor = when(priority){
            TaskPriority.ROUTINE -> Color.Green
            TaskPriority.URGENT -> Color(0xFFFFC800)
            TaskPriority.ASAP -> Color.Red
            TaskPriority.STAT -> Color(0xFFFFC800)
            TaskPriority.NULL -> Color.Gray
          }


          RadioButton(
            selected = selectedPriority.value == priority,
            onClick = { selectedPriority.value = priority },
            colors = RadioButtonColors(
              selectedColor = selectedRadioColor,
              unselectedColor = selectedRadioColor,
              disabledSelectedColor = Color.Gray,
              disabledUnselectedColor = Color.Gray)
          )
          Text(
            text = label,
            modifier = Modifier
              .padding(horizontal = 8.dp)
              .clickable {
                if (priority == TaskPriority.NULL) {
                  //It's removing it from the list
                  selectedPriority.value = TaskPriority.URGENT
                  task.task.status = TaskStatus.REJECTED
                } else {
                  selectedPriority.value = priority
                }
              },
            color = colorResource(id = R.color.optionColor)
          )
        }
      }
    }

    Spacer(modifier = Modifier.height(16.dp))
    Spacer(modifier = Modifier
      .fillMaxWidth()
      .height(1.dp)
      .background(Color.LightGray))
    Spacer(modifier = Modifier.height(16.dp))

    Row(modifier = Modifier
      .height(48.dp)
      .fillMaxWidth()
      .padding(horizontal = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceAround) {

      Button(modifier = Modifier
        .fillMaxHeight()
        .weight(1f) // Equally divide width
        .padding(horizontal = 4.dp),
        colors = ButtonDefaults.buttonColors( // Override default colors
          backgroundColor = Color.White, // Transparent background
          contentColor = Color.White // Set text color to white
        ),
        onClick = {
          onCancel()
          selectedPriority.value = TaskPriority.NULL
          }) {
        Text(text = stringResource(id = R.string.cancel), color = Color(0xFF5B5959))
      }

      Button(modifier = Modifier
        .fillMaxHeight()
        .weight(1f) // Equally divide width
        .padding(horizontal = 4.dp)
        .background(LightColors.primary),
        onClick = {
          onStatusUpdate(selectedPriority.value)
          selectedPriority.value = TaskPriority.NULL
        }) {
        Text(text = stringResource(id = R.string.update_status), color = Color.White)
      }

    }
  }
}

fun getPatientAddress(patient: Patient?): String {
  var addressFinal = ""
  patient?.let { patientItem ->
    patientItem.address.size.let { it > 0 }.let {
      if (it){
        patientItem.address.forEach { address ->
          addressFinal = "${address.city.orEmpty()} ${address.district.orEmpty()} ${address.state.orEmpty()} ${address.text.orEmpty()}"
        }
      }
    }
  }
  return addressFinal
}

@Composable
fun SearchCardItemView(task: TasksViewModel.TaskItem, onSelectTask: (TasksViewModel.TaskItem) -> Unit) {
  Box(
    modifier = Modifier
      .fillMaxWidth()
      .padding(vertical = 8.dp, horizontal = 16.dp)
      .background(Color.White)
      .clickable {
        onSelectTask(task)
        //viewModel.updateTask(task, Task.TaskStatus.INPROGRESS, Task.TaskPriority.ROUTINE)
      }
  ) {
    Card(
      modifier = Modifier
        .fillMaxWidth()
        .background(Color.White),
      elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
      Box(
        modifier = Modifier
          .background(Color.White)
      ) {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp, horizontal = 16.dp)
            .background(Color.White)
        ) {
          var name = ""
          var phone = ""
          if (task.patient?.name?.isNotEmpty() == true && task.patient?.name?.get(0)?.given?.isNotEmpty() == true){
            name = task.patient?.name?.get(0)?.given?.get(0)?.value.toString()
            phone = task.patient?.telecom?.get(0)?.value.toString()
          }
          Row(modifier = Modifier.padding(vertical = 4.dp)) {

            androidx.compose.material.Icon(
              modifier = Modifier.padding(
                vertical = 4.dp,
                horizontal = 4.dp
              ),
              painter = painterResource(id = R.drawable.ic_patient_male),
              contentDescription = FILTER,
              tint = LightColors.primary,
            )

            Column(modifier = Modifier.padding(vertical = 4.dp)) {
              Text(
                modifier = Modifier
                  .padding(vertical = 4.dp, horizontal = 4.dp),
                text = "$name",
                fontSize = 18.sp,
                color = LightColors.primary
              )
              Spacer(modifier = Modifier.height(4.dp))
              Text(
                text = "Phone ${phone}",
                color = colorResource(id = R.color.subTextGrey),
                fontSize = 14.sp,
                modifier = Modifier
                  .padding(horizontal = 4.dp, vertical = 4.dp)
              )
              Spacer(modifier = Modifier.height(4.dp))
              Row(modifier = Modifier.padding(vertical = 4.dp)) {
                var label = ""
                var textColor = Color.Black
                var color = Color.Black

                when(task.task.intent){

                  Task.TaskIntent.PLAN -> {
                    label = "ADD INVESTIGATION"
                    color = Color(0xFFFFF8E0)
                    textColor = Color(0xFFFFC800)
                  }

                  Task.TaskIntent.ORDER -> {
                    label = "URGENT REFERRAL"
                    color = Color(0xFFFFCDD2)
                    textColor = Color(0xFFFF3355)
                  }

                  Task.TaskIntent.PROPOSAL -> {
                    label = "RETAKE PHOTO"
                    color = Color.LightGray
                    textColor = Color.Gray
                  }

                  else -> {
                    label = ""
                    color = Color.LightGray
                    textColor = Color.Gray
                  }
                }

                Text(
                  text = label,
                  color = textColor,
                  fontSize = 14.sp,
                  modifier = Modifier
                    .background(color, shape = MaterialTheme.shapes.small)
                    .padding(horizontal = 4.dp, vertical = 4.dp)
                )
              }
            }
          }
        }
      }
    }
  }
}