@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.chiron.chiroru.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Light = lightColorScheme(
    primary = Color(0xFF365E18), onPrimary = Color.White,
    primaryContainer = Color(0xFFD4F5A5), onPrimaryContainer = Color(0xFF173200),
    secondary = Color(0xFF58634C), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE0E8D3), onSecondaryContainer = Color(0xFF1B2515),
    tertiary = Color(0xFF66558A), onTertiary = Color.White, tertiaryContainer = Color(0xFFECDDFF),
    onTertiaryContainer = Color(0xFF291C45),
    background = Color(0xFFF7FAF0), onBackground = Color(0xFF191D16),
    surface = Color(0xFFF7FAF0), onSurface = Color(0xFF191D16),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF0F4E9),
    surfaceContainer = Color(0xFFEAEFE3), surfaceContainerHigh = Color(0xFFE4E9DD),
    surfaceContainerHighest = Color(0xFFDEE3D7),
    onSurfaceVariant = Color(0xFF454D3E), outline = Color(0xFF757E6C),
    outlineVariant = Color(0xFFC5CCBB),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFA8D58A), onPrimary = Color(0xFF183807),
    primaryContainer = Color(0xFF2F4D1E), onPrimaryContainer = Color(0xFFD4EDB9),
    secondary = Color(0xFFC0CBB2), secondaryContainer = Color(0xFF3F4A35),
    onSecondaryContainer = Color(0xFFE0E8D3),
    tertiary = Color(0xFFD1BDF7), onTertiary = Color(0xFF382751), tertiaryContainer = Color(0xFF4F3E69),
    onTertiaryContainer = Color(0xFFECDDFF),
    background = Color(0xFF12170F), onBackground = Color(0xFFE1E6D9),
    surface = Color(0xFF12170F), onSurface = Color(0xFFE1E6D9),
    surfaceContainerLowest = Color(0xFF0D120A), surfaceContainerLow = Color(0xFF1A1F17),
    surfaceContainer = Color(0xFF1E231B), surfaceContainerHigh = Color(0xFF292E25),
    surfaceContainerHighest = Color(0xFF33392F),
    onSurfaceVariant = Color(0xFFC5CCBB), outline = Color(0xFF8F9885),
    outlineVariant = Color(0xFF454D3E),
)

private val Type = Typography(
    displayLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold,
        fontSize = 52.sp, lineHeight = 58.sp, letterSpacing = (-1.8).sp),
    displaySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium,
        fontSize = 38.sp, lineHeight = 44.sp, letterSpacing = (-1).sp),
    headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold,
        fontSize = 32.sp, lineHeight = 38.sp, letterSpacing = (-0.7).sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = (-0.3).sp),
)

@Composable
internal fun ChiroruTheme(theme: String = "system", content: @Composable () -> Unit) {
    val dark = when (theme) { "light" -> false; "dark" -> true; else -> isSystemInDarkTheme() }
    val colors = if (dark) Dark else Light
    MaterialExpressiveTheme(colorScheme = colors, typography = Type,
        motionScheme = MotionScheme.expressive(), content = content)
}
