package com.maxmfonseca.pontinho.ui.results

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.test.platform.app.InstrumentationRegistry
import com.maxmfonseca.pontinho.R
import com.maxmfonseca.pontinho.domain.model.MatchId
import com.maxmfonseca.pontinho.domain.model.PlayerId
import com.maxmfonseca.pontinho.ui.theme.LocalReducedMotion
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

class ResultsScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun fixtureShowsWinnerAndHighestPenaltyWithoutCelebratingTheLoss() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.setContent {
            CompositionLocalProvider(LocalReducedMotion provides true) {
                ResultsScreen(
                    state = ResultsUiState(
                        matchId = MatchId("result"), localDate = LocalDate.parse("2026-10-14"),
                        players = listOf(
                            ResultPlayer(PlayerId("a"), "Ana", 48, 1, true, false),
                            ResultPlayer(PlayerId("b"), "Bruno", 77, 2, false, false),
                            ResultPlayer(PlayerId("d"), "Diego", 91, 3, false, false),
                            ResultPlayer(PlayerId("c"), "Carla", 112, 4, false, true),
                        ), resultRevision = 1, isFinished = true, isLoading = false,
                    ), onNewMatch = {}, onOpenMatch = {}, onOpenHistory = {},
                )
            }
        }
        composeRule.onNodeWithTag("result_winner_a").assertIsDisplayed()
        composeRule.onNodeWithTag("result_highest_c").assertIsDisplayed()
        composeRule.onNodeWithTag("result_winner_c").assertDoesNotExist()
        composeRule.onNodeWithTag("results_celebration").assertDoesNotExist()
        composeRule.onNodeWithText(context.getString(R.string.results_new_match)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.results_details)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.results_back_history)).assertIsDisplayed()
        composeRule.onNodeWithTag("advertisement").assertDoesNotExist()
    }

    @Test fun celebrationIsVisibleWithoutReducedMotionButDecorativeForTalkBack() {
        composeRule.setContent {
            CompositionLocalProvider(LocalReducedMotion provides false) {
                ResultsScreen(
                    state = ResultsUiState(
                        matchId = MatchId("result"),
                        localDate = LocalDate.parse("2026-10-14"),
                        players = emptyList(),
                        resultRevision = 1,
                        isFinished = true,
                        isLoading = false,
                    ),
                    onNewMatch = {}, onOpenMatch = {}, onOpenHistory = {},
                )
            }
        }

        composeRule.onNodeWithTag("results_celebration", useUnmergedTree = true)
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Text))
    }
}
