package com.maxmfonseca.pontinho.ui.results

import com.maxmfonseca.pontinho.domain.model.*
import com.maxmfonseca.pontinho.domain.repository.*
import com.maxmfonseca.pontinho.ui.test.matchSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ResultsViewModelTest {
    @Test fun fixedFixtureRanksAscendingAndNeverCelebratesHighestPenalty() = runTest {
        val repo = ResultRepository(finishedSnapshot())
        val vm = ResultsViewModel(MatchId("result"), repo, StandardTestDispatcher(testScheduler))
        runCurrent()

        assertEquals(listOf(48, 77, 91, 112), vm.uiState.value.players.map { it.total })
        assertTrue(vm.uiState.value.players.single { it.total == 48 }.isWinner)
        assertFalse(vm.uiState.value.players.single { it.total == 112 }.isWinner)
        assertTrue(vm.uiState.value.players.single { it.total == 112 }.isHighestPenalty)
    }

    @Test fun tiedExtremesAreAllStyledAndLeavingAcknowledgesExactRevisionBeforeNavigation() = runTest {
        val original = finishedSnapshot(listOf(48, 48, 112, 112), revision = 7)
        val repo = ResultRepository(original)
        val vm = ResultsViewModel(original.id, repo, StandardTestDispatcher(testScheduler))
        runCurrent()
        assertEquals(2, vm.uiState.value.players.count { it.isWinner })
        assertEquals(2, vm.uiState.value.players.count { it.isHighestPenalty })

        vm.openHistory()
        advanceUntilIdle()
        assertEquals(listOf(original.id to 7L), repo.acknowledgements)
        assertEquals(ResultsEvent.OpenHistory(original.localDate), vm.events.first())
    }

    private fun finishedSnapshot(
        totals: List<Int> = listOf(48, 77, 91, 112),
        revision: Long = 1,
    ): MatchSnapshot {
        val base = matchSnapshot("result", LocalDate.parse("2026-10-14"), 100,
            listOf("a" to "Ana", "b" to "Bruno", "c" to "Diego", "d" to "Carla"))
        val mapped = base.participants.mapIndexed { index, participant -> participant.id to totals[index] }.toMap()
        val min = totals.min()
        val max = totals.max()
        return base.copy(
            createdAt = Instant.ofEpochMilli(100),
            totals = mapped,
            isFinished = true,
            finishedAt = Instant.ofEpochMilli(200),
            outcome = MatchOutcome(
                mapped.filterValues { it == min }.keys,
                mapped.filterValues { it == max }.keys,
                min,
                max,
            ),
            resultRevision = revision,
        )
    }
}

private class ResultRepository(snapshot: MatchSnapshot) : MatchRepository {
    override suspend fun deleteMatch(matchId: MatchId) = error("unused")
    val state = MutableStateFlow<MatchSnapshot?>(snapshot)
    val acknowledgements = mutableListOf<Pair<MatchId, Long>>()
    override fun observeMatch(matchId: MatchId) = state
    override fun observeHome() = MutableStateFlow(emptyList<DaySummary>())
    override fun observeDay(localDate: LocalDate) = MutableStateFlow(DayHistory(localDate, emptyList(), emptyList(), emptyList()))
    override suspend fun acknowledgeResults(matchId: MatchId, revision: Long) { acknowledgements += matchId to revision }
    override suspend fun createMatch(command: CreateMatch) = error("unused")
    override suspend fun addParticipant(command: AddParticipant) = error("unused")
    override suspend fun addPlayerToMatch(command: AddPlayerToMatch) = error("unused")
    override suspend fun reorderParticipants(command: ReorderParticipants) = error("unused")
    override suspend fun prepareRound(command: PrepareRound) = error("unused")
    override suspend fun updateRoundIndicator(command: UpdateRoundIndicator) = error("unused")
    override suspend fun designateWinner(command: DesignateWinner) = error("unused")
    override suspend fun submitScore(command: SubmitScore) = error("unused")
    override suspend fun removeRoundEntry(command: RemoveRoundEntry) = error("unused")
    override suspend fun updateExplicitScore(command: UpdateExplicitScore) = error("unused")
}
