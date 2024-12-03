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

package org.smartregister.fhircore.quest.ui.register.profile

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.AlertDialog
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PermDeviceInformation
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.smartregister.fhircore.engine.domain.model.ToolBarHomeNavigation
import org.smartregister.fhircore.quest.BuildConfig
import org.smartregister.fhircore.quest.R
import org.smartregister.fhircore.quest.theme.Colors
import org.smartregister.fhircore.quest.theme.Colors.CRAYOLA_LIGHT
import org.smartregister.fhircore.quest.theme.Theme.getBackground
import org.smartregister.fhircore.quest.theme.bodyBold
import org.smartregister.fhircore.quest.theme.bodyMedium
import org.smartregister.fhircore.quest.theme.bodyNormal
import org.smartregister.fhircore.quest.ui.login.CHANGE_PIN__DIALOG
import org.smartregister.fhircore.quest.ui.login.PASSWORD_FORGOT_DIALOG
import org.smartregister.fhircore.quest.ui.main.AppMainViewModel
import org.smartregister.fhircore.quest.ui.main.components.DRAWER_MENU
import org.smartregister.fhircore.quest.ui.main.components.TOP_ROW_ICON_TEST_TAG
import org.smartregister.fhircore.quest.ui.main.components.TopScreenSection
import org.smartregister.fhircore.quest.ui.register.patients.RegisterEvent
import org.smartregister.fhircore.quest.ui.register.patients.RegisterUiState
import org.smartregister.fhircore.quest.ui.register.patients.RegisterViewModel
import org.smartregister.fhircore.quest.ui.register.patients.TOP_REGISTER_SCREEN_TEST_TAG


@Composable
fun ProfileSectionScreen(
    modifier: Modifier = Modifier,
    viewModel: RegisterViewModel,
    appMainViewModel: AppMainViewModel,
    onEvent: (RegisterEvent) -> Unit,
    registerUiState: RegisterUiState,
    searchText: MutableState<String>,
    userName: String = "",
    onBackPressed: () -> Unit,
    onClickChangeLanguage:() -> Unit
) {

    val userNameText = viewModel.getUserName()
    var showForgotPasswordDialog by remember { mutableStateOf(false) }
    var showChangePinDialog by remember { mutableStateOf(false) }
    val selectedSiteName = viewModel.sharedPreferencesHelper.getSiteName() ?: ""

    Scaffold(
        modifier = modifier
            .background(getBackground())
            .fillMaxSize(),
        topBar = {
            Column(
                modifier = modifier
                    .background(Color.White)
                    .fillMaxWidth()
            ) {
                TopScreenSection(
                    modifier = modifier.testTag(TOP_REGISTER_SCREEN_TEST_TAG),
                    title = stringResource(id = org.smartregister.fhircore.engine.R.string.profile),
                    onSync = {},
                    toolBarHomeNavigation = ToolBarHomeNavigation.NAVIGATE_BACK,
                ) { event ->
                    onBackPressed()
                }
            }
        },

        ) { innerPadding ->

        if (showForgotPasswordDialog) {
            ForgotPasswordDialog(
                onDismissDialog = { showForgotPasswordDialog = false },
            )
        }

        if (showChangePinDialog) {
            ChangePinDialog(
                onDismissDialog = { showChangePinDialog = false },
            ){
                showChangePinDialog = false
                viewModel.logout()
            }
        }

        Box {

            Box(
                modifier = modifier
                    .padding(innerPadding)
                    .fillMaxSize()
                    .padding(top = 40.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxSize()
                ) {
                    //Spacer(modifier = Modifier.height(48.dp))
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Image(
                            painter = painterResource(R.drawable.ic_profile), // Replace with your profile picture
                            modifier = Modifier
                                .size(84.dp)
                                .clip(CircleShape)
                                .padding(all = 4.dp),
                            contentDescription = null,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = userNameText,
                            style = bodyBold(fontSize = 18.sp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            color = CRAYOLA_LIGHT,
                            text = stringResource(id = R.string.profile_username, userNameText),
                            style = bodyNormal(16.sp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 40.dp)) {
                            Text(
                                color = CRAYOLA_LIGHT,
                                text = selectedSiteName,
                                style = bodyNormal(16.sp)
                            .copy(textAlign = TextAlign.Center))
                        }
                        Spacer(modifier = Modifier.height(20.dp))
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Card(
                            shape = RoundedCornerShape(4.dp),
                            colors = CardDefaults.cardColors(containerColor = Color.White),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(
                                    horizontal = 16.dp,
                                    vertical = 4.dp
                                ), // Set corner radius here
                            elevation = CardDefaults.cardElevation(2.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        showChangePinDialog = true
                                    }
                                    .padding(horizontal = 16.dp, vertical = 16.dp)
                                    .background(Color.White)) {
                                Image(
                                    painter = painterResource(id = R.drawable.ic_change_pin),
                                    contentDescription = DRAWER_MENU,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = stringResource(id = R.string.change_pin),
                                    style = bodyMedium(fontSize = 18.sp)
                                )
                            }
                        }

                        Card(
                            shape = RoundedCornerShape(4.dp),
                            colors = CardDefaults.cardColors(containerColor = Color.White),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(
                                    horizontal = 16.dp,
                                    vertical = 4.dp
                                ), // Set corner radius here
                            elevation = CardDefaults.cardElevation(2.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onClickChangeLanguage()
                                    }
                                    .padding(horizontal = 16.dp, vertical = 16.dp)
                                    .background(Color.White)) {
                                Image(
                                    painter = painterResource(id = R.drawable.ic_change_language),
                                    contentDescription = DRAWER_MENU,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = stringResource(id = R.string.change_language),
                                    style = bodyMedium(fontSize = 18.sp)
                                )
                            }
                        }

                        Card(
                            shape = RoundedCornerShape(4.dp),
                            colors = CardDefaults.cardColors(containerColor = Color.White),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(
                                    horizontal = 16.dp,
                                    vertical = 4.dp
                                ), // Set corner radius here
                            elevation = CardDefaults.cardElevation(2.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        showForgotPasswordDialog = true
                                    }
                                    .padding(horizontal = 16.dp, vertical = 16.dp)
                                    .background(Color.White)) {
                                Image(
                                    painter = painterResource(id = R.drawable.ic_lock),
                                    contentDescription = DRAWER_MENU,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = stringResource(id = R.string.change_password),
                                    style = bodyMedium(fontSize = 18.sp)
                                )
                            }
                        }

                        Card(
                            shape = RoundedCornerShape(4.dp),
                            colors = CardDefaults.cardColors(containerColor = Color.White),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(
                                    horizontal = 16.dp,
                                    vertical = 4.dp
                                ), // Set corner radius here
                            elevation = CardDefaults.cardElevation(2.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        viewModel.logout()
                                    }
                                    .padding(horizontal = 16.dp, vertical = 16.dp)
                                    .background(Color.White)) {
                                Image(
                                    painter = painterResource(id = R.drawable.ic_logout),
                                    contentDescription = DRAWER_MENU,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = stringResource(id = R.string.logout),
                                    style = bodyMedium(fontSize = 18.sp),
                                    color = Colors.SIZZLING_RED
                                )
                            }
                        }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 16.dp)
                        ) {
                            Icon(
                                Icons.Filled.PermDeviceInformation,
                                contentDescription = DRAWER_MENU,
                                tint = Color.Gray,
                                modifier = modifier.testTag(TOP_ROW_ICON_TEST_TAG),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = stringResource(
                                id = org.smartregister.fhircore.engine.R.string.app_version,
                                BuildConfig.VERSION_CODE,
                                BuildConfig.VERSION_NAME)
                            )
                        }
                    }}
                }
            }
        }
    }
}

@Composable
fun ForgotPasswordDialog(
    onDismissDialog: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AlertDialog(
        onDismissRequest = onDismissDialog,
        title = {
            androidx.compose.material.Text(
                text = stringResource(org.smartregister.fhircore.engine.R.string.forgot_password_title),
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
            )
        },
        text = {
            androidx.compose.material.Text(
                text = stringResource(org.smartregister.fhircore.engine.R.string.call_supervisor),
                fontSize = 16.sp
            )
        },
        buttons = {
            Row(
                modifier = modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                androidx.compose.material.Text(
                    text = stringResource(org.smartregister.fhircore.engine.R.string.cancel),
                    modifier = modifier
                        .padding(horizontal = 10.dp)
                        .clickable { onDismissDialog() },
                )
                androidx.compose.material.Text(
                    color = MaterialTheme.colors.primary,
                    text = stringResource(org.smartregister.fhircore.engine.R.string.ok),
                    modifier = modifier
                        .padding(horizontal = 10.dp)
                        .clickable {
                            onDismissDialog()
                            //forgotPassword()
                        },
                )
            }
        },
        modifier = Modifier.testTag(PASSWORD_FORGOT_DIALOG),
    )
}


@Composable
fun ChangePinDialog(
    onDismissDialog: () -> Unit,
    modifier: Modifier = Modifier,
    onLogout: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismissDialog,
        title = {
            androidx.compose.material.Text(
                text = stringResource(org.smartregister.fhircore.engine.R.string.change_pin_title),
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
            )
        },
        text = {
            androidx.compose.material.Text(
                text = stringResource(org.smartregister.fhircore.engine.R.string.change_pin_desc),
                fontSize = 16.sp
            )
        },
        buttons = {
            Row(
                modifier = modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                androidx.compose.material.Text(
                    text = stringResource(org.smartregister.fhircore.engine.R.string.cancel),
                    modifier = modifier
                        .padding(horizontal = 10.dp)
                        .clickable { onDismissDialog() },
                )
                androidx.compose.material.Text(
                    color = MaterialTheme.colors.primary,
                    text = stringResource(org.smartregister.fhircore.engine.R.string.logout),
                    modifier = modifier
                        .padding(horizontal = 10.dp)
                        .clickable {
                            onLogout()
                        },
                )
            }
        },
        modifier = Modifier.testTag(CHANGE_PIN__DIALOG),
    )
}