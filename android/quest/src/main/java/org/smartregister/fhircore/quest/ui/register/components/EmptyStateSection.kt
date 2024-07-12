package org.smartregister.fhircore.quest.ui.register.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.smartregister.fhircore.engine.ui.theme.LightColors
import org.smartregister.fhircore.engine.ui.theme.SearchHeaderColor
import org.smartregister.fhircore.quest.R
import org.smartregister.fhircore.quest.ui.main.components.FILTER

@Composable
fun EmptyStateSection(isFetchingPatients: Boolean, textLabel : String, icon: Painter, tintColor: Color){
    Column(
        modifier = Modifier
            .background(SearchHeaderColor)
            .fillMaxWidth(),
        verticalArrangement = Arrangement.Center
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth()
                .height(300.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            Column(modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (isFetchingPatients){
                    Text(text = stringResource(id = R.string.loading_patients))
                }else{
                    Box(modifier = Modifier
                        .padding(36.dp)
                        .border(width = 4.dp,
                        shape = androidx.compose.foundation.shape.CircleShape,
                        color = LightColors.primary)) {
                        Icon(
                            modifier = Modifier.padding(
                                vertical = 16.dp,
                                horizontal = 16.dp
                            ),
                            tint = tintColor,
                            painter = icon,
                            contentDescription = FILTER,
                        )
                    }

                    Text(text = textLabel)
                }
            }
        }
    }
}

@Preview
@Composable
fun Preview(){
    EmptyStateSection(false, "No cases to show, start creating", painterResource(id = R.drawable.ic_patient_male), Color.LightGray)
}