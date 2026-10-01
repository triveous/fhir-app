package org.smartregister.fhircore.quest.ui.shared.components

import android.net.ConnectivityManager
import android.net.Network
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Card
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import org.smartregister.fhircore.engine.ui.theme.AppTheme
import org.smartregister.fhircore.engine.util.extension.isDeviceOnline
import org.smartregister.fhircore.quest.R
import org.smartregister.fhircore.quest.theme.Colors.BRANDEIS_BLUE
import org.smartregister.fhircore.quest.theme.Colors.CRAYOLA
import org.smartregister.fhircore.quest.theme.Colors.CRAYOLA_LIGHT
import org.smartregister.fhircore.quest.theme.Colors.SIZZLING_RED
import org.smartregister.fhircore.quest.theme.Colors.WHITE
import org.smartregister.fhircore.quest.theme.bodyMedium
import org.smartregister.fhircore.quest.theme.bodyNormal

const val AI_ENABLE_FAILED_DIALOG_TAG = "aiEnableFailedDialogTag"
const val AI_ENABLE_RETRY_TAG = "aiEnableRetryTag"
const val AI_ENABLE_OFFLINE_MESSAGE_TAG = "aiEnableOfflineMessageTag"

/** The design's scrim (`759:8109`) is lighter than the platform's 0.6 default. */
private const val SCRIM_DIM_AMOUNT = 0.32f

/**
 * "Failed to enable AI", shown instead of the registration form when the site has AI on but the
 * device cannot extract the AI result yet (`AiReadiness.UPDATE_NEEDED`). Never shown on sites that run
 * with AI off. Retry starts a sync, which is what brings the AI result extraction up to date.
 *
 * Laid out to the Figma frames `759:8036` (online) and `787:13093` (offline, which adds the red
 * internet hint under Retry). `usePlatformDefaultWidth = false` because the design pins the card to
 * 24dp side margins rather than the platform's dialog width. Back and outside taps dismiss it: unlike
 * the forced app update, the rest of the app stays usable, only adding a case is blocked.
 */
@Composable
fun AiEnableFailedDialog(
  isOnline: Boolean,
  onRetry: () -> Unit,
  onDismiss: () -> Unit,
) {
  Dialog(
    onDismissRequest = onDismiss,
    properties =
      DialogProperties(
        dismissOnBackPress = true,
        dismissOnClickOutside = true,
        usePlatformDefaultWidth = false,
      ),
  ) {
    (LocalView.current.parent as? DialogWindowProvider)?.window?.let { window ->
      SideEffect { window.setDimAmount(SCRIM_DIM_AMOUNT) }
    }
    AiEnableFailedCard(isOnline = isOnline, onRetry = onRetry)
  }
}

/**
 * Live connectivity for screens that have no activity-level online state (the Cases tab gets
 * `AppMainActivity.isOnline`), so the dialog's offline hint appears and clears as the network does.
 * Listens only while it is in composition.
 */
@Composable
fun rememberIsDeviceOnline(): State<Boolean> {
  val context = LocalContext.current
  val isOnline = remember { mutableStateOf(context.isDeviceOnline()) }
  DisposableEffect(context) {
    val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
    val callback =
      object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
          isOnline.value = true
        }

        override fun onLost(network: Network) {
          isOnline.value = context.isDeviceOnline()
        }
      }
    val registered =
      runCatching { connectivityManager?.registerDefaultNetworkCallback(callback) }.isSuccess
    onDispose {
      if (registered) runCatching { connectivityManager?.unregisterNetworkCallback(callback) }
    }
  }
  return isOnline
}

@Composable
internal fun AiEnableFailedCard(isOnline: Boolean, onRetry: () -> Unit) {
  Card(
    modifier =
      Modifier.fillMaxWidth().padding(horizontal = 24.dp).testTag(AI_ENABLE_FAILED_DIALOG_TAG),
    shape = RoundedCornerShape(4.dp),
    backgroundColor = WHITE,
    elevation = 8.dp,
  ) {
    Column(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
      Image(
        painter = painterResource(id = R.drawable.ic_ai_enable_failed),
        contentDescription = null,
        modifier = Modifier.size(64.dp),
      )

      Spacer(modifier = Modifier.height(24.dp))

      Text(
        text = stringResource(R.string.ai_enable_failed_title),
        style = bodyMedium(18.sp).copy(letterSpacing = 0.15.sp).withFullLineHeight(24.sp),
        color = CRAYOLA,
        modifier = Modifier.semantics { heading() },
      )

      Spacer(modifier = Modifier.height(8.dp))

      Text(
        text = stringResource(R.string.ai_enable_failed_message),
        style = bodyNormal(16.sp).copy(letterSpacing = 0.5.sp).withFullLineHeight(24.sp),
        color = CRAYOLA_LIGHT,
      )

      Spacer(modifier = Modifier.height(24.dp))

      Button(
        onClick = onRetry,
        modifier = Modifier.fillMaxWidth().height(44.dp).testTag(AI_ENABLE_RETRY_TAG),
        shape = RoundedCornerShape(2.dp),
        elevation = ButtonDefaults.elevation(defaultElevation = 2.dp, pressedElevation = 4.dp),
        colors = ButtonDefaults.buttonColors(backgroundColor = BRANDEIS_BLUE, contentColor = WHITE),
      ) {
        Text(
          text = stringResource(R.string.ai_enable_retry).uppercase(),
          style = bodyMedium(16.sp).copy(letterSpacing = 1.25.sp).withFullLineHeight(20.sp),
          color = WHITE,
        )
      }

      if (!isOnline) {
        Spacer(modifier = Modifier.height(16.dp))
        Text(
          text = stringResource(R.string.ai_enable_failed_offline),
          style = bodyNormal(14.sp).copy(letterSpacing = 0.2.sp).withFullLineHeight(20.sp),
          color = SIZZLING_RED,
          modifier =
            Modifier.testTag(AI_ENABLE_OFFLINE_MESSAGE_TAG).semantics {
              liveRegion = LiveRegionMode.Polite
            },
        )
      }
    }
  }
}

/**
 * Figma lays text out in full line boxes; Compose's default line-height style trims the leading above
 * the first line and below the last, which made the title 21dp instead of 24 and pulled Retry 8dp up.
 */
private fun TextStyle.withFullLineHeight(lineHeight: TextUnit) =
  copy(
    lineHeight = lineHeight,
    lineHeightStyle =
      LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Center,
        trim = LineHeightStyle.Trim.None,
      ),
  )

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun AiEnableFailedOnlinePreview() {
  AppTheme { AiEnableFailedCard(isOnline = true, onRetry = {}) }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun AiEnableFailedOfflinePreview() {
  AppTheme { AiEnableFailedCard(isOnline = false, onRetry = {}) }
}
