package org.smartregister.fhircore.quest.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import org.smartregister.fhircore.quest.theme.Colors.ANTI_FLASH_WHITE
import org.smartregister.fhircore.quest.theme.Colors.BLACK
import org.smartregister.fhircore.quest.theme.Colors.SMOKY_BLACK
import org.smartregister.fhircore.quest.theme.Colors.WHITE

/**
 * Created by Jeetesh Surana.
 */

object Theme {
    @Composable
    fun getBackground(darkTheme:Boolean= isSystemInDarkTheme()): Color {
        return if (!darkTheme){
            ANTI_FLASH_WHITE
        } else {
            SMOKY_BLACK
        }
    }

    @Composable
    fun getWhiteBackground(darkTheme:Boolean= isSystemInDarkTheme()): Color {
        return if (!darkTheme){
            WHITE
        } else {
            BLACK
        }
    }
}