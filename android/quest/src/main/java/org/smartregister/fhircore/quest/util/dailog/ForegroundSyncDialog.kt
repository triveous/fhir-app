package org.smartregister.fhircore.quest.util.dailog

import androidx.compose.material.AlertDialog
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.sp
import org.smartregister.fhircore.quest.theme.body18Medium
import org.smartregister.fhircore.quest.theme.bodyMedium
import org.smartregister.fhircore.quest.theme.bodyNormal

/**
 * Created by Jeetesh Surana.
 */

@Composable
fun ForegroundSyncDialog(
    showDialog: Boolean,
    title: String,
    content: String,
    imageCount: Int = 0,
    dismissButtonText: String,
    confirmButtonText: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    if (showDialog) {
        AlertDialog(
            shape = RectangleShape,
            onDismissRequest = onDismiss,
            title = { Text(title, style = body18Medium()) },
            text = { Text(content, style = bodyNormal(14.sp)) },
            confirmButton = { // Okay button
                TextButton(onClick = onDismiss) {
                    Text(dismissButtonText, style = bodyMedium(16.sp))
                }
            },
            dismissButton = { // sync button
                if (imageCount > 0) {
                    TextButton(onClick = onConfirm) {
                        Text(confirmButtonText, style = bodyMedium(16.sp))
                    }
                }
            }
        )
    }
}