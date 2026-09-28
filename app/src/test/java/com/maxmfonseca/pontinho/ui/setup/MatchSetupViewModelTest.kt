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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MatchSetupViewModelTest {
    @Test fun reorderPersistsOneExactPermutationAndRetainsPlayerIdentity() {
        val saved = SavedStateHandle()
        val repo = FakeMatchRepository()
        val vm = MatchSetupViewModel(saved, repo, Dispatchers.Unconfined)
        add(vm, "Ana"); add(vm, "Bruno"); add(vm, "Carla")
        val original = vm.uiState.value.players
        vm.reorderPlayers(listOf(original[1].draftId, original[2].draftId, original[0].draftId))
        assertEquals(listOf("Bruno", "Carla", "Ana"), vm.uiState.value.players.map { it.displayName })
        val restored = MatchSetupViewModel(saved, repo, Dispatchers.Unconfined)
        assertEquals(listOf(original[1], original[2], original[0]), restored.uiState.value.players)
        assertTrue(repo.createdCommands.isEmpty())
    }

    @Test fun reorderRejectsMissingExtraDuplicateAndStaleDraftIds() {
        val vm = viewModel()
        add(vm, "Ana"); add(vm, "Bruno")
        val original = vm.uiState.value.players
        val a = original[0].draftId
        val b = original[1].draftId
        listOf(emptyList(), listOf(a), listOf(a, a), listOf(a, b, "extra"), listOf(b, "stale")).forEach {
            vm.reorderPlayers(it)
            assertEquals(original, vm.uiState.value.players)
        }
    }

    @Test fun reorderDoesNotChangeRosterWhileCreateIsInFlight() = runTest {
        val gate = CompletableDeferred<Unit>()
        val repo = FakeMatchRepository(createBlock = { gate.await(); MatchId("created") })
        val vm = MatchSetupViewModel(SavedStateHandle(), repo, StandardTestDispatcher(testScheduler))
        add(vm, "Ana"); add(vm, "Bruno")
        val original = vm.uiState.value.players
        vm.createMatch()
        vm.reorderPlayers(original.reversed().map { it.draftId })
        assertEquals(original, vm.uiState.value.players)
        gate.complete(Unit)
        advanceUntilIdle()
    }

    @Test fun creationFailurePreservesEditedDraftAndAllowsRetry() = runTest {
        var fail = true
        val repo = FakeMatchRepository(createBlock = {
            if (fail) throw java.io.IOException("Storage unavailable")
            MatchId("retried")
        })
        val saved = SavedStateHandle()
        val vm = MatchSetupViewModel(saved, repo, StandardTestDispatcher(testScheduler))
        add(vm, "Julia"); add(vm, "Max")
        vm.openEditPlayer(vm.uiState.value.players[0].draftId)
        vm.updateDialogName("Julia2"); vm.confirmPlayerName()
        val draft = vm.uiState.value.players
        vm.createMatch(); advanceUntilIdle()
        assertTrue(vm.uiState.value.createError)
        assertEquals(draft, vm.uiState.value.players)
        assertEquals(listOf("Julia2", "Max"), saved.get<ArrayList<String>>(MatchSetupViewModel.PLAYER_NAMES))
        fail = false
        vm.createMatch(); advanceUntilIdle()
        assertFalse(vm.uiState.value.createError)
        assertEquals(MatchSetupEvent.OpenMatch(MatchId("retried")), vm.events.first())
    }

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
    fun distinctPlayersCanChangeOrder() {
        val viewModel = viewModel()
        add(viewModel, "Ana")
        add(viewModel, "Bruno")
        val original = viewModel.uiState.value.players
        assertNotEquals(original[0].draftId, original[1].draftId)

        viewModel.movePlayer(original[1].draftId, -1)

        assertEquals(original[1].draftId, viewModel.uiState.value.players[0].draftId)
        viewModel.removePlayer(original[0].draftId)
        assertEquals(listOf(original[1].draftId), viewModel.uiState.value.players.map { it.draftId })
    }

    @Test fun normalizedRenameClearsIdentityWhileCapitalizationRetainsIt() {
        val saved = SavedStateHandle(mapOf(
            MatchSetupViewModel.DRAFT_IDS to arrayListOf("julia", "max"),
            MatchSetupViewModel.PLAYER_IDS to arrayListOf("original-julia", "original-max"),
            MatchSetupViewModel.PLAYER_NAMES to arrayListOf("Julia", "max"),
        ))
        val repo = FakeMatchRepository()
        val vm = MatchSetupViewModel(saved, repo, Dispatchers.Unconfined)
        vm.openEditPlayer("julia"); vm.updateDialogName("Julia2"); vm.confirmPlayerName()
        assertNull(vm.uiState.value.players[0].playerId)
        assertEquals("Julia2", vm.uiState.value.players[0].displayName)
        vm.openEditPlayer("max"); vm.updateDialogName("MAX"); vm.confirmPlayerName()
        assertEquals(PlayerId("original-max"), vm.uiState.value.players[1].playerId)
        vm.createMatch()
        assertNull(repo.createdCommands.single().players[0].playerId)
        assertEquals("Julia2", repo.createdCommands.single().players[0].displayName)
    }

    @Test fun duplicateNameKeepsTypedTextAndCannotCreateOrReplaceAnotherPlayer() {
        val vm = viewModel()
        add(vm, "Max"); add(vm, " max ")
        assertEquals(" max ", vm.uiState.value.nameDialog?.value)
        assertTrue(vm.uiState.value.nameDialog!!.duplicateNameError)
        assertEquals(1, vm.uiState.value.players.size)
        assertFalse(vm.uiState.value.canCreate)
        vm.updateDialogName("Julia")
        assertFalse(vm.uiState.value.nameDialog!!.duplicateNameError)
        vm.confirmPlayerName()
        vm.openEditPlayer(vm.uiState.value.players[1].draftId)
        vm.updateDialogName("MAX"); vm.confirmPlayerName()
        assertEquals("MAX", vm.uiState.value.nameDialog?.value)
        assertTrue(vm.uiState.value.nameDialog!!.duplicateNameError)
        assertEquals(listOf("Max", "Julia"), vm.uiState.value.players.map { it.displayName })
    }

    @Test fun restoredDuplicateRosterCannotCreate() {
        val saved = SavedStateHandle(mapOf(
            MatchSetupViewModel.DRAFT_IDS to arrayListOf("a", "b"),
            MatchSetupViewModel.PLAYER_IDS to arrayListOf("", ""),
            MatchSetupViewModel.PLAYER_NAMES to arrayListOf("Max", " max "),
        ))
        val repo = FakeMatchRepository()
        val vm = MatchSetupViewModel(saved, repo, Dispatchers.Unconfined)
        assertFalse(vm.uiState.value.canCreate)
        vm.createMatch()
        assertTrue(repo.createdCommands.isEmpty())
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
        add(viewModel, " Bruno ")

        viewModel.createMatch()
        viewModel.createMatch()

        assertTrue(players.upserts.isEmpty())
        assertEquals(listOf("Ana", "Bruno"), matches.createdCommands.single().players.map { it.displayName })
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MatchSetupReorderFailureTest {
    @Test fun failedDraftWriteRestoresCommittedOrderAndAllowsRetry() {
        val saved = SavedStateHandle(mapOf(
            MatchSetupViewModel.DRAFT_IDS to arrayListOf("a", "b"),
            MatchSetupViewModel.PLAYER_IDS to arrayListOf("id-a", "id-b"),
            MatchSetupViewModel.PLAYER_NAMES to arrayListOf("Ana", "Bruno"),
        ))
        val vm = MatchSetupViewModel(saved, FakeMatchRepository(), Dispatchers.Unconfined)
        var fail = true
        val ids = saved.getLiveData<ArrayList<String>>(MatchSetupViewModel.DRAFT_IDS)
        val observer = androidx.lifecycle.Observer<ArrayList<String>> { order ->
            if (fail && order == listOf("b", "a")) {
                fail = false
                throw java.io.IOException("Draft storage unavailable")
            }
        }
        ids.observeForever(observer)
        try {
            vm.reorderPlayers(listOf("b", "a"))
            assertEquals(listOf("Ana", "Bruno"), vm.uiState.value.players.map { it.displayName })
            assertEquals(listOf("a", "b"), saved.get<ArrayList<String>>(MatchSetupViewModel.DRAFT_IDS))
            assertTrue(vm.uiState.value.reorderError)
            vm.reorderPlayers(listOf("b", "a"))
            assertEquals(listOf("Bruno", "Ana"), vm.uiState.value.players.map { it.displayName })
            assertFalse(vm.uiState.value.reorderError)
        } finally { ids.removeObserver(observer) }
    }
}
