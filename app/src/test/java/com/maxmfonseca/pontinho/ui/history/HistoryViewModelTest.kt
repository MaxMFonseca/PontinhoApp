package com.maxmfonseca.pontinho.ui.history

import com.maxmfonseca.pontinho.domain.model.MatchId
import com.maxmfonseca.pontinho.domain.model.PlayerId
import com.maxmfonseca.pontinho.domain.model.PlayerNameKey
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
    @Test fun normalizedNameProjectionKeepsSnapshotNamesAndActiveTotalsSeparateAfterRefresh() = runTest {
        val date = LocalDate.parse("2026-10-14")
        val day = MutableStateFlow(DayHistory(date,
            matches = listOf(
                HistoryMatch(MatchId("active"), 300L, false, listOf(HistoryPlayer(PlayerId("shared"), "Julia2", 999, false, false))),
                HistoryMatch(MatchId("new"), 200L, true, listOf(HistoryPlayer(PlayerId("shared"), "Julia2", 110, false, true))),
                HistoryMatch(MatchId("old"), 100L, true, listOf(HistoryPlayer(PlayerId("shared"), "Julia", 33, true, false))),
            ),
            wins = listOf(DailyStanding(PlayerNameKey("julia"), "Julia", 1), DailyStanding(PlayerNameKey("max"), "Max", 1), DailyStanding(PlayerNameKey("julia2"), "Julia2", 0)),
            penalties = listOf(DailyStanding(PlayerNameKey("julia2"), "Julia2", 110), DailyStanding(PlayerNameKey("max"), "Max", 110), DailyStanding(PlayerNameKey("julia"), "Julia", 33)),
        ))
        val vm = HistoryViewModel(date, HistoryRepository(day), StandardTestDispatcher(testScheduler))
        runCurrent()
        vm.toggleSummary()
        day.value = day.value.copy(matches = day.value.matches.map { match ->
            if (match.isFinished) match else match.copy(players = match.players.map { it.copy(total = 1000) })
        })
        runCurrent()
        val state = vm.uiState.value
        assertEquals(listOf("julia" to 1, "max" to 1, "julia2" to 0), state.wins.map { it.playerKey.value to it.value })
        assertEquals(listOf("julia2" to 110, "max" to 110, "julia" to 33), state.penalties.map { it.playerKey.value to it.value })
        assertEquals(listOf("Julia2", "Julia2", "Julia"), state.matches.map { it.players.single().displayName })
        assertEquals(1000, state.matches.first().players.single().total)
        assertTrue(state.summaryExpanded)
        assertFalse(state.isLoading)
    }

    @Test fun projectionIsPreservedAndExpansionIsTheOnlyLocalHistoryState() = runTest {
        val date = LocalDate.parse("2026-10-14")
        val projection = DayHistory(
            localDate = date,
            matches = listOf(
                HistoryMatch(MatchId("new"), 200L, true, listOf(HistoryPlayer(PlayerId("a"), "Ana", 48, true, false))),
                HistoryMatch(MatchId("old"), 100L, false, listOf(HistoryPlayer(PlayerId("b"), "Bruno", 12, false, false))),
            ),
            wins = listOf(DailyStanding(PlayerNameKey("ana"), "Ana", 2)),
            penalties = listOf(DailyStanding(PlayerNameKey("bruno"), "Bruno", 112)),
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
