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
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.platform.app.InstrumentationRegistry
import com.maxmfonseca.pontinho.R
import com.maxmfonseca.pontinho.settings.AppPreferences
import com.maxmfonseca.pontinho.settings.LanguagePreference
import com.maxmfonseca.pontinho.settings.ThemePreference
import com.maxmfonseca.pontinho.ui.theme.PontinhoTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import android.content.res.Configuration
import androidx.compose.runtime.*
import androidx.compose.material3.Surface
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import com.maxmfonseca.pontinho.ui.learn.LearnScreen
import com.maxmfonseca.pontinho.ui.about.AboutScreen
import java.io.FileInputStream
import java.util.Locale

class SettingsScreensTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun secondaryScreensKeepActionsAndOfflineContentReachableAcrossLocalesThemesAndTextSizes() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        var scenario by mutableStateOf(Triple("pt-BR", ThemePreference.LIGHT, 1f))
        var screen by mutableStateOf("settings")
        var preferences by mutableStateOf(AppPreferences())
        val calls = mutableListOf<String>()
        composeRule.setContent {
            val (locale, theme, scale) = scenario
            val localized = base.createConfigurationContext(Configuration(base.resources.configuration).apply { setLocale(Locale.forLanguageTag(locale)) })
            CompositionLocalProvider(LocalContext provides localized, LocalResources provides localized.resources,
                LocalDensity provides Density(base.resources.displayMetrics.density, scale)) {
                PontinhoTheme(theme, reducedMotion = true) { Surface { Box(Modifier.size(320.dp, 568.dp)) {
                    key(screen, scenario) {
                        when (screen) {
                            "settings" -> SettingsScreen({ calls += "back" }, { calls += "appearance" }, { calls += "language" }, { calls += "learn" }, { calls += "about" })
                            "appearance" -> AppearanceScreen(preferences, { calls += "back" }, { preferences = preferences.copy(theme = it) })
                            "language" -> LanguageScreen(preferences.language, { calls += "back" }, { preferences = preferences.copy(language = it) })
                            "learn" -> LearnScreen({ calls += "back" })
                            "about" -> AboutScreen("1.0.0", { calls += "back" }, { calls += "learn" })
                        }
                    }
                } } }
            }
        }
        val radios = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)
        val switches = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)
        for (locale in listOf("pt-BR", "en-US")) for (theme in listOf(ThemePreference.LIGHT, ThemePreference.DARK)) for (scale in listOf(1f, 1.5f, 2f)) {
            val localized = base.createConfigurationContext(Configuration(base.resources.configuration).apply { setLocale(Locale.forLanguageTag(locale)) })
            val capture = locale == "pt-BR" && theme == ThemePreference.LIGHT && scale == 1f || locale == "en-US" && theme == ThemePreference.DARK && scale == 2f
            for (destination in listOf("settings", "appearance", "language", "learn", "about")) {
                composeRule.runOnIdle { scenario = Triple(locale, theme, scale); screen = destination; preferences = AppPreferences(); calls.clear() }
                val back = composeRule.onNodeWithContentDescription(localized.getString(R.string.match_back))
                back.performScrollTo().assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).performClick()
                when (destination) {
                    "settings" -> {
                        composeRule.onAllNodes(hasClickAction()).assertCountEquals(5)
                        for ((title, summary) in listOf(R.string.route_appearance to R.string.settings_appearance_summary,
                            R.string.route_language to R.string.settings_language_summary,
                            R.string.route_learn to R.string.settings_learn_summary,
                            R.string.route_about to R.string.settings_about_summary)) {
                            composeRule.onNodeWithText(localized.getString(title)).performScrollTo().assertIsDisplayed()
                                .assertTextContains(localized.getString(summary)).assertHeightIsAtLeast(48.dp).performClick()
                        }
                        composeRule.runOnIdle { assertEquals(listOf("back", "appearance", "language", "learn", "about"), calls) }
                        back.performScrollTo()
                    }
                    "appearance" -> {
                        composeRule.onAllNodes(radios).assertCountEquals(3)
                        composeRule.onAllNodes(switches).assertCountEquals(0)
                        for (title in listOf(R.string.appearance_theme_light, R.string.appearance_theme_dark, R.string.appearance_theme_system)) {
                            composeRule.onNodeWithText(localized.getString(title)).performScrollTo().assertHeightIsAtLeast(48.dp).performClick().assertIsSelected()
                        }
                        composeRule.onNodeWithTag("reduced_motion_switch").assertDoesNotExist()
                    }
                    "language" -> {
                        composeRule.onAllNodes(radios).assertCountEquals(3)
                        for ((title, value) in listOf(R.string.language_english to LanguagePreference.ENGLISH_UNITED_STATES,
                            R.string.language_portuguese to LanguagePreference.PORTUGUESE_BRAZIL,
                            R.string.language_device to LanguagePreference.SYSTEM)) {
                            composeRule.onNodeWithText(localized.getString(title)).performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick().assertIsSelected()
                            composeRule.runOnIdle { assertEquals(value, preferences.language) }
                        }
                        back.performScrollTo()
                    }
                    "learn" -> {
                        for (text in listOf(R.string.learn_penalties, R.string.learn_hand_rotation, R.string.learn_wildcards,
                            R.string.learn_card_values, R.string.learn_round_completion, R.string.learn_match_end,
                            R.string.learn_late_players, R.string.score_correction_help)) {
                            composeRule.onNodeWithText(localized.getString(text)).performScrollTo().assertIsDisplayed()
                        }
                        for (description in listOf(R.string.learn_example_ace_hearts_description, R.string.learn_example_two_diamonds_description,
                            R.string.learn_example_king_spades_description, R.string.learn_example_printed_joker_description)) {
                            composeRule.onNodeWithContentDescription(localized.getString(description)).performScrollTo().assertIsDisplayed()
                        }
                    }
                    "about" -> {
                        composeRule.onNodeWithText(localized.getString(R.string.about_version, "1.0.0")).performScrollTo().assertIsDisplayed()
                        composeRule.onNodeWithText(localized.getString(R.string.about_purpose)).performScrollTo().assertIsDisplayed()
                        composeRule.onNodeWithText("maxmfonseca12@gmail.com").performScrollTo().assertIsDisplayed()
                        composeRule.onNodeWithText(localized.getString(R.string.about_purpose)).performScrollTo().performFirstLinkClick()
                        composeRule.onNodeWithTag("about_learn").assertDoesNotExist()
                        composeRule.runOnIdle { assertEquals(listOf("back", "learn"), calls) }
                    }
                }
                listOf("Backup", "Remove ads", "Remover anúncios", "Privacy", "Privacidade", "License", "Licenças", "Camera", "Câmera")
                    .forEach { composeRule.onNodeWithText(it).assertDoesNotExist() }
                if (capture) {
                    composeRule.mainClock.advanceTimeBy(500)
                    composeRule.waitForIdle()
                    val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("screencap -p /sdcard/Download/task7-$destination-$locale-$scale.png")
                    FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
                    descriptor.close()
                }
            }
        }
    }

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
        composeRule.onNodeWithContentDescription(context.getString(R.string.match_back)).assertIsDisplayed()
        composeRule.onNodeWithText("Backup").assertDoesNotExist()
        composeRule.onNodeWithText("Remover anúncios").assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(listOf("appearance", "language", "learn", "about"), clicks) }
    }

    @Test fun appearanceSupportsThemesWithoutMotionControlAtLargeText() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var theme: ThemePreference? = null
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                PontinhoTheme(themePreference = ThemePreference.DARK, reducedMotion = true) {
                    AppearanceScreen(
                        preferences = AppPreferences(theme = ThemePreference.SYSTEM, reducedMotion = null),
                        onBack = {}, onThemeSelected = { theme = it },
                    )
                }
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.appearance_theme_dark)).performClick()
        composeRule.onNodeWithTag("reduced_motion_switch").assertDoesNotExist()
        composeRule.onNodeWithTag("appearance_root").assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals(ThemePreference.DARK, theme)
        }
    }

    @Test fun storedMotionPreferenceDoesNotExposeAMotionControl() {
        composeRule.setContent {
            PontinhoTheme(reducedMotion = true) {
                AppearanceScreen(AppPreferences(reducedMotion = false), {}, {})
            }
        }
        composeRule.onNodeWithTag("reduced_motion_switch").assertDoesNotExist()
        composeRule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)).assertCountEquals(3)
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
