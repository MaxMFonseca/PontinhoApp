package com.maxmfonseca.pontinho.ui.match

import com.maxmfonseca.pontinho.domain.model.*
import com.maxmfonseca.pontinho.domain.repository.*
import com.maxmfonseca.pontinho.domain.rules.RoundEngine
import com.maxmfonseca.pontinho.domain.rules.RoundTransition
import com.maxmfonseca.pontinho.ui.components.RoundStatusKind
import com.maxmfonseca.pontinho.ui.test.matchSnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MatchViewModelTest {
    @Test fun rosterGuardTracksOpenPausedAndFinishedState() = runTest {
        val repo = ScoringRepository()
        val initial = repo.snapshot.value!!
        val vm = MatchViewModel(initial.id, repo, StandardTestDispatcher(testScheduler))
        runCurrent()
        assertTrue(vm.uiState.value.canManagePlayers)
        vm.openAddPlayer()
        assertEquals("", vm.uiState.value.playerName)
        vm.dismissDialog()
        for (status in listOf(RoundStatus.OPEN, RoundStatus.PAUSED)) {
            repo.snapshot.value = initial.copy(rounds = listOf(repo.round(1, RoundDraft(repo.ids)).copy(status = status)))
            runCurrent()
            assertFalse(vm.uiState.value.canManagePlayers)
            vm.openAddPlayer(); vm.movePlayer(repo.ids[0], 1)
            assertNull(vm.uiState.value.playerName)
        }
        repo.snapshot.value = initial.copy(isFinished = true)
        runCurrent()
        assertFalse(vm.uiState.value.canManagePlayers)
    }

    @Test fun correctionsPreloadExplicitScoreAndProtectAutomaticZero() = runTest {
        val repo = ScoringRepository()
        val vm = MatchViewModel(repo.snapshot.value!!.id, repo, StandardTestDispatcher(testScheduler))
        runCurrent(); prepare(vm, repo.ids[0]); advanceUntilIdle()
        vm.updateScore("101"); vm.confirmScore(); advanceUntilIdle()
        vm.addScore(repo.ids[1]); vm.updateScore("2"); vm.confirmScore(); advanceUntilIdle()
        vm.editScore(repo.ids[0], 1)
        assertEquals("101", vm.uiState.value.scoreDialog!!.value)
        assertTrue(vm.uiState.value.scoreDialog!!.isCorrection)
        assertFalse(vm.uiState.value.scoreDialog!!.readOnly)
        vm.updateScore("5"); vm.confirmScore(); advanceUntilIdle()
        assertFalse(vm.uiState.value.isFinished)
        assertEquals(5, vm.uiState.value.players[0].total)
        assertEquals(RoundEntryOrigin.EXPLICIT, repo.snapshot.value!!.rounds[0].draft.entries[repo.ids[0]]!!.origin)
        vm.editScore(repo.ids[2], 1)
        assertTrue(vm.uiState.value.scoreDialog!!.readOnly)
        assertEquals("0", vm.uiState.value.scoreDialog!!.value)
        vm.updateScore("20"); vm.confirmScore(); vm.wentOut(); advanceUntilIdle()
        assertEquals("0", vm.uiState.value.scoreDialog!!.value)
        vm.deleteScore(); advanceUntilIdle()
        assertNull(repo.snapshot.value!!.openRound!!.draft.winner)
        assertNull(repo.snapshot.value!!.openRound!!.draft.entries[repo.ids[2]])
        assertFalse(vm.uiState.value.canManagePlayers)
        assertEquals(listOf(0, 0, 0), vm.uiState.value.players.map { it.total })
        vm.addScore(repo.ids[2]); vm.wentOut(); advanceUntilIdle()
        assertEquals(RoundStatus.COMPLETE, repo.snapshot.value!!.rounds[0].status)
    }

    @Test fun pickerAndScoreCancellationPersistNoRound() = runTest {
        val repo = ScoringRepository()
        val vm = MatchViewModel(repo.snapshot.value!!.id, repo, StandardTestDispatcher(testScheduler))
        runCurrent()
        vm.addScore(repo.ids[0])
        assertEquals(repo.ids[0], vm.uiState.value.picker?.participantId)
        vm.dismissDialog()
        assertNull(vm.uiState.value.picker)
        assertTrue(repo.snapshot.value!!.rounds.isEmpty())
        vm.addScore(repo.ids[0])
        vm.selectIndicator(Indicator(Rank.KING, CardColor.RED))
        vm.confirmIndicator()
        advanceUntilIdle()
        assertEquals(Indicator(Rank.KING, CardColor.RED), vm.uiState.value.scoreDialog?.indicator)
        assertTrue(repo.snapshot.value!!.rounds.isEmpty())
        vm.dismissDialog()
        assertTrue(repo.snapshot.value!!.rounds.isEmpty())
    }

    @Test fun explicitZeroIsPendingUntilInferredWinnerClosesRoundAndAdvancesHand() = runTest {
        val repo = ScoringRepository()
        val vm = MatchViewModel(repo.snapshot.value!!.id, repo, StandardTestDispatcher(testScheduler))
        runCurrent()
        prepare(vm, repo.ids[0]); advanceUntilIdle()
        vm.updateScore("0"); vm.confirmScore(); advanceUntilIdle()
        assertEquals(1, repo.snapshot.value!!.rounds.size)
        assertEquals(RoundStatusKind.PENDING, vm.uiState.value.players[0].history[0].status)
        assertEquals(0, vm.uiState.value.players[0].history[0].score)
        assertFalse(vm.uiState.value.players[0].canAddScore)
        vm.addScore(repo.ids[1])
        assertNull(vm.uiState.value.picker)
        assertNotNull(vm.uiState.value.scoreDialog)
        vm.updateScore("12"); vm.confirmScore(); advanceUntilIdle()
        val state = vm.uiState.value
        assertEquals(RoundStatusKind.LOSS, state.players[0].history[0].status)
        assertEquals(0, state.players[0].history[0].score)
        assertEquals(RoundStatusKind.WINNER, state.players[2].history[0].status)
        assertEquals(0, state.players[2].history[0].score)
        assertEquals(listOf(false, true, false), state.players.map { it.isDealer })
        assertEquals(2, state.roundNumber)
        assertEquals(listOf(0, 12, 0), state.players.map { it.total })
    }

    @Test fun bateuMarksOnlyOneWinnerWithoutClosingOrAdvancingUntilOtherScoresExist() = runTest {
        val repo = ScoringRepository()
        val vm = MatchViewModel(repo.snapshot.value!!.id, repo, StandardTestDispatcher(testScheduler))
        runCurrent(); prepare(vm, repo.ids[0]); advanceUntilIdle()
        vm.wentOut(); advanceUntilIdle()
        assertFalse(repo.snapshot.value!!.rounds.single().draft.isComplete)
        assertEquals(RoundStatusKind.WINNER, vm.uiState.value.players[0].history[0].status)
        assertEquals(RoundStatusKind.EMPTY, vm.uiState.value.players[1].history[0].status)
        assertEquals(repo.ids[0], vm.uiState.value.players.single { it.isDealer }.id)
        vm.addScore(repo.ids[1])
        assertFalse(vm.uiState.value.scoreDialog!!.wentOutEnabled)
        vm.wentOut(); advanceUntilIdle()
        assertEquals(repo.ids[0], repo.snapshot.value!!.openRound!!.draft.designatedWinner)
        vm.updateScore("2"); vm.confirmScore(); advanceUntilIdle()
        vm.addScore(repo.ids[2]); vm.updateScore("3"); vm.confirmScore(); advanceUntilIdle()
        assertTrue(repo.snapshot.value!!.rounds.single().draft.isComplete)
        assertEquals(0, vm.uiState.value.players[0].history[0].score)
    }

    @Test fun invalidInputIsRetainedAndDuplicateTapCommitsOnce() = runTest {
        val gate = CompletableDeferred<Unit>()
        val repo = ScoringRepository(gate = gate)
        val vm = MatchViewModel(repo.snapshot.value!!.id, repo, StandardTestDispatcher(testScheduler))
        runCurrent(); prepare(vm, repo.ids[0]); advanceUntilIdle()
        for (invalid in listOf("", "-1", "2147483648", "12x")) {
            vm.updateScore(invalid); vm.confirmScore(); runCurrent()
            assertEquals(invalid, vm.uiState.value.scoreDialog!!.value)
            assertFalse(vm.uiState.value.scoreDialog!!.canConfirm)
            assertTrue(repo.snapshot.value!!.rounds.isEmpty())
        }
        vm.updateScore("2147483647")
        assertTrue(vm.uiState.value.scoreDialog!!.canConfirm)
        vm.updateScore("4"); vm.confirmScore(); vm.confirmScore(); runCurrent()
        assertTrue(vm.uiState.value.isSaving)
        gate.complete(Unit); advanceUntilIdle()
        assertEquals(1, repo.submissions)
        assertEquals(4, vm.uiState.value.players[0].history[0].score)
        assertNull(vm.uiState.value.scoreDialog)
    }

    @Test fun restoredOpenRoundSkipsPickerAndPausedRoundIsNotCurrent() = runTest {
        val repo = ScoringRepository()
        val initial = repo.snapshot.value!!
        val open = repo.round(1, RoundDraft(repo.ids, mapOf(repo.ids[0] to RoundEntry(0, RoundEntryOrigin.EXPLICIT))))
        val paused = repo.round(2, RoundDraft(repo.ids)).copy(status = RoundStatus.PAUSED)
        repo.snapshot.value = initial.copy(rounds = listOf(open.copy(isCorrection = true), paused))
        val vm = MatchViewModel(initial.id, repo, StandardTestDispatcher(testScheduler))
        runCurrent()
        assertEquals(1, vm.uiState.value.roundNumber)
        assertFalse(vm.uiState.value.players[0].canAddScore)
        vm.addScore(repo.ids[1])
        assertNull(vm.uiState.value.picker)
        assertEquals(open.id, vm.uiState.value.scoreDialog!!.roundId)
        assertEquals(open.indicator, vm.uiState.value.scoreDialog!!.indicator)
    }

    @Test fun finishedSnapshotHidesScoringAndEmitsResultsOnlyOncePerRevision() = runTest {
        val repo = ScoringRepository()
        val vm = MatchViewModel(repo.snapshot.value!!.id, repo, StandardTestDispatcher(testScheduler))
        runCurrent(); prepare(vm, repo.ids[0]); advanceUntilIdle()
        vm.updateScore("101"); vm.confirmScore(); advanceUntilIdle()
        assertFalse(vm.uiState.value.isFinished)
        vm.addScore(repo.ids[1]); vm.updateScore("1"); vm.confirmScore(); advanceUntilIdle()
        assertTrue(vm.uiState.value.isFinished)
        assertTrue(vm.uiState.value.players.none { it.canAddScore })
        assertEquals(MatchEvent.OpenResults(repo.snapshot.value!!.id), vm.events.first())
        repo.snapshot.value = repo.snapshot.value!!.copy(lastPlayedAt = Instant.ofEpochSecond(50))
        runCurrent()
        var additional = 0
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { additional++ } }
        runCurrent()
        assertEquals(0, additional)
        vm.addScore(repo.ids[0]); assertNull(vm.uiState.value.picker)
        vm.viewResults(); runCurrent(); assertEquals(1, additional)
    }

    @Test fun lateParticipantHasNonparticipationAndPausedOnlySnapshotCannotStartRound() = runTest {
        val repo = ScoringRepository()
        val initial = repo.snapshot.value!!
        repo.snapshot.value = initial.copy(
            participants = initial.participants.mapIndexed { i, p -> if (i == 2) p.copy(eligibleFromRoundOrdinal = 2) else p },
            rounds = listOf(repo.round(1, RoundDraft(repo.ids.take(2))).copy(status = RoundStatus.PAUSED)),
        )
        val vm = MatchViewModel(initial.id, repo, StandardTestDispatcher(testScheduler))
        runCurrent()
        assertEquals(RoundStatusKind.NOT_PARTICIPATING, vm.uiState.value.players[2].history[0].status)
        assertTrue(vm.uiState.value.players.none { it.canAddScore })
        vm.addScore(repo.ids[0]); assertNull(vm.uiState.value.picker)
    }

    private fun prepare(vm: MatchViewModel, id: ParticipantId) {
        vm.addScore(id)
        vm.selectIndicator(Indicator(Rank.ACE, CardColor.BLACK))
        vm.confirmIndicator()
    }
}

/** In-memory storage boundary; real RoundEngine handles all transition rules. */
private class ScoringRepository(private val gate: CompletableDeferred<Unit>? = null) : MatchRepository {
    val snapshot = MutableStateFlow<MatchSnapshot?>(matchSnapshot("match", LocalDate.of(2026, 10, 14), 2,
        listOf("a" to "Ana", "b" to "Bruno", "c" to "Carla")))
    val ids get() = snapshot.value!!.participants.map { it.id }
    var submissions = 0
    override fun observeMatch(matchId: MatchId) = snapshot
    override fun observeHome() = MutableStateFlow(emptyList<DaySummary>())
    override fun observeDay(localDate: LocalDate) = MutableStateFlow(DayHistory(localDate, emptyList(), emptyList(), emptyList()))
    override suspend fun chooseIndicator(command: ChooseIndicator): PreparedRound {
        check(snapshot.value!!.rounds.none { it.status != RoundStatus.COMPLETE })
        val ordinal = snapshot.value!!.rounds.size + 1
        return PreparedRound(RoundId("r$ordinal"), command.matchId, ordinal, command.indicator)
    }
    fun round(ordinal: Int, draft: RoundDraft) = RoundSnapshot(RoundId("r$ordinal"), ordinal,
        Indicator(Rank.ACE, CardColor.BLACK), ids[0], draft, Instant.EPOCH, null, RoundStatus.OPEN, false)
    private fun writable(id: RoundId, prepared: PreparedRound?): RoundSnapshot =
        snapshot.value!!.rounds.singleOrNull { it.id == id } ?: requireNotNull(prepared).let {
            round(it.ordinal, RoundDraft(ids)).copy(id = it.id, indicator = it.indicator,
                dealerParticipantId = snapshot.value!!.dealerParticipantId)
        }
    override suspend fun submitScore(command: SubmitScore) {
        gate?.await()
        val r = writable(command.roundId, command.preparedRound)
        val transition = RoundEngine.submitScore(r.draft, command.participantId, command.points)
        check(transition is RoundTransition.Applied)
        submissions++
        persist(r, transition.state)
    }
    override suspend fun designateWinner(command: DesignateWinner) {
        val r = writable(command.roundId, command.preparedRound)
        persist(r, RoundEngine.designateWinner(r.draft, command.participantId))
    }
    private fun persist(r: RoundSnapshot, draft: RoundDraft) {
        val before = snapshot.value!!
        val completed = draft.isComplete
        val next = r.copy(draft = draft, status = if (completed) RoundStatus.COMPLETE else RoundStatus.OPEN,
            completedAt = if (completed) Instant.EPOCH else null)
        val rounds = (before.rounds.filterNot { it.id == r.id } + next).sortedBy { it.ordinal }
        val totals = ids.associateWith { id -> rounds.filter { it.status == RoundStatus.COMPLETE }.sumOf { it.draft.entries[id]?.points ?: 0 } }
        val finished = totals.values.any { it > 100 }
        snapshot.value = before.copy(rounds = rounds, totals = totals,
            dealerParticipantId = if (completed) ids[(ids.indexOf(r.dealerParticipantId) + 1) % ids.size] else before.dealerParticipantId,
            isFinished = finished, finishedAt = if (finished) Instant.EPOCH else null,
            resultRevision = if (finished) 1 else 0)
    }
    override suspend fun createMatch(command: CreateMatch): MatchId = error("unused")
    override suspend fun addParticipant(command: AddParticipant) = error("unused")
    override suspend fun addPlayerToMatch(command: AddPlayerToMatch): ParticipantId = error("unused")
    override suspend fun reorderParticipants(command: ReorderParticipants) = error("unused")
    override suspend fun removeRoundEntry(command: RemoveRoundEntry) {
        val r = snapshot.value!!.rounds.single { it.id == command.roundId }
        persist(r, (RoundEngine.removeEntry(r.draft, command.participantId) as RoundTransition.Applied).state)
    }
    override suspend fun updateExplicitScore(command: UpdateExplicitScore) {
        val r = snapshot.value!!.rounds.single { it.id == command.roundId }
        val old = r.draft.entries.getValue(command.participantId)
        require(old.origin == RoundEntryOrigin.EXPLICIT)
        persist(r, r.draft.copy(entries = r.draft.entries + (command.participantId to old.copy(points = command.points))))
    }
    override suspend fun acknowledgeResults(matchId: MatchId, revision: Long) = error("unused")
}
