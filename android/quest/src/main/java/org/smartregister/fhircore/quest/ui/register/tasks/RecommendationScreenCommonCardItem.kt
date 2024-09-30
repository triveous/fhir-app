package org.smartregister.fhircore.quest.ui.register.tasks

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.MaterialTheme
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.smartregister.fhircore.quest.R
import org.smartregister.fhircore.quest.theme.Colors
import org.smartregister.fhircore.quest.theme.Colors.BRANDEIS_BLUE
import org.smartregister.fhircore.quest.theme.Colors.CRAYOLA_LIGHT
import org.smartregister.fhircore.quest.theme.body14Medium
import org.smartregister.fhircore.quest.theme.body18Medium
import org.smartregister.fhircore.quest.theme.bodyExtraBold
import org.smartregister.fhircore.quest.theme.bodyNormal

/**
 * Created by Jeetesh Surana.
 */

@Composable
fun CardItemViewAllTask(
    viewModel: TasksViewModel,
    task: TasksViewModel.TaskItem,
    onSelectTask: (TasksViewModel.TaskItem) -> Unit
) {
    var name = ""
    var phone = ""
    if (task.patient?.name?.isNotEmpty() == true && task.patient.name?.get(0)?.given?.isNotEmpty() == true) {
        name = task.patient.name?.get(0)?.given?.get(0)?.value.toString()
        phone = task.patient.telecom?.get(0)?.value.toString()
    }
    val taskStatusList = viewModel.getTaskCodeWithValue(task)
    println("CardItemView getTaskStatusList--> $taskStatusList")

    RecommendationItem(name, phone, taskStatusList) {
        onSelectTask(task)
    }
}


@Composable
fun RecommendationItem(
    name: String,
    phone: String,
    taskStatusList: List<Pair<String, String>>?,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(4.dp),
        colors = CardDefaults.cardColors().copy(Color.White, Color.White, Color.White, Color.White),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .background(Color.White)
        ) {
            Row {
                Image(
                    painter = painterResource(id = R.drawable.ic_patient_male),
                    contentDescription = org.smartregister.fhircore.quest.ui.main.components.FILTER
                )
                Column(modifier = Modifier.padding(horizontal = 12.dp)) {
                    Text(
                        text = name, style = body18Medium(), color = BRANDEIS_BLUE
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.Center) {
                        Text(
                            text = stringResource(id = R.string.phone),
                            style = bodyExtraBold(14.sp),
                            color = CRAYOLA_LIGHT
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = phone,
                            style = bodyNormal(14.sp),
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    MultiRecommendationStatus(taskStatusList)
                }
            }
        }
    }
}

@Composable
fun MultiRecommendationStatus(taskStatusList: List<Pair<String, String>>?) {
    val lazyListState = rememberLazyListState()
    LazyRow(
        state = lazyListState,
        contentPadding = PaddingValues(start = 8.dp, top = 8.dp, bottom = 8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(Colors.ANTI_FLASH_WHITE, shape = RoundedCornerShape(4.dp))
    ) {
        items(count = taskStatusList?.size ?: 0) { position ->
            val data = taskStatusList?.get(position)
            Row(modifier = Modifier.padding(end = 8.dp)) {
                val label = data?.second?.uppercase() ?: ""
                var textColor = Color.Black
                var color = Color.Black
                val taskCode = TaskCode.fromCode(data?.first ?: "") ?: ""

                when (taskCode) {
                    TaskCode.ADDITIONAL_INVESTIGATION_NEEDED -> {
                        color = Colors.CORNSILK
                        textColor = Colors.PHILIPPINE_YELLOW
                    }

                    TaskCode.QUIT_HABIT -> {
                        color = Colors.LAVENDER_WEB
                        textColor = Colors.DEEP_LILAC
                    }

                    TaskCode.URGENT_REFER_TO_HOSPITAL -> {
                        color = Colors.LIGHT_RED
                        textColor = Colors.SIZZLING_RED
                    }

                    TaskCode.RETAKE_IMAGE -> {
                        color = Color.LightGray
                        textColor = Color.Gray
                    }

                    else -> {
                        color = Color.LightGray
                        textColor = Color.Gray
                    }
                }

                Text(
                    text = label.uppercase(),
                    color = textColor,
                    style = body14Medium(),
                    modifier = Modifier
                        .background(
                            color, shape = MaterialTheme.shapes.small
                        )
                        .padding(horizontal = 8.dp, vertical = 8.dp)
                )
            }
        }
    }
}

@Composable
fun MultiRecommendationStatusColumn(taskStatusList: List<Pair<String, String>>?) {
    val lazyListState = rememberLazyListState()
    LazyColumn(
        state = lazyListState,
        modifier = Modifier.fillMaxWidth()
    ) {
        items(count = taskStatusList?.size ?: 0) { position ->
            val data = taskStatusList?.get(position)
            Row(modifier = Modifier.padding(bottom = 16.dp)) {
                val label = data?.second?.uppercase() ?: ""
                var textColor = Color.Black
                var color = Color.Black
                val taskCode = TaskCode.fromCode(data?.first ?: "") ?: ""

                when (taskCode) {
                    TaskCode.ADDITIONAL_INVESTIGATION_NEEDED -> {
                        color = Colors.CORNSILK
                        textColor = Colors.PHILIPPINE_YELLOW
                    }

                    TaskCode.QUIT_HABIT -> {
                        color = Colors.LAVENDER_WEB
                        textColor = Colors.DEEP_LILAC
                    }

                    TaskCode.URGENT_REFER_TO_HOSPITAL -> {
                        color = Colors.LIGHT_RED
                        textColor = Colors.SIZZLING_RED
                    }

                    TaskCode.RETAKE_IMAGE -> {
                        color = Color.LightGray
                        textColor = Color.Gray
                    }

                    else -> {
                        color = Color.LightGray
                        textColor = Color.Gray
                    }
                }

                Text(
                    text = label.uppercase(),
                    color = textColor,
                    style = body14Medium(),
                    modifier = Modifier
                        .background(
                            color, shape = MaterialTheme.shapes.small
                        ).fillMaxWidth()
                        .padding(12.dp)
                )
            }
        }
    }
}