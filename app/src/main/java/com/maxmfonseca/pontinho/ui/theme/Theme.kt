package com.maxmfonseca.pontinho.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColorScheme = lightColorScheme(
    primary = Emerald,
    onPrimary = Color.White,
    secondary = WarmYellow,
    onSecondary = Navy,
    tertiary = Coral,
    background = OffWhite,
    onBackground = Navy,
    surface = Color.White,
    onSurface = Navy,
)

private val DarkColorScheme = darkColorScheme(
    primary = EmeraldLight,
    onPrimary = Navy,
    secondary = WarmYellow,
    onSecondary = Navy,
    tertiary = Coral,
    background = Navy,
    onBackground = OffWhite,
    surface = NavySurface,
    onSurface = OffWhite,
)

@Composable
fun PontinhoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        typography = PontinhoTypography,
        content = content,
    )
}
