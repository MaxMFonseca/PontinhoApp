package com.maxmfonseca.pontinho.ui.settings

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maxmfonseca.pontinho.settings.AppPreferences
import com.maxmfonseca.pontinho.settings.LanguagePreference
import com.maxmfonseca.pontinho.settings.PreferencesRepository
import com.maxmfonseca.pontinho.settings.ThemePreference
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SettingsUiState(val preferences: AppPreferences = AppPreferences())

interface AppLocaleController {
    fun apply(language: LanguagePreference)
}

class AndroidXAppLocaleController(@Suppress("UNUSED_PARAMETER") context: Context) : AppLocaleController {
    override fun apply(language: LanguagePreference) {
        AppCompatDelegate.setApplicationLocales(
            LocaleListCompat.forLanguageTags(language.applicationLocaleTags()),
        )
    }
}

class SettingsViewModel(
    private val preferencesRepository: PreferencesRepository,
    private val localeController: AppLocaleController,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) : ViewModel() {
    val uiState: StateFlow<SettingsUiState> = preferencesRepository.preferences
        .map(::SettingsUiState)
        .stateIn(viewModelScope, SharingStarted.Eagerly, SettingsUiState())

    fun setTheme(theme: ThemePreference) = update {
        preferencesRepository.setTheme(theme)
    }

    fun setReducedMotion(reducedMotion: Boolean?) = update {
        preferencesRepository.setReducedMotion(reducedMotion)
    }

    fun setLanguage(language: LanguagePreference) {
        viewModelScope.launch(mainDispatcher) {
            withContext(dispatcher) { preferencesRepository.setLanguage(language) }
            localeController.apply(language)
        }
    }

    private fun update(block: suspend () -> Unit) {
        viewModelScope.launch(dispatcher) { block() }
    }
}
