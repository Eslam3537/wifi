package com.example.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val AppleDarkColorScheme = darkColorScheme(
    primary = AppleBlueDark,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF152A4A),
    onPrimaryContainer = Color(0xFF90C2FF),
    secondary = AppleIndigoDark,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF26234D),
    onSecondaryContainer = Color(0xFFBFBFFF),
    tertiary = AppleTealDark,
    onTertiary = Color.Black,
    tertiaryContainer = Color(0xFF143340),
    onTertiaryContainer = Color(0xFF8FE6FF),
    background = AppleBgDark,
    onBackground = AppleTextPrimaryDark,
    surface = AppleSurfaceDark,
    onSurface = AppleTextPrimaryDark,
    surfaceVariant = AppleSurfaceSecondaryDark,
    onSurfaceVariant = AppleTextSecondaryDark,
    error = AppleRedDark,
    onError = Color.White,
    errorContainer = Color(0xFF4A1817),
    onErrorContainer = Color(0xFFFFB4AB),
    outline = AppleBorderDark,
    outlineVariant = Color(0x33FFFFFF)
)

private val AppleLightColorScheme = lightColorScheme(
    primary = AppleBlueLight,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE5F1FF),
    onPrimaryContainer = Color(0xFF003F8A),
    secondary = AppleIndigoLight,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEBEBFC),
    onSecondaryContainer = Color(0xFF2B286E),
    tertiary = AppleTealLight,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE4F8FC),
    onTertiaryContainer = Color(0xFF004953),
    background = AppleBgLight,
    onBackground = AppleTextPrimaryLight,
    surface = AppleSurfaceLight,
    onSurface = AppleTextPrimaryLight,
    surfaceVariant = AppleSurfaceSecondaryLight,
    onSurfaceVariant = Color(0xFF6C6C70),
    error = AppleRedLight,
    onError = Color.White,
    errorContainer = Color(0xFFFFECEB),
    onErrorContainer = Color(0xFF8A0000),
    outline = AppleBorderLight,
    outlineVariant = Color(0x1F000000)
)

@Composable
fun MyApplicationTheme(
    themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    darkTheme: Boolean = when (themeMode) {
        AppThemeMode.SYSTEM -> isSystemInDarkTheme()
        AppThemeMode.LIGHT -> false
        AppThemeMode.DARK -> true
    },
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) AppleDarkColorScheme else AppleLightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                val insetsController = WindowCompat.getInsetsController(window, view)
                insetsController.isAppearanceLightStatusBars = !darkTheme
                insetsController.isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
