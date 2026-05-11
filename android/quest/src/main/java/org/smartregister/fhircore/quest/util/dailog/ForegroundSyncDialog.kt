package org.smartregister.fhircore.quest.util.dailog

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.AlertDialog
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.sp
import org.smartregister.fhircore.quest.theme.Theme
import org.smartregister.fhircore.quest.theme.body18Medium
import org.smartregister.fhircore.quest.theme.bodyMedium
import org.smartregister.fhircore.quest.theme.bodyNormal
import org.smartregister.fhircore.quest.theme.getTextColor

/**
 * Created by Jeetesh Surana.
 */

@Composable
fun ForegroundSyncDialog(
    showDialog: Boolean,
    title: String,
    content: String,
    imageCount: Int = 0,
    patientsCount: Int = 0,
    dismissButtonText: String,
    confirmButtonText: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
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
            onDismissRequest = onDismiss,
            title = { Text(title, style = body18Medium(darkTheme)) },
            text = { Text(content, style = bodyNormal(14.sp, darkTheme)) },
            confirmButton = { // Okay button
                TextButton(onClick = onDismiss) {
                    Text(dismissButtonText, style = bodyMedium(16.sp, darkTheme))
                }
            },
            dismissButton = { // sync button
                //Allowing users always have an option to trigger sync manually
                TextButton(onClick = onConfirm) {
                    Text(confirmButtonText, style = bodyMedium(16.sp, darkTheme))
                }
            }
        )
    }
}