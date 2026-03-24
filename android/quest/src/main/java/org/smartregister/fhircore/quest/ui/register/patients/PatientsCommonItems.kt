package org.smartregister.fhircore.quest.ui.register.patients

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.fhir.datacapture.extensions.asStringValue
import org.hl7.fhir.r4.model.Patient
import org.hl7.fhir.r4.model.QuestionnaireResponse
import org.smartregister.fhircore.engine.util.extension.encodeResourceToString
import org.smartregister.fhircore.quest.R
import org.smartregister.fhircore.quest.theme.Colors.BRANDEIS_BLUE
import org.smartregister.fhircore.quest.theme.Colors.CRAYOLA
import org.smartregister.fhircore.quest.theme.Colors.CRAYOLA_LIGHT
import org.smartregister.fhircore.quest.theme.body18Medium
import org.smartregister.fhircore.quest.theme.bodyExtraBold
import org.smartregister.fhircore.quest.theme.bodyNormal
import org.smartregister.fhircore.quest.ui.main.components.FILTER
import org.smartregister.fhircore.quest.util.OpensrpDateUtils.convertToDate
import org.smartregister.fhircore.quest.util.OpensrpDateUtils.getRegistrationDateFromExtension

@Composable
internal fun DraftsItem(
    response: QuestionnaireResponse,
    modifier: Modifier,
    viewModel: RegisterViewModel,
    onEditResponse: (String) -> Unit?,
    onDeleteResponse: (String, Boolean) -> Unit,
) {
    val result = response.item?.firstOrNull()?.item.takeIf { (it?.size ?: 0) >= 1 }
    val title = result?.get(1)?.answer?.firstOrNull()?.value?.asStringValue() ?: "Guest"

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(4.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
        Box(modifier = modifier.background(Color.White)) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
                    .background(Color.White),
            ) {
                Row {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_draft),
                        contentDescription = FILTER,
                        modifier = Modifier.padding(8.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Column {
                        Row {
                            Text(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(end = 8.dp, top = 8.dp),
                                text = title,
                                style = body18Medium().copy(color = CRAYOLA),
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Box(
                                modifier = Modifier.clickable {
                                    val json = response.encodeResourceToString()
                                    onEditResponse(json)
                                    viewModel.deleteIfNotOldDraft(response.id)
                                },
                            ) {
                                Image(
                                    modifier = Modifier.padding(8.dp),
                                    painter = painterResource(id = R.drawable.edit_draft),
                                    contentDescription = FILTER,
                                )
                            }
                            Box(
                                modifier = modifier.clickable {
                                    onDeleteResponse(response.id, true)
                                },
                            ) {
                                Icon(
                                    modifier = Modifier.padding(8.dp),
                                    painter = painterResource(id = R.drawable.ic_delete_draft),
                                    contentDescription = FILTER,
                                )
                            }
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(bottom = 8.dp),
                        ) {
                            Text(
                                text = stringResource(id = R.string.created),
                                style = bodyExtraBold(fontSize = 14.sp).copy(color = CRAYOLA_LIGHT),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = convertToDate(response.meta.lastUpdated),
                                style = bodyNormal(14.sp).copy(color = CRAYOLA_LIGHT),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SyncedPatientCardItem(
    patientData: Patient,
    patient: RegisterViewModel.AllPatientsResourceData,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(4.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
        Box(modifier = Modifier.background(Color.White)) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .background(Color.White),
            ) {
                Row(
                    modifier = Modifier.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_patient_male),
                        contentDescription = FILTER,
                        tint = BRANDEIS_BLUE,
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        val patientName = patientData.name?.firstOrNull()?.given?.firstOrNull()?.value.orEmpty()
                        Text(
                            text = patientName,
                            style = body18Medium(),
                            color = BRANDEIS_BLUE,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        PatientDetailRow(
                            label = stringResource(id = R.string.unique_id_label),
                            value = patientData.identifierFirstRep?.value.takeUnless { it.isNullOrEmpty() }
                                ?: stringResource(id = R.string.not_available),
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        PatientDetailRow(
                            label = stringResource(id = R.string.visited),
                            value = getRegistrationDateFromExtension(patient.patient),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PatientDetailRow(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = bodyExtraBold(fontSize = 14.sp).copy(color = CRAYOLA_LIGHT),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = value,
            style = bodyNormal(14.sp).copy(color = CRAYOLA_LIGHT),
        )
    }
}

@Composable
fun getSyncImageList(imageCount: Int): String =
    stringResource(id = R.string.image_left, imageCount.toString())

@Composable
fun getPatientsCount(patientsCount: Int): String =
    stringResource(id = R.string.patients_left, patientsCount.toString())