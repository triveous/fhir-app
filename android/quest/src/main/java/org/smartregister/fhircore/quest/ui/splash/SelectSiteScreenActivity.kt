package org.smartregister.fhircore.quest.ui.splash

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import org.smartregister.fhircore.engine.ui.base.BaseMultiLanguageActivity
import org.smartregister.fhircore.engine.util.extension.applyWindowInsetListener
import org.smartregister.fhircore.quest.R
import org.smartregister.fhircore.quest.theme.Theme.getWhiteBackground
import org.smartregister.fhircore.quest.theme.typography
import org.smartregister.fhircore.quest.ui.appsetting.AppSettingActivity
import org.smartregister.fhircore.quest.ui.selectSite.SelectSiteScreenActivity

/**
 * Created by Jeetesh Surana.
 */
@AndroidEntryPoint
class SplashActivity : BaseMultiLanguageActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installSplashScreen()
        this.applyWindowInsetListener()
        setContent {
            SplashScreen()
            LaunchedEffect(key1 = true) {
                val fhirBaseUrl = sharedPreferencesHelper.getFhirBaseUrlWithoutDefaultValue()
                delay(1 * 1000) // 3 seconds delay
                if (fhirBaseUrl.isNullOrEmpty()) {
                    startActivity(Intent(this@SplashActivity, SelectSiteScreenActivity::class.java))
                } else {
                    startActivity(Intent(this@SplashActivity, AppSettingActivity::class.java))
                }
                finish()

            }
        }
    }
}

@Preview
@Composable
fun SplashScreen() {
    Surface(modifier = Modifier.fillMaxSize().background(getWhiteBackground())) {
        Box(contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Image(
                    painter = painterResource(id = R.drawable.ic_app_logo),
                    contentDescription = "Splash Logo"
                )
                Spacer(modifier = Modifier.height(50.dp))
                Text(text = stringResource(id = R.string.app_name), style = typography().h2)
            }
        }
    }
}
