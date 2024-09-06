package org.smartregister.fhircore.quest.ui.register.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.smartregister.fhircore.quest.R
import org.smartregister.fhircore.quest.theme.Body16Normal
import org.smartregister.fhircore.quest.theme.Colors
import org.smartregister.fhircore.quest.ui.main.components.FILTER

@Composable
fun EmptyStateSection(isFetchingPatients: Boolean, textLabel : String, icon: Painter, modifier: Modifier=Modifier.padding(36.dp)){
    Column(
        modifier = Modifier
            .background(Colors.TRANSPARENT).padding(horizontal = 16.dp)
            .fillMaxWidth().height(300.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (isFetchingPatients){
                Text(text = stringResource(id = R.string.loading_patients), style = Body16Normal())
            }else{
                Image(modifier = Modifier.padding(vertical = 16.dp, horizontal = 16.dp),
                    painter = icon,
                    contentDescription = FILTER,
                )
                Text(text = textLabel, style = Body16Normal())
            }
        }
    }
}

//@Preview
//@Composable
//fun Preview(){
//    EmptyStateSection(false, "No cases to show, start creating", painterResource(id = R.drawable.ic_patient_male))
//}