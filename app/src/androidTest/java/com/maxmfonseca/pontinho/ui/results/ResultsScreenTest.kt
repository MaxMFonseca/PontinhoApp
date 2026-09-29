package com.maxmfonseca.pontinho.ui.results

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.platform.app.InstrumentationRegistry
import com.maxmfonseca.pontinho.R
import com.maxmfonseca.pontinho.domain.model.MatchId
import com.maxmfonseca.pontinho.domain.model.PlayerId
import com.maxmfonseca.pontinho.ui.theme.LocalReducedMotion
import com.maxmfonseca.pontinho.ui.theme.PontinhoTheme
import com.maxmfonseca.pontinho.settings.ThemePreference
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.FileInputStream
import java.time.LocalDate
import java.util.Locale

class ResultsScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun tiedExtremesAndAllActionsRemainReachableAcrossLocalesThemesAndFontScales() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        var scenario by mutableStateOf(Triple("pt-BR", ThemePreference.LIGHT, 1f))
        val calls = mutableListOf<String>()
        composeRule.setContent {
            val (locale, theme, scale) = scenario
            val localized = base.createConfigurationContext(Configuration(base.resources.configuration).apply { setLocale(Locale.forLanguageTag(locale)) })
            CompositionLocalProvider(LocalContext provides localized, LocalResources provides localized.resources,
                LocalDensity provides Density(base.resources.displayMetrics.density, scale)) {
                PontinhoTheme(theme) { Surface { Box(Modifier.size(320.dp, 568.dp)) {
                    ResultsScreen(ResultsUiState(MatchId("ties"), players = listOf(
                        ResultPlayer(PlayerId("a"), "Julia", 33, 1, true, false),
                        ResultPlayer(PlayerId("b"), "Max", 33, 1, true, false),
                        ResultPlayer(PlayerId("c"), "Julia2", 110, 3, false, true),
                        ResultPlayer(PlayerId("d"), "Nome longo para verificar a leitura completa", 110, 3, false, true),
                    ), isFinished = true, isLoading = false),
                        { calls += "new" }, { calls += "details" }, { calls += "history" })
                } } }
            }
        }
        for (locale in listOf("pt-BR", "en-US")) for (theme in listOf(ThemePreference.LIGHT, ThemePreference.DARK)) for (scale in listOf(1f, 1.5f, 2f)) {
            composeRule.runOnIdle { scenario = Triple(locale, theme, scale); calls.clear() }
            val localized = base.createConfigurationContext(Configuration(base.resources.configuration).apply { setLocale(Locale.forLanguageTag(locale)) })
            for (id in listOf("a", "b")) {
                composeRule.onNodeWithTag("results_root").performScrollToNode(hasTestTag("result_player_$id"))
                composeRule.onNodeWithTag("result_player_$id").performScrollTo().assertIsDisplayed().assert(
                    SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, localized.getString(R.string.results_winner)))
            }
            for (id in listOf("c", "d")) {
                composeRule.onNodeWithTag("results_root").performScrollToNode(hasTestTag("result_player_$id"))
                composeRule.onNodeWithTag("result_player_$id").performScrollTo().assertIsDisplayed().assert(
                    SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, localized.getString(R.string.results_highest_penalty)))
            }
            composeRule.onNodeWithText(localized.getString(R.string.results_lower_better)).assertDoesNotExist()
            composeRule.onNodeWithText(localized.getString(R.string.results_winner)).assertDoesNotExist()
            composeRule.onNodeWithText(localized.getString(R.string.results_highest_penalty)).assertDoesNotExist()
            if (locale == "en-US" && theme == ThemePreference.DARK && scale == 2f) capture("task6-results-en-dark-200.png")
            for (action in listOf(R.string.results_new_match, R.string.results_details, R.string.results_back_history)) {
                composeRule.onNodeWithTag("results_root").performScrollToNode(hasText(localized.getString(action)))
                composeRule.onNodeWithText(localized.getString(action)).performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
            }
            composeRule.runOnIdle { assertEquals(listOf("new", "details", "history"), calls) }
            if (locale == "pt-BR" && theme == ThemePreference.LIGHT && scale == 1f) {
                composeRule.onNodeWithTag("results_root").performScrollToIndex(0)
                capture("task6-results-pt-light-100.png")
            }
        }
    }

    private fun capture(name: String) {
        composeRule.waitForIdle()
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("screencap -p /sdcard/Download/$name")
        FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
        descriptor.close()
    }

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
        composeRule.onNodeWithTag("result_player_a").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, context.getString(R.string.results_winner)))
        composeRule.onNodeWithTag("result_player_c").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, context.getString(R.string.results_highest_penalty)))
        composeRule.onNodeWithText(context.getString(R.string.results_lower_better)).assertDoesNotExist()
        composeRule.onNodeWithText(context.getString(R.string.results_winner)).assertDoesNotExist()
        composeRule.onNodeWithText(context.getString(R.string.results_highest_penalty)).assertDoesNotExist()
        composeRule.onNodeWithTag("results_celebration").assertDoesNotExist()
        for (action in listOf(R.string.results_new_match, R.string.results_details, R.string.results_back_history)) {
            composeRule.onNodeWithTag("results_root").performScrollToNode(hasText(context.getString(action)))
            composeRule.onNodeWithText(context.getString(action)).performScrollTo().assertIsDisplayed()
        }
        composeRule.onNodeWithTag("advertisement").assertDoesNotExist()
    }

    @Test fun finishedResultsHaveNoCelebrationEvenWithoutReducedMotion() {
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

        composeRule.onNodeWithTag("results_celebration", useUnmergedTree = true).assertDoesNotExist()
    }
}
