package com.maxmfonseca.pontinho.ui.home

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.test.platform.app.InstrumentationRegistry
import com.maxmfonseca.pontinho.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

class HomeScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun emptyHomeShowsBothEntryActionsAndNoAdvertisement() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var newMatchClicks = 0
        var learnClicks = 0
        composeRule.setContent {
            HomeScreen(
                state = HomeUiState(isLoading = false),
                onNewMatch = { newMatchClicks++ },
                onLearn = { learnClicks++ },
                onOpenDay = {},
            )
        }

        composeRule.onNodeWithText(context.getString(R.string.home_new_match)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.home_learn)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.home_empty_title)).assertIsDisplayed()
        composeRule.onNodeWithTag("advertisement").assertDoesNotExist()
        composeRule.runOnIdle {
            assertEquals(1, newMatchClicks)
            assertEquals(1, learnClicks)
        }
    }

    @Test
    fun populatedHomeShowsNewestDateCardFirst() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.setContent {
            HomeScreen(
                state = HomeUiState(
                    dateCards = listOf(
                        HomeDateCard(LocalDate.parse("2026-10-14"), 2, 1, listOf("Ana", "Bruno")),
                        HomeDateCard(LocalDate.parse("2026-10-12"), 1, 0, listOf("Carla")),
                    ),
                    isLoading = false,
                ),
                onNewMatch = {},
                onLearn = {},
                onOpenDay = {},
            )
        }

        composeRule.onNodeWithTag("home_day_0").assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.home_today)).assertIsDisplayed()
        composeRule.onNodeWithText("Ana · Bruno").assertIsDisplayed()
    }

    @Test fun settingsGlyphHasLocalizedTalkBackDescription() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.setContent {
            HomeScreen(HomeUiState(isLoading = false), {}, {}, {}, onSettings = {})
        }
        composeRule.onNodeWithContentDescription(context.getString(R.string.home_settings))
            .assertContentDescriptionEquals(context.getString(R.string.home_settings))
    }
}
