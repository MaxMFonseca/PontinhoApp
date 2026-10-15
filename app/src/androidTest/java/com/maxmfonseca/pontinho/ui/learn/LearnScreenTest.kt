package com.maxmfonseca.pontinho.ui.learn

import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performFirstLinkClick
import androidx.compose.ui.test.performScrollTo
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.maxmfonseca.pontinho.R
import com.maxmfonseca.pontinho.data.local.PontinhoDatabase
import com.maxmfonseca.pontinho.data.repository.RoomMatchRepository
import com.maxmfonseca.pontinho.data.repository.RoomPlayerRepository
import com.maxmfonseca.pontinho.di.AppContainer
import com.maxmfonseca.pontinho.settings.AppPreferences
import com.maxmfonseca.pontinho.settings.LanguagePreference
import com.maxmfonseca.pontinho.settings.PreferencesRepository
import com.maxmfonseca.pontinho.settings.ThemePreference
import com.maxmfonseca.pontinho.ui.navigation.PontinhoNavHost
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class LearnScreenTest {
    @get:Rule val composeRule = createComposeRule()
    private lateinit var db: PontinhoDatabase

    @Before fun openDatabase() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), PontinhoDatabase::class.java,
        ).build()
    }

    @After fun closeDatabase() { db.close() }

    @Test fun learnShowsAllTwentyNumberedRuleSectionsAndScoreTable() {
        composeRule.setContent { LearnScreen(onBack = {}) }

        composeRule.onNodeWithTag("learn_root").assertIsDisplayed()
        composeRule.onAllNodesWithTag("learn_rule_section", useUnmergedTree = true)
            .assertCountEquals(20)
        composeRule.onNodeWithTag("learn_score_table")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test fun completeRulesAreLocalizedInPortuguese() {
        showLearnInLocale("pt-BR")
        assertLocalizedHeadings("Regras do jogo", "1. Objetivo do jogo", "20. Fim da partida")
    }

    @Test fun completeRulesAreLocalizedInEnglish() {
        showLearnInLocale("en-US")
        assertLocalizedHeadings("Game rules", "1. Objective", "20. End of the match")
    }

    @Test fun aboutAndHomeLearnActionsNavigateToRulesAndBackToTheirSource() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = object : PreferencesRepository {
            override val preferences = MutableStateFlow(AppPreferences())
            override suspend fun setTheme(theme: ThemePreference) = Unit
            override suspend fun setLanguage(language: LanguagePreference) = Unit
            override suspend fun setReducedMotion(reducedMotion: Boolean?) = Unit
            override suspend fun setCameraEducationSeen(seen: Boolean) = Unit
        }
        val container = object : AppContainer {
            override val playerRepository = RoomPlayerRepository(db)
            override val matchRepository = RoomMatchRepository(db)
            override val preferencesRepository = preferences
        }
        composeRule.setContent { PontinhoNavHost(container) }

        composeRule.onNodeWithContentDescription(context.getString(R.string.home_settings)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.route_about)).performScrollTo().performClick()
        composeRule.onNodeWithText(context.getString(R.string.about_purpose)).performScrollTo().performFirstLinkClick()

        composeRule.onNodeWithTag("learn_root").assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.learn_rules_heading)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(context.getString(R.string.match_back)).performClick()
        composeRule.onNodeWithTag("about_root").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(context.getString(R.string.match_back)).performClick()
        composeRule.onNodeWithTag("settings_root").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(context.getString(R.string.match_back)).performClick()
        composeRule.onNodeWithContentDescription(context.getString(R.string.home_learn)).performClick()
        composeRule.onNodeWithTag("learn_root").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(context.getString(R.string.match_back)).performClick()
        composeRule.onNodeWithTag("home_root").assertIsDisplayed()
    }

    private fun showLearnInLocale(tag: String) {
        val localized = localizedContext(InstrumentationRegistry.getInstrumentation().targetContext, tag)
        composeRule.setContent {
            CompositionLocalProvider(
                LocalContext provides localized,
                LocalConfiguration provides localized.resources.configuration,
            ) { LearnScreen(onBack = {}) }
        }
    }

    private fun assertLocalizedHeadings(vararg expected: String) {
        expected.forEach { text ->
            composeRule.onNodeWithText(text).performScrollTo().assertIsDisplayed()
        }
    }

    private fun localizedContext(context: Context, tag: String): Context {
        val configuration = Configuration(context.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(tag))
        }
        return context.createConfigurationContext(configuration)
    }
}
