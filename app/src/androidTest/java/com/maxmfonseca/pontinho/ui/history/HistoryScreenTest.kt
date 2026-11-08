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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.io.FileInputStream
import java.util.Locale

class HistoryScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun matchCardPlacesPlayerNameLeftAndScoreRight() {
        composeRule.setContent {
            HistoryScreen(
                state = HistoryUiState(
                    localDate = LocalDate.parse("2026-10-14"),
                    matches = listOf(HistoryMatch(MatchId("match"), 0, true, listOf(
                        HistoryPlayer(PlayerId("ana"), "Ana", 42, true, false),
                    ))),
                    isLoading = false,
                ),
                onBack = {}, onSettings = {}, onNewMatch = {}, onOpenMatch = {}, onToggleSummary = {},
            )
        }

        composeRule.onNodeWithTag("history_root").performScrollToNode(hasTestTag("history_match_0"))
        val cardBounds = composeRule.onNodeWithTag("history_match_0").fetchSemanticsNode().boundsInRoot
        val nameBounds = composeRule.onNode(
            hasText("Ana") and hasAnyAncestor(hasTestTag("history_match_0")),
            useUnmergedTree = true,
        ).fetchSemanticsNode().boundsInRoot
        val scoreBounds = composeRule.onNode(
            hasText("42") and hasAnyAncestor(hasTestTag("history_match_0")),
            useUnmergedTree = true,
        ).fetchSemanticsNode().boundsInRoot
        val inset = with(composeRule.density) { 16.dp.toPx() }

        assertEquals(cardBounds.left + inset, nameBounds.left, 1f)
        assertEquals(cardBounds.right - inset, scoreBounds.right, 1f)
        assertEquals(nameBounds.center.y, scoreBounds.center.y, 1f)
    }

    @SuppressLint("RestrictedApi")
    @Test fun dailySummaryFormatsWinsAndTotalPointsAcrossLocales() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        var locale by mutableStateOf("pt-BR")
        composeRule.setContent {
            val localized = base.createConfigurationContext(Configuration(base.resources.configuration).apply {
                setLocale(Locale.forLanguageTag(locale))
            })
            CompositionLocalProvider(LocalContext provides localized, LocalResources provides localized.resources,
                LocalProvidableLocaleList provides androidx.compose.ui.text.intl.LocaleList(locale)) {
                PontinhoTheme { HistoryScreen(HistoryUiState(LocalDate.parse("2026-10-14"),
                    wins = listOf(DailyStanding(PlayerNameKey("ana"), "Ana", 1, 2)),
                    penalties = listOf(DailyStanding(PlayerNameKey("ana"), "Ana", 111, 2)),
                    summaryExpanded = true, isLoading = false), {}, {}, {}, {}, {}) }
            }
        }
        for ((tag, wins, points, label) in listOf(
            listOf("pt-BR", "1/2 (50,0%)", "111 (55,5)", "Pontos totais"),
            listOf("en-US", "1/2 (50.0%)", "111 (55.5)", "Total points"),
        )) {
            composeRule.runOnIdle { locale = tag }
            composeRule.onNodeWithText(label).performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithTag("daily_wins_ana").performScrollTo().assertTextEquals(wins, "Ana")
            composeRule.onNodeWithTag("daily_points_ana").performScrollTo().assertTextEquals(points, "Ana")
            for ((rowTag, value) in listOf("daily_wins_ana" to wins, "daily_points_ana" to points)) {
                val rowBounds = composeRule.onNodeWithTag(rowTag).fetchSemanticsNode().boundsInRoot
                val nameBounds = composeRule.onNode(
                    hasText("Ana") and hasAnyAncestor(hasTestTag(rowTag)),
                    useUnmergedTree = true,
                ).fetchSemanticsNode().boundsInRoot
                val valueBounds = composeRule.onNode(
                    hasText(value) and hasAnyAncestor(hasTestTag(rowTag)),
                    useUnmergedTree = true,
                ).fetchSemanticsNode().boundsInRoot
                assertEquals("$rowTag should anchor the name to the left", rowBounds.left, nameBounds.left, 1f)
                assertEquals("$rowTag should anchor the value to the right", rowBounds.right, valueBounds.right, 1f)
                assertTrue("$rowTag should place the name before the value", nameBounds.left < valueBounds.left)
            }
        }
    }

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
                        wins = listOf(DailyStanding(PlayerNameKey("julia"), "Julia", 1, 1), DailyStanding(PlayerNameKey("max"), "Max", 1, 1), DailyStanding(PlayerNameKey("julia2"), "Julia2", 0, 1)),
                        penalties = listOf(DailyStanding(PlayerNameKey("julia2"), "Julia2", 110, 1), DailyStanding(PlayerNameKey("max"), "Max", 110, 1), DailyStanding(PlayerNameKey("julia"), "Julia", 33, 1)),
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
            val time = LocalTime.MIDNIGHT.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(Locale.forLanguageTag(locale)))
            composeRule.onNodeWithTag("history_match_0").assertTextContains("$time · ${localized.getString(R.string.history_in_progress)}")
            composeRule.onNode(
                hasTestTag("history_player_a") and hasAnyAncestor(hasTestTag("history_match_0")),
            ).performScrollTo().assertIsDisplayed().assertTextEquals("Julia2", "999")
            composeRule.onNodeWithTag("history_delete_active").assertContentDescriptionEquals(localized.getString(R.string.history_delete_description, time))
            composeRule.onNodeWithText(localized.getString(R.string.history_provisional), useUnmergedTree = true).assertDoesNotExist()
            if (locale == "en-US" && theme == ThemePreference.DARK && scale == 2f) capture("task6-history-compact-en-dark-200.png")
            for ((index, name) in listOf(1 to "Julia", 2 to "Julia2")) {
                root.performScrollToNode(hasTestTag("history_match_$index"))
                composeRule.onNode(hasText(name, substring = true) and hasAnyAncestor(hasTestTag("history_match_$index"))).performScrollTo().assertIsDisplayed()
                composeRule.onNodeWithTag("history_match_$index").assertTextContains("$time · ${localized.getString(R.string.history_completed)}")
                    .assertHeightIsAtLeast(48.dp).performClick()
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
                        DailyStanding(PlayerNameKey("julia"), "Julia", 1, 1),
                        DailyStanding(PlayerNameKey("max"), "Max", 1, 1),
                        DailyStanding(PlayerNameKey("julia2"), "Julia2", 0, 1),
                    ),
                    penalties = listOf(
                        DailyStanding(PlayerNameKey("julia2"), "Julia2", 110, 1),
                        DailyStanding(PlayerNameKey("max"), "Max", 110, 1),
                        DailyStanding(PlayerNameKey("julia"), "Julia", 33, 1),
                    ),
                ), {}, {}, {}, {}, {},
            )
        }
        for ((metric, rows) in listOf(
            "wins" to listOf(Triple("julia", "Julia", 1), Triple("max", "Max", 1), Triple("julia2", "Julia2", 0)),
            "points" to listOf(Triple("julia2", "Julia2", 110), Triple("max", "Max", 110), Triple("julia", "Julia", 33)),
        )) {
            composeRule.onNodeWithTag("daily_${metric}_card").performScrollTo().assertExists()
            for ((key, name, value) in rows) {
                val row = composeRule.onNode(hasTestTag("daily_${metric}_$key") and hasAnyAncestor(hasTestTag("daily_${metric}_card")))
                row.performScrollTo().assertIsDisplayed()
                val texts = row.fetchSemanticsNode().config[SemanticsProperties.Text].map { it.text }
                assertTrue("$key should retain its display name", name in texts)
                val valuePrefix = if (metric == "wins") "$value/" else "$value ("
                assertTrue("$key should retain its $metric value", texts.any { it.startsWith(valuePrefix) })
            }
        }
    }

    @Test fun compactRowsKeepRepositoryOrderAndAnnounceOnlyFinishedResults() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var toggles = 0
        composeRule.setContent {
            HistoryScreen(
                state = HistoryUiState(
                    localDate = LocalDate.parse("2026-10-14"),
                    matches = listOf(
                        HistoryMatch(MatchId("active"), 2, false, listOf(HistoryPlayer(PlayerId("a"), "Ana", 112, true, true))),
                        HistoryMatch(MatchId("done"), 1, true, listOf(
                            HistoryPlayer(PlayerId("c"), "Carla", 112, false, true),
                            HistoryPlayer(PlayerId("d"), "Dora", 112, false, true),
                            HistoryPlayer(PlayerId("b"), "Bruno", 48, true, false),
                            HistoryPlayer(PlayerId("e"), "Eva", 48, true, false),
                        )),
                    ),
                    wins = listOf(DailyStanding(PlayerNameKey("bruno"), "Bruno", 1, 1)),
                    penalties = listOf(DailyStanding(PlayerNameKey("carla"), "Carla", 112, 1)),
                    summaryExpanded = true,
                    isLoading = false,
                ),
                onBack = {}, onSettings = {}, onNewMatch = {}, onOpenMatch = {},
                onToggleSummary = { toggles++ },
            )
        }
        composeRule.onNodeWithTag("history_root").performScrollToNode(hasTestTag("history_match_0"))
        composeRule.onNodeWithText(context.getString(R.string.history_provisional), useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithTag("history_player_a").assertTextEquals("Ana", "112")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
        composeRule.onNodeWithTag("history_root").performScrollToNode(hasTestTag("history_match_1"))
        for ((id, name) in listOf("c" to "Carla", "d" to "Dora")) {
            composeRule.onNodeWithTag("history_player_$id").performScrollTo().assertIsDisplayed()
                .assertTextEquals(name, "112").assert(
                SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, context.getString(R.string.results_highest_penalty)))
        }
        for ((id, name) in listOf("b" to "Bruno", "e" to "Eva")) {
            composeRule.onNodeWithTag("history_player_$id").performScrollTo().assertIsDisplayed()
                .assertTextEquals(name, "48").assert(
                SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, context.getString(R.string.results_winner)))
        }
        val positions = listOf("c", "d", "b", "e").map {
            composeRule.onNodeWithTag("history_player_$it").fetchSemanticsNode().positionInRoot.y
        }
        assertTrue(positions.zipWithNext().all { (first, second) -> first < second })
        for (label in listOf(R.string.results_winner, R.string.results_highest_penalty)) {
            composeRule.onNodeWithText(context.getString(label), useUnmergedTree = true).assertDoesNotExist()
        }
        composeRule.onNodeWithTag("history_root").performScrollToNode(hasTestTag("daily_wins_card"))
        composeRule.onNodeWithTag("daily_wins_bruno").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("advertisement").assertDoesNotExist()
        composeRule.onNodeWithTag("history_root").performScrollToNode(hasTestTag("daily_summary_toggle"))
        composeRule.onNodeWithTag("daily_summary_toggle").performClick()
        composeRule.runOnIdle { assertEquals(1, toggles) }
    }

    @Test fun allTiedPlayersAnnounceWinnerBeforeHighestPenalty() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.setContent {
            HistoryScreen(HistoryUiState(LocalDate.parse("2026-10-14"), isLoading = false,
                matches = listOf(HistoryMatch(MatchId("tie"), 0, true, listOf(
                    HistoryPlayer(PlayerId("a"), "Ana", 110, true, true),
                    HistoryPlayer(PlayerId("b"), "Bia", 110, true, true),
                )))), {}, {}, {}, {}, {})
        }
        composeRule.onNodeWithTag("history_root").performScrollToNode(hasTestTag("history_match_0"))
        for ((id, name) in listOf("a" to "Ana", "b" to "Bia")) {
            composeRule.onNodeWithTag("history_player_$id").assertTextEquals(name, "110").assert(
                SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, context.getString(R.string.results_winner)))
        }
    }

    @Test fun pendingDeletionShowsConfirmationAndSavingDisablesActions() {
        var state by mutableStateOf(HistoryUiState(LocalDate.parse("2026-10-14"), isLoading = false,
            matches = listOf(HistoryMatch(MatchId("active"), 0, false, emptyList())),
            pendingDeleteMatchId = MatchId("active")))
        composeRule.setContent { HistoryScreen(state, {}, {}, {}, {}, {}) }
        composeRule.onNodeWithTag("history_delete_dialog").assertIsDisplayed()
        composeRule.onNodeWithTag("history_delete_confirm").assertIsEnabled()
        composeRule.onNodeWithTag("history_delete_cancel").assertIsEnabled()
        composeRule.runOnIdle { state = state.copy(isDeleting = true) }
        composeRule.onNodeWithTag("history_delete_confirm").assertIsNotEnabled()
        composeRule.onNodeWithTag("history_delete_cancel").assertIsNotEnabled()
    }

    @Test fun deleteActionsStaySeparateFromOpeningAndFailureAllowsRetry() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val calls = mutableListOf<String>()
        var state by mutableStateOf(HistoryUiState(LocalDate.parse("2026-10-14"), isLoading = false,
            matches = listOf(
                HistoryMatch(MatchId("active"), 0, false, listOf(HistoryPlayer(PlayerId("a"), "Ana", 12, false, false))),
                HistoryMatch(MatchId("done"), 0, true, listOf(HistoryPlayer(PlayerId("b"), "Bia", 110, true, true))),
            )))
        composeRule.setContent {
            HistoryScreen(state, {}, {}, {}, { calls += "open:${it.value}" }, {},
                onRequestDelete = { calls += "request:${it.value}"; state = state.copy(pendingDeleteMatchId = it) },
                onCancelDelete = { calls += "cancel"; state = state.copy(pendingDeleteMatchId = null) },
                onConfirmDelete = { calls += "confirm"; state = state.copy(isDeleting = true) },
                onClearDeleteError = { calls += "clear"; state = state.copy(deleteError = false) })
        }
        val root = composeRule.onNodeWithTag("history_root")
        root.performScrollToNode(hasTestTag("history_match_0"))
        composeRule.onNodeWithTag("history_match_0").performClick()
        composeRule.onNodeWithTag("history_delete_active").assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
            .performTouchInput { click() }
        composeRule.runOnIdle { assertEquals(listOf("open:active", "request:active"), calls) }
        composeRule.onNodeWithText(context.getString(R.string.history_delete_message)).assertIsDisplayed()
        composeRule.onNodeWithTag("history_delete_cancel").performClick()
        composeRule.onNodeWithTag("history_delete_dialog").assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(listOf("open:active", "request:active", "clear", "cancel"), calls); calls.clear() }

        root.performScrollToNode(hasTestTag("history_match_1"))
        composeRule.onNodeWithTag("history_delete_done").performTouchInput { click() }
        composeRule.onNodeWithTag("history_delete_confirm").performClick()
        composeRule.onNodeWithTag("history_delete_cancel").assertIsNotEnabled().performTouchInput { click() }
        composeRule.onNodeWithTag("history_delete_confirm").assertIsNotEnabled().performTouchInput { click() }
        composeRule.runOnIdle { assertEquals(listOf("request:done", "clear", "confirm"), calls) }
        composeRule.onNodeWithTag("history_match_1").assertExists()

        composeRule.runOnIdle { state = state.copy(isDeleting = false, deleteError = true) }
        composeRule.onNodeWithText(context.getString(R.string.history_delete_error)).assertIsDisplayed()
        composeRule.onNodeWithTag("history_match_1").assertExists()
        composeRule.onNodeWithTag("history_delete_confirm").assertIsEnabled().performClick()
        composeRule.onNodeWithTag("history_delete_error").assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(listOf("request:done", "clear", "confirm", "clear", "confirm"), calls) }
        composeRule.onNodeWithTag("history_delete_dialog").assertIsDisplayed()
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
