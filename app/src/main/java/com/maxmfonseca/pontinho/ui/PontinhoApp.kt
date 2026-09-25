package com.maxmfonseca.pontinho.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.maxmfonseca.pontinho.di.AppContainer
import com.maxmfonseca.pontinho.settings.AppPreferences
import com.maxmfonseca.pontinho.ui.navigation.PontinhoNavHost
import com.maxmfonseca.pontinho.ui.theme.PontinhoTheme

@Composable
fun PontinhoApp(container: AppContainer) {
    val preferences by container.preferencesRepository.preferences.collectAsStateWithLifecycle(
        initialValue = AppPreferences(),
    )
    val systemReducedMotion = rememberSystemReducedMotion()

    PontinhoTheme(
        themePreference = preferences.theme,
        reducedMotion = preferences.resolveReducedMotion(systemReducedMotion),
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            PontinhoNavHost(container = container)
        }
    }
}
