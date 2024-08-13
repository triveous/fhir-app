package org.smartregister.fhircore.quest.ui.selectSite

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import dagger.hilt.android.AndroidEntryPoint
import org.smartregister.fhircore.engine.configuration.app.ApplicationConfiguration
import org.smartregister.fhircore.engine.di.BaseUrlsHolder
import org.smartregister.fhircore.engine.ui.base.BaseMultiLanguageActivity
import org.smartregister.fhircore.engine.ui.theme.AppTheme
import org.smartregister.fhircore.engine.util.extension.applyWindowInsetListener
import org.smartregister.fhircore.quest.QuestApplication
import org.smartregister.fhircore.quest.di.config.AuthConfigurationHelper
import org.smartregister.fhircore.quest.ui.selectSite.viewModel.SelectSiteViewModel
import javax.inject.Inject


/**
 * Created by Jeetesh Surana.
 */
@AndroidEntryPoint
class SelectSiteScreenActivity : BaseMultiLanguageActivity() {

    private val selectSiteViewModel by viewModels<SelectSiteViewModel>()
    var applicationConfiguration: ApplicationConfiguration?=null
    @Inject
    lateinit var baseUrlsHolder: BaseUrlsHolder
    @Inject
    lateinit var authConfigurationHelper: AuthConfigurationHelper
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        this.applyWindowInsetListener()
        applicationConfiguration = ApplicationConfiguration(
            appId = "appId",
            configType = "application",
            appTitle = "FHIRCore App",
        )
        setContent { AppTheme {
            SelectSiteScreen(
                this@SelectSiteScreenActivity,
                selectSiteViewModel,ApplicationConfiguration(
            appId = "appId",
            configType = "application",
            appTitle = "FHIRCore App",
        ),{
            (application as QuestApplication).updateFhirServerHost()
            baseUrlsHolder.getUpdatedData()
            authConfigurationHelper.getUpdateAuthConfiguration()
            restartApp()
//            launchActivityWithNoBackStackHistory<AppSettingActivity>()
        }) } }
    }
    private fun restartApp() {
        val intent = baseContext.packageManager.getLaunchIntentForPackage(baseContext.packageName)
        intent?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        Runtime.getRuntime().exit(0)
    }
}

