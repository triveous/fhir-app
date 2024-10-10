package org.smartregister.fhircore.quest.ui.register.profile


import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Button
import androidx.compose.material.Icon
import androidx.compose.material.Scaffold
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.rememberScaffoldState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import dagger.hilt.android.AndroidEntryPoint
import org.smartregister.fhircore.engine.domain.model.ToolBarHomeNavigation
import org.smartregister.fhircore.engine.ui.theme.AppTheme
import org.smartregister.fhircore.engine.ui.theme.SearchHeaderColor
import org.smartregister.fhircore.quest.R
import org.smartregister.fhircore.quest.theme.Colors
import org.smartregister.fhircore.quest.theme.body14Medium
import org.smartregister.fhircore.quest.theme.body18Medium
import org.smartregister.fhircore.quest.ui.main.components.TopScreenSection

/**
 * Created by Jeetesh Surana.
 */
@AndroidEntryPoint
class ChangeLanguageFragment : Fragment() {

    private val languageViewModel by viewModels<LanguageViewModel>()
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setContent {
                val scaffoldState = rememberScaffoldState()
                AppTheme {
                    Scaffold(
                        modifier = Modifier.background(SearchHeaderColor),
                        scaffoldState = scaffoldState,
                        topBar = {
                                TopScreenSection(
                                    title = stringResource(id = R.string.change_language),
                                    onSync = {},
                                    toolBarHomeNavigation = ToolBarHomeNavigation.NAVIGATE_BACK,) { event ->
                                    requireActivity().supportFragmentManager.popBackStack()
                                }
                        }
                    ) { _ ->
                        LanguageSelectionScreen(languageViewModel) {
                            languageViewModel.updateLanguage()
                            requireActivity().supportFragmentManager.popBackStack()
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LanguageSelectionScreen(viewModel: LanguageViewModel, onClick: () -> Unit) {
    val languages by viewModel.languages.collectAsState()
    val selectedLanguage by viewModel.selectedLanguage.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = stringResource(id = R.string.select_language),
            style = body14Medium(),
            color = Colors.CRAYOLA_LIGHT,
            modifier = Modifier.padding(bottom = 16.dp, top = 6.dp)
        )

        languages.forEach { language ->
            LanguageItem(
                language = language,
                isSelected = selectedLanguage == language,
                onClick = { viewModel.selectLanguage(language) }
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = {
                onClick()
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
        ) {
            Text(text = stringResource(id = R.string.update))
        }
    }
}

@Composable
fun LanguageItem(
    language: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = if (isSelected) Colors.BRANDEIS_BLUE else Colors.WHITE),
        shape = RoundedCornerShape(4.dp),
        elevation = CardDefaults.cardElevation(3.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = language,
                color = if (isSelected) Colors.WHITE else Colors.BLACK,
                style = body18Medium(),
                modifier = Modifier.weight(1f)
            )

            if (isSelected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Selected",
                    tint = Colors.BRANDEIS_BLUE
                )
            }
        }
    }
}
