package com.maxmfonseca.pontinho.ui.history

import com.maxmfonseca.pontinho.domain.model.MatchId
import com.maxmfonseca.pontinho.domain.model.PlayerId
import com.maxmfonseca.pontinho.domain.repository.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HistoryViewModelTest {
    @Test fun projectionIsPreservedAndExpansionIsTheOnlyLocalHistoryState() = runTest {
        val date = LocalDate.parse("2026-10-14")
        val projection = DayHistory(
            localDate = date,
            matches = listOf(
                HistoryMatch(MatchId("new"), 200L, true, listOf(HistoryPlayer(PlayerId("a"), "Ana", 48, true, false))),
                HistoryMatch(MatchId("old"), 100L, false, listOf(HistoryPlayer(PlayerId("b"), "Bruno", 12, false, false))),
            ),
            wins = listOf(DailyStanding(PlayerId("a"), "Ana", 2)),
            penalties = listOf(DailyStanding(PlayerId("b"), "Bruno", 112)),
        )
        val repo = HistoryRepository(MutableStateFlow(projection))
        val vm = HistoryViewModel(date, repo, StandardTestDispatcher(testScheduler))
        runCurrent()

        assertEquals(listOf("new", "old"), vm.uiState.value.matches.map { it.id.value })
        assertFalse(vm.uiState.value.summaryExpanded)
        vm.toggleSummary()
        assertTrue(vm.uiState.value.summaryExpanded)
        assertEquals(projection.wins, vm.uiState.value.wins)
        assertEquals(projection.penalties, vm.uiState.value.penalties)
    }
}

private class HistoryRepository(private val day: MutableStateFlow<DayHistory>) : MatchRepository {
    override fun observeDay(localDate: LocalDate) = day
    override fun observeHome() = MutableStateFlow(emptyList<DaySummary>())
    override fun observeMatch(matchId: MatchId) = MutableStateFlow(null)
    override suspend fun createMatch(command: CreateMatch) = error("unused")
    override suspend fun addParticipant(command: AddParticipant) = error("unused")
    override suspend fun addPlayerToMatch(command: AddPlayerToMatch) = error("unused")
    override suspend fun reorderParticipants(command: ReorderParticipants) = error("unused")
    override suspend fun chooseIndicator(command: ChooseIndicator) = error("unused")
    override suspend fun designateWinner(command: DesignateWinner) = error("unused")
    override suspend fun submitScore(command: SubmitScore) = error("unused")
    override suspend fun removeRoundEntry(command: RemoveRoundEntry) = error("unused")
    override suspend fun updateExplicitScore(command: UpdateExplicitScore) = error("unused")
    override suspend fun acknowledgeResults(matchId: MatchId, revision: Long) = error("unused")
}
