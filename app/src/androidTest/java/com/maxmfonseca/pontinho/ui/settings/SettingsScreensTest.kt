package com.maxmfonseca.pontinho.ui.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import com.maxmfonseca.pontinho.R
import com.maxmfonseca.pontinho.settings.AppPreferences
import com.maxmfonseca.pontinho.settings.LanguagePreference
import com.maxmfonseca.pontinho.settings.ThemePreference
import com.maxmfonseca.pontinho.ui.theme.PontinhoTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SettingsScreensTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun settingsShowsOnlyImplementedDestinations() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val clicks = mutableListOf<String>()
        composeRule.setContent {
            SettingsScreen(
                onBack = {},
                onAppearance = { clicks += "appearance" },
                onLanguage = { clicks += "language" },
                onLearn = { clicks += "learn" },
                onAbout = { clicks += "about" },
            )
        }

        listOf(R.string.route_appearance, R.string.route_language, R.string.route_learn, R.string.route_about)
            .forEach { composeRule.onNodeWithText(context.getString(it)).performClick() }
        composeRule.onNodeWithText("Backup").assertDoesNotExist()
        composeRule.onNodeWithText("Remover anúncios").assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(listOf("appearance", "language", "learn", "about"), clicks) }
    }

    @Test fun appearanceSupportsEveryThemeAndMotionChoiceAtLargeText() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var theme: ThemePreference? = null
        var motion: Boolean? = false
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                PontinhoTheme(themePreference = ThemePreference.DARK, reducedMotion = true) {
                    AppearanceScreen(
                        preferences = AppPreferences(theme = ThemePreference.SYSTEM, reducedMotion = null),
                        onBack = {}, onThemeSelected = { theme = it }, onReducedMotionSelected = { motion = it },
                    )
                }
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.appearance_theme_dark)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.appearance_motion_reduce)).performClick()
        composeRule.onNodeWithTag("appearance_root").assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals(ThemePreference.DARK, theme)
            assertEquals(true, motion)
        }
    }

    @Test fun languageSupportsDevicePortugueseAndEnglish() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var selected: LanguagePreference? = null
        composeRule.setContent {
            LanguageScreen(
                selected = LanguagePreference.SYSTEM,
                onBack = {},
                onLanguageSelected = { selected = it },
            )
        }

        composeRule.onNodeWithTag("language_system").assertIsSelected()
        composeRule.onNodeWithText(context.getString(R.string.language_english)).performClick()
        composeRule.runOnIdle { assertEquals(LanguagePreference.ENGLISH_UNITED_STATES, selected) }
        composeRule.onNodeWithText(context.getString(R.string.language_portuguese)).assertIsDisplayed()
    }

    @Test fun languageChoicesRemainReachableOnSmallPhoneAtLargeText() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                Box(Modifier.size(width = 320.dp, height = 480.dp)) {
                    LanguageScreen(LanguagePreference.SYSTEM, {}, {})
                }
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.language_english))
            .performScrollTo()
            .assertIsDisplayed()
    }
}
