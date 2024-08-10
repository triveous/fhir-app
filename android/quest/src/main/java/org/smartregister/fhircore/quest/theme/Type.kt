package org.smartregister.fhircore.quest.theme

//noinspection UsingMaterialAndMaterial3Libraries
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import org.smartregister.fhircore.engine.R

/**
 * Created by Jeetesh Surana.
 */

@Composable
fun typography(darkTheme: Boolean = isSystemInDarkTheme()): Typography {
    val openSansFontFamily: FontFamily = robotoFontFamilyData()

    return Typography(
        h1 = TextStyle(
            fontFamily = openSansFontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 40.sp, color = getTextColor(darkTheme)
        ),
        h2 = TextStyle(
            fontFamily = openSansFontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 32.sp,
            color = getTextColor(darkTheme)
        ),
        h3 = TextStyle(
            fontFamily = openSansFontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 24.sp,
            letterSpacing = (-.8).sp, color = getTextColor(darkTheme)
        ),
        h4 = TextStyle(
            fontFamily = openSansFontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp,
            letterSpacing = (-.66).sp, color = getTextColor(darkTheme)
        ),
        h5 = TextStyle(
            fontFamily = openSansFontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
            letterSpacing = (-.6).sp, color = getTextColor(darkTheme)
        ),
        h6 = TextStyle(
            fontFamily = openSansFontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
            letterSpacing = 0.3.sp, color = getTextColor(darkTheme)
        )
    )
}

@Composable
fun body14Medium(darkTheme: Boolean = isSystemInDarkTheme()): TextStyle {
    val robotoFontFamily: FontFamily = robotoFontFamilyData()
    return TextStyle(
        fontFamily = robotoFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp, color = getTextColor(darkTheme)
    )
}

@Composable
fun body18Medium(darkTheme: Boolean = isSystemInDarkTheme()): TextStyle {
    val robotoFontFamily: FontFamily = robotoFontFamilyData()
    return TextStyle(
        fontFamily = robotoFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 18.sp, color = getTextColor(darkTheme)
    )
}

@Composable
fun bodyMedium(fontSize: TextUnit, darkTheme: Boolean = isSystemInDarkTheme()): TextStyle {
    val robotoFontFamily: FontFamily = robotoFontFamilyData()
    return TextStyle(
        fontFamily = robotoFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = fontSize, color = getTextColor(darkTheme)
    )
}
@Composable
fun bodyNormal(fontSize: TextUnit, darkTheme: Boolean = isSystemInDarkTheme()): TextStyle {
    val robotoFontFamily: FontFamily = robotoFontFamilyData()
    return TextStyle(
        fontFamily = robotoFontFamily,
        fontWeight = FontWeight.Normal,
        letterSpacing = 0.15.sp,
        fontSize = fontSize, color = getTextColor(darkTheme)
    )
}
@Composable
fun bodyBold(fontSize: TextUnit, darkTheme: Boolean = isSystemInDarkTheme()): TextStyle {
    val robotoFontFamily: FontFamily = robotoFontFamilyData()
    return TextStyle(
        fontFamily = robotoFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = fontSize, color = getTextColor(darkTheme)
    )
}

@Composable
fun getTextColor(darkTheme: Boolean = isSystemInDarkTheme()): Color {
    return if (darkTheme) {
        Colors.WHITE
    } else {
        Colors.CRAYOLA
    }
}

@Composable
private fun robotoFontFamilyData(): FontFamily {
    val robotoBlack = Font(R.font.roboto_black, FontWeight.ExtraBold)
    val robotoBold = Font(R.font.roboto_bold, FontWeight.Bold)
    val robotoMedium = Font(R.font.roboto_medium, FontWeight.Medium)
    val robotoRegular = Font(R.font.roboto_regular, FontWeight.Normal)
    return FontFamily(
        robotoRegular,
        robotoMedium,
        robotoBold,
        robotoBlack
    )
}