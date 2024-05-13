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

package org.smartregister.fhircore.quest.ui.register

import android.os.Bundle
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.AlertDialog
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Tab
import androidx.compose.material.TabRow
import androidx.compose.material.TextButton
import androidx.compose.material.TextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.paging.compose.LazyPagingItems
import com.google.android.fhir.datacapture.extensions.asStringValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.smartregister.fhircore.engine.R
import org.smartregister.fhircore.engine.configuration.navigation.NavigationMenuConfig
import org.smartregister.fhircore.engine.configuration.register.NoResultsConfig
import org.smartregister.fhircore.engine.domain.model.ResourceData
import org.smartregister.fhircore.engine.domain.model.ToolBarHomeNavigation
import org.smartregister.fhircore.engine.ui.components.register.LoaderDialog
import org.smartregister.fhircore.engine.ui.components.register.RegisterHeader
import org.smartregister.fhircore.engine.ui.theme.LightColors
import org.smartregister.fhircore.engine.ui.theme.SearchHeaderColor
import org.smartregister.fhircore.engine.util.annotation.PreviewWithBackgroundExcludeGenerated
import org.smartregister.fhircore.engine.util.extension.encodeResourceToString
import org.smartregister.fhircore.engine.util.extension.extractLogicalIdUuid
import org.smartregister.fhircore.quest.event.ToolbarClickEvent
import org.smartregister.fhircore.quest.ui.main.AppMainViewModel
import org.smartregister.fhircore.quest.ui.main.components.FILTER
import org.smartregister.fhircore.quest.ui.main.components.TopScreenSection
import org.smartregister.fhircore.quest.ui.questionnaire.QuestionnaireActivity.Companion.QUESTIONNAIRE_RESPONSE_PREFILL
import org.smartregister.fhircore.quest.ui.register.components.RegisterCardList
import org.smartregister.fhircore.quest.ui.shared.components.ExtendedFab
import org.smartregister.fhircore.quest.util.extensions.handleClickEvent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

const val NO_REGISTER_VIEW_COLUMN_TEST_TAG = "noRegisterViewColumnTestTag"
const val NO_REGISTER_VIEW_TITLE_TEST_TAG = "noRegisterViewTitleTestTag"
const val NO_REGISTER_VIEW_MESSAGE_TEST_TAG = "noRegisterViewMessageTestTag"
const val NO_REGISTER_VIEW_BUTTON_TEST_TAG = "noRegisterViewButtonTestTag"
const val NO_REGISTER_VIEW_BUTTON_ICON_TEST_TAG = "noRegisterViewButtonIconTestTag"
const val NO_REGISTER_VIEW_BUTTON_TEXT_TEST_TAG = "noRegisterViewButtonTextTestTag"
const val REGISTER_CARD_TEST_TAG = "registerCardListTestTag"
const val FIRST_TIME_SYNC_DIALOG = "firstTimeSyncTestTag"
const val FAB_BUTTON_REGISTER_TEST_TAG = "fabTestTag"
const val TOP_REGISTER_SCREEN_TEST_TAG = "topScreenTestTag"

fun convertToDate(input: Date): String {
  val outputFormat = SimpleDateFormat("dd MMMM yyyy", Locale.getDefault())

  // Format the date to the desired output format
  return outputFormat.format(input)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RegisterScreen(
  modifier: Modifier = Modifier,
  openDrawer: (Boolean) -> Unit,
  viewModel : RegisterViewModel,
  appMainViewModel: AppMainViewModel,
  onEvent: (RegisterEvent) -> Unit,
  registerUiState: RegisterUiState,
  searchText: MutableState<String>,
  currentPage: MutableState<Int>,
  pagingItems: LazyPagingItems<ResourceData>,
  navController: NavController,
  toolBarHomeNavigation: ToolBarHomeNavigation = ToolBarHomeNavigation.OPEN_DRAWER,
) {
  val lazyListState: LazyListState = rememberLazyListState()


  Scaffold(
    modifier = modifier.background(Color.White),
    topBar = {
      Column(modifier = modifier.background(SearchHeaderColor),) {

        // Top section has toolbar and a results counts view
        val filterActions = registerUiState.registerConfiguration?.registerFilter?.dataFilterActions
        TopScreenSection(
          modifier = modifier.testTag(TOP_REGISTER_SCREEN_TEST_TAG),
          title = registerUiState.screenTitle,
          searchText = searchText.value,
          filteredRecordsCount = registerUiState.filteredRecordsCount,
          searchPlaceholder = registerUiState.registerConfiguration?.searchBar?.display,
          toolBarHomeNavigation = toolBarHomeNavigation,
          onSync = appMainViewModel::onEvent,
          onSearchTextChanged = { searchText ->
            onEvent(RegisterEvent.SearchRegister(searchText = searchText))
          },
          isFilterIconEnabled = filterActions?.isNotEmpty() ?: false,
        ) { event ->
          when (event) {
            ToolbarClickEvent.Navigate ->
              when (toolBarHomeNavigation) {
                ToolBarHomeNavigation.OPEN_DRAWER -> openDrawer(true)
                ToolBarHomeNavigation.NAVIGATE_BACK -> navController.popBackStack()
              }
            ToolbarClickEvent.FilterData -> {
              onEvent(RegisterEvent.ResetFilterRecordsCount)
              filterActions?.handleClickEvent(navController)
            }
          }
        }
        // Only show counter during search
        if (searchText.value.isNotEmpty()) RegisterHeader(resultCount = pagingItems.itemCount)

        registerUiState.registerConfiguration?.noResults?.let { noResultConfig ->

          Box {
            NoRegisterDataView(
              modifier = modifier,
              viewModel = viewModel,
              noResults = noResultConfig
            ) {
              noResultConfig.actionButton?.actions?.handleClickEvent(navController)
            }
          }
        }
      }

    },

    floatingActionButton = {
      val fabActions = registerUiState.registerConfiguration?.fabActions
      if (!fabActions.isNullOrEmpty() && fabActions.first().visible) {
        ExtendedFab(
          modifier = modifier.testTag(FAB_BUTTON_REGISTER_TEST_TAG),
          fabActions = fabActions,
          navController = navController,
          lazyListState = lazyListState,
        )
      }
    },
  ) { innerPadding ->

    Box(modifier = modifier.padding(innerPadding)) {
      if (registerUiState.isFirstTimeSync) {
        val isSyncUpload = registerUiState.isSyncUpload.collectAsState(initial = false).value
        LoaderDialog(
          modifier = modifier.testTag(FIRST_TIME_SYNC_DIALOG),
          percentageProgressFlow = registerUiState.progressPercentage,
          dialogMessage =
            stringResource(
              id = if (isSyncUpload) R.string.syncing_up else R.string.syncing_down,
            ),
          showPercentageProgress = true,
        )
      }
      if (
        registerUiState.totalRecordsCount > 0 &&
          registerUiState.registerConfiguration?.registerCard != null
      ) {
        RegisterCardList(
          modifier = modifier.testTag(REGISTER_CARD_TEST_TAG),
          registerCardConfig = registerUiState.registerConfiguration.registerCard,
          pagingItems = pagingItems,
          navController = navController,
          lazyListState = lazyListState,
          onEvent = onEvent,
          registerUiState = registerUiState,
          currentPage = currentPage,
          showPagination = searchText.value.isEmpty(),
        )
      } else {

        registerUiState.registerConfiguration?.noResults?.let { noResultConfig ->

          Box (modifier = modifier
            .background(SearchHeaderColor))
          {
            val tabTitles = listOf("ALL PATIENTS", "DRAFTS", "UN-SYNCED")
            val pagerState = rememberPagerState(pageCount = { 3 }, initialPage = 0)

            val patients by viewModel.patientsStateFlow.collectAsState()
            val savedRes by viewModel.savedDraftResponse.collectAsState()
            val unSynced by viewModel.unSyncedStateFlow.collectAsState()
            var deleteDraftId by remember { mutableStateOf("") }
            var showDeleteDialog by remember { mutableStateOf(false) }


            TabRow(
              modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
              backgroundColor = SearchHeaderColor,
              selectedTabIndex = pagerState.currentPage,
              contentColor = SearchHeaderColor,
            ) {
              tabTitles.forEachIndexed { index, title ->
                Tab(
                  text = { Text(title, color = Color.Gray, fontSize = 14.sp) },
                  selected = pagerState.currentPage == index,
                  selectedContentColor = SearchHeaderColor,
                  modifier = Modifier
                    .clip(RoundedCornerShape(24.dp))
                    .background(
                      if (pagerState.currentPage == index) Color.White else SearchHeaderColor
                    ),
                  onClick = {
                    CoroutineScope(Dispatchers.IO).launch {
                      pagerState.scrollToPage(index)
                    }
                  }
                )
              }
            }
            HorizontalPager(state = pagerState) {


              // Content for each tab (your fragment content goes here)
              tabTitles.forEach { title ->
                if(pagerState.currentPage == 0){
                  Box(modifier = modifier
                    .padding(top = 64.dp, start = 16.dp, end = 16.dp)
                    .fillMaxHeight()
                    .fillMaxWidth()
                    .background(SearchHeaderColor)
                  ) {

                    Box(modifier = modifier
                      .fillMaxHeight()
                      .background(SearchHeaderColor)
                      .fillMaxWidth()) {
                      LazyColumn {
                        items(patients) { patient ->
                          Card(
                            modifier = Modifier
                              .fillMaxWidth()
                              .padding(vertical = 4.dp)
                              .background(Color.White),
                            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                          ) {
                            Box(
                              modifier = modifier
                                .background(Color.White)
                            ) {
                              Column(
                                modifier = Modifier
                                  .fillMaxWidth()
                                  .padding(vertical = 8.dp, horizontal = 16.dp)
                                  .background(Color.White)
                              ) {
                                Row(modifier = modifier.padding(vertical = 4.dp)) {
                                  androidx.compose.material.Icon(
                                    modifier = Modifier.padding(horizontal = 4.dp),
                                    painter = painterResource(id = org.smartregister.fhircore.quest.R.drawable.patient_icon),
                                    contentDescription = FILTER,
                                    tint =  LightColors.primary
                                  )
                                  Text(
                                    modifier = Modifier
                                      .weight(1f)
                                      .padding(vertical = 4.dp, horizontal = 4.dp),
                                    text = patient.name.firstOrNull()?.given?.firstOrNull()?.value ?: "",
                                    style = MaterialTheme.typography.h6,
                                    color = LightColors.primary
                                  )
                                  Spacer(modifier = Modifier.height(16.dp))
                                  //Text(text = "Sync: ${patient.}")
                                }

                                Row(modifier = modifier.padding(vertical = 4.dp)) {
                                  Box(modifier = modifier.padding(vertical = 8.dp, horizontal = 36.dp)) {
                                    Text(text = "Visited ${convertToDate(patient.meta.lastUpdated)}")
                                  }
                                }
                              }
                            }
                          }
                        }
                      }
                    }
                  }
                }else if(pagerState.currentPage == 1){
                  if (savedRes.isNotEmpty()){
                    if (showDeleteDialog){
                      DeleteRecordDialog(
                        onDismiss = {
                          deleteDraftId = ""
                          showDeleteDialog = false
                        },
                        onConfirm = {
                          viewModel.softDeleteDraft(deleteDraftId)
                          deleteDraftId = ""
                          showDeleteDialog = false
                        },
                        onCancel = {
                          deleteDraftId = ""
                          showDeleteDialog = false
                        }
                      )
                    }

                    Box(modifier = modifier
                      .padding(top = 64.dp, start = 16.dp, end = 16.dp)
                      .fillMaxHeight()
                      .fillMaxWidth()
                      .background(SearchHeaderColor)
                    ) {
                      Box(modifier = modifier
                        .fillMaxHeight()
                        .background(SearchHeaderColor)
                        .fillMaxWidth()) {
                        LazyColumn {
                          items(savedRes) { response ->
                            Box(
                              modifier = modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp)
                                .background(Color.White)
                            ) {
                              Card(
                                modifier = Modifier
                                  .fillMaxWidth()
                                  .background(Color.White),
                                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                              ) {
                                Box(
                                  modifier = modifier
                                    .background(Color.White)
                                ) {
                                  Column(
                                    modifier = Modifier
                                      .fillMaxWidth()
                                      .padding(vertical = 16.dp, horizontal = 16.dp)
                                      .background(Color.White)
                                  ) {
                                    Row(modifier = modifier.padding(vertical = 4.dp)) {

                                      androidx.compose.material.Icon(
                                        modifier = Modifier.padding(vertical = 4.dp, horizontal = 4.dp),
                                        painter = painterResource(id = org.smartregister.fhircore.quest.R.drawable.ic_draft),
                                        contentDescription = FILTER,
                                      )

                                      Text(
                                        modifier = Modifier
                                          .weight(1f)
                                          .padding(vertical = 4.dp, horizontal = 8.dp),
                                        text = response.item[0].item[0].answer[0].value.asStringValue(),
                                        style = MaterialTheme.typography.h6,
                                        color = Color.DarkGray
                                      )
                                      Spacer(modifier = Modifier.height(16.dp))

                                      Box(
                                        modifier = Modifier.clickable {
                                          val json = response.encodeResourceToString()
                                          registerUiState.registerConfiguration?.noResults?.let { noResultConfig ->
                                            val bundle = Bundle()
                                            bundle.putString(QUESTIONNAIRE_RESPONSE_PREFILL, json)
                                            noResultConfig.actionButton?.actions?.handleClickEvent(navController, bundle = bundle)
                                          }
                                        }
                                      ) {
                                        Icon(
                                          modifier = Modifier.padding(
                                            vertical = 4.dp,
                                            horizontal = 8.dp
                                          ),
                                          painter = painterResource(id = org.smartregister.fhircore.quest.R.drawable.edit_draft),
                                          contentDescription = FILTER,
                                        )
                                      }
                                      Box(
                                        modifier = Modifier.clickable {

                                          deleteDraftId = response.id.extractLogicalIdUuid()
                                          showDeleteDialog = true

                                        }
                                      ) {
                                        androidx.compose.material.Icon(
                                          modifier = Modifier.padding(
                                            vertical = 4.dp,
                                            horizontal = 8.dp
                                          ),
                                          painter = painterResource(id = org.smartregister.fhircore.quest.R.drawable.ic_delete_draft),
                                          contentDescription = FILTER,
                                        )
                                      }
                                    }

                                    Row(modifier = modifier.padding(vertical = 8.dp, horizontal = 36.dp)) {
                                      Text(text = "Created: ${convertToDate(response.meta.lastUpdated)}")
                                    }
                                  }
                                }
                              }
                            }
                          }
                        }
                      }
                    }
                  }else{
                    Box(modifier = modifier
                      .fillMaxHeight()
                      .background(SearchHeaderColor)
                      .padding(top = 48.dp)
                      .fillMaxWidth()) {

                      Box(modifier = modifier.padding(horizontal = 16.dp)) {
                        Text(text = "No draft patients to show.")
                      }
                    }
                  }
                }else if(pagerState.currentPage == 2) {

                  if (unSynced.isNotEmpty()) {

                    Box(
                      modifier = modifier
                        .padding(top = 64.dp, start = 16.dp, end = 16.dp)
                        .fillMaxHeight()
                        .fillMaxWidth()
                        .background(SearchHeaderColor)
                    ) {
                      Box(
                        modifier = modifier
                          .background(SearchHeaderColor)
                          .fillMaxWidth()
                      ) {
                        LazyColumn {
                          items(unSynced) { patient ->
                            Box(
                              modifier = modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp)
                                .background(Color.White)
                            ) {
                              Card(
                                modifier = Modifier
                                  .fillMaxWidth()
                                  .background(Color.White),
                                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                              ) {
                                Box(
                                  modifier = modifier
                                    .background(Color.White)
                                ) {
                                  Column(
                                    modifier = Modifier
                                      .fillMaxWidth()
                                      .padding(vertical = 16.dp, horizontal = 16.dp)
                                      .background(Color.White)
                                  ) {
                                    Row(modifier = modifier.padding(vertical = 4.dp)) {

                                      androidx.compose.material.Icon(
                                        modifier = Modifier.padding(
                                          vertical = 4.dp,
                                          horizontal = 4.dp
                                        ),
                                        painter = painterResource(id = com.google.android.fhir.datacapture.R.drawable.ic_document_file),
                                        contentDescription = FILTER,
                                        tint = Color.Black,
                                      )

                                      Text(
                                        modifier = Modifier
                                          .weight(1f)
                                          .padding(vertical = 4.dp, horizontal = 4.dp),
                                        text = patient.name,
                                        style = MaterialTheme.typography.h6,
                                        color = LightColors.primary
                                      )
                                      Spacer(modifier = Modifier.height(16.dp))

                                      Text(text = "Sync: Un-Synced")

                                    }

                                    Row(modifier = modifier.padding(vertical = 4.dp)) {
                                      Text(text = "Gender: ${patient.gender}")
                                      //Text(text = patient.gender.name)
                                    }
                                    /*if (patient.birthDate != null) {
                                      Row {
                                        Text(text = "DoB: ")
                                        Text(text = patient.birthDate.toString())
                                      }
                                    }*/
                                  }
                                }
                              }
                            }
                          }
                        }
                      }
                    }
                }/*else {
                    Box(
                      modifier = modifier
                        .background(SearchHeaderColor)
                        .padding(top = 48.dp)
                        .fillMaxWidth()
                    ) {

                      Box(modifier = modifier.padding(horizontal = 16.dp)) {
                        Text(text = "No un-synced patients to show.")
                      }
                    }
                  }*/

                }
              }
            }

          }

        }
      }
    }
  }
}


@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CustomTabRow(
  tabTitles: List<String>,
  pagerState: PagerState,
  modifier: Modifier = Modifier,
  backgroundColor: Color = Color.LightGray,
  selectedBackgroundColor: Color = MaterialTheme.colors.primary,
  contentColor: Color = Color.Black,
  selectedContentColor: Color = Color.White,
) {
  TabRow(
    selectedTabIndex = pagerState.currentPage,
    contentColor = contentColor,
    indicator = {
      // Customize tab indicator (optional)
      TabIndicator(
        backgroundColor = selectedBackgroundColor,
        //modifier = Modifier.tabIndicatorOffset(it.get(0))
      )
    },
    modifier = modifier.background(backgroundColor)
  ) {
    tabTitles.forEachIndexed { index, title ->
      Tab(
        text = {
          Text(
            text = title,
            color = if (pagerState.currentPage == index) selectedContentColor else contentColor,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp
          )
        },
        selected = pagerState.currentPage == index,
        selectedContentColor = selectedContentColor,
        onClick = {
          CoroutineScope(Dispatchers.IO).launch {
            pagerState.scrollToPage(index)
          }
        }
      )
    }
  }
}

@Composable
private fun TabIndicator(
  backgroundColor: Color,
  modifier: Modifier = Modifier,
) {
  Box(
    modifier = modifier
      .height(4.dp)
      .fillMaxWidth()
      .background(backgroundColor)
  )
}

/*
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CustomTabRow(
  pagerState: PagerState, // Replace with your pager state
  tabTitles: List<String> // Replace with your list of tab titles
) {
  val scope = rememberCoroutineScope()

  TabRow(
    selectedTabIndex = pagerState.currentPage,
    backgroundColor = Color.LightGray,
    indicator = { tabPositions ->
      // Custom indicator to highlight selected tab
      TabRowDefaults.Indicator(
        Modifier
          .customTabIndicatorOffset(tabPositions)
          .background(Color.Transparent)
      )
    }
  ) {
    tabTitles.forEachIndexed { index, title ->
      Tab(
        text = {
          Text(
            text = title,
            color = if (pagerState.currentPage == index) Color.Black else Color.Gray,
            fontSize = 14.sp
          )
        },
        selected = pagerState.currentPage == index,
        selectedContentColor = Color.White,
        unselectedContentColor = Color.Gray,
        onClick = {
          scope.launch {
            pagerState.scrollToPage(index)
          }
        },
        modifier = Modifier
          .padding(horizontal = 8.dp, vertical = 4.dp)
          .background(
            color = if (pagerState.currentPage == index) Color.White else Color.Transparent,
            shape = RoundedCornerShape(20.dp)
          )
          .padding(horizontal = 16.dp, vertical = 8.dp)
      )
    }
  }
}*/


/*@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TabsContent(modifier: Modifier = Modifier,
                noResults: NoResultsConfig,
                viewModel : RegisterViewModel,) {


  Column {
    TabRow(
      selectedTabIndex = pagerState.currentPage,
      contentColor = Color.Black, // Customize tab text color
    ) {
      tabTitles.forEachIndexed { index, title ->
        Tab(
          text = { androidx.compose.material.Text(title) },
          selected = pagerState.currentPage == index,
          onClick = {
            //val context = LocalContext.current
            CoroutineScope(Dispatchers.Main).launch {
              pagerState.scrollToPage(index)
            }
          }
        )
      }
    }
    HorizontalPager(state = pagerState) {
      // Content for each tab (your fragment content goes here)
      tabTitles.forEach { title ->
        //TabPage(title)

        NoRegisterDataView(modifier = modifier, viewModel = viewModel, noResults = noResults) {
          noResults.actionButton?.actions?.handleClickEvent(navController)
        }
      }
    }
  }
}*/

@Composable
fun NoRegisterDataView(
  modifier: Modifier = Modifier,
  noResults: NoResultsConfig,
  viewModel : RegisterViewModel,
  onClick: () -> Unit,
) {
  val patients by viewModel.patientsStateFlow.collectAsState()

  Column(
    modifier = modifier
      .fillMaxWidth()
      .padding(16.dp)
      .background(SearchHeaderColor)
      .testTag(NO_REGISTER_VIEW_COLUMN_TEST_TAG),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Top,
  ) {

    if (noResults.actionButton != null) {
      Row() {
        Box {
          Button(
            modifier = modifier
              .padding(vertical = 16.dp)
              .fillMaxWidth()
              .testTag(NO_REGISTER_VIEW_BUTTON_TEST_TAG),
            onClick = onClick,
          ) {
            Icon(
              imageVector = Icons.Filled.Add,
              contentDescription = null,
              modifier
                .padding(end = 8.dp)
                .testTag(NO_REGISTER_VIEW_BUTTON_ICON_TEST_TAG),
            )
            Text(
              text = noResults.actionButton?.display?.uppercase().toString(),
              modifier.testTag(NO_REGISTER_VIEW_BUTTON_TEXT_TEST_TAG),
            )
          }
        }
      }
    }

    if (patients.isEmpty()){
      Text(
        text = noResults.title,
        fontSize = 16.sp,
        modifier = modifier
          .padding(vertical = 8.dp)
          .testTag(NO_REGISTER_VIEW_TITLE_TEST_TAG),
        fontWeight = FontWeight.Bold,
      )
      Text(
        text = noResults.message,
        modifier =
        modifier
          .padding(start = 32.dp, end = 32.dp)
          .testTag(NO_REGISTER_VIEW_MESSAGE_TEST_TAG),
        textAlign = TextAlign.Center,
        fontSize = 15.sp,
        color = Color.Gray,
      )
    }

    /*Column(
      modifier = modifier
        .fillMaxSize()
        .padding(horizontal = 8.dp, vertical = 8.dp)
        .background(SearchHeaderColor)
        .testTag(NO_REGISTER_VIEW_COLUMN_TEST_TAG),
      horizontalAlignment = Alignment.Start,
    ) {
      if (patients.isNotEmpty()){
        Text(
          text = "RECENTS",
          fontSize = 16.sp,
          modifier = modifier
            .padding(vertical = 8.dp)
            .testTag(NO_REGISTER_VIEW_TITLE_TEST_TAG),
          fontWeight = FontWeight.Bold,
        )
      }
      Box(modifier = modifier) {
        LazyColumn {
          items(patients) { patient ->
            Card(
              modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
                .background(SearchHeaderColor),
              elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
              Box(modifier = modifier
                .fillMaxWidth()
                .background(Color.White)) {
                Column(
                  modifier = Modifier
                    .padding(16.dp)
                    .background(Color.White)
                ) {
                  Row(modifier = modifier.padding(vertical = 4.dp)) {
                    Text(
                      modifier = Modifier.weight(1f),
                      text = patient.name.firstOrNull()?.given?.firstOrNull()?.value ?: "",
                      style = MaterialTheme.typography.h6,
                      color = LightColors.primary
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    //Text(text = patient.address)
                  }

                  Row(modifier = modifier.padding(vertical = 4.dp)) {
                  Text(text = "Gender: ")
                    Text(text = patient.gender?.name ?: "")
                  }
                  *//*if (patient.dob != null) {
                    Row {
                      Text(text = "DoB: ")
                      Text(text = patient.dob.toString())
                    }
                  }*//*
                }
              }
            }
          }
        }
      }
    }*/

  }
}

@PreviewWithBackgroundExcludeGenerated
@Composable
private fun PreviewNoRegistersView() {
  NoRegisterDataView(
    viewModel = viewModel(),
    noResults =
      NoResultsConfig(
        title = "Title",
        message = "This is message",
        actionButton = NavigationMenuConfig(display = "Button Text", id = "1"),
      ),
  ) {}
}


@Composable
fun DeleteRecordDialog(
  onDismiss: () -> Unit,
  onConfirm: () -> Unit,
  onCancel: () -> Unit
) {

  AlertDialog(
    onDismissRequest = onDismiss,
    title = {
      Text(text = "Delete record", fontSize = 28.sp, color = Color.Black)
    },
    text = {
      Text(text = "Are you sure you want to delete the record?", color = Color.Gray)
    },
    confirmButton = {
      TextButton(onClick = onConfirm) {
        Text(text = "Yes", color = LightColors.primary)
      }
    },
    dismissButton = {
      TextButton(onClick = onCancel) {
        Text(text = "No", color = LightColors.primary)
      }
    },
    properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = true)
  )
}