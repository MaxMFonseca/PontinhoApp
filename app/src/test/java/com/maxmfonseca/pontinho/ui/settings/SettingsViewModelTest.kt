package com.maxmfonseca.pontinho.ui.settings

import com.maxmfonseca.pontinho.settings.AppPreferences
import com.maxmfonseca.pontinho.settings.LanguagePreference
import com.maxmfonseca.pontinho.settings.PreferencesRepository
import com.maxmfonseca.pontinho.settings.ThemePreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    @Test
    fun preferencesDriveAppearanceAndLanguageState() = runTest {
        val repository = FakePreferencesRepository()
        val localeController = RecordingLocaleController()
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val viewModel = SettingsViewModel(repository, localeController, dispatcher, dispatcher)

            advanceUntilIdle()
            assertEquals(AppPreferences(), viewModel.uiState.value.preferences)

            viewModel.setTheme(ThemePreference.DARK)
            advanceUntilIdle()
            viewModel.setReducedMotion(true)
            advanceUntilIdle()
            viewModel.setLanguage(LanguagePreference.ENGLISH_UNITED_STATES)
            advanceUntilIdle()

            assertEquals(ThemePreference.DARK, viewModel.uiState.value.preferences.theme)
            assertEquals(true, viewModel.uiState.value.preferences.reducedMotion)
            assertEquals(LanguagePreference.ENGLISH_UNITED_STATES, viewModel.uiState.value.preferences.language)
            assertEquals(listOf(LanguagePreference.ENGLISH_UNITED_STATES), localeController.applied)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun systemLanguageUsesEmptyAndroidXLocaleOverride() {
        assertEquals("", LanguagePreference.SYSTEM.applicationLocaleTags())
        assertEquals("pt-BR", LanguagePreference.PORTUGUESE_BRAZIL.applicationLocaleTags())
        assertEquals("en-US", LanguagePreference.ENGLISH_UNITED_STATES.applicationLocaleTags())
    }

    private class FakePreferencesRepository : PreferencesRepository {
        private val mutable = MutableStateFlow(AppPreferences())
        override val preferences = mutable

        override suspend fun setTheme(theme: ThemePreference) {
            mutable.update { it.copy(theme = theme) }
        }

        override suspend fun setLanguage(language: LanguagePreference) {
            mutable.update { it.copy(language = language) }
        }

        override suspend fun setReducedMotion(reducedMotion: Boolean?) {
            mutable.update { it.copy(reducedMotion = reducedMotion) }
        }

        override suspend fun setCameraEducationSeen(seen: Boolean) {
            mutable.update { it.copy(cameraEducationSeen = seen) }
        }
    }

    private class RecordingLocaleController : AppLocaleController {
        val applied = mutableListOf<LanguagePreference>()
        override fun apply(language: LanguagePreference) {
            applied += language
        }
    }
}
