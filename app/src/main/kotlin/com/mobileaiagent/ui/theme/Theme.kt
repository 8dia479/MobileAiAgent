package com.mobileaiagent.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val BrandBlue = Color(0xFF4C8DFF)
private val BrandBlueDark = Color(0xFF2A5FB8)
private val Ink = Color(0xFF101828)
private val Paper = Color(0xFFF7F9FC)

private val LightColors = lightColorScheme(
    primary = BrandBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE8FF),
    onPrimaryContainer = Color(0xFF001B3D),
    secondary = Color(0xFF4A6274),
    tertiary = Color(0xFF7B4E9B),
    background = Paper,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    surfaceVariant = Color(0xFFE7ECF3),
    onSurfaceVariant = Color(0xFF44546A),
    error = Color(0xFFB3261E),
    outline = Color(0xFFB8C2D0),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9DC0FF),
    onPrimary = Color(0xFF002F65),
    primaryContainer = BrandBlueDark,
    onPrimaryContainer = Color(0xFFDCE8FF),
    secondary = Color(0xFFB1C9DB),
    tertiary = Color(0xFFD6B7F2),
    background = Color(0xFF0B1220),
    onBackground = Color(0xFFE4E8EF),
    surface = Color(0xFF141C2B),
    onSurface = Color(0xFFE4E8EF),
    surfaceVariant = Color(0xFF253044),
    onSurfaceVariant = Color(0xFFBCC8D8),
    error = Color(0xFFFFB4AB),
    outline = Color(0xFF475569),
)

private val AppTypography = Typography(
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 22.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 15.sp, lineHeight = 21.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
)

/** Monospace style used for logs, tool output and code blocks. */
val MonoStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp)

@Composable
fun MobileAIAgentTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        content = content,
    )
}
