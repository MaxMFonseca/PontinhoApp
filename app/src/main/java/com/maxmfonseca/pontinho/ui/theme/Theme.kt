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
    primaryContainer = EmeraldContainer,
    onPrimaryContainer = Navy,
    inversePrimary = EmeraldLight,
    secondary = WarmYellow,
    onSecondary = Navy,
    secondaryContainer = YellowContainer,
    onSecondaryContainer = Navy,
    tertiary = SkyBlue,
    onTertiary = Navy,
    tertiaryContainer = BlueContainer,
    onTertiaryContainer = Navy,
    error = CoralStrong,
    onError = OffWhite,
    errorContainer = CoralContainer,
    onErrorContainer = Navy,
    background = OffWhite,
    onBackground = Navy,
    surface = WarmSurface,
    onSurface = Navy,
    surfaceVariant = WarmSurfaceHigh,
    onSurfaceVariant = NeutralText,
    outline = NeutralOutline,
    outlineVariant = WarmSurfaceHigh,
    scrim = Navy,
    inverseSurface = Navy,
    inverseOnSurface = OffWhite,
    surfaceTint = Emerald,
    surfaceBright = WarmSurface,
    surfaceDim = WarmSurfaceHigh,
    surfaceContainer = WarmSurfaceLow,
    surfaceContainerHigh = WarmSurfaceHigh,
    surfaceContainerHighest = WarmSurfaceHigh,
    surfaceContainerLow = WarmSurface,
    surfaceContainerLowest = WarmSurface,
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
    primaryContainer = EmeraldContainerDark,
    onPrimaryContainer = OffWhite,
    inversePrimary = Emerald,
    secondary = WarmYellow,
    onSecondary = Navy,
    secondaryContainer = YellowContainerDark,
    onSecondaryContainer = YellowContainer,
    tertiary = SkyBlue,
    onTertiary = Navy,
    tertiaryContainer = BlueContainerDark,
    onTertiaryContainer = BlueContainer,
    error = Coral,
    onError = Navy,
    errorContainer = CoralContainerDark,
    onErrorContainer = CoralContainer,
    background = Navy,
    onBackground = OffWhite,
    surface = NavySurface,
    onSurface = OffWhite,
    surfaceVariant = NavySurfaceHigh,
    onSurfaceVariant = NeutralTextLight,
    outline = NeutralOutlineDark,
    outlineVariant = NavySurfaceHigh,
    scrim = Navy,
    inverseSurface = OffWhite,
    inverseOnSurface = Navy,
    surfaceTint = EmeraldLight,
    surfaceBright = NavySurfaceHigh,
    surfaceDim = Navy,
    surfaceContainer = NavySurface,
    surfaceContainerHigh = NavySurfaceHigh,
    surfaceContainerHighest = NavySurfaceHigh,
    surfaceContainerLow = NavySurface,
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
