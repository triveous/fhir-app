package org.smartregister.fhircore.quest.util.dailog

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.AlertDialog
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.smartregister.fhircore.quest.R
import org.smartregister.fhircore.quest.theme.Theme
import org.smartregister.fhircore.quest.theme.body18Medium
import org.smartregister.fhircore.quest.theme.bodyMedium
import org.smartregister.fhircore.quest.theme.bodyNormal
import org.smartregister.fhircore.quest.theme.getTextColor

/**
 * Created by Jeetesh Surana.
 */

sealed class ForegroundSyncDialogState {
    object Loading : ForegroundSyncDialogState()

    data class Loaded(
        val imageCount: Int,
        val patientsCount: Int,
    ) : ForegroundSyncDialogState()

    object Failed : ForegroundSyncDialogState()
}

const val FOREGROUND_SYNC_DIALOG_LOADING_TAG = "foregroundSyncDialogLoading"
const val FOREGROUND_SYNC_DIALOG_CONTENT_TAG = "foregroundSyncDialogContent"
const val FOREGROUND_SYNC_DIALOG_ERROR_TAG = "foregroundSyncDialogError"

@Composable
fun ForegroundSyncDialog(
    showDialog: Boolean,
    title: String,
    state: ForegroundSyncDialogState,
    dismissButtonText: String,
    confirmButtonText: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    onRetry: () -> Unit,
) {
    if (showDialog) {
        // Resolve dark-theme state once in the caller's composition and reuse
        // it for both the dialog surface and every Text inside its lambdas.
        // The AlertDialog content runs in its own subcomposition (separate
        // Window), and on this screen it can disagree with the outer tree
        // after navigation — which produced a white-on-white (blank) dialog.
        val darkTheme = isSystemInDarkTheme()
        AlertDialog(
            shape = RectangleShape,
            backgroundColor = Theme.getWhiteBackground(darkTheme),
            contentColor = getTextColor(darkTheme),
            // Do not let a back press or outside tap close the dialog while the current counts are
            // being read. Otherwise a user can leave before ever seeing the authoritative result.
            onDismissRequest = {
                if (state !is ForegroundSyncDialogState.Loading) {
                    onDismiss()
                }
            },
            title = { Text(title, style = body18Medium(darkTheme)) },
            text = {
                when (state) {
                    ForegroundSyncDialogState.Loading -> {
                        Column(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .testTag(FOREGROUND_SYNC_DIALOG_LOADING_TAG),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(32.dp))
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = stringResource(R.string.loading_sync_status),
                                style = bodyNormal(14.sp, darkTheme),
                            )
                        }
                    }

                    is ForegroundSyncDialogState.Loaded -> {
                        Text(
                            modifier = Modifier.testTag(FOREGROUND_SYNC_DIALOG_CONTENT_TAG),
                            text =
                                "${stringResource(R.string.image_left, state.imageCount.toString())}\n" +
                                    stringResource(
                                        R.string.patients_left,
                                        state.patientsCount.toString(),
                                    ),
                            style = bodyNormal(14.sp, darkTheme),
                        )
                    }

                    ForegroundSyncDialogState.Failed -> {
                        Text(
                            modifier = Modifier.testTag(FOREGROUND_SYNC_DIALOG_ERROR_TAG),
                            text = stringResource(R.string.sync_status_load_failed),
                            style = bodyNormal(14.sp, darkTheme),
                        )
                    }
                }
            },
            confirmButton = { // Okay button
                if (state !is ForegroundSyncDialogState.Loading) {
                    TextButton(onClick = onDismiss) {
                        Text(dismissButtonText, style = bodyMedium(16.sp, darkTheme))
                    }
                }
            },
            dismissButton = {
                when (state) {
                    ForegroundSyncDialogState.Loading -> Unit
                    is ForegroundSyncDialogState.Loaded -> {
                        // Allow users to trigger sync manually even when both live counts are zero.
                        TextButton(onClick = onConfirm) {
                            Text(confirmButtonText, style = bodyMedium(16.sp, darkTheme))
                        }
                    }

                    ForegroundSyncDialogState.Failed -> {
                        TextButton(onClick = onRetry) {
                            Text(
                                text =
                                    stringResource(
                                        org.smartregister.fhircore.engine.R.string.try_again,
                                    ),
                                style = bodyMedium(16.sp, darkTheme),
                            )
                        }
                    }
                }
            },
        )
    }
}
