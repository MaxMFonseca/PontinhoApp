package com.maxmfonseca.pontinho.ui.history

import android.annotation.SuppressLint
import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalProvidableLocaleList
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.platform.app.InstrumentationRegistry
import com.maxmfonseca.pontinho.R
import com.maxmfonseca.pontinho.domain.model.MatchId
import com.maxmfonseca.pontinho.domain.model.PlayerId
import com.maxmfonseca.pontinho.domain.model.PlayerNameKey
import com.maxmfonseca.pontinho.domain.repository.*
import com.maxmfonseca.pontinho.ui.theme.PontinhoTheme
import com.maxmfonseca.pontinho.settings.ThemePreference
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.io.FileInputStream
import java.util.Locale

class HistoryScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @SuppressLint("RestrictedApi") // Compose explicitly exposes this locale provider for tests.
    @Test fun metricsSnapshotsAndActionsAreReachableAcrossLocalesThemesAndFontScales() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        var scenario by mutableStateOf(Triple("pt-BR", ThemePreference.LIGHT, 1f))
        val calls = mutableListOf<String>()
        composeRule.setContent {
            val (locale, theme, scale) = scenario
            val localized = base.createConfigurationContext(Configuration(base.resources.configuration).apply { setLocale(Locale.forLanguageTag(locale)) })
            CompositionLocalProvider(LocalContext provides localized, LocalResources provides localized.resources,
                LocalProvidableLocaleList provides androidx.compose.ui.text.intl.LocaleList(locale),
                LocalDensity provides Density(base.resources.displayMetrics.density, scale)) {
                PontinhoTheme(theme) { Surface { Box(Modifier.size(320.dp, 568.dp)) {
                    HistoryScreen(HistoryUiState(LocalDate.parse("2026-10-14"),
                        matches = listOf(
                            HistoryMatch(MatchId("active"), 3, false, listOf(HistoryPlayer(PlayerId("a"), "Julia2", 999, false, false))),
                            HistoryMatch(MatchId("first"), 2, true, listOf(HistoryPlayer(PlayerId("a"), "Julia", 33, true, false))),
                            HistoryMatch(MatchId("second"), 1, true, listOf(HistoryPlayer(PlayerId("a"), "Julia2", 110, false, true))),
                        ),
                        wins = listOf(DailyStanding(PlayerNameKey("julia"), "Julia", 1), DailyStanding(PlayerNameKey("max"), "Max", 1), DailyStanding(PlayerNameKey("julia2"), "Julia2", 0)),
                        penalties = listOf(DailyStanding(PlayerNameKey("julia2"), "Julia2", 110), DailyStanding(PlayerNameKey("max"), "Max", 110), DailyStanding(PlayerNameKey("julia"), "Julia", 33)),
                        summaryExpanded = true, isLoading = false),
                        { calls += "back" }, { calls += "settings" }, { calls += "new" }, { calls += it.value }, { calls += "toggle" })
                } } }
            }
        }
        for (locale in listOf("pt-BR", "en-US")) for (theme in listOf(ThemePreference.LIGHT, ThemePreference.DARK)) for (scale in listOf(1f, 1.5f, 2f)) {
            composeRule.runOnIdle { scenario = Triple(locale, theme, scale); calls.clear() }
            val localized = base.createConfigurationContext(Configuration(base.resources.configuration).apply { setLocale(Locale.forLanguageTag(locale)) })
            val root = composeRule.onNodeWithTag("history_root")
            root.performScrollToIndex(0)
            composeRule.onNodeWithText(if (locale == "pt-BR") "14 de outubro de 2026" else "October 14, 2026").assertIsDisplayed()
            for (label in listOf(R.string.match_back, R.string.home_settings)) {
                composeRule.onNodeWithContentDescription(localized.getString(label)).assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).performClick()
            }
            root.performScrollToNode(hasText(localized.getString(R.string.home_new_match)))
            composeRule.onNodeWithText(localized.getString(R.string.home_new_match)).performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
            root.performScrollToNode(hasTestTag("daily_summary_toggle"))
            composeRule.onNodeWithTag("daily_summary_toggle").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
            for (metric in listOf("wins", "points")) {
                root.performScrollToNode(hasTestTag("daily_${metric}_card"))
                for (name in listOf("julia", "max", "julia2")) composeRule.onNodeWithTag("daily_${metric}_$name").performScrollTo().assertIsDisplayed()
                if (locale == "pt-BR" && theme == ThemePreference.LIGHT && scale == 1f || locale == "en-US" && theme == ThemePreference.DARK && scale == 2f)
                    capture("task6-history-$metric-$locale-$scale.png")
            }
            root.performScrollToNode(hasTestTag("history_match_0"))
            composeRule.onNodeWithText(localized.getString(R.string.history_provisional), useUnmergedTree = true).performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithTag("history_match_0").assertTextContains(localized.getString(R.string.results_total, 999))
            if (locale == "en-US" && theme == ThemePreference.DARK && scale == 2f) capture("task6-history-provisional-en-dark-200.png")
            for ((index, name) in listOf(1 to "Julia", 2 to "Julia2")) {
                root.performScrollToNode(hasTestTag("history_match_$index"))
                composeRule.onNodeWithTag("history_match_$index").assertTextContains(name).assertHeightIsAtLeast(48.dp).performClick()
            }
            composeRule.runOnIdle { assertEquals(listOf("back", "settings", "new", "toggle", "first", "second"), calls) }
        }
    }

    private fun capture(name: String) {
        composeRule.waitForIdle()
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("screencap -p /sdcard/Download/$name")
        FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
        descriptor.close()
    }

    @Test fun dailyMetricsKeepJuliaAndJulia2SeparateUnderStableNameKeys() {
        composeRule.setContent {
            HistoryScreen(
                state = HistoryUiState(
                    localDate = LocalDate.parse("2026-10-14"), summaryExpanded = true, isLoading = false,
                    wins = listOf(
                        DailyStanding(PlayerNameKey("julia"), "Julia", 1),
                        DailyStanding(PlayerNameKey("max"), "Max", 1),
                        DailyStanding(PlayerNameKey("julia2"), "Julia2", 0),
                    ),
                    penalties = listOf(
                        DailyStanding(PlayerNameKey("julia2"), "Julia2", 110),
                        DailyStanding(PlayerNameKey("max"), "Max", 110),
                        DailyStanding(PlayerNameKey("julia"), "Julia", 33),
                    ),
                ), {}, {}, {}, {}, {},
            )
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for ((metric, rows) in listOf(
            "wins" to listOf(Triple("julia", "Julia", 1), Triple("max", "Max", 1), Triple("julia2", "Julia2", 0)),
            "points" to listOf(Triple("julia2", "Julia2", 110), Triple("max", "Max", 110), Triple("julia", "Julia", 33)),
        )) {
            composeRule.onNodeWithTag("daily_${metric}_card").performScrollTo().assertExists()
            for ((key, name, value) in rows) {
                val row = composeRule.onNode(hasTestTag("daily_${metric}_$key") and hasAnyAncestor(hasTestTag("daily_${metric}_card")))
                row.performScrollTo().assertIsDisplayed().assertTextContains(name)
                row.assertTextContains(context.getString(if (metric == "wins") R.string.history_wins else R.string.history_penalties) + ": " + value)
            }
        }
    }

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
                    wins = listOf(DailyStanding(PlayerNameKey("bruno"), "Bruno", 1)),
                    penalties = listOf(DailyStanding(PlayerNameKey("carla"), "Carla", 112)),
                    summaryExpanded = true,
                    isLoading = false,
                ),
                onBack = {}, onSettings = {}, onNewMatch = {}, onOpenMatch = {},
                onToggleSummary = { toggles++ },
            )
        }
        composeRule.onNodeWithTag("history_root").performScrollToNode(hasText(context.getString(R.string.history_provisional), substring = true))
        composeRule.onNodeWithText(context.getString(R.string.history_provisional), useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("history_root").performScrollToNode(hasTestTag("history_match_1"))
        composeRule.onNodeWithTag("history_winner_b", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("history_highest_c", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("history_root").performScrollToNode(hasTestTag("daily_wins_card"))
        composeRule.onNodeWithTag("daily_wins_bruno").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("advertisement").assertDoesNotExist()
        composeRule.onNodeWithTag("history_root").performScrollToNode(hasTestTag("daily_summary_toggle"))
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
