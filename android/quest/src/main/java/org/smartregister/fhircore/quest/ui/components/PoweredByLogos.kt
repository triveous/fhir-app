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
package org.smartregister.fhircore.quest.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.smartregister.fhircore.engine.R
import org.smartregister.fhircore.quest.theme.Colors.CRAYOLA_LIGHT

const val POWERED_BY_LOGOS_TAG = "poweredByLogosTag"

/**
 * "Powered by" partner logos footer shared by the Select Site and Login screens. The logo bitmaps
 * live in drawable-xxxhdpi (exported at 4x), so their intrinsic size matches the dp dimensions from
 * the Figma design one-to-one.
 */
@Composable
fun PoweredByLogos(modifier: Modifier = Modifier) {
  Column(modifier = modifier.testTag(POWERED_BY_LOGOS_TAG)) {
    Text(
      text = stringResource(id = R.string.powered_by),
      color = CRAYOLA_LIGHT,
      fontSize = 12.sp,
      letterSpacing = 0.31.sp,
      modifier = Modifier.wrapContentWidth().padding(bottom = 8.dp),
    )
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Image(
        painter =
          painterResource(id = org.smartregister.fhircore.quest.R.drawable.ic_powered_tanuh),
        contentDescription = stringResource(id = R.string.partner_tanuh),
        contentScale = ContentScale.Fit,
        modifier = Modifier.size(54.dp),
      )
      Image(
        painter = painterResource(id = org.smartregister.fhircore.quest.R.drawable.ic_powered_moe),
        contentDescription = stringResource(id = R.string.partner_moe),
        contentScale = ContentScale.Fit,
        modifier = Modifier.width(87.dp).height(58.dp),
      )
      Image(
        painter =
          painterResource(id = org.smartregister.fhircore.quest.R.drawable.ic_powered_iisc_seal),
        contentDescription = stringResource(id = R.string.iisc),
        contentScale = ContentScale.Fit,
        modifier = Modifier.size(44.dp),
      )
      Image(
        painter =
          painterResource(id = org.smartregister.fhircore.quest.R.drawable.ic_powered_artpark),
        contentDescription = stringResource(id = R.string.partner_artpark),
        contentScale = ContentScale.Fit,
        modifier = Modifier.width(99.dp).height(22.dp),
      )
    }
  }
}

@Preview(showBackground = true, backgroundColor = 0xFFFFFFFF)
@Composable
private fun PoweredByLogosPreview() {
  PoweredByLogos()
}
