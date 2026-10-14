package com.maxmfonseca.pontinho.ui.match

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import com.maxmfonseca.pontinho.R
import com.maxmfonseca.pontinho.domain.model.*
import com.maxmfonseca.pontinho.ui.components.*
import com.maxmfonseca.pontinho.ui.theme.PontinhoTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.util.Locale
import android.content.res.Configuration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.geometry.Offset
import androidx.compose.material3.Surface
import androidx.compose.ui.text.TextLayoutResult
import com.maxmfonseca.pontinho.settings.ThemePreference

class MatchScreenTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun phaseIsOneLocalizedStatusWithoutRedundantCorrectionOrWildcardText() {
        var language by mutableStateOf("pt-BR")
        var phase by mutableStateOf(MatchPhase.NOT_STARTED)
        compose.setContent {
            val localized = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
                setLocale(Locale.forLanguageTag(language))
            })
            CompositionLocalProvider(LocalContext provides localized, LocalResources provides localized.resources) {
                PontinhoTheme { Content(fixture(3).copy(roundNumber = 4, phase = phase,
                    isFinished = phase == MatchPhase.FINISHED, isCorrectingRound = phase == MatchPhase.EDITING,
                    indicator = Indicator(Rank.NINE, CardColor.RED))) }
            }
        }
        val labels = mapOf(
            "pt-BR" to listOf("Rodada 4 não iniciada", "Contando pontos da rodada 4", "Editando rodada 4", "Partida encerrada"),
            "en-US" to listOf("Round 4 not started", "Counting points for round 4", "Editing round 4", "Match finished"),
        )
        for ((locale, expected) in labels) for ((index, value) in MatchPhase.entries.withIndex()) {
            compose.runOnIdle { language = locale; phase = value }
            compose.onNodeWithTag("match_status").assertTextEquals(expected[index]).assertIsDisplayed()
            compose.onAllNodesWithText(expected[index]).assertCountEquals(1)
            compose.onNodeWithText("10♥", substring = true).assertDoesNotExist()
            compose.onNodeWithText(if (locale == "pt-BR") "Corrigindo a rodada 4" else "Correcting round 4").assertDoesNotExist()
        }
    }

    @Test fun jokerHistoryCentersOneSlotAndScrollsTwelveToCurrentAtLargeFont() {
        var count by mutableIntStateOf(1)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(context.resources.displayMetrics.density, 2f)) {
                PontinhoTheme { Box(Modifier.size(320.dp, 568.dp)) {
                    Content(fixture(3).copy(roundNumber = count, jokers = (1..count).map {
                        RoundJokerUi(null, it, null)
                    }))
                } }
            }
        }
        val scroll = SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange)
        compose.onNodeWithTag("joker_history").assert(scroll.not())
        val first = compose.onNodeWithTag("joker_round_1").assertIsDisplayed()
            .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).fetchSemanticsNode().boundsInRoot
        val row = compose.onNodeWithTag("joker_history").fetchSemanticsNode().boundsInRoot
        assertEquals(row.center.x, first.center.x, 1f)
        for (rounds in listOf(12, 13)) {
            compose.runOnIdle { count = rounds }
            compose.onNodeWithTag("joker_history").assert(scroll)
            val current = compose.onNodeWithTag("joker_round_$rounds").assertIsDisplayed()
                .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).fetchSemanticsNode()
            assertEquals("Current card must be fully visible", current.layoutInfo.coordinates.size.width.toFloat(), current.boundsInRoot.width, 1f)
            compose.onNodeWithTag("joker_round_1").assertIsNotDisplayed()
        }
        capture("task4-joker-history-13-font-2.png")
    }

    @Test fun overflowingJokerHistoryRevealsActiveCorrectionRoundWhenItChanges() {
        var activeRound by mutableIntStateOf(12)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(context.resources.displayMetrics.density, 2f)) {
                PontinhoTheme { Box(Modifier.size(320.dp, 568.dp)) {
                    Content(fixture(3).copy(roundNumber = activeRound,
                        phase = if (activeRound == 12) MatchPhase.COUNTING else MatchPhase.EDITING,
                        isCorrectingRound = activeRound != 12,
                        jokers = (1..12).map { RoundJokerUi(RoundId("r$it"), it, null) }))
                } }
            }
        }
        compose.onNodeWithTag("joker_round_12").assertIsDisplayed()
        for (round in listOf(2, 8, 1, 12)) {
            compose.runOnIdle { activeRound = round }
            compose.onNodeWithTag("joker_history")
                .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange))
            val active = compose.onNodeWithTag("joker_round_$round").assertIsDisplayed().fetchSemanticsNode()
            assertEquals("Active round $round must be fully visible", active.layoutInfo.coordinates.size.width.toFloat(),
                active.boundsInRoot.width, 1f)
            if (round == 2) compose.onNodeWithTag("joker_round_12").assertIsNotDisplayed()
        }
    }

    @Test fun initiallyFinishedOverflowHistoryRevealsLastCompletedRound() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(context.resources.displayMetrics.density, 2f)) {
                PontinhoTheme { Box(Modifier.size(320.dp, 568.dp)) {
                    Content(fixture(3).copy(roundNumber = 13, phase = MatchPhase.FINISHED, isFinished = true,
                        jokers = (1..12).map { RoundJokerUi(RoundId("r$it"), it, null) }))
                } }
            }
        }
        compose.onNodeWithTag("joker_history")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange))
        val last = compose.onNodeWithTag("joker_round_12").assertIsDisplayed().fetchSemanticsNode()
        assertEquals("Last completed round must be fully visible on initial render",
            last.layoutInfo.coordinates.size.width.toFloat(), last.boundsInRoot.width, 1f)
        compose.onNodeWithTag("joker_round_1").assertIsNotDisplayed()
        compose.onNodeWithTag("joker_round_13").assertDoesNotExist()
    }

    @Test fun cameraPickerHidesRetainedScoreDialogUntilDismissed() {
        var picker by mutableStateOf<IndicatorPickerState?>(IndicatorPickerState(RoundId("r1"), 1, PickerPurpose.CAMERA))
        compose.setContent { PontinhoTheme {
            MatchScreen(fixture(3).copy(picker = picker, scoreDialog = MatchScoreDialogState(
                ParticipantId("p0"), "Ana", RoundId("r1"), 1, null, true, "42", canScan = true)),
                {}, {}, {}, {}, { picker = null }, {}, {}, {}, {})
        } }
        compose.onNodeWithTag("joker_dialog").assertIsDisplayed()
        compose.onNodeWithTag("score_input").assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.cancel)).performClick()
        compose.onNodeWithTag("score_input").assertIsFocused().assertTextContains("42")
        compose.onNodeWithText(if (context.resources.configuration.locales[0].language == "pt") "Rodada 1" else "Round 1").assertIsDisplayed()
    }

    @Test fun pickerHasThirteenRanksTwoColorsAndNoVerticalScroll() {
        compose.setContent { PontinhoTheme { Content(fixture(3).copy(picker = IndicatorPickerState(RoundId("round"), 1, PickerPurpose.EDIT))) } }
        compose.onAllNodes(SemanticsMatcher("rank control") {
            it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("indicator_rank_") == true
        }).assertCountEquals(13)
        compose.onAllNodes(SemanticsMatcher("color control") {
            it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("indicator_color_") == true
        }).assertCountEquals(2)
        compose.onNodeWithTag("joker_options").assert(hasScrollAction().not())
    }

    @Test fun normalHandleMovementReordersWithoutLongPress() {
        var state by mutableStateOf(fixture(3).copy(canAddPlayer = true, canReorderPlayers = true))
        val moves = mutableListOf<List<ParticipantId>>()
        compose.setContent { PontinhoTheme {
            MatchScreen(state, {}, {}, {}, {}, {}, {}, {}, {}, {}, onReorderPlayers = { moves.add(it) })
        } }
        val rowHeight = compose.onNodeWithTag("match_player_p0").fetchSemanticsNode().boundsInRoot.height
        compose.onNodeWithTag("match_drag_p0").performTouchInput {
            down(center); moveBy(androidx.compose.ui.geometry.Offset(0f, rowHeight * 1.2f)); up()
        }
        compose.runOnIdle { assertEquals(listOf(listOf("p1", "p0", "p2")), moves.map { order -> order.map { it.value } }) }
    }

    @Test fun dragHandleHasFortyEightDpVisibleTarget() {
        compose.setContent { PontinhoTheme { Content(fixture(3).copy(canAddPlayer = true, canReorderPlayers = true)) } }
        compose.onNodeWithTag("match_drag_p0").assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
    }

    @Test fun edgeDragScrollsTwelvePlayersAndCommitsOnceOnRelease() {
        val commits = mutableListOf<List<ParticipantId>>()
        compose.setContent { PontinhoTheme {
            MatchScreen(fixture(12).copy(canAddPlayer = true, canReorderPlayers = true), {}, {}, {}, {}, {}, {}, {}, {}, {},
                onReorderPlayers = { commits.add(it) })
        } }
        val list = compose.onNodeWithTag("match_players")
        val bounds = list.fetchSemanticsNode().boundsInRoot
        val handle = compose.onNodeWithTag("match_drag_p0")
        val start = handle.fetchSemanticsNode().boundsInRoot.center
        compose.mainClock.autoAdvance = false
        handle.performTouchInput { down(center); moveBy(Offset(0f, bounds.bottom - start.y - 12f)) }
        repeat(240) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        compose.runOnIdle { assertTrue(commits.isEmpty()) }
        capture("task5-edge-before-release.png")
        list.performTouchInput { up() }
        compose.mainClock.autoAdvance = true
        compose.runOnIdle { assertEquals(listOf((1..11).map { "p$it" } + "p0"), commits.map { it.map { id -> id.value } }) }
    }

    @Test fun cancellationAndDisablingRestorePreviewAndCardBodyNeverReorders() {
        var state by mutableStateOf(fixture(3).copy(canAddPlayer = true, canReorderPlayers = true))
        val commits = mutableListOf<List<ParticipantId>>()
        compose.setContent { PontinhoTheme {
            MatchScreen(state, {}, {}, {}, {}, {}, {}, {}, {}, {}, onReorderPlayers = { commits.add(it) })
        } }
        val handle = compose.onNodeWithTag("match_drag_p0")
        val row = compose.onNodeWithTag("match_player_p0")
        val amount = compose.onNodeWithTag("match_player_p1").fetchSemanticsNode().boundsInRoot.center.y -
            row.fetchSemanticsNode().boundsInRoot.center.y + 24f * context.resources.displayMetrics.density
        compose.onNodeWithText(longName(0)).performTouchInput { down(center); moveBy(Offset(0f, amount)); up() }
        compose.runOnIdle { assertTrue(commits.isEmpty()) }
        compose.onNodeWithTag("match_players").performScrollToIndex(0)
        for (disable in listOf(false, true)) {
            handle.performTouchInput { down(center); moveBy(Offset(0f, amount)) }
            compose.waitForIdle()
            assertTrue(row.fetchSemanticsNode().boundsInRoot.top > compose.onNodeWithTag("match_player_p1").fetchSemanticsNode().boundsInRoot.top)
            if (disable) compose.runOnIdle { state = state.copy(canAddPlayer = false, canReorderPlayers = false) }
            handle.performTouchInput { cancel() }
            compose.runOnIdle { assertTrue(commits.isEmpty()) }
            assertTrue(row.fetchSemanticsNode().boundsInRoot.top < compose.onNodeWithTag("match_player_p1").fetchSemanticsNode().boundsInRoot.top)
        }
        for (guarded in listOf(
            fixture(3).copy(canAddPlayer = false, canReorderPlayers = false),
            fixture(3).copy(canAddPlayer = true, canReorderPlayers = true, pausedRoundNumbers = listOf(2)),
            fixture(3).copy(canAddPlayer = true, canReorderPlayers = true, isCorrectingRound = true),
            fixture(3).copy(canAddPlayer = true, canReorderPlayers = true, isFinished = true),
        )) {
            compose.runOnIdle { state = guarded }
            handle.assertIsNotEnabled()
            assertTrue(row.fetchSemanticsNode().config.getOrNull(SemanticsActions.CustomActions).isNullOrEmpty())
        }
    }

    @Test fun pickerEveryControlIsReachableAtTwoHundredPercentAcrossLocalesAndThemes() {
        val base = context
        var scenario by mutableStateOf(Triple("pt-BR", ThemePreference.LIGHT, 1f))
        var rank by mutableStateOf<Rank?>(null)
        var color by mutableStateOf<CardColor?>(null)
        var error by mutableStateOf(false)
        compose.setContent {
            val (locale, theme, scale) = scenario
            val localized = base.createConfigurationContext(Configuration(base.resources.configuration).apply { setLocale(Locale.forLanguageTag(locale)) })
            CompositionLocalProvider(LocalContext provides localized, LocalResources provides localized.resources,
                LocalDensity provides Density(base.resources.displayMetrics.density, scale)) {
                PontinhoTheme(theme) { JokerPicker(rank, color, false, error, { rank = it }, { color = it }, {}, {}) }
            }
        }
        for (locale in listOf("pt-BR", "en-US")) for (theme in listOf(ThemePreference.LIGHT, ThemePreference.DARK)) for (scale in listOf(1f, 1.5f, 2f)) {
            compose.runOnIdle { scenario = Triple(locale, theme, scale); rank = null; color = null; error = false }
            val localized = base.createConfigurationContext(Configuration(base.resources.configuration).apply { setLocale(Locale.forLanguageTag(locale)) })
            val confirm = compose.onNodeWithText(localized.getString(R.string.confirm))
            confirm.assertIsNotEnabled()
            for (option in Rank.entries.map { "indicator_rank_${it.name}" } + CardColor.entries.map { "indicator_color_${it.name}" }) {
                compose.onNodeWithTag(option).assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).performClick().assertIsSelected()
            }
            confirm.assertIsDisplayed().assertIsEnabled()
            compose.onNodeWithText(localized.getString(R.string.cancel)).assertIsDisplayed().assertHeightIsAtLeast(48.dp)
            compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)).assertCountEquals(0)
            compose.runOnIdle { error = true }
            compose.onNodeWithText(localized.getString(R.string.match_save_error)).assertIsDisplayed()
            confirm.assertIsDisplayed().assertIsEnabled()
            if (locale == "pt-BR" && theme == ThemePreference.LIGHT && scale == 1f ||
                locale == "en-US" && theme == ThemePreference.DARK && scale == 2f) capture("task5-picker-$locale-$scale.png")
        }
    }

    @Test fun matchCompactLayoutRetainsActionsAtTwoHundredPercentAcrossLocalesAndThemes() {
        val base = context
        var scenario by mutableStateOf(Triple("pt-BR", ThemePreference.LIGHT, 1f))
        compose.setContent {
            val (locale, theme, scale) = scenario
            val localized = base.createConfigurationContext(Configuration(base.resources.configuration).apply { setLocale(Locale.forLanguageTag(locale)) })
            CompositionLocalProvider(LocalContext provides localized, LocalResources provides localized.resources,
                LocalDensity provides Density(base.resources.displayMetrics.density, scale)) {
                PontinhoTheme(theme) { Surface { Box(Modifier.size(320.dp, 568.dp)) {
                    Content(fixture(12).copy(canAddPlayer = true, canReorderPlayers = true,
                        players = fixture(12).players.map { it.copy(total = 1234) }))
                } } }
            }
        }
        for (locale in listOf("pt-BR", "en-US")) for (theme in listOf(ThemePreference.LIGHT, ThemePreference.DARK)) for (scale in listOf(1f, 1.5f, 2f)) {
            compose.runOnIdle { scenario = Triple(locale, theme, scale) }
            val list = compose.onNodeWithTag("match_players")
            list.performScrollToIndex(0)
            compose.onNodeWithTag("match_drag_p0").assertIsDisplayed().assertWidthIsAtLeast(48.dp)
            compose.onNode(hasTestTag("player_action") and hasAnyAncestor(hasTestTag("match_player_p0")))
                .assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
            val ancestor = hasAnyAncestor(hasTestTag("match_player_p0"))
            val identity = compose.onNode(hasTestTag("player_identity") and ancestor).fetchSemanticsNode().boundsInRoot
            val name = compose.onNode(hasTestTag("player_name") and ancestor).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val total = compose.onNode(hasTestTag("player_total") and ancestor).assertIsDisplayed()
                .assertTextEquals("1234").fetchSemanticsNode().boundsInRoot
            assertTrue("Name and total must share a horizontal row at $scenario", name.top < total.bottom && total.top < name.bottom)
            assertTrue("Total must follow the name and fit the identity row at $scenario", total.left >= name.right && total.right <= identity.right)
            compose.onNode(hasTestTag("round_cell_1") and ancestor).assertIsDisplayed()
            if (locale == "pt-BR" && theme == ThemePreference.LIGHT && scale == 1f ||
                locale == "en-US" && theme == ThemePreference.DARK && scale == 2f) capture("task5-match-$locale-$scale.png")
            list.performScrollToIndex(12)
            compose.onNodeWithTag("match_add_player").assertIsDisplayed().assertIsEnabled().assertHeightIsAtLeast(48.dp)
        }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val capture = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("screencap -p /sdcard/Download/$name")
        FileInputStream(capture.fileDescriptor).use { it.readBytes() }
        capture.close()
    }

    @Test fun narrowLargeFontKeepsMaximumTotalReadableWithNameAndReachableControls() {
        var total by mutableIntStateOf(1234)
        var selected: ParticipantId? = null
        var corrected: Pair<ParticipantId, Int>? = null
        val base = context
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(base.resources.displayMetrics.density, 2f)) {
                PontinhoTheme { Surface { Box(Modifier.size(320.dp, 568.dp)) {
                    val state = fixture(3).copy(canAddPlayer = true, canReorderPlayers = true,
                        players = fixture(3).players.map { player -> player.copy(total = total,
                            history = listOf(PlayerRoundHistory(1, RoundStatusKind.LOSS, 12, canEdit = true))) })
                    MatchScreen(state, {}, { selected = it }, {}, {}, {}, {}, {}, {}, {},
                        onEditScore = { id, round -> corrected = id to round })
                } } }
            }
        }
        for (value in listOf(1234, Int.MAX_VALUE)) {
            compose.runOnIdle { total = value }
            val ancestor = hasAnyAncestor(hasTestTag("match_player_p0"))
            val identity = compose.onNode(hasTestTag("player_identity") and ancestor).fetchSemanticsNode().boundsInRoot
            val name = compose.onNode(hasTestTag("player_name") and ancestor).assertIsDisplayed().assertWidthIsAtLeast(100.dp)
            val score = compose.onNode(hasTestTag("player_total") and ancestor).assertIsDisplayed().assertTextEquals(value.toString())
            val nameLayout = mutableListOf<TextLayoutResult>()
            val scoreLayout = mutableListOf<TextLayoutResult>()
            name.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(nameLayout) }
            score.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(scoreLayout) }
            assertTrue("At least Alex must remain visible, not just one initial", nameLayout.single().getLineEnd(0, visibleEnd = true) >= 4)
            assertFalse("The complete accepted total must fit without clipping", scoreLayout.single().hasVisualOverflow)
            assertEquals(value.toString().length, scoreLayout.single().getLineEnd(0, visibleEnd = true))
            val nameBounds = name.fetchSemanticsNode().boundsInRoot
            val scoreBounds = score.fetchSemanticsNode().boundsInRoot
            assertTrue("Name and total remain inline", nameBounds.top < scoreBounds.bottom && scoreBounds.top < nameBounds.bottom)
            assertTrue("Total follows name inside identity", nameBounds.right <= scoreBounds.left && scoreBounds.right <= identity.right)
            val drag = compose.onNodeWithTag("match_drag_p0").assertIsDisplayed().assertIsEnabled()
                .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
            val action = compose.onNode(hasTestTag("player_action") and ancestor).assertIsDisplayed().assertIsEnabled()
                .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
            val correction = compose.onNode(hasTestTag("round_cell_1") and ancestor).assertIsDisplayed()
                .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
            val targets = listOf(drag, action, correction).map { it.fetchSemanticsNode().boundsInRoot }
            targets.forEach { assertFalse("Controls must not overlap identity", it.overlaps(identity)) }
            assertFalse(targets[0].overlaps(targets[1]))
            assertFalse(targets[1].overlaps(targets[2]))
            action.performClick()
            correction.performClick()
            compose.runOnIdle {
                assertEquals(ParticipantId("p0"), selected)
                assertEquals(ParticipantId("p0") to 1, corrected)
            }
        }
        capture("final-fix-match-max-total-320dp-2.0.png")
    }

    @Test fun addPlayerIsLastScrollingItemAndGuardedDuringRounds() {
        var allowed by mutableStateOf(true)
        var added = 0
        compose.setContent { PontinhoTheme {
            MatchScreen(fixture(12).copy(canAddPlayer = allowed, canReorderPlayers = allowed), {}, {}, {}, {}, {}, {}, {}, {}, {}, onAddPlayer = { added++ })
        } }
        compose.onNodeWithTag("match_add_player").assertDoesNotExist()
        compose.onNodeWithTag("match_players").performScrollToIndex(12)
        compose.onNodeWithTag("match_add_player").assertIsDisplayed().assertIsEnabled().performClick()
        assertEquals(1, added)
        val last = compose.onNodeWithTag("match_player_p11").fetchSemanticsNode().boundsInRoot
        assertTrue(compose.onNodeWithTag("match_add_player").fetchSemanticsNode().boundsInRoot.top >= last.bottom)
        compose.runOnIdle { allowed = false }
        compose.onNodeWithTag("match_add_player").assertIsNotEnabled().performClick()
        assertEquals(1, added)
        compose.onNodeWithTag("match_players").performScrollToIndex(0)
        compose.onNodeWithTag("match_add_player").assertDoesNotExist()
    }

    @Test fun lateNameDialogFocusCopyValidationAndConfirmation() {
        var name by mutableStateOf("")
        var confirmed = false
        compose.setContent { PontinhoTheme {
            MatchScreen(fixture(3).copy(canAddPlayer = true, canReorderPlayers = true, playerName = name), {}, {}, {}, {}, {}, {}, {}, {}, {},
                onPlayerNameChange = { name = it }, onConfirmPlayerName = { confirmed = true })
        } }
        compose.onNodeWithTag("player_name_input").assertIsFocused()
        compose.onNodeWithText(context.getString(R.string.match_late_player_help)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.confirm)).assertIsNotEnabled()
        compose.onNodeWithTag("player_name_input").performTextInput("Dora")
        compose.onNodeWithText(context.getString(R.string.confirm)).performClick()
        assertTrue(confirmed)
    }

    @Test fun roundCellProvidesOneMinimumTouchTargetAndDotAndValueOpenSameCorrection() {
        val clicks = mutableListOf<Pair<ParticipantId, Int>>()
        val state = fixture(3).copy(players = fixture(3).players.map { p -> p.copy(history = listOf(
            PlayerRoundHistory(1, RoundStatusKind.LOSS, 12, true),
            PlayerRoundHistory(2, RoundStatusKind.NOT_PARTICIPATING, null),
            PlayerRoundHistory(3, RoundStatusKind.WINNER, 0, false))) })
        compose.setContent { PontinhoTheme {
            MatchScreen(state, {}, {}, {}, {}, {}, {}, {}, {}, {}, onEditScore = { id, round -> clicks += id to round })
        } }
        val ancestor = hasAnyAncestor(hasTestTag("match_player_p0"))
        val cell = compose.onNode(hasTestTag("round_cell_1") and ancestor)
        cell.assertHasClickAction()
        assertEquals(
            context.getString(R.string.match_edit_score, longName(0), 1),
            cell.fetchSemanticsNode().config[SemanticsActions.OnClick].label,
        )
        val bounds = cell.getUnclippedBoundsInRoot()
        assertTrue("Correction target width was ${bounds.right - bounds.left}", bounds.right - bounds.left >= 48.dp)
        assertTrue("Correction target height was ${bounds.bottom - bounds.top}", bounds.bottom - bounds.top >= 48.dp)

        val dot = compose.onNode(hasTestTag("round_dot_1") and ancestor, useUnmergedTree = true)
        val score = compose.onNode(hasTestTag("round_score_1") and ancestor, useUnmergedTree = true)
        compose.onAllNodesWithText("R1", useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodesWithText("R2", useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodesWithText("R3", useUnmergedTree = true).assertCountEquals(0)
        dot.assertWidthIsEqualTo(14.dp).assertHeightIsEqualTo(14.dp)
        dot.assertHasNoClickAction().performTouchInput { click(center) }
        assertEquals(listOf(ParticipantId("p0") to 1), clicks)
        score.assertHasNoClickAction().performTouchInput { click(center) }
        assertEquals(listOf(ParticipantId("p0") to 1, ParticipantId("p0") to 1), clicks)
        cell.performTouchInput { click(Offset(2f, 2f)) }
        assertEquals(List(3) { ParticipantId("p0") to 1 }, clicks)
        compose.onNode(hasTestTag("round_cell_2") and ancestor, useUnmergedTree = true).assertHasNoClickAction()
        compose.onNode(hasTestTag("round_cell_3") and ancestor, useUnmergedTree = true).assertHasNoClickAction()
    }

    @Test fun correctionDialogPreloadsExplicitAndWinnerIsReadOnlyWithTrash() {
        var winner by mutableStateOf(false)
        var value by mutableStateOf("23")
        var removed = 0
        compose.setContent { PontinhoTheme {
            ScoreDialog("Ana", 1, "A", if (winner) "0" else value, { value = it }, {}, {}, {},
                isCorrection = true, readOnly = winner, onDelete = { removed++ })
        } }
        compose.onNodeWithTag("score_input").assertTextContains("23").assertIsFocused().performTextReplacement("8")
        compose.onNodeWithText(context.getString(R.string.score_correction_help)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.went_out)).assertDoesNotExist()
        compose.onNodeWithContentDescription(context.getString(R.string.score_delete)).performClick()
        assertEquals(1, removed)
        compose.runOnIdle { winner = true }
        compose.onNodeWithTag("score_input").assertTextContains("0").assert(hasSetTextAction().not())
        compose.onNodeWithText(context.getString(R.string.confirm)).assertIsNotEnabled()
        compose.onNodeWithContentDescription(context.getString(R.string.score_delete)).performClick()
        assertEquals(2, removed)
    }

    @Test fun dragKeepsParticipantIdentityAcrossRecompositionAndExposesAccessibleMoves() {
        var state by mutableStateOf(fixture(3).copy(canAddPlayer = true, canReorderPlayers = true))
        val moves = mutableListOf<List<ParticipantId>>()
        compose.setContent { PontinhoTheme {
            MatchScreen(state, {}, {}, {}, {}, {}, {}, {}, {}, {}, onReorderPlayers = { order ->
                moves.add(order)
                state = state.copy(players = order.map { id -> state.players.single { it.id == id } })
            })
        } }
        val row = compose.onNodeWithTag("match_player_p1")
        val actions = row.fetchSemanticsNode().config[SemanticsActions.CustomActions]
        assertEquals(listOf(context.getString(R.string.setup_move_up, longName(1)), context.getString(R.string.setup_move_down, longName(1))), actions.map { it.label })
        compose.runOnIdle { actions[0].action() }
        assertEquals(listOf("p1", "p0", "p2"), moves.single().map { it.value })
        val height = row.fetchSemanticsNode().boundsInRoot.height
        val handle = compose.onNodeWithTag("match_drag_p1")
        handle.assertContentDescriptionEquals(context.getString(R.string.player_drag, longName(1)))
        handle.performTouchInput { down(center); moveBy(androidx.compose.ui.geometry.Offset(0f, height * 1.3f)) }
        compose.waitForIdle()
        assertTrue(row.fetchSemanticsNode().boundsInRoot.top > compose.onNodeWithTag("match_player_p0").fetchSemanticsNode().boundsInRoot.top)
        compose.runOnIdle { assertEquals("Drag preview cannot persist", 1, moves.size) }
        compose.runOnIdle { state = state.copy(players = state.players.map { it.copy(total = 10) }) }
        handle.performTouchInput { moveBy(androidx.compose.ui.geometry.Offset(0f, height * .1f)); up() }
        assertEquals(listOf("p0", "p1", "p2"), moves.last().map { it.value })
        compose.runOnIdle { assertEquals(listOf("p0", "p1", "p2"), state.players.map { it.id.value }) }
        compose.runOnIdle { state = state.copy(canAddPlayer = false, canReorderPlayers = false) }
        assertTrue(row.fetchSemanticsNode().config.getOrNull(SemanticsActions.CustomActions).isNullOrEmpty())
        handle.assertIsNotEnabled()
    }

    @Test fun threeFiveAndTwelveLongNamesAtBothFontScalesKeepIdentityActionsAndHistoryReachable() {
        var count by mutableIntStateOf(3)
        var scale by mutableFloatStateOf(1f)
        var selected: ParticipantId? = null
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                PontinhoTheme { Content(fixture(count), onAddScore = { selected = it }) }
            }
        }
        for (font in listOf(1f, 1.5f, 2f)) for (players in listOf(3, 5, 12)) {
            compose.runOnIdle { count = players; scale = font }
            compose.onNodeWithTag("match_players").performScrollToIndex(0)
            val action = compose.onNodeWithContentDescription(context.getString(R.string.match_add_score, longName(0)))
            action.assertIsDisplayed().assertIsEnabled()
            val before = action.fetchSemanticsNode().boundsInRoot
            val history = compose.onNode(hasTestTag("player_round_history") and hasAnyAncestor(hasTestTag("match_player_p0")))
            history.assert(hasScrollAction()).performTouchInput { swipeLeft() }
            assertTrue("Gesture must move history", history.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange].value() > 0f)
            action.assertIsDisplayed().performClick()
            assertEquals(before, action.fetchSemanticsNode().boundsInRoot)
            assertEquals(ParticipantId("p0"), selected)
            compose.onNodeWithText(longName(0)).assertIsDisplayed()
            val output = File(context.getExternalFilesDir(null), "task6-$players-players-font-$font.png")
            output.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            compose.onNodeWithTag("match_players").performScrollToIndex(players - 1)
            compose.onNodeWithContentDescription(context.getString(R.string.match_add_score, longName(players - 1)))
                .assertIsDisplayed().performClick()
            assertEquals(ParticipantId("p${players - 1}"), selected)
            compose.onNodeWithTag("match_players").performScrollToIndex(players)
            compose.onNodeWithText(context.getString(R.string.setup_add_player)).assertIsDisplayed().assertIsNotEnabled()
        }
    }

    @Test fun roundColumnsAlignAcrossDifferentScoreWidthsAndFollowSharedHorizontalGesture() {
        val state = fixture(3).copy(players = fixture(3).players.mapIndexed { index, player ->
            player.copy(history = player.history.map { it.copy(score = if (index == 0) 2147483647 else 0) })
        })
        compose.setContent { PontinhoTheme { Content(state) } }
        fun score(player: Int, round: Int) = compose.onNode(hasTestTag("round_score_$round") and hasAnyAncestor(hasTestTag("match_player_p$player")), useUnmergedTree = true)
        val a = score(0, 2).getUnclippedBoundsInRoot()
        val b = score(1, 2).getUnclippedBoundsInRoot()
        assertEquals("Round columns must line up regardless of score length", (a.left.value + a.right.value) / 2, (b.left.value + b.right.value) / 2, 1f)
        val history = compose.onNode(hasTestTag("player_round_history") and hasAnyAncestor(hasTestTag("match_player_p0")))
        history.performTouchInput { swipeLeft() }
        val afterA = score(0, 2).getUnclippedBoundsInRoot()
        val afterB = score(1, 2).getUnclippedBoundsInRoot()
        assertTrue("Shared gesture must move the history", afterA.left < a.left)
        assertEquals((afterA.left.value + afterA.right.value) / 2, (afterB.left.value + afterB.right.value) / 2, 1f)
    }

    @Test fun oneDealerEveryColumnHasScoreAndCompletedMatchReplacesPlusWithResults() {
        var finished by mutableStateOf(false)
        var viewed = false
        compose.setContent { PontinhoTheme { Content(fixture(3).copy(isFinished = finished), onViewResults = { viewed = true }) } }
        compose.onAllNodesWithContentDescription(context.getString(R.string.deals_cards)).assertCountEquals(1)
        compose.onAllNodesWithTag("round_score_1", useUnmergedTree = true).assertCountEquals(3)
        compose.onNodeWithText(context.getString(R.string.camera)).assertDoesNotExist()
        compose.runOnIdle { finished = true }
        compose.onAllNodesWithTag("player_action").assertCountEquals(0)
        compose.onNodeWithText(context.getString(R.string.match_view_result)).performClick()
        assertTrue(viewed)
    }

    @Test fun pickerAsksForTheWildcardDirectlyAndPreviewsOneRankColoredCard() {
        val localized = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
            setLocale(Locale.forLanguageTag("pt-BR"))
        })
        var state by mutableStateOf(MatchUiState(isLoading = false, picker = IndicatorPickerState(RoundId("round"), 1, PickerPurpose.EDIT)))
        var confirmed = false
        compose.setContent {
            CompositionLocalProvider(LocalContext provides localized, LocalResources provides localized.resources) {
                PontinhoTheme { Content(state,
                    onSelectIndicatorRank = { state = state.copy(picker = state.picker!!.copy(selectedRank = it)) },
                    onSelectIndicatorColor = { state = state.copy(picker = state.picker!!.copy(selectedColor = it)) },
                    onConfirmIndicator = { confirmed = true }) }
            }
        }
        compose.onNodeWithText("Escolher coringa").assertIsDisplayed()
        compose.onNodeWithText(localized.getString(R.string.confirm)).assertIsNotEnabled()
        compose.onNodeWithTag("indicator_rank_TEN").performClick().assertIsSelected()
        compose.onNodeWithText(localized.getString(R.string.confirm)).assertIsNotEnabled()
        compose.onNodeWithTag("indicator_color_RED").performClick().assertIsSelected()
        compose.onNodeWithTag("joker_preview").assertContentDescriptionEquals("Coringa: 10 vermelho")
        compose.onNodeWithTag("joker_card_TEN_RED", true).assert(
            SemanticsMatcher.expectValue(CardRankLabelKey, "10") and
                SemanticsMatcher.keyNotDefined(CardSuitGlyphKey))
        compose.onNodeWithText(localized.getString(R.string.confirm)).performClick()
        assertTrue(confirmed)
        compose.onNodeWithTag("indicator_rank_ACE").performClick().assertIsSelected()
        compose.onNodeWithTag("indicator_rank_TEN").assertIsNotSelected()
        compose.onNodeWithTag("indicator_color_BLACK").performClick().assertIsSelected()
        compose.onNodeWithTag("indicator_color_RED").assertIsNotSelected()
    }

    @Test fun historyCardsAnnounceRoundRankAndColorAndEditCompletedRound() {
        var clicked: Int? = null
        val localized = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
            setLocale(Locale.forLanguageTag("pt-BR"))
        })
        compose.setContent {
            CompositionLocalProvider(LocalContext provides localized, LocalResources provides localized.resources) {
                PontinhoTheme { MatchScreen(fixture(3).copy(jokers = listOf(
                    RoundJokerUi(null, 3, null),
                    RoundJokerUi(RoundId("one"), 1, Indicator(Rank.KING, CardColor.RED)),
                    RoundJokerUi(RoundId("two"), 2, Indicator(Rank.NINE, CardColor.BLACK)),
                )), {}, {}, {}, {}, {}, {}, {}, {}, {}, onEditJoker = { clicked = it }) }
            }
        }
        compose.onNodeWithText("Coringas").assertIsDisplayed()
        listOf("Rodada 1: coringa A vermelho", "Rodada 2: coringa 10 preto", "Rodada 3: coringa não definido")
            .forEach { compose.onAllNodesWithContentDescription(it).assertCountEquals(1) }
        val first = compose.onNodeWithTag("joker_round_1")
        val second = compose.onNodeWithTag("joker_round_2")
        assertTrue(first.fetchSemanticsNode().boundsInRoot.right <= second.fetchSemanticsNode().boundsInRoot.left)
        first.performClick()
        assertEquals(1, clicked)
        for ((tag, rank) in listOf("joker_card_ACE_RED" to "A", "joker_card_TEN_BLACK" to "10")) {
            compose.onNodeWithTag(tag, true).assert(SemanticsMatcher.expectValue(CardRankLabelKey, rank))
                .assert(SemanticsMatcher.keyNotDefined(CardSuitGlyphKey))
        }
        compose.onAllNodesWithTag("empty_card", true).assertCountEquals(1)
    }

    @Test fun scoreDialogFocusValidationRowsAndWinnerDisabledAreAccessible() {
        var value by mutableStateOf("2147483648")
        var confirmed: Int? = null
        compose.setContent { PontinhoTheme {
            ScoreDialog("Ana", 1, "A", value, { value = it }, { confirmed = it }, {}, {}, wentOutEnabled = false)
        } }
        val input = compose.onNodeWithTag("score_input")
        input.assertIsFocused()
        compose.onNodeWithText(context.getString(R.string.confirm)).assertIsNotEnabled()
        input.assertTextContains("2147483648")
        input.performTextReplacement("0")
        val confirm = compose.onNodeWithText(context.getString(R.string.confirm))
        val winner = compose.onNodeWithText(context.getString(R.string.went_out)).assertIsNotEnabled()
        val inputBounds = input.fetchSemanticsNode().boundsInRoot
        val confirmBounds = confirm.fetchSemanticsNode().boundsInRoot
        assertTrue("Confirm belongs alongside input", confirmBounds.center.y in inputBounds.top..inputBounds.bottom)
        assertTrue(winner.fetchSemanticsNode().boundsInRoot.top > inputBounds.bottom)
        confirm.performClick()
        assertEquals(0, confirmed)
        compose.onNodeWithText(context.getString(R.string.camera)).assertDoesNotExist()
    }

    @Test fun scoreDialogKeepsTypedValueAndUsableInputForRetryAtSystemFontScale() {
        var value by mutableStateOf("2147483648")
        var confirmed: Int? = null
        // Dialog windows use Android's density, so the compact matrix also runs this
        // test with actual system font_scale=2.0 rather than a surrounding override.
        compose.setContent { PontinhoTheme {
            ScoreDialog("Ana", 1, "A preto", value, { value = it }, { confirmed = it }, {}, {}, hasSaveError = true)
        } }
        val input = compose.onNodeWithTag("score_input")
        capture("task5-score-compact-2.0.png")
        val minimumInputWidth = if (context.resources.configuration.fontScale >= 1.5f) 200.dp else 120.dp
        input.assertIsFocused().assertTextContains("2147483648").assertWidthIsAtLeast(minimumInputWidth)
        compose.onNodeWithText(context.getString(R.string.confirm)).assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.score_invalid)).performScrollTo().assertIsDisplayed()
        input.performScrollTo().performTextReplacement("12")
        compose.onNodeWithText(context.getString(R.string.match_save_error)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.confirm)).performScrollTo().assertIsDisplayed().performClick()
        assertEquals(12, confirmed)
    }

    @Composable private fun Content(state: MatchUiState, onAddScore: (ParticipantId) -> Unit = {},
        onSelectIndicatorRank: (Rank) -> Unit = {}, onSelectIndicatorColor: (CardColor) -> Unit = {},
        onConfirmIndicator: () -> Unit = {}, onViewResults: () -> Unit = {}) {
        MatchScreen(state, {}, onAddScore, onSelectIndicatorRank, onConfirmIndicator, {}, {}, {}, {}, onViewResults,
            onSelectIndicatorColor = onSelectIndicatorColor)
    }
    private fun fixture(count: Int) = MatchUiState(isLoading = false, roundNumber = 21,
        players = (0 until count).map { i -> MatchPlayer(ParticipantId("p$i"), longName(i), i * 4,
            i == 0, (1..20).map { PlayerRoundHistory(it, if (i == 0) RoundStatusKind.WINNER else RoundStatusKind.LOSS, 0) }, true) })
    private fun longName(i: Int) = "Alexandre Maximiliano de Albuquerque e Silva $i"
}
