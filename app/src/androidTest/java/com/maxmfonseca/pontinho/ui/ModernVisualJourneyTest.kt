package com.maxmfonseca.pontinho.ui

import android.content.res.Configuration
import android.view.WindowInsets
import android.view.inspector.WindowInspector
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.espresso.Espresso.closeSoftKeyboard
import com.maxmfonseca.pontinho.R
import com.maxmfonseca.pontinho.domain.model.*
import com.maxmfonseca.pontinho.domain.repository.*
import com.maxmfonseca.pontinho.settings.*
import com.maxmfonseca.pontinho.ui.about.AboutScreen
import com.maxmfonseca.pontinho.ui.history.*
import com.maxmfonseca.pontinho.ui.home.*
import com.maxmfonseca.pontinho.ui.learn.LearnScreen
import com.maxmfonseca.pontinho.ui.match.*
import com.maxmfonseca.pontinho.ui.results.*
import com.maxmfonseca.pontinho.ui.settings.*
import com.maxmfonseca.pontinho.ui.setup.*
import com.maxmfonseca.pontinho.ui.theme.PontinhoTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.FileInputStream
import java.time.LocalDate
import java.util.Locale

/** Catches clipped actions, overlapping controls, raw glyphs and undersized targets across the app. */
class ModernVisualJourneyTest {
    @get:Rule val compose = createComposeRule()
    private val base get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val screens = listOf("home", "setup", "match", "picker", "score", "results", "history", "settings", "appearance", "language", "learn", "about")

    /** Run additionally with real emulator font_scale=1.5 and 2.0: Dialog owns an Android window. */
    @Test fun dialogsRemainReachableAtActualSystemFontScale() {
        var locale by mutableStateOf("pt-BR")
        var theme by mutableStateOf(ThemePreference.LIGHT)
        var screen by mutableStateOf("picker")
        val font = base.resources.configuration.fontScale
        compose.setContent {
            val context = base.createConfigurationContext(Configuration(base.resources.configuration).apply { setLocale(Locale.forLanguageTag(locale)) })
            CompositionLocalProvider(LocalContext provides context, LocalResources provides context.resources) {
                PontinhoTheme(theme) { key(locale, theme, screen) { Screen(screen) } }
            }
        }
        for (language in listOf("pt-BR", "en-US")) for (mode in listOf(ThemePreference.LIGHT, ThemePreference.DARK)) for (destination in listOf("picker", "score")) {
            compose.runOnIdle { locale = language; theme = mode; screen = destination }
            val context = base.createConfigurationContext(Configuration(base.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) })
            val scenario = "$destination/$language/$mode/actual-system-font=$font"
            val confirm = compose.onNodeWithText(context.getString(R.string.confirm))
            if (destination == "score") {
                dismissSettledKeyboard()
                confirm.performScrollTo()
            }
            confirm.assertIsDisplayed().assertIsEnabled()
            assertTarget(confirm.fetchSemanticsNode(), scenario)
            compose.onNodeWithText(context.getString(R.string.cancel)).assertIsDisplayed()
            if (destination == "picker") {
                val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                compose.onNode(hasText(context.getString(R.string.confirm)) and !hasClickAction(), true)
                    .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                assertEquals("A one-word picker action must not break inside the word at $scenario", 1, layouts.single().lineCount)
                for (rank in Rank.entries) compose.onNodeWithTag("indicator_rank_${rank.name}").assertIsDisplayed().assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
                for (color in CardColor.entries) compose.onNodeWithTag("indicator_color_${color.name}").assertIsDisplayed().assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
                compose.onAllNodes(hasScrollAction() and hasAnyAncestor(hasTestTag("joker_dialog")), true).assertCountEquals(0)
            }
            inspectVisibleControls(scenario)
            capture("actual-$destination-$language-${mode.name.lowercase()}-$font")
        }
    }

    @Test fun everyScreenKeepsActionsReachableInBothLocalesThemesAndThreeFontScales() {
        var locale by mutableStateOf("pt-BR")
        var theme by mutableStateOf(ThemePreference.LIGHT)
        var scale by mutableFloatStateOf(1f)
        var screen by mutableStateOf("home")
        var learnClicks = 0
        compose.setContent {
            val context = base.createConfigurationContext(Configuration(base.resources.configuration).apply { setLocale(Locale.forLanguageTag(locale)) })
            CompositionLocalProvider(LocalContext provides context, LocalResources provides context.resources,
                LocalDensity provides Density(base.resources.displayMetrics.density, scale)) {
                PontinhoTheme(theme) {
                    Surface { Box(Modifier.size(320.dp, 568.dp)) { key(locale, theme, scale, screen) { Screen(screen, onLearn = { learnClicks++ }) } } }
                }
            }
        }
        for (language in listOf("pt-BR", "en-US")) for (mode in listOf(ThemePreference.LIGHT, ThemePreference.DARK)) for (font in listOf(1f, 1.5f, 2f)) {
            for (destination in screens) {
                compose.runOnIdle { locale = language; theme = mode; scale = font; screen = destination }
                if (destination == "score") dismissSettledKeyboard()
                val context = base.createConfigurationContext(Configuration(base.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) })
                val scenario = "$destination/$language/$mode/$font"
                val root = when (destination) { "picker", "score" -> "match_root"; else -> "${destination}_root" }
                compose.onNodeWithTag(root).assertIsDisplayed()
                // Only one scene may be composed. Dialog + underlying match is intentional.
                screens.filterNot { it == destination || (destination in listOf("picker", "score") && it == "match") }
                    .filterNot { it in listOf("picker", "score") }.forEach { compose.onAllNodesWithTag("${it}_root").assertCountEquals(0) }
                val actions: List<SemanticsMatcher> = when (destination) {
                    "home" -> listOf(hasContentDescription(context.getString(R.string.home_learn)), hasTestTag("home_new_match"), hasTestTag("home_day_0"))
                    "setup" -> listOf(hasTestTag("setup_add_player"), hasTestTag("setup_use_last_match"), hasTestTag("setup_create_match"))
                    "match" -> listOf(hasContentDescription(context.getString(R.string.match_add_score, "Julia")), hasTestTag("match_add_player"))
                    "picker", "score" -> listOf(hasText(context.getString(R.string.confirm)), hasText(context.getString(R.string.cancel)))
                    "results" -> listOf(R.string.results_new_match, R.string.results_details, R.string.results_back_history).map { hasText(context.getString(it)) }
                    "history" -> listOf(hasText(context.getString(R.string.home_new_match)), hasTestTag("daily_summary_toggle"), hasTestTag("history_match_0"), hasTestTag("history_delete_match"))
                    "settings" -> listOf(R.string.route_appearance, R.string.route_language, R.string.route_learn, R.string.route_about).map { hasText(context.getString(it)) }
                    "appearance" -> listOf(R.string.appearance_theme_system, R.string.appearance_theme_light, R.string.appearance_theme_dark).map { hasText(context.getString(it)) }
                    "language" -> listOf(R.string.language_device, R.string.language_portuguese, R.string.language_english).map { hasText(context.getString(it)) }
                    "about" -> emptyList()
                    else -> listOf(hasContentDescription(context.getString(R.string.match_back)))
                }
                inspectVisibleControls(scenario)
                for (action in actions) {
                    val node = compose.onNode(action)
                    if (destination in listOf("home", "setup", "results", "history")) {
                        compose.onNodeWithTag(root).performScrollToNode(action)
                    } else if (destination == "match") {
                        compose.onNodeWithTag("match_players").performScrollToNode(action)
                    } else if (destination == "score" && action == actions.first()) {
                        node.performScrollTo()
                    } else if (destination !in listOf("picker", "score")) node.performScrollTo()
                    node.assertIsDisplayed()
                    assertTarget(node.fetchSemanticsNode(), scenario)
                    inspectVisibleControls(scenario)
                }
                if (destination == "appearance") compose.onNodeWithTag("reduced_motion_switch").assertDoesNotExist()
                if (destination == "about") {
                    val before = learnClicks
                    compose.onNodeWithText(context.getString(R.string.about_purpose))
                        .performScrollTo().assertIsDisplayed().performFirstLinkClick()
                    compose.runOnIdle { assertEquals(before + 1, learnClicks) }
                    compose.onNodeWithTag("about_learn").assertDoesNotExist()
                }
                if (destination == "picker") {
                    compose.onAllNodes(SemanticsMatcher("rank") { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("indicator_rank_") == true }, true).assertCountEquals(13)
                    compose.onAllNodes(SemanticsMatcher("color") { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("indicator_color_") == true }, true).assertCountEquals(2)
                    compose.onAllNodes(hasScrollAction() and hasAnyAncestor(hasTestTag("joker_dialog")), true).assertCountEquals(0)
                }
                if (destination == "learn") compose.onNodeWithText(context.getString(R.string.score_correction_help)).performScrollTo().assertIsDisplayed()
                capture("$destination-$language-${mode.name.lowercase()}-$font")
            }
        }
    }

    private fun inspectVisibleControls(scenario: String) {
        val nodes = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick)).fetchSemanticsNodes()
            .filter { node -> compose.onNode(SemanticsMatcher("same node") { it.id == node.id }).isDisplayed() }
        nodes.filter { node ->
            val size = node.layoutInfo.coordinates.size
            node.boundsInRoot.width >= size.width - 1 && node.boundsInRoot.height >= size.height - 1
        }.forEach { assertTarget(it, scenario) }
        val visible = nodes.filter { it.boundsInRoot.width > 0 && it.boundsInRoot.height > 0 }
        for (i in visible.indices) for (j in i + 1 until visible.size) {
            val firstRoot = generateSequence(visible[i]) { it.parent }.last().id
            val secondRoot = generateSequence(visible[j]) { it.parent }.last().id
            // A nested action, such as History's delete button, belongs inside its clickable card.
            // Independent controls must still have separate bounds, and every target is checked above.
            val nested = generateSequence(visible[i].parent) { it.parent }.any { it.id == visible[j].id } ||
                generateSequence(visible[j].parent) { it.parent }.any { it.id == visible[i].id }
            if (firstRoot == secondRoot && !nested) assertFalse("Controls overlap at $scenario: ${visible[i].boundsInRoot} ${visible[i].config} / ${visible[j].boundsInRoot} ${visible[j].config}", visible[i].boundsInRoot.overlaps(visible[j].boundsInRoot))
        }
        val rawControls = setOf("⚙", "✎", "×", "⠿", "←", "＋", "›", "ⓘ", "⇄", "▤")
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), true).fetchSemanticsNodes().forEach { node ->
            node.config[SemanticsProperties.Text].forEach { assertFalse("Raw control at $scenario: ${it.text}", it.text in rawControls) }
        }
    }

    private fun assertTarget(node: SemanticsNode, scenario: String) {
        val minimum = 48 * base.resources.displayMetrics.density - 1
        // Material may draw a 40dp button while expanding its actual pointer target to 48dp.
        val target = node.touchBoundsInRoot
        assertTrue("48dp touch target at $scenario: ${node.config}; actual=$target", target.width >= minimum && target.height >= minimum)
    }

    @Composable private fun Screen(screen: String, onLearn: () -> Unit = {}) {
        val today = LocalDate.of(2026, 10, 14)
        val participant = ParticipantId("julia")
        val players = listOf(MatchPlayer(participant, "Julia", 33, true, emptyList(), true), MatchPlayer(ParticipantId("max"), "Max", 70, false, emptyList(), true))
        when (screen) {
            "home" -> HomeScreen(HomeUiState(isLoading = false, dateCards = listOf(HomeDateCard(today, 2, 1, listOf("Julia", "Max")))), {}, {}, {}, onSettings = {})
            "setup" -> MatchSetupScreen(MatchSetupUiState(players = listOf(DraftPlayer("julia", null, "Julia"), DraftPlayer("max", null, "Max"))), {}, {}, {}, {}, { _, _ -> }, {}, {}, {}, {}, {}, {}, {})
            "match", "picker", "score" -> MatchScreen(MatchUiState(isLoading = false, players = players, canAddPlayer = true, canReorderPlayers = true,
                picker = if (screen == "picker") IndicatorPickerState(RoundId("round"), 1, PickerPurpose.EDIT, Rank.ACE, CardColor.RED) else null,
                scoreDialog = if (screen == "score") MatchScoreDialogState(participant, "Julia", RoundId("round"), 1, Indicator(Rank.ACE, CardColor.RED), true, "33") else null),
                {}, {}, {}, {}, {}, {}, {}, {}, {})
            "results" -> ResultsScreen(ResultsUiState(MatchId("match"), today, listOf(ResultPlayer(PlayerId("julia"), "Julia", 33, 1, true, false), ResultPlayer(PlayerId("max"), "Max", 110, 2, false, true)), isFinished = true, isLoading = false), {}, {}, {})
            "history" -> HistoryScreen(HistoryUiState(today, matches = listOf(HistoryMatch(MatchId("match"), 0, true, listOf(HistoryPlayer(PlayerId("julia"), "Julia", 33, true, false), HistoryPlayer(PlayerId("max"), "Max", 110, false, true)))),
                wins = listOf(DailyStanding(PlayerNameKey.from("Julia"), "Julia", 1)), penalties = listOf(DailyStanding(PlayerNameKey.from("Max"), "Max", 110)), summaryExpanded = true, isLoading = false), {}, {}, {}, {}, {})
            "settings" -> SettingsScreen({}, {}, {}, {}, {})
            "appearance" -> AppearanceScreen(AppPreferences(), {}, {})
            "language" -> LanguageScreen(LanguagePreference.SYSTEM, {}, {})
            "learn" -> LearnScreen({})
            "about" -> AboutScreen("1.0.0", {}, onLearn, {})
        }
    }

    private fun capture(name: String) {
        compose.mainClock.advanceTimeBy(100)
        compose.waitForIdle()
        val fd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("screencap -p /sdcard/Download/task8-$name.png")
        FileInputStream(fd.fileDescriptor).use { it.readBytes() }; fd.close()
    }

    private fun dismissSettledKeyboard() {
        // The IME is a separate Android window and can appear after Compose becomes idle.
        // Wait for it, then use the normal dismissal path before checking/capturing actions.
        fun imeVisible() = WindowInspector.getGlobalWindowViews().any {
            it.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true
        }
        compose.waitUntil(5_000) { imeVisible() }
        closeSoftKeyboard()
        compose.waitUntil(5_000) { !imeVisible() }
    }
}
