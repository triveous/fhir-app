package org.smartregister.fhircore.quest.ui.shared.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Card
import androidx.compose.material.OutlinedButton
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import org.smartregister.fhircore.engine.ui.theme.AppTheme
import org.smartregister.fhircore.quest.R
import org.smartregister.fhircore.quest.theme.Colors.BRANDEIS_BLUE
import org.smartregister.fhircore.quest.theme.Colors.CRAYOLA
import org.smartregister.fhircore.quest.theme.Colors.CRAYOLA_LIGHT
import org.smartregister.fhircore.quest.theme.Colors.WHITE
import org.smartregister.fhircore.quest.theme.bodyMedium
import org.smartregister.fhircore.quest.theme.bodyNormal

const val LOGOUT_PENDING_SYNC_DIALOG_TAG = "logoutPendingSyncDialogTag"
const val LOGOUT_PENDING_SYNC_LOGOUT_TAG = "logoutPendingSyncLogoutTag"
const val LOGOUT_PENDING_SYNC_SYNC_NOW_TAG = "logoutPendingSyncSyncNowTag"
const val LOGOUT_PENDING_SYNC_CLOSE_TAG = "logoutPendingSyncCloseTag"

/** The design's scrim (`759:7580`) is lighter than the platform's 0.6 default. */
private const val SCRIM_DIM_AMOUNT = 0.32f

/**
 * "N cases pending to sync", shown when the user logs out with data still to upload. Laid out to the
 * Figma frame `759:7507` (card `759:7581`).
 *
 * Sync now uploads it first, and a logout after that lets a different user log in. Log out keeps the
 * pre-existing behaviour: the session ends but the data stays, so only the same user can log back in.
 * Back, outside taps and the close icon dismiss it and cancel the logout.
 *
 * [caseCount] is the number of cases with unsynced changes; when other data is pending (e.g. only
 * photos) it is 0 and the title falls back to a count-free wording.
 */
@Composable
fun LogoutPendingSyncDialog(
  caseCount: Int,
  onLogout: () -> Unit,
  onSyncNow: () -> Unit,
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
    LogoutPendingSyncCard(
      caseCount = caseCount,
      onLogout = onLogout,
      onSyncNow = onSyncNow,
      onClose = onDismiss,
    )
  }
}

@Composable
internal fun LogoutPendingSyncCard(
  caseCount: Int,
  onLogout: () -> Unit,
  onSyncNow: () -> Unit,
  onClose: () -> Unit,
) {
  Card(
    modifier =
      Modifier.fillMaxWidth().padding(horizontal = 24.dp).testTag(LOGOUT_PENDING_SYNC_DIALOG_TAG),
    shape = RoundedCornerShape(4.dp),
    backgroundColor = WHITE,
    elevation = 8.dp,
  ) {
    Box {
      Column(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
        Image(
          painter = painterResource(id = R.drawable.ic_logout_pending_sync),
          contentDescription = null,
          modifier = Modifier.size(64.dp),
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
          text =
            if (caseCount > 0) {
              pluralStringResource(R.plurals.logout_pending_sync_title, caseCount, caseCount)
            } else {
              stringResource(R.string.logout_pending_sync_data_title)
            },
          style = bodyMedium(18.sp).copy(letterSpacing = 0.15.sp).withFullLineHeight(24.sp),
          color = CRAYOLA,
          modifier = Modifier.semantics { heading() },
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
          text = stringResource(R.string.logout_pending_sync_message),
          style = bodyNormal(16.sp).copy(letterSpacing = 0.5.sp).withFullLineHeight(24.sp),
          color = CRAYOLA_LIGHT,
        )

        Spacer(modifier = Modifier.height(24.dp))

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
          OutlinedButton(
            onClick = onLogout,
            modifier = Modifier.weight(1f).height(44.dp).testTag(LOGOUT_PENDING_SYNC_LOGOUT_TAG),
            shape = RoundedCornerShape(2.dp),
            border = BorderStroke(1.dp, BRANDEIS_BLUE),
            contentPadding = BUTTON_CONTENT_PADDING,
            colors =
              ButtonDefaults.outlinedButtonColors(
                backgroundColor = WHITE,
                contentColor = BRANDEIS_BLUE,
              ),
          ) {
            ButtonLabel(text = stringResource(R.string.logout_pending_sync_logout), color = BRANDEIS_BLUE)
          }

          Button(
            onClick = onSyncNow,
            modifier = Modifier.weight(1f).height(44.dp).testTag(LOGOUT_PENDING_SYNC_SYNC_NOW_TAG),
            shape = RoundedCornerShape(2.dp),
            contentPadding = BUTTON_CONTENT_PADDING,
            elevation = ButtonDefaults.elevation(defaultElevation = 2.dp, pressedElevation = 4.dp),
            colors =
              ButtonDefaults.buttonColors(backgroundColor = BRANDEIS_BLUE, contentColor = WHITE),
          ) {
            ButtonLabel(text = stringResource(R.string.sync_now), color = WHITE)
          }
        }
      }
      DialogCloseButton(
        onClick = onClose,
        modifier = Modifier.align(Alignment.TopEnd).testTag(LOGOUT_PENDING_SYNC_CLOSE_TAG),
      )
    }
  }
}

/** Two buttons share 264dp, so the default 16dp side padding would crowd the longer labels. */
private val BUTTON_CONTENT_PADDING = PaddingValues(horizontal = 8.dp)

@Composable
private fun ButtonLabel(text: String, color: Color) {
  Text(
    text = text.uppercase(),
    style = bodyMedium(16.sp).copy(letterSpacing = 1.25.sp).withFullLineHeight(20.sp),
    color = color,
    textAlign = TextAlign.Center,
    maxLines = 1,
  )
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun LogoutPendingSyncCasesPreview() {
  AppTheme { LogoutPendingSyncCard(caseCount = 3, onLogout = {}, onSyncNow = {}, onClose = {}) }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun LogoutPendingSyncDataOnlyPreview() {
  AppTheme { LogoutPendingSyncCard(caseCount = 0, onLogout = {}, onSyncNow = {}, onClose = {}) }
}
