package com.strong.clearlink.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColorScheme = lightColorScheme(
    primary = TealPrimary,
    onPrimary = Color.White,
    secondary = TealDark,
    onSecondary = Color.White,
    background = Mist,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
)

private val DarkColorScheme = darkColorScheme(
    primary = TealPrimary,
    onPrimary = Color.White,
    secondary = Sand,
    onSecondary = Ink,
    background = Color(0xFF1C1917),
    onBackground = Mist,
    surface = Color(0xFF292524),
    onSurface = Mist,
)

@Composable
fun ClearLinkTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content,
    )
}
