package com.maxmfonseca.pontinho.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextReplacement
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.maxmfonseca.pontinho.MainActivity
import com.maxmfonseca.pontinho.PontinhoApplication
import com.maxmfonseca.pontinho.R
import com.maxmfonseca.pontinho.data.local.PontinhoDatabase
import com.maxmfonseca.pontinho.data.repository.RoomMatchRepository
import com.maxmfonseca.pontinho.domain.model.MatchParticipant
import com.maxmfonseca.pontinho.domain.repository.MatchRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ManualMatchJourneyTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val repository: MatchRepository
        get() = (compose.activity.application as PontinhoApplication).container.matchRepository

    @Test fun completeOfflineJourneyPersistsCorrectsFinishesAndReopensHistory() = runBlocking {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("home_root").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText(text(R.string.home_new_match))[0].performClick()
        compose.onNodeWithTag("setup_root").assertIsDisplayed()

        listOf("Ana", "Bruno", "Carla", "Diego").forEach(::addSetupPlayer)
        moveSetupPlayer("Ana", R.string.setup_move_down)
        moveSetupPlayer("Ana", R.string.setup_move_up)
        compose.onNodeWithText(text(R.string.setup_create_match)).performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("match_root").fetchSemanticsNodes().isNotEmpty() }

        val created = repository.observeHome().first().flatMap { it.matches }
            .maxBy { it.createdAt }
        val matchId = created.id
        suspend fun participant(name: String): MatchParticipant =
            repository.observeMatch(matchId).first()!!.participants.single { it.displayName == name }

        val ana = participant("Ana")
        val bruno = participant("Bruno")
        val carla = participant("Carla")
        val diego = participant("Diego")
        assertEquals(listOf("Ana", "Bruno", "Carla", "Diego"),
            repository.observeMatch(matchId).first()!!.participants.map { it.displayName })

        scoreRound(mapOf("Bruno" to 20, "Carla" to 25, "Diego" to 30))
        scoreRound(mapOf("Ana" to 12, "Carla" to 20, "Diego" to 25))
        scoreRound(mapOf("Ana" to 15, "Bruno" to 22, "Diego" to 36))
        scoreRound(mapOf("Ana" to 21, "Bruno" to 35, "Carla" to 37))

        var snapshot = repository.observeMatch(matchId).first()!!
        assertEquals(listOf(48, 77, 82, 91), listOf(ana, bruno, carla, diego).map { snapshot.totals.getValue(it.id) })
        assertEquals(listOf(ana.id, bruno.id, carla.id, diego.id),
            snapshot.rounds.map { it.draft.winner?.participantId })

        compose.onNodeWithTag("match_players").performScrollToIndex(4)
        compose.onNodeWithTag("match_add_player").performClick()
        compose.onNodeWithTag("player_name_input").assertIsFocused().performTextReplacement("Eva")
        compose.onNodeWithText(text(R.string.confirm)).performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Eva").fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithTag("player_name_input").fetchSemanticsNodes().isEmpty()
        }
        val eva = participant("Eva")
        snapshot = repository.observeMatch(matchId).first()!!
        assertEquals(0, snapshot.totals.getValue(eva.id))
        assertEquals(5, eva.eligibleFromRoundOrdinal)

        openScore("Ana", selectIndicator = true)
        compose.onNodeWithText(text(R.string.went_out)).performClick()
        waitForScoreDialogToClose()
        enterScore("Bruno", 0)
        enterScore("Diego", 0)

        snapshot = repository.observeMatch(matchId).first()!!
        assertEquals(5, snapshot.openRound?.ordinal)
        assertEquals(ana.id, snapshot.openRound?.draft?.designatedWinner)
        assertEquals(0, snapshot.openRound?.draft?.entries?.get(bruno.id)?.points)
        assertEquals(0, snapshot.openRound?.draft?.entries?.get(diego.id)?.points)

        val carlaRow = hasAnyAncestor(hasTestTag("match_player_${carla.id.value}"))
        compose.onNode(hasTestTag("round_score_4") and carlaRow, useUnmergedTree = true)
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("score_input").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("score_input").assertTextContains("37").performTextReplacement("36")
        compose.onNodeWithText(text(R.string.confirm)).performClick()
        waitForScoreDialogToClose()
        snapshot = repository.observeMatch(matchId).first()!!
        assertEquals(36, snapshot.rounds.single { it.ordinal == 4 }.draft.entries.getValue(carla.id).points)
        assertEquals(5, snapshot.openRound?.ordinal)

        compose.activityRule.scenario.recreate()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("match_root").fetchSemanticsNodes().isNotEmpty() }
        snapshot = repository.observeMatch(matchId).first()!!
        assertEquals(36, snapshot.rounds.single { it.ordinal == 4 }.draft.entries.getValue(carla.id).points)
        assertEquals(5, snapshot.openRound?.ordinal)

        enterScore("Carla", 31)
        enterScore("Eva", 50)
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("results_root").fetchSemanticsNodes().isNotEmpty() }

        snapshot = repository.observeMatch(matchId).first()!!
        assertTrue(snapshot.isFinished)
        assertEquals(listOf(48, 77, 112, 91, 50), listOf(ana, bruno, carla, diego, eva).map { snapshot.totals.getValue(it.id) })
        assertEquals(setOf(ana.id), snapshot.outcome?.winners)
        assertEquals(setOf(carla.id), snapshot.outcome?.highestScorers)
        compose.onNodeWithTag("result_winner_${ana.playerId.value}").assertIsDisplayed()
        compose.onNodeWithTag("result_highest_${carla.playerId.value}").assertIsDisplayed()
        compose.onAllNodesWithTag("result_winner_${carla.playerId.value}").assertCountEquals(0)

        compose.activityRule.scenario.recreate()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("results_root").fetchSemanticsNodes().isNotEmpty() }

        val independentDatabase = Room.databaseBuilder(
            ApplicationProvider.getApplicationContext(), PontinhoDatabase::class.java, "pontinho.db",
        ).build()
        try {
            val independentlyRestored = RoomMatchRepository(independentDatabase).observeMatch(matchId).first()
            assertNotNull(independentlyRestored)
            assertTrue(independentlyRestored!!.isFinished)
            assertEquals(112, independentlyRestored.totals.getValue(carla.id))
            assertTrue(independentlyRestored.hasPendingResults)
        } finally {
            independentDatabase.close()
        }

        compose.onNodeWithText(text(R.string.results_back_history)).performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("history_root").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("history_match_0").assertIsDisplayed()
        compose.onNodeWithTag("history_winner_${ana.playerId.value}", useUnmergedTree = true)
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("history_highest_${carla.playerId.value}", useUnmergedTree = true)
            .performScrollTo().assertIsDisplayed()
        assertFalse(repository.observeMatch(matchId).first()!!.hasPendingResults)
    }

    private fun addSetupPlayer(name: String) {
        compose.onNodeWithText(text(R.string.setup_add_player)).performScrollTo().performClick()
        compose.onNodeWithTag("player_name_input").assertIsFocused().performTextReplacement(name)
        compose.onNodeWithText(text(R.string.confirm)).performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText(name).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun moveSetupPlayer(name: String, labelResource: Int) {
        val label = text(labelResource, name)
        val node = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions), useUnmergedTree = true)
            .fetchSemanticsNodes().single { semantic ->
                semantic.config[SemanticsActions.CustomActions].any { it.label == label }
            }
        assertTrue(node.config[SemanticsActions.CustomActions].single { it.label == label }.action())
        compose.waitForIdle()
    }

    private fun scoreRound(scores: Map<String, Int>) {
        scores.entries.forEachIndexed { index, (name, score) ->
            enterScore(name, score, selectIndicator = index == 0)
        }
    }

    private fun enterScore(name: String, points: Int, selectIndicator: Boolean = false) {
        openScore(name, selectIndicator)
        compose.onNodeWithTag("score_input").assertIsFocused().performTextReplacement(points.toString())
        compose.onNodeWithText(text(R.string.confirm)).performClick()
        waitForScoreDialogToClose()
    }

    private fun openScore(name: String, selectIndicator: Boolean) {
        compose.onNodeWithContentDescription(text(R.string.match_add_score, name)).performClick()
        if (selectIndicator) {
            compose.onNodeWithTag("indicator_RED_ACE").performScrollTo().performClick()
            compose.onNodeWithText(text(R.string.confirm)).performClick()
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("score_input").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("score_input").assertIsFocused()
    }

    private fun waitForScoreDialogToClose() {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("score_input").fetchSemanticsNodes().isEmpty() }
    }

    private fun text(resource: Int, vararg args: Any): String = compose.activity.getString(resource, *args)
}
