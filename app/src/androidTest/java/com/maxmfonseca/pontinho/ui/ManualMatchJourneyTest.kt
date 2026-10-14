package com.maxmfonseca.pontinho.ui

import android.app.UiModeManager
import android.content.res.Configuration
import androidx.core.view.WindowCompat
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.maxmfonseca.pontinho.MainActivity
import com.maxmfonseca.pontinho.PontinhoApplication
import com.maxmfonseca.pontinho.R
import com.maxmfonseca.pontinho.data.local.PontinhoDatabase
import com.maxmfonseca.pontinho.data.repository.RoomMatchRepository
import com.maxmfonseca.pontinho.domain.model.*
import com.maxmfonseca.pontinho.domain.repository.*
import com.maxmfonseca.pontinho.settings.ThemePreference
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.FileInputStream

/** Real navigation + file-backed repositories. Literal totals catch historical identity relabeling. */
class ManualMatchJourneyTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val repository: MatchRepository
        get() = (compose.activity.application as PontinhoApplication).container.matchRepository

    @Test fun explicitThemeControlsSystemBarIconsAcrossOppositeDeviceThemesAndRecreation() = runBlocking {
        val preferences = (compose.activity.application as PontinhoApplication).container.preferencesRepository
        val originalTheme = preferences.preferences.first().theme
        val originalNight = compose.activity.getSystemService(UiModeManager::class.java).nightMode
        fun systemTheme(night: Boolean) {
            shell("cmd uimode night ${if (night) "yes" else "no"}")
            compose.waitUntil(10_000) {
                val actual = compose.activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                actual == if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            }
        }
        fun assertBars(lightBackground: Boolean) = compose.runOnIdle {
            val window = compose.activity.window
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            assertEquals("Status-bar icons must follow the app's effective theme", lightBackground, controller.isAppearanceLightStatusBars)
            assertEquals("Navigation-bar icons must follow the app's effective theme", lightBackground, controller.isAppearanceLightNavigationBars)
        }
        fun awaitTheme(label: Int) = compose.waitUntil(10_000) {
            compose.onNodeWithText(text(label)).fetchSemanticsNode()
                .config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Selected) == true
        }
        try {
            systemTheme(false)
            await("home_root")
            compose.onNodeWithContentDescription(text(R.string.home_settings)).performClick()
            compose.onNodeWithText(text(R.string.route_appearance)).performClick()
            compose.onNodeWithText(text(R.string.appearance_theme_dark)).performClick()
            awaitTheme(R.string.appearance_theme_dark)
            assertBars(false)
            capture("system-bars-explicit-dark-system-light")
            compose.activityRule.scenario.recreate()
            await("appearance_root")
            awaitTheme(R.string.appearance_theme_dark)
            assertBars(false)
            systemTheme(true)
            compose.onNodeWithText(text(R.string.appearance_theme_light)).performClick()
            awaitTheme(R.string.appearance_theme_light)
            assertBars(true)
            capture("system-bars-explicit-light-system-dark")
            compose.activityRule.scenario.recreate()
            await("appearance_root")
            awaitTheme(R.string.appearance_theme_light)
            assertBars(true)
        } finally {
            preferences.setTheme(originalTheme)
            shell("cmd uimode night ${when (originalNight) { UiModeManager.MODE_NIGHT_NO -> "no"; UiModeManager.MODE_NIGHT_YES -> "yes"; else -> "auto" }}")
        }
    }

    @Test fun completeOfflineJourneyPersistsCorrectsRenamesAndReopensExactDailySummary() = runBlocking {
        clearDatabase()
        openSetup()
        listOf("Max", "Julia").forEach(::addSetupPlayer)
        val max = compose.onNodeWithContentDescription(text(R.string.player_drag, "Max"))
        val julia = compose.onNodeWithContentDescription(text(R.string.player_drag, "Julia"))
        val distance = julia.fetchSemanticsNode().boundsInRoot.center.y - max.fetchSemanticsNode().boundsInRoot.center.y
        max.performTouchInput { down(center); moveBy(Offset(0f, distance * 1.4f)); up() }
        compose.activityRule.scenario.recreate()
        createMatch()
        await("match_root")
        val first = repository.observeHome().first().flatMap { it.matches }.maxBy { it.createdAt }
        assertEquals(listOf("Julia", "Max"), first.participants.map { it.displayName })
        val juliaId = first.participants.single { it.displayName == "Julia" }.id
        val maxId = first.participants.single { it.displayName == "Max" }.id
        enterScore("Max", 20, true)
        enterScore("Julia", 10, true)
        enterScore("Max", 30, true)
        enterScore("Julia", 24, true)
        compose.onNodeWithTag("match_players").performScrollToIndex(2)
        compose.onNodeWithTag("match_add_player").performClick()
        enterName("Eva")
        val eva = repository.observeMatch(first.id).first()!!.participants.single { it.displayName == "Eva" }
        assertEquals(5, eva.eligibleFromRoundOrdinal)
        openScore("Julia", true)
        compose.onNodeWithText(text(R.string.went_out)).performClick()
        waitForScoreDialogToClose()
        enterScore("Max", 60)
        compose.onNode(hasTestTag("round_score_4") and hasAnyAncestor(hasTestTag("match_player_${juliaId.value}")), true)
            .performScrollTo().performClick()
        compose.onNodeWithTag("score_input").assertTextContains("24").performTextReplacement("23")
        compose.onNodeWithText(text(R.string.confirm)).performClick()
        waitForScoreDialogToClose()
        compose.activityRule.scenario.recreate()
        await("match_root")
        assertEquals(5, repository.observeMatch(first.id).first()!!.openRound?.ordinal)
        enterScore("Eva", 50)
        await("results_root")
        val completed = repository.observeMatch(first.id).first()!!
        assertTrue(completed.isFinished)
        assertEquals(5, completed.rounds.size)
        assertEquals(33, completed.totals.getValue(juliaId))
        assertEquals(110, completed.totals.getValue(maxId))
        assertEquals(setOf(juliaId), completed.outcome!!.winners)
        capture("journey-first-results")
        compose.onNodeWithText(text(R.string.results_back_history)).performScrollTo().performClick()
        await("history_root")
        compose.onNodeWithText(text(R.string.home_new_match)).performScrollTo().performClick()
        await("setup_root")
        compose.onNodeWithTag("setup_use_last_match").performScrollTo().performClick()
        compose.onNodeWithContentDescription(text(R.string.setup_remove_player, "Eva")).performScrollTo().performClick()
        compose.onNodeWithContentDescription(text(R.string.player_edit, "Julia")).performScrollTo().performClick()
        enterName("Julia2")
        createMatch()
        await("match_root")
        enterScore("Julia2", 110, true)
        await("results_root")
        compose.activityRule.scenario.recreate()
        await("results_root")
        compose.onNodeWithText(text(R.string.results_back_history)).performScrollTo().performClick()
        await("history_root")
        compose.onNodeWithTag("daily_summary_toggle").performScrollTo().performClick()
        for ((name, wins, points) in listOf(Triple("julia", 1, 33), Triple("max", 1, 110), Triple("julia2", 0, 110))) {
            compose.onNodeWithTag("daily_wins_$name").performScrollTo().assertTextContains(text(R.string.history_metric_value, text(R.string.history_wins), wins))
            compose.onNodeWithTag("daily_points_$name").performScrollTo().assertTextContains(text(R.string.history_metric_value, text(R.string.history_penalties), points))
        }
        capture("journey-daily-summary")
        compose.activityRule.scenario.recreate()
        await("history_root")
        withIndependentDatabase { reopened ->
            val restored = RoomMatchRepository(reopened)
            val history = restored.observeDay(first.localDate).first()
            assertEquals(2, history.matches.size)
            assertEquals(mapOf("julia" to 1, "max" to 1, "julia2" to 0, "eva" to 0), history.wins.associate { it.playerKey.value to it.value })
            assertEquals(mapOf("julia" to 33, "max" to 110, "julia2" to 110, "eva" to 50), history.penalties.associate { it.playerKey.value to it.value })
            assertEquals(listOf("Julia", "Max", "Eva"), restored.observeMatch(first.id).first()!!.participants.map { it.displayName })
            assertFalse(restored.observeMatch(first.id).first()!!.hasPendingResults)
        }
    }

    @Test fun twelvePlayerSetupAndTwentyRoundMatchDragBothEdgesCancelCommitAndSurviveRecreation() = runBlocking {
        clearDatabase()
        openSetup()
        val names = (1..12).map { "Player $it" }
        names.forEach(::addSetupPlayer)
        edgeDrag("setup_root", names.first(), release = false)
        continueToTopAndCancel("setup_root")
        compose.onNodeWithTag("setup_root").performScrollToIndex(3)
        assertTrue(compose.onNodeWithText("Player 1").fetchSemanticsNode().boundsInRoot.top <
            compose.onNodeWithText("Player 2").fetchSemanticsNode().boundsInRoot.top)
        edgeDrag("setup_root", names.first(), release = true)
        compose.activityRule.scenario.recreate()
        createMatch()
        await("match_root")
        val created = repository.observeHome().first().flatMap { it.matches }.maxBy { it.createdAt }
        val setupOrder = names.drop(1) + names.first()
        assertEquals(setupOrder, created.participants.map { it.displayName })
        repeat(20) { ordinal ->
            val round = repository.prepareRound(PrepareRound(created.id, Indicator(Rank.ACE, CardColor.RED)))
            created.participants.dropLast(1).forEachIndexed { index, player ->
                repository.submitScore(SubmitScore(created.id, round.id, player.id, 1, "stress-$ordinal-$index", round))
            }
        }
        compose.waitForIdle()
        assertEquals(20, repository.observeMatch(created.id).first()!!.rounds.size)
        edgeDrag("match_players", setupOrder.first(), release = false)
        continueToTopAndCancel("match_players")
        assertEquals(setupOrder, repository.observeMatch(created.id).first()!!.participants.map { it.displayName })
        edgeDrag("match_players", setupOrder.first(), release = true)
        val finalOrder = setupOrder.drop(1) + setupOrder.first()
        compose.waitUntil(10_000) { runBlocking { repository.observeMatch(created.id).first()!!.participants.map { it.displayName } == finalOrder } }
        compose.activityRule.scenario.recreate()
        await("match_root")
        withIndependentDatabase { reopened ->
            val restored = RoomMatchRepository(reopened).observeMatch(created.id).first()!!
            assertEquals(finalOrder, restored.participants.map { it.displayName })
            assertEquals(20, restored.rounds.size)
            assertFalse(restored.isFinished)
        }
        capture("journey-twelve-players-twenty-rounds")
    }

    private fun edgeDrag(listTag: String, name: String, release: Boolean) {
        val list = compose.onNodeWithTag(listTag)
        list.performScrollToNode(hasContentDescription(text(R.string.player_drag, name)))
        val bounds = list.fetchSemanticsNode().boundsInRoot
        val handle = compose.onNodeWithContentDescription(text(R.string.player_drag, name))
        val start = handle.fetchSemanticsNode().boundsInRoot.center
        compose.mainClock.autoAdvance = false
        handle.performTouchInput { down(center); moveBy(Offset(0f, bounds.bottom - 12f - start.y)) }
        advanceDragFrames()
        assertTrue("The bottom edge must auto-scroll the long roster", list.fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange].value() > 0f)
        if (release) {
            list.performTouchInput { up() }
            compose.mainClock.autoAdvance = true
            compose.waitForIdle()
        }
    }
    private fun continueToTopAndCancel(listTag: String) {
        val list = compose.onNodeWithTag(listTag)
        list.performTouchInput { moveTo(Offset(width / 2f, 12f)) }
        advanceDragFrames()
        assertEquals("The top edge must return to the beginning", 0f, list.fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange].value(), 1f)
        list.performTouchInput { cancel() }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
    }
    private fun advanceDragFrames() { repeat(180) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() } }
    private fun independentDatabase() = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), PontinhoDatabase::class.java, "pontinho.db").build()
    private inline fun withIndependentDatabase(block: (PontinhoDatabase) -> Unit) {
        val database = independentDatabase()
        try { block(database) } finally { database.close() }
    }
    private fun clearDatabase() { withIndependentDatabase { it.clearAllTables() } }
    private fun openSetup() { await("home_root"); compose.onAllNodesWithText(text(R.string.home_new_match))[0].performClick(); await("setup_root") }
    private fun createMatch() {
        compose.onNodeWithTag("setup_root").performScrollToNode(hasTestTag("setup_create_match"))
        compose.onNodeWithTag("setup_create_match").performClick()
    }
    private fun addSetupPlayer(name: String) {
        compose.onNodeWithTag("setup_root").performScrollToNode(hasTestTag("setup_add_player"))
        compose.onNodeWithTag("setup_add_player").performClick()
        enterName(name)
    }
    private fun enterName(name: String) {
        compose.onNodeWithTag("player_name_input").assertIsFocused().performTextReplacement(name)
        compose.onNodeWithText(text(R.string.confirm)).performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("player_name_input").fetchSemanticsNodes().isEmpty() }
    }
    private fun enterScore(name: String, points: Int, indicator: Boolean = false) {
        openScore(name, indicator)
        compose.onNodeWithTag("score_input").performTextReplacement(points.toString())
        compose.onNodeWithText(text(R.string.confirm)).performClick()
        waitForScoreDialogToClose()
    }
    private fun openScore(name: String, indicator: Boolean) {
        val action = hasContentDescription(text(R.string.match_add_score, name)) and isEnabled()
        // A restored/new destination can exist before its repository-backed controls are ready.
        compose.waitUntil(10_000) { compose.onAllNodes(action).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(action).performScrollTo().performClick()
        if (indicator) {
            compose.onAllNodes(hasTestTagPrefix("indicator_rank_"), true).assertCountEquals(13)
            compose.onAllNodes(hasTestTagPrefix("indicator_color_"), true).assertCountEquals(2)
            compose.onNodeWithTag("indicator_rank_ACE").performClick()
            compose.onNodeWithTag("indicator_color_RED").performClick()
            compose.onNodeWithText(text(R.string.confirm)).performClick()
        }
        await("score_input")
        compose.onNodeWithTag("score_input").assertIsFocused()
    }
    private fun waitForScoreDialogToClose() { compose.waitUntil(10_000) { compose.onAllNodesWithTag("score_input").fetchSemanticsNodes().isEmpty() } }
    private fun await(tag: String) { compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() } }
    private fun text(resource: Int, vararg args: Any) = compose.activity.getString(resource, *args)
    private fun hasTestTagPrefix(prefix: String) = SemanticsMatcher("tag starts with $prefix") {
        it.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.TestTag) { "" }.startsWith(prefix)
    }
    private fun capture(name: String) {
        compose.mainClock.advanceTimeBy(100)
        compose.waitForIdle()
        val fd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("screencap -p /sdcard/Download/task8-$name.png")
        FileInputStream(fd.fileDescriptor).use { it.readBytes() }; fd.close()
    }
    private fun shell(command: String) {
        val fd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        FileInputStream(fd.fileDescriptor).use { it.readBytes() }; fd.close()
    }
}
