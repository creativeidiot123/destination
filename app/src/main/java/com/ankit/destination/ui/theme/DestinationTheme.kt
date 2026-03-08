package com.ankit.destination.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    // Primary
    primary = md_theme_dark_primary,
    onPrimary = md_theme_dark_onPrimary,
    primaryContainer = md_theme_dark_primaryContainer,
    onPrimaryContainer = md_theme_dark_onPrimaryContainer,
    // Secondary
    secondary = md_theme_dark_secondary,
    onSecondary = md_theme_dark_onSecondary,
    secondaryContainer = md_theme_dark_secondaryContainer,
    onSecondaryContainer = md_theme_dark_onSecondaryContainer,
    // Tertiary
    tertiary = md_theme_dark_tertiary,
    onTertiary = md_theme_dark_onTertiary,
    tertiaryContainer = md_theme_dark_tertiaryContainer,
    onTertiaryContainer = md_theme_dark_onTertiaryContainer,
    // Error
    error = md_theme_dark_error,
    errorContainer = md_theme_dark_errorContainer,
    onError = md_theme_dark_onError,
    onErrorContainer = md_theme_dark_onErrorContainer,
    // Surfaces/Backgrounds
    background = md_theme_dark_background,
    onBackground = md_theme_dark_onBackground,
    surface = md_theme_dark_surface,
    onSurface = md_theme_dark_onSurface,
    surfaceVariant = md_theme_dark_surfaceVariant,
    onSurfaceVariant = md_theme_dark_onSurfaceVariant,
    // Outlines
    outline = md_theme_dark_outline,
    outlineVariant = md_theme_dark_outline.copy(alpha = 0.5f), // Add outline variant
    // Surface Containers (New in M3 Expressive)
    surfaceDim = md_theme_dark_surface.copy(alpha = 0.8f),
    surfaceBright = md_theme_dark_surface,
    surfaceContainerLowest = md_theme_dark_surface.copy(alpha = 0.2f),
    surfaceContainerLow = md_theme_dark_surface.copy(alpha = 0.4f),
    surfaceContainer = md_theme_dark_surface.copy(alpha = 0.6f),
    surfaceContainerHigh = md_theme_dark_surface.copy(alpha = 0.8f),
    surfaceContainerHighest = md_theme_dark_surface
)

private val LightColorScheme = lightColorScheme(
    // Primary
    primary = md_theme_light_primary,
    onPrimary = md_theme_light_onPrimary,
    primaryContainer = md_theme_light_primaryContainer,
    onPrimaryContainer = md_theme_light_onPrimaryContainer,
    // Secondary
    secondary = md_theme_light_secondary,
    onSecondary = md_theme_light_onSecondary,
    secondaryContainer = md_theme_light_secondaryContainer,
    onSecondaryContainer = md_theme_light_onSecondaryContainer,
    // Tertiary
    tertiary = md_theme_light_tertiary,
    onTertiary = md_theme_light_onTertiary,
    tertiaryContainer = md_theme_light_tertiaryContainer,
    onTertiaryContainer = md_theme_light_onTertiaryContainer,
    // Error
    error = md_theme_light_error,
    errorContainer = md_theme_light_errorContainer,
    onError = md_theme_light_onError,
    onErrorContainer = md_theme_light_onErrorContainer,
    // Surfaces/Backgrounds
    background = md_theme_light_background,
    onBackground = md_theme_light_onBackground,
    surface = md_theme_light_surface,
    onSurface = md_theme_light_onSurface,
    surfaceVariant = md_theme_light_surfaceVariant,
    onSurfaceVariant = md_theme_light_onSurfaceVariant,
    // Outlines
    outline = md_theme_light_outline,
    outlineVariant = md_theme_light_outline.copy(alpha = 0.5f), // Add outline variant
    // Surface Containers (New in M3 Expressive)
    surfaceDim = md_theme_light_surface.copy(alpha = 0.8f),
    surfaceBright = md_theme_light_surface,
    surfaceContainerLowest = md_theme_light_surface.copy(alpha = 0.2f),
    surfaceContainerLow = md_theme_light_surface.copy(alpha = 0.4f),
    surfaceContainer = md_theme_light_surface.copy(alpha = 0.6f),
    surfaceContainerHigh = md_theme_light_surface.copy(alpha = 0.8f),
    surfaceContainerHighest = md_theme_light_surface
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DestinationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= 31 ->
            if (darkTheme) dynamicDarkColorScheme(context)
            else dynamicLightColorScheme(context)
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val density = androidx.compose.ui.platform.LocalDensity.current
    val scaledDensity = androidx.compose.ui.unit.Density(
        density = density.density,
        fontScale = density.fontScale.coerceAtMost(1.2f)
    )

    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.ui.platform.LocalDensity provides scaledDensity
    ) {
        MaterialExpressiveTheme(
            colorScheme = colorScheme,
            motionScheme = AppMotionScheme,
            shapes = AppShapes,
            typography = AppTypography,
            content = content
        )
    }
}
