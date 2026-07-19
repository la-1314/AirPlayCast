package com.miui.airplaycast.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColors = lightColorScheme(
    primary = MiPrimary,
    onPrimary = MiOnPrimary,
    primaryContainer = MiSurfaceVariant,
    onPrimaryContainer = MiOnBackground,
    secondary = MiSecondary,
    onSecondary = MiOnSecondary,
    tertiary = MiAccent,
    background = MiBackground,
    onBackground = MiOnBackground,
    surface = MiSurface,
    onSurface = MiOnSurface,
    surfaceVariant = MiSurfaceVariant,
    onSurfaceVariant = MiOnSurfaceVariant,
    error = MiError
)

private val DarkColors = darkColorScheme(
    primary = MiDarkPrimary,
    onPrimary = MiDarkOnPrimary,
    primaryContainer = MiDarkSurfaceVariant,
    onPrimaryContainer = MiDarkOnBackground,
    secondary = MiDarkSecondary,
    onSecondary = MiDarkOnSecondary,
    tertiary = MiDarkAccent,
    background = MiDarkBackground,
    onBackground = MiDarkOnBackground,
    surface = MiDarkSurface,
    onSurface = MiDarkOnSurface,
    surfaceVariant = MiDarkSurfaceVariant,
    onSurfaceVariant = MiDarkOnSurfaceVariant,
    error = MiError
)

@Composable
fun MiuiXTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as android.app.Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    val shapes = Shapes(
        extraSmall = MiShapes().extraSmall,
        small = MiShapes().small,
        medium = MiShapes().medium,
        large = MiShapes().large,
        extraLarge = MiShapes().extraLarge
    )

    CompositionLocalProvider(
        LocalMiShapes provides MiShapes()
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = MiTypography,
            shapes = shapes,
            content = content
        )
    }
}
