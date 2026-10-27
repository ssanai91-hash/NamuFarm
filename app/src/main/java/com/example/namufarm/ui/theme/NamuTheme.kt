package com.example.namufarm.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object NamuColors {
    val primary = Color(0xFF2D6A4F)
    val dark = Color(0xFF193C32)
    val navPill = Color(0xFFDCEFE2)
    val accent = Color(0xFFC7EFAD)
    val danger = Color(0xFFB3261E)
    val text = Color(0xFF1F2A26)
    val muted = Color(0xFF5B6B63)
    val border = Color(0xFFDFE5E2)
    val input = Color(0xFFB9C7C0)
    val background = Color(0xFFF4F6F5)
    val surface = Color.White
    val soft = Color(0xFFEEF5F0)
    const val LOW_STOCK = 20
}

/** Display feedback separately from error classification; legacy prefixes never reach the screen. */
data class NamuMessage(val text: String, val isError: Boolean = false) {
    companion object {
        fun fromLegacy(value: String): NamuMessage = NamuMessage(
            namuPlainText(value), value.trimStart().let {
                it.startsWith("❌") || it.startsWith("⚠") || it.startsWith("🚫") ||
                    it.contains("실패") || it.contains("오류")
            }
        )
    }
}

fun namuPlainText(value: String): String = value
    .replace(Regex("[\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{FE0F}\\x{200D}]"), "")
    .trim()

@Composable
fun NamuTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = NamuColors.primary, onPrimary = Color.White,
            primaryContainer = NamuColors.navPill, onPrimaryContainer = NamuColors.dark,
            secondary = NamuColors.primary, tertiary = NamuColors.primary,
            secondaryContainer = NamuColors.soft, onSecondaryContainer = NamuColors.dark,
            tertiaryContainer = NamuColors.soft, onTertiaryContainer = NamuColors.dark,
            background = NamuColors.background, surface = Color.White,
            onBackground = NamuColors.text, onSurface = NamuColors.text,
            onSurfaceVariant = NamuColors.muted, outline = NamuColors.input,
            outlineVariant = NamuColors.border, error = NamuColors.danger
        ),
        typography = Typography(
            headlineSmall = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold),
            titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold),
            titleMedium = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold),
            titleSmall = TextStyle(fontSize = 17.sp, lineHeight = 23.sp, fontWeight = FontWeight.Bold),
            bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 23.sp),
            bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
            bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 19.sp),
            labelLarge = TextStyle(fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.Bold),
            labelMedium = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
            labelSmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp)
        ), content = content
    )
}
