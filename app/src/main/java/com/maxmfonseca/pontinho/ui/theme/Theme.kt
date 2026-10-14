package com.maxmfonseca.pontinho.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
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
    onPrimary = Zinc950,
    primaryContainer = EmeraldContainerDark,
    onPrimaryContainer = Zinc50,
    inversePrimary = Emerald,
    secondary = WarmYellow,
    onSecondary = Zinc950,
    secondaryContainer = Zinc800,
    onSecondaryContainer = Zinc50,
    tertiary = SkyBlue,
    onTertiary = Zinc950,
    tertiaryContainer = Zinc800,
    onTertiaryContainer = Zinc50,
    error = Coral,
    onError = Zinc950,
    errorContainer = CoralContainerDark,
    onErrorContainer = CoralContainer,
    background = Zinc950,
    onBackground = Zinc50,
    surface = Zinc900,
    onSurface = Zinc50,
    surfaceVariant = Zinc800,
    onSurfaceVariant = Zinc400,
    outline = Zinc700,
    outlineVariant = Zinc800,
    scrim = Zinc950,
    inverseSurface = Zinc50,
    inverseOnSurface = Zinc950,
    surfaceTint = EmeraldLight,
    surfaceBright = Zinc800,
    surfaceDim = Zinc950,
    surfaceContainer = Zinc900,
    surfaceContainerHigh = Zinc800,
    surfaceContainerHighest = Zinc800,
    surfaceContainerLow = Zinc900,
    surfaceContainerLowest = Zinc950,
    primaryFixed = EmeraldLight,
    primaryFixedDim = Emerald,
    onPrimaryFixed = Zinc950,
    onPrimaryFixedVariant = Zinc950,
    secondaryFixed = WarmYellow,
    secondaryFixedDim = WarmYellow,
    onSecondaryFixed = Zinc950,
    onSecondaryFixedVariant = Zinc950,
    tertiaryFixed = SkyBlue,
    tertiaryFixedDim = SkyBlue,
    onTertiaryFixed = Zinc950,
    onTertiaryFixedVariant = Zinc950,
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
    val view = LocalView.current
    if (!view.isInEditMode) SideEffect {
        view.context.findActivity()?.window?.let { window ->
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }
    CompositionLocalProvider(LocalReducedMotion provides reducedMotion) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
            typography = PontinhoTypography,
            content = content,
        )
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
