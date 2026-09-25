package com.maxmfonseca.pontinho.ui.history

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.test.platform.app.InstrumentationRegistry
import com.maxmfonseca.pontinho.R
import com.maxmfonseca.pontinho.domain.model.MatchId
import com.maxmfonseca.pontinho.domain.model.PlayerId
import com.maxmfonseca.pontinho.domain.repository.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

class HistoryScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun activeTotalsAreProvisionalAndSummaryExcludesAdvertisement() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var toggles = 0
        composeRule.setContent {
            HistoryScreen(
                state = HistoryUiState(
                    localDate = LocalDate.parse("2026-10-14"),
                    matches = listOf(
                        HistoryMatch(MatchId("active"), 2, false, listOf(HistoryPlayer(PlayerId("a"), "Ana", 112, false, false))),
                        HistoryMatch(MatchId("done"), 1, true, listOf(
                            HistoryPlayer(PlayerId("b"), "Bruno", 48, true, false),
                            HistoryPlayer(PlayerId("c"), "Carla", 112, false, true),
                        )),
                    ),
                    wins = listOf(DailyStanding(PlayerId("b"), "Bruno", 1)),
                    penalties = listOf(DailyStanding(PlayerId("c"), "Carla", 112)),
                    summaryExpanded = true,
                    isLoading = false,
                ),
                onBack = {}, onSettings = {}, onNewMatch = {}, onOpenMatch = {},
                onToggleSummary = { toggles++ },
            )
        }
        composeRule.onNodeWithText(context.getString(R.string.history_provisional)).assertIsDisplayed()
        composeRule.onNodeWithTag("history_winner_b", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("history_highest_c", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("1. Bruno").assertIsDisplayed()
        composeRule.onNodeWithTag("advertisement").assertDoesNotExist()
        composeRule.onNodeWithTag("daily_summary_toggle").performClick()
        composeRule.runOnIdle { assertEquals(1, toggles) }
    }

    @Test fun summaryToggleAnnouncesItsCurrentState() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.setContent {
            HistoryScreen(HistoryUiState(LocalDate.parse("2026-10-14"), summaryExpanded = false, isLoading = false), {}, {}, {}, {}, {})
        }
        composeRule.onNodeWithTag("daily_summary_toggle").assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription,
                context.getString(R.string.history_summary_collapsed),
            ),
        )
    }
}
