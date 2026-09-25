package com.maxmfonseca.pontinho.ui.setup

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.down
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.up
import androidx.test.platform.app.InstrumentationRegistry
import com.maxmfonseca.pontinho.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MatchSetupScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun setupShowsRequiredActionsAndNoProhibitedControls() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.setContent { SetupContent(MatchSetupUiState()) }

        composeRule.onNodeWithText(context.getString(R.string.route_setup)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.setup_first_player_help)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.setup_add_player)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.setup_use_last_match)).assertIsNotEnabled()
        composeRule.onNodeWithText(context.getString(R.string.setup_no_previous_match)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.setup_minimum_players)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.cancel)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.setup_create_match)).assertIsNotEnabled()
        composeRule.onNodeWithText("Data", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("Editar jogadores", substring = true).assertDoesNotExist()
        composeRule.onNodeWithTag("advertisement").assertDoesNotExist()
        composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState)).assertCountEquals(0)
    }

    @Test fun backGlyphHasLocalizedTalkBackDescription() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.setContent { SetupContent(MatchSetupUiState()) }
        composeRule.onNodeWithContentDescription(context.getString(R.string.match_back))
            .assertContentDescriptionEquals(context.getString(R.string.match_back))
    }

    @Test
    fun playerNameIsEditableRemovalIsXAndRowsExposeAccessibleMoveActions() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val players = listOf(
            DraftPlayer("a", null, "Ana"),
            DraftPlayer("b", null, "Bruno"),
        )
        var edited: String? = null
        var removed: String? = null
        var moved: Pair<String, Int>? = null
        composeRule.setContent {
            SetupContent(
                state = MatchSetupUiState(players = players),
                onEditPlayer = { edited = it },
                onRemovePlayer = { removed = it },
                onMovePlayer = { id, direction -> moved = id to direction },
            )
        }

        composeRule.onNodeWithText("Ana").performClick()
        composeRule.onNodeWithContentDescription(context.getString(R.string.player_edit, "Ana")).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(context.getString(R.string.player_drag, "Ana")).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(context.getString(R.string.setup_remove_player, "Ana")).performClick()
        val actions = composeRule.onNodeWithTag("setup_player_a").fetchSemanticsNode()
            .config[SemanticsActions.CustomActions]
        assertTrue(actions.any { it.label == context.getString(R.string.setup_move_down, "Ana") })
        actions.first { it.label == context.getString(R.string.setup_move_down, "Ana") }.action()
        composeRule.runOnIdle {
            assertEquals("a", edited)
            assertEquals("a", removed)
            assertEquals("a" to 1, moved)
        }
    }

    @Test
    fun physicalDragKeepsOriginalIdentityAcrossSettledRecomposition() {
        var players by mutableStateOf(listOf(
            DraftPlayer("a", null, "Ana"),
            DraftPlayer("b", null, "Bruno"),
            DraftPlayer("c", null, "Carla"),
            DraftPlayer("d", null, "Diego"),
        ))
        composeRule.setContent {
            SetupContent(
                state = MatchSetupUiState(players = players),
                onMovePlayer = { id, direction ->
                    val from = players.indexOfFirst { it.draftId == id }
                    val to = from + direction
                    if (from in players.indices && to in players.indices) {
                        players = players.toMutableList().apply { add(to, removeAt(from)) }
                    }
                },
            )
        }
        val row = composeRule.onNodeWithTag("setup_player_a")
        val height = row.fetchSemanticsNode().layoutInfo.coordinates.size.height.toFloat()

        row.performTouchInput {
            down(center)
            advanceEventTime(700)
            moveBy(Offset(0f, height * 1.2f))
        }
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("setup_player_a").performTouchInput {
            moveBy(Offset(0f, height * 1.2f))
            up()
        }

        composeRule.runOnIdle {
            assertEquals(listOf("b", "c", "a", "d"), players.map { it.draftId })
        }
    }

    @Test
    fun sharedNameDialogIsFocusedAndKeepsBlankValidationVisible() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.setContent {
            SetupContent(
                MatchSetupUiState(
                    nameDialog = PlayerNameDialogState(null, "   ", showBlankError = true),
                ),
            )
        }

        composeRule.onNodeWithTag("player_name_input").assertIsFocused()
        composeRule.onNodeWithText(context.getString(R.string.setup_blank_name_error)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.confirm)).assertIsEnabled()
    }

    @Test
    fun replacementRequiresConfirmationForNonemptyDraft() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.setContent {
            SetupContent(
                MatchSetupUiState(
                    players = listOf(DraftPlayer("a", null, "Ana")),
                    latestMatchPlayers = listOf(DraftPlayer("b", null, "Bruno")),
                    showReplaceConfirmation = true,
                ),
            )
        }

        composeRule.onNodeWithText(context.getString(R.string.setup_replace_title)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.setup_replace_message)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.setup_replace_action)).assertIsEnabled()
    }

    @Test
    fun twoPlayersEnableCreate() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.setContent {
            SetupContent(
                MatchSetupUiState(
                    players = listOf(
                        DraftPlayer("a", null, "Ana"),
                        DraftPlayer("b", null, "Bruno"),
                    ),
                ),
            )
        }

        composeRule.onNodeWithText(context.getString(R.string.setup_create_match)).assertIsEnabled()
    }

    @Composable
    private fun SetupContent(
        state: MatchSetupUiState,
        onEditPlayer: (String) -> Unit = {},
        onRemovePlayer: (String) -> Unit = {},
        onMovePlayer: (String, Int) -> Unit = { _, _ -> },
    ) = MatchSetupScreen(
        state = state,
        onBack = {},
        onAddPlayer = {},
        onEditPlayer = onEditPlayer,
        onRemovePlayer = onRemovePlayer,
        onMovePlayer = onMovePlayer,
        onUseLastMatch = {},
        onDismissReplace = {},
        onConfirmReplace = {},
        onDialogNameChange = {},
        onDismissName = {},
        onConfirmName = {},
        onCreateMatch = {},
    )
}
