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

class MatchScreenTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun addPlayerIsLastScrollingItemAndGuardedDuringRounds() {
        var allowed by mutableStateOf(true)
        var added = 0
        compose.setContent { PontinhoTheme {
            MatchScreen(fixture(12).copy(canManagePlayers = allowed), {}, {}, {}, {}, {}, {}, {}, {}, {}, onAddPlayer = { added++ })
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
            MatchScreen(fixture(3).copy(canManagePlayers = true, playerName = name), {}, {}, {}, {}, {}, {}, {}, {}, {},
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
        dot.assertHasNoClickAction().performTouchInput { click(center) }
        score.assertHasNoClickAction().performTouchInput { click(center) }
        assertEquals(listOf(ParticipantId("p0") to 1, ParticipantId("p0") to 1), clicks)
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
        var state by mutableStateOf(fixture(3).copy(canManagePlayers = true))
        val moves = mutableListOf<Pair<ParticipantId, Int>>()
        compose.setContent { PontinhoTheme {
            MatchScreen(state, {}, {}, {}, {}, {}, {}, {}, {}, {}, onMovePlayer = { id, delta ->
                moves += id to delta
                val from = state.players.indexOfFirst { it.id == id }
                val to = (from + delta).coerceIn(state.players.indices)
                state = state.copy(players = state.players.toMutableList().apply { add(to, removeAt(from)) })
            })
        } }
        val row = compose.onNodeWithTag("match_player_p1")
        val actions = row.fetchSemanticsNode().config[SemanticsActions.CustomActions]
        assertEquals(listOf(context.getString(R.string.setup_move_up, longName(1)), context.getString(R.string.setup_move_down, longName(1))), actions.map { it.label })
        compose.runOnIdle { actions[0].action() }
        assertEquals(ParticipantId("p1") to -1, moves.single())
        val height = row.fetchSemanticsNode().boundsInRoot.height
        val handle = compose.onNodeWithTag("match_drag_p1")
        handle.assertContentDescriptionEquals(context.getString(R.string.player_drag, longName(1)))
        handle.performTouchInput { down(center); advanceEventTime(700); moveBy(androidx.compose.ui.geometry.Offset(0f, height * .6f)) }
        compose.runOnIdle { state = state.copy(players = state.players.map { it.copy(total = 10) }) }
        handle.performTouchInput { moveBy(androidx.compose.ui.geometry.Offset(0f, height * .5f)); up() }
        assertEquals(ParticipantId("p1") to 1, moves.last())
        compose.runOnIdle { assertEquals(listOf("p0", "p1", "p2"), state.players.map { it.id.value }) }
        compose.runOnIdle { state = state.copy(canManagePlayers = false) }
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
        for (font in listOf(1f, 1.5f)) for (players in listOf(3, 5, 12)) {
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
        assertEquals((afterA.left.value + afterA.right.value) / 2, (afterB.left.value + afterB.right.value) / 2, 1f)
    }

    @Test fun oneDealerEveryColumnHasScoreAndCompletedMatchReplacesPlusWithResults() {
        var finished by mutableStateOf(false)
        var viewed = false
        compose.setContent { PontinhoTheme { Content(fixture(3).copy(isFinished = finished), onViewResults = { viewed = true }) } }
        compose.onAllNodesWithText("☝ ${context.getString(R.string.deals_cards)}").assertCountEquals(1)
        compose.onAllNodesWithTag("round_score_1", useUnmergedTree = true).assertCountEquals(3)
        compose.onNodeWithText(context.getString(R.string.camera)).assertDoesNotExist()
        compose.runOnIdle { finished = true }
        compose.onAllNodesWithTag("player_action").assertCountEquals(0)
        compose.onNodeWithText(context.getString(R.string.match_view_result)).performClick()
        assertTrue(viewed)
    }

    @Test fun pickerExposesBothColorsAllRanksAndKingPreviewBeforeConfirm() {
        var state by mutableStateOf(MatchUiState(isLoading = false, picker = IndicatorPickerState(ParticipantId("p0"))))
        var confirmed = false
        compose.setContent { PontinhoTheme { Content(state,
            onSelectIndicator = { state = state.copy(picker = state.picker!!.copy(selected = it)) },
            onConfirmIndicator = { confirmed = true }) } }
        compose.onNodeWithText(context.getString(R.string.confirm)).assertIsNotEnabled()
        compose.onNodeWithTag("joker_options").performScrollToNode(hasTestTag("indicator_BLACK_KING"))
        compose.onNodeWithTag("indicator_BLACK_KING").performClick()
        compose.onNodeWithTag("joker_preview").assertTextContains("A♠", substring = true)
        compose.onNodeWithText(context.getString(R.string.confirm)).performClick()
        assertTrue(confirmed)
        compose.onNodeWithTag("joker_options").performScrollToNode(hasTestTag("indicator_RED_ACE"))
        compose.onNodeWithTag("indicator_RED_ACE").assertContentDescriptionEquals("A ${context.getString(R.string.indicator_red)}")
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

    @Composable private fun Content(state: MatchUiState, onAddScore: (ParticipantId) -> Unit = {},
        onSelectIndicator: (Indicator) -> Unit = {}, onConfirmIndicator: () -> Unit = {}, onViewResults: () -> Unit = {}) {
        MatchScreen(state, {}, onAddScore, onSelectIndicator, onConfirmIndicator, {}, {}, {}, {}, onViewResults)
    }
    private fun fixture(count: Int) = MatchUiState(isLoading = false, roundNumber = 21,
        players = (0 until count).map { i -> MatchPlayer(ParticipantId("p$i"), longName(i), i * 4,
            i == 0, (1..20).map { PlayerRoundHistory(it, if (i == 0) RoundStatusKind.WINNER else RoundStatusKind.LOSS, 0) }, true) })
    private fun longName(i: Int) = "Alexandre Maximiliano de Albuquerque e Silva $i"
}
