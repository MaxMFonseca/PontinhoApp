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
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.unit.dp
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
import androidx.compose.ui.test.cancel
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasTestTag
import androidx.test.platform.app.InstrumentationRegistry
import com.maxmfonseca.pontinho.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.unit.Density
import com.maxmfonseca.pontinho.settings.ThemePreference
import com.maxmfonseca.pontinho.ui.theme.PontinhoTheme
import com.maxmfonseca.pontinho.ui.components.ImmediateDragHandle
import java.util.Locale
import java.io.FileInputStream

class MatchSetupScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test fun duplicateRosterExplainsDisabledCreateAndClearsAfterNameIsResolved() {
        var state by mutableStateOf(MatchSetupUiState(players = listOf(
            DraftPlayer("a", null, "Max Fonseca"), DraftPlayer("b", null, "  MAX fonseca "),
        )))
        composeRule.setContent { SetupContent(state) }
        val list = composeRule.onNodeWithTag("setup_root")
        list.performScrollToNode(hasTestTag("setup_create_match"))
        composeRule.onNodeWithTag("setup_create_match").assertIsNotEnabled()
        composeRule.onNodeWithTag("setup_duplicate_names_error").assertIsDisplayed()
        composeRule.runOnIdle {
            state = state.copy(players = state.players.map { if (it.draftId == "b") it.copy(displayName = "Julia") else it })
        }
        composeRule.onNodeWithTag("setup_duplicate_names_error").assertDoesNotExist()
        composeRule.onNodeWithTag("setup_create_match").assertIsEnabled()
    }

    @Test fun reorderFailureIsVisibleEvenWhenTheRosterHasTwelvePlayers() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.setContent {
            SetupContent(MatchSetupUiState(
                players = (0..11).map { DraftPlayer("p$it", null, "Player $it") },
                reorderError = true,
            ))
        }
        composeRule.onNodeWithText(context.getString(R.string.setup_reorder_error)).assertIsDisplayed()
    }

    @Test fun disablingAnActiveHandleCancelsWithoutCommitting() {
        var enabled by mutableStateOf(true)
        var starts = 0
        var cancels = 0
        var releases = 0
        composeRule.setContent {
            ImmediateDragHandle(enabled, "Drag Ana", onDragStart = { starts++ }, onDragDelta = {},
                onDragEnd = { releases++ }, onDragCancel = { cancels++ })
        }
        composeRule.onNodeWithContentDescription("Drag Ana").performTouchInput {
            down(center); moveBy(Offset(0f, 120f))
        }
        composeRule.runOnIdle { assertEquals(1, starts); enabled = false }
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Drag Ana").assertIsNotEnabled()
        composeRule.runOnIdle { assertEquals(1, cancels); assertEquals(0, releases) }
    }

    @Test fun setupActionsRemainReachableAtAllFontScalesLocalesAndThemes() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        var scenario by mutableStateOf(Triple("pt-BR", ThemePreference.LIGHT, 1f))
        var safeTop = 0
        composeRule.setContent {
            val (locale, theme, scale) = scenario
            val context = base.createConfigurationContext(Configuration(base.resources.configuration).apply { setLocale(Locale.forLanguageTag(locale)) })
            CompositionLocalProvider(LocalContext provides context, LocalResources provides context.resources,
                LocalDensity provides Density(base.resources.displayMetrics.density, scale)) {
                val inset = WindowInsets.safeDrawing.getTop(LocalDensity.current)
                SideEffect { safeTop = inset }
                PontinhoTheme(theme) { Surface { Box(Modifier.size(320.dp, 568.dp)) {
                    SetupContent(MatchSetupUiState(players = listOf(
                        DraftPlayer("a", null, "Alexandre Maximiliano de Albuquerque"), DraftPlayer("b", null, "Bruno"),
                    )))
                } } }
            }
        }
        for (locale in listOf("pt-BR", "en-US")) for (theme in listOf(ThemePreference.LIGHT, ThemePreference.DARK)) for (scale in listOf(1f, 1.5f, 2f)) {
            composeRule.runOnIdle { scenario = Triple(locale, theme, scale) }
            val list = composeRule.onNodeWithTag("setup_root")
            list.performScrollToIndex(0)
            val help = if (locale == "pt-BR")
                "Adicione os jogadores em ordem anti-horária e toque na mão para escolher quem começa."
            else "Add players in counter-clockwise order and tap the hand to choose who starts."
            composeRule.onNodeWithText(help).assertIsDisplayed()
            val localized = base.createConfigurationContext(Configuration(base.resources.configuration).apply { setLocale(Locale.forLanguageTag(locale)) })
            val back = composeRule.onNodeWithContentDescription(localized.getString(R.string.match_back)).fetchSemanticsNode().boundsInRoot
            assertTrue("Back must be below the system status area", back.top >= safeTop)
            listOf("setup_player_a", "setup_add_player", "setup_use_last_match", "setup_create_match").forEach { tag ->
                list.performScrollToNode(hasTestTag(tag))
                composeRule.onNodeWithTag(tag).assertIsDisplayed().assertHeightIsAtLeast(48.dp)
            }
            val button = composeRule.onNodeWithTag("setup_create_match").fetchSemanticsNode().boundsInRoot
            val label = composeRule.onNodeWithTag("setup_create_match_label", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertTrue("Create label must fit at $scenario", button.contains(label.topLeft) && button.contains(label.bottomRight))
            if (locale == "pt-BR" && theme == ThemePreference.LIGHT && scale == 1f ||
                locale == "en-US" && theme == ThemePreference.DARK && scale == 2f) {
                val capture = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
                    "screencap -p /sdcard/Download/task4-setup-footer-$locale-$scale.png")
                FileInputStream(capture.fileDescriptor).use { it.readBytes() }
                capture.close()
                list.performScrollToIndex(0)
                composeRule.waitForIdle()
                val topCapture = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
                    "screencap -p /sdcard/Download/task4-setup-$locale-$scale.png")
                FileInputStream(topCapture.fileDescriptor).use { it.readBytes() }
                topCapture.close()
            }
        }
    }

    @Test
    fun setupShowsRequiredActionsAndNoProhibitedControls() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.setContent { SetupContent(MatchSetupUiState()) }

        composeRule.onNodeWithText(context.getString(R.string.route_setup)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.setup_first_player_help)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.setup_add_player)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.setup_use_last_match)).assertIsNotEnabled()
        composeRule.onNodeWithText(context.getString(R.string.setup_no_previous_match)).assertIsDisplayed()
        composeRule.onNodeWithText("Mínimo de 2 jogadores").assertDoesNotExist()
        composeRule.onNodeWithText("Minimum 2 players").assertDoesNotExist()
        composeRule.onNodeWithText("Substitui a lista atual.").assertDoesNotExist()
        composeRule.onNodeWithText("Replaces the current list.").assertDoesNotExist()
        composeRule.onNodeWithText(context.getString(R.string.cancel)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.setup_create_match)).assertIsNotEnabled()
        composeRule.onNodeWithText("Data", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("Editar jogadores", substring = true).assertDoesNotExist()
        composeRule.onNodeWithTag("advertisement").assertDoesNotExist()
        composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState)).assertCountEquals(0)
    }

    @Test fun handButtonSelectsOpeningPlayerWithoutMovingCards() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var selected = "a"
        val players = listOf(DraftPlayer("a", null, "Ana"), DraftPlayer("b", null, "Bruno"))
        composeRule.setContent {
            SetupContent(
                MatchSetupUiState(players = players, initialDealerDraftId = selected),
                onSelectInitialDealer = { selected = it },
            )
        }
        composeRule.onNodeWithTag("setup_hand_a")
            .assertContentDescriptionEquals(context.getString(R.string.setup_initial_hand_selected, "Ana"))
            .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        val before = players.map { composeRule.onNodeWithTag("setup_player_${it.draftId}").fetchSemanticsNode().boundsInRoot.top }
        composeRule.onNodeWithTag("setup_hand_b").performClick()
        composeRule.runOnIdle { assertEquals("b", selected) }
        val after = players.map { composeRule.onNodeWithTag("setup_player_${it.draftId}").fetchSemanticsNode().boundsInRoot.top }
        assertEquals(before, after)
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
    fun normalHandleMovementReordersWithoutLongPress() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val commits = mutableListOf<List<String>>()
        composeRule.setContent {
            SetupContent(MatchSetupUiState(players = listOf(
                DraftPlayer("a", null, "Ana"), DraftPlayer("b", null, "Bruno"),
            )), onReorderPlayers = { commits.add(it) })
        }
        val rowHeight = composeRule.onNodeWithTag("setup_player_a").fetchSemanticsNode().boundsInRoot.height
        composeRule.onNodeWithContentDescription(context.getString(R.string.player_drag, "Ana"))
            .performTouchInput {
                down(center)
                moveBy(Offset(0f, rowHeight * 1.4f))
                up()
            }
        composeRule.runOnIdle { assertEquals(listOf(listOf("b", "a")), commits) }
    }

    @Test
    fun dragHandleHasFortyEightDpVisibleTarget() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.setContent { SetupContent(MatchSetupUiState(players = listOf(DraftPlayer("a", null, "Ana")))) }
        composeRule.onNodeWithContentDescription(context.getString(R.string.player_drag, "Ana"))
            .assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
    }

    @Test
    fun physicalDragKeepsOriginalIdentityAcrossSettledRecomposition() {
        var players by mutableStateOf(listOf(
            DraftPlayer("a", null, "Ana"),
            DraftPlayer("b", null, "Bruno"),
            DraftPlayer("c", null, "Carla"),
            DraftPlayer("d", null, "Diego"),
        ))
        val commits = mutableListOf<List<String>>()
        composeRule.setContent {
            SetupContent(
                state = MatchSetupUiState(players = players),
                onReorderPlayers = { order ->
                    commits.add(order)
                    players = order.map { id -> players.single { it.draftId == id } }
                },
            )
        }
        val row = composeRule.onNodeWithTag("setup_player_a")
        val height = row.fetchSemanticsNode().layoutInfo.coordinates.size.height.toFloat()

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val handle = composeRule.onNodeWithContentDescription(context.getString(R.string.player_drag, "Ana"))
        handle.performTouchInput {
            down(center)
            moveBy(Offset(0f, height * 1.4f))
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle { assertTrue("Preview must not persist", commits.isEmpty()) }
        val previewAna = composeRule.onNodeWithTag("setup_player_a").fetchSemanticsNode().boundsInRoot
        val previewBruno = composeRule.onNodeWithTag("setup_player_b").fetchSemanticsNode().boundsInRoot
        assertTrue("Crossing Bruno's midpoint must preview Ana below Bruno", previewAna.top > previewBruno.top)
        handle.performTouchInput {
            moveBy(Offset(0f, height * 1.4f))
            up()
        }

        composeRule.runOnIdle {
            assertEquals(listOf("b", "c", "a", "d"), players.map { it.draftId })
            assertEquals(listOf(listOf("b", "c", "a", "d")), commits)
        }
    }

    @Test fun draggingNameOrCardDoesNotReorder() {
        val commits = mutableListOf<List<String>>()
        composeRule.setContent { SetupContent(MatchSetupUiState(players = roster()), onReorderPlayers = { commits.add(it) }) }
        composeRule.onNodeWithText("Ana").performTouchInput {
            down(center); moveBy(Offset(0f, 180f)); up()
        }
        composeRule.runOnIdle { assertTrue(commits.isEmpty()) }
    }

    @Test fun cancelledDragRestoresCommittedOrderWithoutCallback() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val commits = mutableListOf<List<String>>()
        composeRule.setContent { SetupContent(MatchSetupUiState(players = roster()), onReorderPlayers = { commits.add(it) }) }
        val initialAna = composeRule.onNodeWithTag("setup_player_a").fetchSemanticsNode().boundsInRoot
        val initialBruno = composeRule.onNodeWithTag("setup_player_b").fetchSemanticsNode().boundsInRoot
        val movement = initialBruno.center.y - initialAna.center.y + 16f * context.resources.displayMetrics.density
        composeRule.onNodeWithContentDescription(context.getString(R.string.player_drag, "Ana")).performTouchInput {
            down(center); moveBy(Offset(0f, movement))
        }
        composeRule.waitForIdle()
        val previewAna = composeRule.onNodeWithTag("setup_player_a").fetchSemanticsNode().boundsInRoot
        val previewBruno = composeRule.onNodeWithTag("setup_player_b").fetchSemanticsNode().boundsInRoot
        assertTrue("Cancellation test must first produce a reordered preview", previewAna.top > previewBruno.top)
        composeRule.onNodeWithContentDescription(context.getString(R.string.player_drag, "Ana")).performTouchInput { cancel() }
        composeRule.runOnIdle { assertTrue(commits.isEmpty()) }
        val ana = composeRule.onNodeWithTag("setup_player_a").fetchSemanticsNode().boundsInRoot
        val bruno = composeRule.onNodeWithTag("setup_player_b").fetchSemanticsNode().boundsInRoot
        assertTrue(ana.top < bruno.top)
    }

    @Test fun edgeDragAutoScrollsTwelvePlayersAndCommitsOnlyOnRelease() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val commits = mutableListOf<List<String>>()
        val players = (0..11).map { DraftPlayer("p$it", null, "Player $it") }
        composeRule.setContent { SetupContent(MatchSetupUiState(players = players), onReorderPlayers = { commits.add(it) }) }
        val list = composeRule.onNodeWithTag("setup_root")
        list.performScrollToNode(hasTestTag("setup_player_p0"))
        val bounds = list.fetchSemanticsNode().boundsInRoot
        val handle = composeRule.onNodeWithContentDescription(context.getString(R.string.player_drag, "Player 0"))
        val start = handle.fetchSemanticsNode().boundsInRoot.center
        composeRule.mainClock.autoAdvance = false
        handle.performTouchInput {
            down(center); moveBy(Offset(0f, bounds.bottom - start.y - 12f))
        }
        repeat(120) { composeRule.mainClock.advanceTimeByFrame(); composeRule.waitForIdle() }
        composeRule.runOnIdle { assertTrue(commits.isEmpty()) }
        list.performTouchInput { up() }
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertEquals(1, commits.size)
            assertEquals((1..11).map { "p$it" } + "p0", commits.single())
        }
    }

    @Test fun edgeDragReachesLastPositionWithTallRowsAtTwoHundredPercent() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val commits = mutableListOf<List<String>>()
        val players = (0..11).map { DraftPlayer("p$it", null, "Player $it Alexandre Maximiliano de Albuquerque") }
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(context.resources.displayMetrics.density, 2f)) {
                Box(Modifier.size(320.dp, 568.dp)) {
                    SetupContent(MatchSetupUiState(players = players), onReorderPlayers = { commits.add(it) })
                }
            }
        }
        val list = composeRule.onNodeWithTag("setup_root")
        list.performScrollToNode(hasTestTag("setup_player_p0"))
        val bounds = list.fetchSemanticsNode().boundsInRoot
        val handle = composeRule.onNodeWithContentDescription(context.getString(R.string.player_drag, players.first().displayName))
        val start = handle.fetchSemanticsNode().boundsInRoot.center
        composeRule.mainClock.autoAdvance = false
        handle.performTouchInput { down(center); moveBy(Offset(0f, bounds.bottom - start.y - 12f)) }
        repeat(360) { composeRule.mainClock.advanceTimeByFrame(); composeRule.waitForIdle() }
        composeRule.runOnIdle { assertTrue(commits.isEmpty()) }
        list.performTouchInput { up() }
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertEquals(listOf((1..11).map { "p$it" } + "p0"), commits)
        }
    }

    @Test fun edgeDragEvaluatesFinalViewportEvenWhenNoScrollRemains() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val commits = mutableListOf<List<String>>()
        val players = (0..9).map { DraftPlayer("p$it", null, "Player $it") }
        composeRule.setContent { SetupContent(MatchSetupUiState(players = players), onReorderPlayers = { commits.add(it) }) }
        val list = composeRule.onNodeWithTag("setup_root")
        list.performScrollToNode(hasTestTag("setup_player_p0"))
        // Capture the layout driver before dragging disables user scrolling. This
        // models a pending scroll/layout settling without injecting another pointer move.
        val settleViewport = list.fetchSemanticsNode().config[SemanticsActions.ScrollToIndex].action!!
        val bounds = list.fetchSemanticsNode().boundsInRoot
        val handle = composeRule.onNodeWithContentDescription(context.getString(R.string.player_drag, "Player 0"))
        val start = handle.fetchSemanticsNode().boundsInRoot.center
        composeRule.mainClock.autoAdvance = false
        handle.performTouchInput { down(center); moveBy(Offset(0f, bounds.bottom - start.y - 12f)) }
        // Settle the final viewport before the next edge frame. The scroll attempt in
        // that frame consumes zero, but these newly laid-out midpoints still count.
        repeat(2) { composeRule.mainClock.advanceTimeByFrame(); composeRule.waitForIdle() }
        composeRule.runOnIdle { settleViewport(3 + players.size + 2) } // Three headers, then the final footer.
        repeat(4) { composeRule.mainClock.advanceTimeByFrame(); composeRule.waitForIdle() }
        composeRule.runOnIdle { assertTrue(commits.isEmpty()) }
        list.performTouchInput { up() }
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
        composeRule.runOnIdle { assertEquals(listOf((1..9).map { "p$it" } + "p0"), commits) }
    }

    private fun roster() = listOf(DraftPlayer("a", null, "Ana"), DraftPlayer("b", null, "Bruno"), DraftPlayer("c", null, "Carla"))

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
        onReorderPlayers: (List<String>) -> Unit = {},
        onSelectInitialDealer: (String) -> Unit = {},
    ) = MatchSetupScreen(
        state = state,
        onBack = {},
        onAddPlayer = {},
        onEditPlayer = onEditPlayer,
        onRemovePlayer = onRemovePlayer,
        onMovePlayer = onMovePlayer,
        onReorderPlayers = onReorderPlayers,
        onSelectInitialDealer = onSelectInitialDealer,
        onUseLastMatch = {},
        onDismissReplace = {},
        onConfirmReplace = {},
        onDialogNameChange = {},
        onDismissName = {},
        onConfirmName = {},
        onCreateMatch = {},
    )
}
