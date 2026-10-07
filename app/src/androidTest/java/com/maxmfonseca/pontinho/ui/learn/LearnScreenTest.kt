package com.maxmfonseca.pontinho.ui.learn

import androidx.room.Room
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performFirstLinkClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import android.content.res.Configuration
import java.util.Locale
import org.junit.Assert.assertTrue
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

    @Test fun learnShowsOfflineApprovedRulesWithoutDeferredCopy() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.setContent { LearnScreen(onBack = {}) }

        composeRule.onNodeWithTag("learn_root").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(context.getString(R.string.match_back)).assertIsDisplayed()
        listOf(
            R.string.learn_penalties,
            R.string.learn_round_completion,
            R.string.learn_match_end,
            R.string.learn_hand_rotation,
            R.string.learn_wildcards,
            R.string.learn_card_values,
            R.string.learn_scanner_cards,
            R.string.learn_late_players,
            R.string.score_correction_help,
        ).forEach { composeRule.onNodeWithText(context.getString(it)).performScrollTo().assertIsDisplayed() }
        listOf(
            R.string.learn_objective_title,
            R.string.learn_order_title,
            R.string.learn_wildcard_title,
            R.string.learn_scanner_title,
            R.string.learn_values_title,
            R.string.learn_round_title,
            R.string.learn_finish_title,
            R.string.learn_late_title,
        ).forEach { composeRule.onNodeWithText(context.getString(it)).performScrollTo().assertIsDisplayed() }
        composeRule.onNodeWithTag("learn_card_examples").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.route_placeholder)).assertDoesNotExist()
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
        composeRule.onNodeWithText(context.getString(R.string.learn_penalties)).assertIsDisplayed()
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

    @Test fun latePlayerHelpExplainsOpenAndUpcomingRoundsWithoutChangingPriorHistoryInBothLocales() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val portuguese = localizedContext(context, "pt-BR").getString(R.string.learn_late_players)
        val english = localizedContext(context, "en-US").getString(R.string.learn_late_players)

        assertTrue(portuguese.contains("sinal de menos cinza"))
        assertTrue(portuguese.contains("rodada em andamento"))
        assertTrue(portuguese.contains("zero"))
        assertTrue(portuguese.contains("próxima rodada"))
        assertTrue(english.contains("gray minus"))
        assertTrue(english.contains("open round"))
        assertTrue(english.contains("zero"))
        assertTrue(english.contains("next round"))
        val portugueseDialog = localizedContext(context, "pt-BR").getString(R.string.match_late_player_help)
        val englishDialog = localizedContext(context, "en-US").getString(R.string.match_late_player_help)
        assertTrue(portugueseDialog.contains("rodada em andamento"))
        assertTrue(portugueseDialog.contains("próxima rodada"))
        assertTrue(portugueseDialog.contains("zero"))
        assertTrue(portugueseDialog.contains("cinza"))
        assertTrue(englishDialog.contains("open round"))
        assertTrue(englishDialog.contains("next round"))
        assertTrue(englishDialog.contains("zero"))
        assertTrue(englishDialog.contains("gray"))
    }

    @Test fun portugueseCardExamplesExposeLocalizedTalkBackDescriptions() {
        showLearnInLocale("pt-BR")
        val context = localizedContext(InstrumentationRegistry.getInstrumentation().targetContext, "pt-BR")
        listOf(
            R.string.learn_example_ace_hearts_description,
            R.string.learn_example_two_diamonds_description,
            R.string.learn_example_king_spades_description,
            R.string.learn_example_chosen_wildcard_description,
        ).forEach { composeRule.onNodeWithContentDescription(context.getString(it)).performScrollTo().assertIsDisplayed() }
        composeRule.onNodeWithText("A♥", substring = true, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun englishCardExamplesExposeLocalizedTalkBackDescriptions() {
        showLearnInLocale("en-US")
        val context = localizedContext(InstrumentationRegistry.getInstrumentation().targetContext, "en-US")
        listOf(
            R.string.learn_example_ace_hearts_description,
            R.string.learn_example_two_diamonds_description,
            R.string.learn_example_king_spades_description,
            R.string.learn_example_chosen_wildcard_description,
        ).forEach { composeRule.onNodeWithContentDescription(context.getString(it)).performScrollTo().assertIsDisplayed() }
        composeRule.onNodeWithText("A♥", substring = true, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun scannerGuidanceUsesOnlyStandardCardsAndTheChosenWildcardInBothLocales() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val portuguese = localizedContext(context, "pt-BR").getString(R.string.learn_scanner_cards)
        val english = localizedContext(context, "en-US").getString(R.string.learn_scanner_cards)

        assertTrue(portuguese.contains("apenas cartas de um baralho padrão de 52 cartas"))
        assertTrue(portuguese.contains("figura e pela cor escolhidas"))
        assertTrue(!portuguese.contains("impresso", ignoreCase = true))
        assertTrue(english.contains("only cards from a standard 52-card deck"))
        assertTrue(english.contains("chosen rank and color"))
        assertTrue(!english.contains("printed", ignoreCase = true))
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

    private fun localizedContext(context: android.content.Context, tag: String): android.content.Context {
        val configuration = Configuration(context.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(tag))
        }
        return context.createConfigurationContext(configuration)
    }
}
