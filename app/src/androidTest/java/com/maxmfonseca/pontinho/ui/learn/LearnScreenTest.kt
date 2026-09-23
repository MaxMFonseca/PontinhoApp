package com.maxmfonseca.pontinho.ui.learn

import androidx.room.Room
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
        listOf(
            R.string.learn_penalties,
            R.string.learn_round_completion,
            R.string.learn_match_end,
            R.string.learn_hand_rotation,
            R.string.learn_wildcards,
            R.string.learn_card_values,
            R.string.learn_late_players,
        ).forEach { composeRule.onNodeWithText(context.getString(it)).assertIsDisplayed() }
        composeRule.onNodeWithText(context.getString(R.string.route_placeholder)).assertDoesNotExist()
    }

    @Test fun homeLearnActionNavigatesToRuleContent() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = object : PreferencesRepository {
            override val preferences = MutableStateFlow(AppPreferences())
            override suspend fun setTheme(theme: ThemePreference) = Unit
            override suspend fun setLanguage(language: LanguagePreference) = Unit
            override suspend fun setReducedMotion(reducedMotion: Boolean?) = Unit
        }
        val container = object : AppContainer {
            override val playerRepository = RoomPlayerRepository(db)
            override val matchRepository = RoomMatchRepository(db)
            override val preferencesRepository = preferences
        }
        composeRule.setContent { PontinhoNavHost(container) }

        composeRule.onNodeWithText(context.getString(R.string.home_learn)).performClick()

        composeRule.onNodeWithTag("learn_root").assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.learn_penalties)).assertIsDisplayed()
    }
}
