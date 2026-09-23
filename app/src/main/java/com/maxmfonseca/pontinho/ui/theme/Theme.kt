package com.maxmfonseca.pontinho.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import com.maxmfonseca.pontinho.settings.ThemePreference

private val LightColorScheme = lightColorScheme(
    primary = Emerald,
    onPrimary = OffWhite,
    primaryContainer = EmeraldLight,
    onPrimaryContainer = Navy,
    inversePrimary = EmeraldLight,
    secondary = WarmYellow,
    onSecondary = Navy,
    secondaryContainer = WarmYellow,
    onSecondaryContainer = Navy,
    tertiary = SkyBlue,
    onTertiary = Navy,
    tertiaryContainer = SkyBlue,
    onTertiaryContainer = Navy,
    error = Coral,
    onError = Navy,
    errorContainer = Coral,
    onErrorContainer = Navy,
    background = OffWhite,
    onBackground = Navy,
    surface = OffWhite,
    onSurface = Navy,
    surfaceVariant = SkyBlue,
    onSurfaceVariant = Navy,
    outline = Navy,
    outlineVariant = SkyBlue,
    scrim = Navy,
    inverseSurface = Navy,
    inverseOnSurface = OffWhite,
    surfaceTint = Emerald,
    surfaceBright = OffWhite,
    surfaceDim = OffWhite,
    surfaceContainer = OffWhite,
    surfaceContainerHigh = OffWhite,
    surfaceContainerHighest = OffWhite,
    surfaceContainerLow = OffWhite,
    surfaceContainerLowest = OffWhite,
    primaryFixed = EmeraldLight,
    primaryFixedDim = Emerald,
    onPrimaryFixed = Navy,
    onPrimaryFixedVariant = Navy,
    secondaryFixed = WarmYellow,
    secondaryFixedDim = WarmYellow,
    onSecondaryFixed = Navy,
    onSecondaryFixedVariant = Navy,
    tertiaryFixed = SkyBlue,
    tertiaryFixedDim = SkyBlue,
    onTertiaryFixed = Navy,
    onTertiaryFixedVariant = Navy,
)

private val DarkColorScheme = darkColorScheme(
    primary = EmeraldLight,
    onPrimary = Navy,
    primaryContainer = Emerald,
    onPrimaryContainer = OffWhite,
    inversePrimary = Emerald,
    secondary = WarmYellow,
    onSecondary = Navy,
    secondaryContainer = WarmYellow,
    onSecondaryContainer = Navy,
    tertiary = SkyBlue,
    onTertiary = Navy,
    tertiaryContainer = SkyBlue,
    onTertiaryContainer = Navy,
    error = Coral,
    onError = Navy,
    errorContainer = Coral,
    onErrorContainer = Navy,
    background = Navy,
    onBackground = OffWhite,
    surface = NavySurface,
    onSurface = OffWhite,
    surfaceVariant = NavySurface,
    onSurfaceVariant = OffWhite,
    outline = SkyBlue,
    outlineVariant = NavySurface,
    scrim = Navy,
    inverseSurface = OffWhite,
    inverseOnSurface = Navy,
    surfaceTint = EmeraldLight,
    surfaceBright = NavySurface,
    surfaceDim = Navy,
    surfaceContainer = Navy,
    surfaceContainerHigh = NavySurface,
    surfaceContainerHighest = NavySurface,
    surfaceContainerLow = Navy,
    surfaceContainerLowest = Navy,
    primaryFixed = EmeraldLight,
    primaryFixedDim = Emerald,
    onPrimaryFixed = Navy,
    onPrimaryFixedVariant = Navy,
    secondaryFixed = WarmYellow,
    secondaryFixedDim = WarmYellow,
    onSecondaryFixed = Navy,
    onSecondaryFixedVariant = Navy,
    tertiaryFixed = SkyBlue,
    tertiaryFixedDim = SkyBlue,
    onTertiaryFixed = Navy,
    onTertiaryFixedVariant = Navy,
)

val LocalReducedMotion = staticCompositionLocalOf { false }

@Composable
fun PontinhoTheme(
    themePreference: ThemePreference = ThemePreference.SYSTEM,
    reducedMotion: Boolean = false,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themePreference) {
        ThemePreference.SYSTEM -> isSystemInDarkTheme()
        ThemePreference.LIGHT -> false
        ThemePreference.DARK -> true
    }
    CompositionLocalProvider(LocalReducedMotion provides reducedMotion) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
            typography = PontinhoTypography,
            content = content,
        )
    }
}
