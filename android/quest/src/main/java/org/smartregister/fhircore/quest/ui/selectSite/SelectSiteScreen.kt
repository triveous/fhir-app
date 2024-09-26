package org.smartregister.fhircore.quest.ui.selectSite

import android.annotation.SuppressLint
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Card
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.contentColorFor
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.smartregister.fhircore.engine.R
import org.smartregister.fhircore.engine.configuration.app.ApplicationConfiguration
import org.smartregister.fhircore.engine.data.remote.selectSite.SelectSite
import org.smartregister.fhircore.engine.ui.components.register.LoaderDialog
import org.smartregister.fhircore.engine.ui.theme.LightColors
import org.smartregister.fhircore.engine.ui.theme.LoginFieldBackgroundColor
import org.smartregister.fhircore.engine.util.extension.appVersion
import org.smartregister.fhircore.quest.BuildConfig
import org.smartregister.fhircore.quest.theme.Colors.BRANDEIS_BLUE
import org.smartregister.fhircore.quest.theme.Colors.CRAYOLA_LIGHT
import org.smartregister.fhircore.quest.theme.Theme.getBackground
import org.smartregister.fhircore.quest.theme.body14Medium
import org.smartregister.fhircore.quest.theme.body18Medium
import org.smartregister.fhircore.quest.theme.bodyBold
import org.smartregister.fhircore.quest.theme.bodyMedium
import org.smartregister.fhircore.quest.ui.login.APP_LOGO_TAG
import org.smartregister.fhircore.quest.ui.login.APP_NAME_TEXT_TAG
import org.smartregister.fhircore.quest.ui.login.LOGIN_BUTTON_TAG
import org.smartregister.fhircore.quest.ui.selectSite.viewModel.SelectSiteViewModel

@Composable
fun SelectSiteScreen(
    selectSiteScreenActivity: SelectSiteScreenActivity? = null,
    siteViewModel: SelectSiteViewModel? = null,
    applicationConfiguration: ApplicationConfiguration,
    onContinueButtonClicked: () -> Unit,
    @SuppressLint("ModifierParameter") modifier: Modifier = Modifier,
    showProgressBar: Boolean = false,
    appVersionPair: Pair<Int, String>? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val (versionCode, versionName) = remember { appVersionPair ?: context.appVersion() }

    Surface(
        modifier =
        modifier
            .fillMaxSize()
            .scrollable(orientation = Orientation.Vertical, state = rememberScrollState()),
        color = getBackground(),
        contentColor = contentColorFor(backgroundColor = Color.DarkGray),
    ) {
        Box {
            Column(
                modifier =
                modifier
                    .padding(horizontal = 16.dp, vertical = 32.dp)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = modifier.padding(4.dp), verticalArrangement = Arrangement.Top) {
                    // TODO Add configurable logo. Images to be downloaded from server
                    if (applicationConfiguration.loginConfig.showLogo) {
                        Image(
                            painter = painterResource(org.smartregister.fhircore.quest.R.drawable.ic_logo),
                            contentDescription = stringResource(id = R.string.app_logo),
                            modifier =
                            modifier
                                .align(Alignment.CenterHorizontally)
                                .requiredHeight(54.dp)
                                .requiredWidth(54.dp)
                                .testTag(APP_LOGO_TAG),
                        )
                    }
                    Spacer(modifier = modifier.height(16.dp))
                    if (
                        applicationConfiguration.appTitle.isNotEmpty() &&
                        applicationConfiguration.loginConfig.showAppTitle
                    ) {
                        Text(
                            style = bodyBold(30.sp),
                            text = stringResource(R.string.appname),
                            modifier = modifier
                                .wrapContentWidth()
                                .align(Alignment.CenterHorizontally)
                                .testTag(APP_NAME_TEXT_TAG),
                        )
                    }

                    Spacer(modifier = modifier.height(32.dp))
                    Text(
                        text = stringResource(R.string.select_your_site),
                        style = bodyMedium(20.sp),
                        modifier = modifier.align(Alignment.CenterHorizontally)
                    )

                    Spacer(modifier = modifier.height(24.dp))
                    val selectSiteList by siteViewModel!!.selectSiteList.observeAsState(initial = arrayListOf())

                    Box(contentAlignment = Alignment.Center, modifier = modifier.fillMaxWidth()) {
                        SiteSelectionDropdown(
                            showProgressBar,
                            modifier,
                            applicationConfiguration,
                            items = selectSiteList,
                            selectedItem = siteViewModel?.selectedSite?.value,
                            onItemSelected = { siteViewModel?.selectedSite?.value = it },
                            onContinue = {
                                scope.launch {
                                    siteViewModel?.selectedSite?.value?.let {
                                        siteViewModel.setSelectSite(it)
                                    }
                                    delay(300)
                                    onContinueButtonClicked()
                                }
                            }
                        )

                        if (showProgressBar) {
                            CircularProgressIndicator(
                                modifier = modifier
                                    .align(Alignment.Center)
                                    .size(18.dp),
                                strokeWidth = 1.6.dp,
                                color = LightColors.primary,
                            )
                        }
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = modifier
                        .fillMaxWidth()
                        .padding(vertical = 20.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Column {
                        Text(
                            text = stringResource(id = R.string.powered_by),
                            style = body14Medium(),
                            color = CRAYOLA_LIGHT,
                            modifier = modifier
                                .wrapContentWidth()
                                .padding(bottom = 8.dp),
                        )
                        Row {
                            Image(
                                painter = painterResource(id = org.smartregister.fhircore.quest.R.drawable.ic_iisc),
                                contentDescription = stringResource(id = R.string.powered_by),
                                modifier = Modifier
                                    .width(43.dp)
                                    .height(35.dp)
                            )
                            Text(
                                text = stringResource(id = R.string.iisc),
                                style = body18Medium(),
                                color = BRANDEIS_BLUE,
                                modifier = modifier
                                    .wrapContentWidth()
                                    .fillMaxHeight()
                                    .align(Alignment.Bottom),
                            )
                        }
                    }

                    Text(
                        text = stringResource(id = R.string.app_version, BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME),
                        style = body14Medium(),
                        color = CRAYOLA_LIGHT,
                        fontWeight = FontWeight(400),
                        modifier = modifier
                            .wrapContentWidth()
                            .padding(bottom = 8.dp)
                            .fillMaxHeight()
                            .align(Alignment.Bottom),
                    )
                }
            }
            // Show loader
            val observeAsState = siteViewModel?.isLoading?.observeAsState(initial = false)
            if (observeAsState?.value==true) {
                LoaderDialog(modifier = modifier, dialogMessage = stringResource(R.string.loading))
            }
            val error = siteViewModel?.mError?.observeAsState(initial = "")
            // show error
            if (!error?.value.isNullOrEmpty()){
                Toast.makeText(selectSiteScreenActivity, error?.value, Toast.LENGTH_SHORT).show()
            }
        }

    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SiteSelectionDropdown(
    showProgressBar: Boolean,
    modifier: Modifier = Modifier,
    applicationConfiguration: ApplicationConfiguration,
    items: List<SelectSite>?,
    selectedItem: SelectSite?,
    onItemSelected: (SelectSite) -> Unit,
    onContinue: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val bringIntoViewRequester = remember { BringIntoViewRequester() }

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = 1.dp,
        shape = RoundedCornerShape(4.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 20.dp)
        ) {
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = !expanded }
            ) {
                OutlinedTextField(
                    value = selectedItem?.name ?: "",
                    onValueChange = {},
                    readOnly = true,
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor()

                )

                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    modifier = Modifier.background(Color.White)
                ) {
                    items?.forEach { item ->
                        androidx.compose.material3.DropdownMenuItem(
                            colors = MenuDefaults.itemColors().copy(
                                leadingIconColor = Color.White,
                                trailingIconColor = Color.White,
                                disabledLeadingIconColor = Color.White,
                                disabledTrailingIconColor = Color.White
                            ),
                            text = { Text(text = item.name?:"", textAlign = TextAlign.Start) },
                            onClick = {
                                onItemSelected(item)
                                expanded = false
                            }
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                colors =
                ButtonDefaults.buttonColors(
                    backgroundColor = MaterialTheme.colors.primaryVariant,
                    disabledContentColor =
                    if (applicationConfiguration.useDarkTheme) {
                        LoginFieldBackgroundColor
                    } else {
                        Color.Gray
                    },
                    contentColor = Color.White,
                ),
                onClick = onContinue,
                modifier =
                modifier
                    .fillMaxWidth()
                    .bringIntoViewRequester(bringIntoViewRequester)
                    .testTag(LOGIN_BUTTON_TAG),
                elevation = null,
            ) {
                Text(
                    text = if (!showProgressBar) stringResource(id = R.string.btn_continue) else "",
                    modifier = modifier.padding(8.dp),
                )
            }
        }
    }
}