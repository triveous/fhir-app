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
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.fhir.datacapture.extensions.asStringValue
import org.hl7.fhir.r4.model.ContactPoint
import org.hl7.fhir.r4.model.Enumerations
import org.hl7.fhir.r4.model.Patient
import org.hl7.fhir.r4.model.QuestionnaireResponse
import org.smartregister.fhircore.engine.util.extension.encodeResourceToString
import org.smartregister.fhircore.engine.util.extension.yearsPassed
import org.smartregister.fhircore.quest.R
import org.smartregister.fhircore.quest.theme.Colors.BRANDEIS_BLUE
import org.smartregister.fhircore.quest.theme.Colors.CRAYOLA
import org.smartregister.fhircore.quest.theme.Colors.CRAYOLA_LIGHT
import org.smartregister.fhircore.quest.theme.Colors.FEMALE_ICON_PINK
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
    // null = sync status not yet checked - show no icon rather than defaulting to "pending".
    isSynced: Boolean? = null,
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
                    val isFemale = patientData.hasGender() &&
                        patientData.gender == Enumerations.AdministrativeGender.FEMALE
                    Icon(
                        painter = painterResource(
                            id = if (isFemale) R.drawable.ic_patient_female else R.drawable.ic_patient_male,
                        ),
                        contentDescription = FILTER,
                        tint = if (isFemale) FEMALE_ICON_PINK else BRANDEIS_BLUE,
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        val fullName = patientData.nameFirstRep.nameAsSingleString
                        val genderLetter = if (patientData.hasGender() &&
                            patientData.gender != Enumerations.AdministrativeGender.NULL
                        ) {
                            patientData.gender.name.first().toString()
                        } else {
                            null
                        }
                        val ageYears = if (patientData.hasBirthDate()) {
                            patientData.birthDate.yearsPassed().toString()
                        } else {
                            null
                        }
                        val nameSuffixParts = listOfNotNull(genderLetter, ageYears)
                        val nameSuffix = if (nameSuffixParts.isNotEmpty()) {
                            ", " + nameSuffixParts.joinToString(", ")
                        } else {
                            ""
                        }
                        Row {
                            Text(
                                text = fullName,
                                style = body18Medium(),
                                color = BRANDEIS_BLUE,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            if (nameSuffix.isNotEmpty()) {
                                Text(
                                    text = nameSuffix,
                                    style = body18Medium(),
                                    color = BRANDEIS_BLUE,
                                    maxLines = 1,
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        PatientDetailRow(
                            label = stringResource(id = R.string.unique_id_label),
                            value = patientData.identifierFirstRep?.value.takeUnless { it.isNullOrEmpty() }
                                ?: stringResource(id = R.string.not_available),
                            valueLetterSpacing = 1.5.sp,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        PatientDetailRow(
                            label = stringResource(id = R.string.visited),
                            value = getRegistrationDateFromExtension(patient.patient),
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        PatientDetailRow(
                            label = stringResource(id = R.string.phone_label),
                            value = patientData.telecom
                                ?.firstOrNull { it.system == ContactPoint.ContactPointSystem.PHONE }
                                ?.value
                                .takeUnless { it.isNullOrEmpty() }
                                ?: stringResource(id = R.string.not_available),
                            valueLetterSpacing = 1.5.sp,
                            trailingIcon = isSynced?.let { synced ->
                                {
                                    Icon(
                                        painter = painterResource(
                                            id = if (synced) R.drawable.ic_done_all else R.drawable.ic_schedule,
                                        ),
                                        contentDescription = stringResource(
                                            id = if (synced) R.string.case_synced else R.string.case_sync_pending,
                                        ),
                                        tint = CRAYOLA_LIGHT,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PatientDetailRow(
    label: String,
    value: String,
    // Figma: labels use Body2Bold (0.2sp tracking); values use Body2 (0.2sp) except Ref ID/Phone,
    // which use Body2Numeric (1.5sp) for the digit strings.
    valueLetterSpacing: TextUnit = 0.2.sp,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = label,
            style = bodyExtraBold(fontSize = 14.sp).copy(color = CRAYOLA_LIGHT, letterSpacing = 0.2.sp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = value,
            style = bodyNormal(14.sp).copy(color = CRAYOLA_LIGHT, letterSpacing = valueLetterSpacing),
            // Matches the Figma row's flex-[1_0_0] value: fills the remaining row width so a
            // trailing icon (sync status) lands flush at the card's end, not hugging the text.
            modifier = Modifier.weight(1f),
        )
        if (trailingIcon != null) {
            Spacer(modifier = Modifier.width(8.dp))
            trailingIcon()
        }
    }
}

@Composable
fun getSyncImageList(imageCount: Int): String =
    stringResource(id = R.string.image_left, imageCount.toString())

@Composable
fun getPatientsCount(patientsCount: Int): String =
    stringResource(id = R.string.patients_left, patientsCount.toString())