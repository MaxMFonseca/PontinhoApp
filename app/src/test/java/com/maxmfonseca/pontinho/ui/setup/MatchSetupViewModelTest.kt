package com.maxmfonseca.pontinho.ui.setup

import androidx.lifecycle.SavedStateHandle
import com.maxmfonseca.pontinho.domain.model.MatchId
import com.maxmfonseca.pontinho.domain.model.PlayerId
import com.maxmfonseca.pontinho.domain.repository.DaySummary
import com.maxmfonseca.pontinho.ui.test.FakeMatchRepository
import com.maxmfonseca.pontinho.ui.test.FakePlayerRepository
import com.maxmfonseca.pontinho.ui.test.matchSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MatchSetupViewModelTest {
    @Test
    fun setupStartsEmptyAndCreateRequiresTwoPlayers() {
        val viewModel = viewModel()

        assertTrue(viewModel.uiState.value.players.isEmpty())
        assertFalse(viewModel.uiState.value.canCreate)
        assertEquals(2, viewModel.uiState.value.minimumPlayers)
    }

    @Test
    fun addAndEditTrimNamesWhileBlankInputStaysInFocusedDialog() {
        val viewModel = viewModel()

        viewModel.openAddPlayer()
        viewModel.updateDialogName("  Ana  ")
        viewModel.confirmPlayerName()
        val player = viewModel.uiState.value.players.single()
        assertEquals("Ana", player.displayName)

        viewModel.openEditPlayer(player.draftId)
        viewModel.updateDialogName("   ")
        viewModel.confirmPlayerName()

        assertEquals("   ", viewModel.uiState.value.nameDialog?.value)
        assertTrue(viewModel.uiState.value.nameDialog?.showBlankError == true)
        assertEquals("Ana", viewModel.uiState.value.players.single().displayName)
    }

    @Test
    fun equalNamesRemainDistinctPlayersAndOrderCanChange() {
        val viewModel = viewModel()
        add(viewModel, "Ana")
        add(viewModel, "Ana")
        val original = viewModel.uiState.value.players
        assertNotEquals(original[0].draftId, original[1].draftId)

        viewModel.movePlayer(original[1].draftId, -1)

        assertEquals(original[1].draftId, viewModel.uiState.value.players[0].draftId)
        viewModel.removePlayer(original[0].draftId)
        assertEquals(listOf(original[1].draftId), viewModel.uiState.value.players.map { it.draftId })
    }

    @Test
    fun draftRosterRestoresWithoutPersistingPlayersOrMatches() {
        val savedState = SavedStateHandle()
        val players = FakePlayerRepository()
        val matches = FakeMatchRepository()
        val first = MatchSetupViewModel(savedState, matches, Dispatchers.Unconfined)
        add(first, "Ana")
        add(first, "Bruno")
        first.movePlayer(first.uiState.value.players[1].draftId, -1)

        val restored = MatchSetupViewModel(savedState, matches, Dispatchers.Unconfined)

        assertEquals(listOf("Bruno", "Ana"), restored.uiState.value.players.map { it.displayName })
        assertTrue(players.upserts.isEmpty())
        assertTrue(matches.createdCommands.isEmpty())
    }

    @Test
    fun lastMatchReplacesEmptyDraftImmediatelyAndNonemptyDraftAfterConfirmation() {
        val latest = matchSnapshot(
            id = "latest",
            date = LocalDate.parse("2026-10-14"),
            lastPlayedEpochSecond = 20,
            players = listOf("p1" to "Ana", "p2" to "Bruno"),
        )
        val older = matchSnapshot(
            id = "older",
            date = LocalDate.parse("2026-10-13"),
            lastPlayedEpochSecond = 10,
            players = listOf("p3" to "Carla", "p4" to "Diego"),
        )
        val matches = FakeMatchRepository(
            MutableStateFlow(
                listOf(
                    DaySummary(older.localDate, listOf(older)),
                    DaySummary(latest.localDate, listOf(latest)),
                ),
            ),
        )
        val viewModel = viewModel(matches = matches)

        viewModel.requestUseLastMatch()
        assertEquals(listOf("Ana", "Bruno"), viewModel.uiState.value.players.map { it.displayName })
        add(viewModel, "Eva")
        viewModel.requestUseLastMatch()

        assertTrue(viewModel.uiState.value.showReplaceConfirmation)
        assertEquals(listOf("Ana", "Bruno", "Eva"), viewModel.uiState.value.players.map { it.displayName })
        viewModel.confirmUseLastMatch()
        assertEquals(listOf("Ana", "Bruno"), viewModel.uiState.value.players.map { it.displayName })
    }

    @Test
    fun reuseIsDisabledWithExplanationWhenNoPreviousMatchExists() {
        val viewModel = viewModel()

        assertFalse(viewModel.uiState.value.canUseLastMatch)
        assertTrue(viewModel.uiState.value.showNoPreviousMatchExplanation)
    }

    @Test
    fun cancelPersistsNothing() {
        val players = FakePlayerRepository()
        val matches = FakeMatchRepository()
        val viewModel = viewModel(players, matches)
        add(viewModel, "Ana")
        add(viewModel, "Bruno")

        viewModel.cancel()

        assertTrue(players.upserts.isEmpty())
        assertTrue(matches.createdCommands.isEmpty())
    }

    @Test
    fun createPersistsTrimmedRosterInOrderAndNavigatesOnce() = runBlocking {
        val players = FakePlayerRepository()
        val matches = FakeMatchRepository(createdMatchId = MatchId("match-7"))
        val viewModel = viewModel(players, matches)
        add(viewModel, " Ana ")
        add(viewModel, "Ana")

        viewModel.createMatch()
        viewModel.createMatch()

        assertTrue(players.upserts.isEmpty())
        assertEquals(listOf("Ana", "Ana"), matches.createdCommands.single().players.map { it.displayName })
        assertNotEquals(
            matches.createdCommands.single().players[0].draftId,
            matches.createdCommands.single().players[1].draftId,
        )
        assertEquals(MatchSetupEvent.OpenMatch(MatchId("match-7")), viewModel.events.first())
    }

    @Test
    fun reconstructionBeforeCallRetriesThePersistedToken() = runTest {
        val saved = savedDraft(token = "token-before")
        val matches = FakeMatchRepository(createdMatchId = MatchId("restored"))
        val viewModel = MatchSetupViewModel(saved, matches, StandardTestDispatcher(testScheduler))

        advanceUntilIdle()

        assertEquals("token-before", matches.createdCommands.single().creationToken)
        assertEquals(MatchSetupEvent.OpenMatch(MatchId("restored")), viewModel.events.first())
    }

    @Test
    fun reconstructionAtCommitBeforeEventRetriesIdempotentlyThenPersistsCommit() = runTest {
        val saved = savedDraft(token = "committed-in-db")
        val matches = FakeMatchRepository(createBlock = { MatchId("same-match") })
        val viewModel = MatchSetupViewModel(saved, matches, StandardTestDispatcher(testScheduler))

        advanceUntilIdle()

        assertEquals("same-match", saved.get<String>(MatchSetupViewModel.COMMITTED_MATCH_ID))
        assertEquals("committed-in-db", matches.createdCommands.single().creationToken)
        assertEquals(MatchSetupEvent.OpenMatch(MatchId("same-match")), viewModel.events.first())
    }

    @Test
    fun reconstructionAfterCommitNavigatesWithoutAnotherCreate() = runTest {
        val saved = savedDraft(token = "token-after").apply {
            this[MatchSetupViewModel.COMMITTED_MATCH_ID] = "already-created"
        }
        val matches = FakeMatchRepository()
        val viewModel = MatchSetupViewModel(saved, matches, StandardTestDispatcher(testScheduler))

        advanceUntilIdle()

        assertTrue(matches.createdCommands.isEmpty())
        assertEquals(MatchSetupEvent.OpenMatch(MatchId("already-created")), viewModel.events.first())
        viewModel.navigationHandled(MatchId("already-created"))
        assertNull(saved.get<String>(MatchSetupViewModel.CREATION_TOKEN))
        assertNull(saved.get<String>(MatchSetupViewModel.COMMITTED_MATCH_ID))
    }

    @Test
    fun concurrentHomeEmissionAndDoubleCreatePreserveDraftLatestRosterAndSingleCommand() = runTest {
        val gate = CompletableDeferred<Unit>()
        val matches = FakeMatchRepository(createBlock = { gate.await(); MatchId("one") })
        val dispatcher = StandardTestDispatcher(testScheduler)
        val viewModel = MatchSetupViewModel(SavedStateHandle(), matches, dispatcher)
        add(viewModel, "Ana")
        add(viewModel, "Bruno")
        val draftIds = viewModel.uiState.value.players.map { it.draftId }
        val latest = matchSnapshot("latest", LocalDate.parse("2026-10-14"), 10, listOf("p1" to "Carla", "p2" to "Diego"))

        matches.home.value = listOf(DaySummary(latest.localDate, listOf(latest)))
        viewModel.createMatch()
        viewModel.createMatch()
        runCurrent()

        assertEquals(draftIds, viewModel.uiState.value.players.map { it.draftId })
        assertEquals(listOf("Carla", "Diego"), viewModel.uiState.value.latestMatchPlayers.map { it.displayName })
        assertTrue(viewModel.uiState.value.isCreating)
        assertEquals(1, matches.createdCommands.size)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, matches.createdCommands.size)
    }

    private fun savedDraft(token: String) = SavedStateHandle(mapOf(
        MatchSetupViewModel.DRAFT_IDS to arrayListOf("draft-a", "draft-b"),
        MatchSetupViewModel.PLAYER_IDS to arrayListOf("", ""),
        MatchSetupViewModel.PLAYER_NAMES to arrayListOf("Ana", "Bruno"),
        MatchSetupViewModel.CREATION_TOKEN to token,
    ))

    private fun viewModel(
        players: FakePlayerRepository = FakePlayerRepository(),
        matches: FakeMatchRepository = FakeMatchRepository(),
    ) = MatchSetupViewModel(SavedStateHandle(), matches, Dispatchers.Unconfined)

    private fun add(viewModel: MatchSetupViewModel, name: String) {
        viewModel.openAddPlayer()
        viewModel.updateDialogName(name)
        viewModel.confirmPlayerName()
    }
}
