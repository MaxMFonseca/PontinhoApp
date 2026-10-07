package com.maxmfonseca.pontinho.ui.about

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.espresso.Espresso.pressBack
import com.maxmfonseca.pontinho.di.AppContainer
import com.maxmfonseca.pontinho.domain.repository.MatchRepository
import com.maxmfonseca.pontinho.domain.repository.PlayerRepository
import com.maxmfonseca.pontinho.settings.AppPreferences
import com.maxmfonseca.pontinho.settings.LanguagePreference
import com.maxmfonseca.pontinho.settings.PreferencesRepository
import com.maxmfonseca.pontinho.settings.ThemePreference
import com.maxmfonseca.pontinho.ui.navigation.Destination
import com.maxmfonseca.pontinho.ui.navigation.PontinhoNavHost
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AboutNavigationTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun navHostPersistsEducationOnlyWhenSystemBackExplicitlyDismissesIt() {
        val preferences = RecordingPreferencesRepository()
        val container = object : AppContainer {
            override val playerRepository: PlayerRepository get() = error("not used")
            override val matchRepository: MatchRepository get() = error("not used")
            override val preferencesRepository: PreferencesRepository = preferences
        }
        composeRule.setContent {
            PontinhoNavHost(container = container, startDestination = Destination.About.route)
        }

        composeRule.onNodeWithTag("about_camera_education").performScrollTo().performClick()
        composeRule.onNodeWithTag("camera_education_root").assertExists()
        composeRule.runOnIdle {
            assertEquals(0, preferences.educationWrites)
            assertFalse(preferences.preferences.value.cameraEducationSeen)
        }

        pressBack()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            preferences.preferences.value.cameraEducationSeen
        }
        composeRule.onNodeWithTag("about_root").assertExists()
        composeRule.runOnIdle {
            assertEquals(1, preferences.educationWrites)
            assertTrue(preferences.preferences.value.cameraEducationSeen)
        }
    }

    private class RecordingPreferencesRepository : PreferencesRepository {
        override val preferences = MutableStateFlow(AppPreferences())
        var educationWrites = 0

        override suspend fun setTheme(theme: ThemePreference) = Unit
        override suspend fun setLanguage(language: LanguagePreference) = Unit
        override suspend fun setReducedMotion(reducedMotion: Boolean?) = Unit
        override suspend fun setCameraEducationSeen(seen: Boolean) {
            educationWrites++
            preferences.update { it.copy(cameraEducationSeen = seen) }
        }
    }
}
