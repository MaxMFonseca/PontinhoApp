package com.maxmfonseca.pontinho.ui.history

import com.maxmfonseca.pontinho.domain.model.MatchId
import com.maxmfonseca.pontinho.domain.model.PlayerId
import com.maxmfonseca.pontinho.domain.model.PlayerNameKey
import com.maxmfonseca.pontinho.domain.repository.*
import com.maxmfonseca.pontinho.domain.statistics.LifetimePlayerStatistics
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HistoryViewModelTest {
    @Test fun deleteRequestCanBeCancelledWithoutWrite() = runTest {
        val day = MutableStateFlow(deletionDay())
        val repo = HistoryRepository(day)
        val vm = HistoryViewModel(day.value.localDate, repo, StandardTestDispatcher(testScheduler))
        runCurrent()
        vm.toggleSummary()
        vm.requestDelete(MatchId("missing"))
        assertNull(vm.uiState.value.pendingDeleteMatchId)
        vm.confirmDelete()
        vm.requestDelete(MatchId("delete"))
        assertEquals(MatchId("delete"), vm.uiState.value.pendingDeleteMatchId)
        assertEquals(day.value.matches, vm.uiState.value.matches)

        vm.cancelDelete()
        vm.confirmDelete()
        runCurrent()

        assertTrue(repo.deletions.isEmpty())
        assertNull(vm.uiState.value.pendingDeleteMatchId)
        assertFalse(vm.uiState.value.isDeleting)
        assertFalse(vm.uiState.value.deleteError)
        assertTrue(vm.uiState.value.summaryExpanded)
        assertEquals(day.value.wins, vm.uiState.value.wins)
        assertEquals(day.value.penalties, vm.uiState.value.penalties)
    }

    @Test fun deleteConfirmRunsOnceAndWaitsForProjection() = runTest {
        val day = MutableStateFlow(deletionDay())
        val commit = CompletableDeferred<Unit>()
        val repo = HistoryRepository(day) { commit.await() }
        val vm = HistoryViewModel(day.value.localDate, repo, StandardTestDispatcher(testScheduler))
        runCurrent()
        vm.toggleSummary()
        vm.requestDelete(MatchId("delete"))
        vm.confirmDelete()
        vm.confirmDelete()
        vm.cancelDelete()
        vm.requestDelete(MatchId("keep"))
        assertTrue(vm.uiState.value.isDeleting)
        runCurrent()
        assertEquals(listOf(MatchId("delete")), repo.deletions)
        assertEquals(MatchId("delete"), vm.uiState.value.pendingDeleteMatchId)

        // Projection updates during the write must not be overwritten by local state changes.
        day.value = day.value.copy(penalties = listOf(DailyStanding(PlayerNameKey("ana"), "Ana", 220)))
        runCurrent()
        commit.complete(Unit)
        runCurrent()
        assertTrue(vm.uiState.value.isDeleting)
        assertEquals(day.value.matches, vm.uiState.value.matches)
        assertEquals(day.value.penalties, vm.uiState.value.penalties)
        vm.confirmDelete()
        runCurrent()
        assertEquals(1, repo.deletions.size)

        day.value = day.value.copy(matches = day.value.matches.filterNot { it.id == MatchId("delete") }, wins = emptyList())
        runCurrent()
        assertNull(vm.uiState.value.pendingDeleteMatchId)
        assertFalse(vm.uiState.value.isDeleting)
        assertFalse(vm.uiState.value.deleteError)
        assertTrue(vm.uiState.value.summaryExpanded)
        assertEquals(day.value.matches, vm.uiState.value.matches)
        assertEquals(day.value.wins, vm.uiState.value.wins)
        assertEquals(day.value.penalties, vm.uiState.value.penalties)
    }

    @Test fun deleteFailureKeepsCardAndAllowsRetry() = runTest {
        val day = MutableStateFlow(deletionDay())
        var failWrite = true
        val repo = HistoryRepository(day) {
            if (failWrite) throw IllegalStateException("Write failed")
            day.value = day.value.copy(matches = day.value.matches.filterNot { it.id == MatchId("delete") })
        }
        val vm = HistoryViewModel(day.value.localDate, repo, StandardTestDispatcher(testScheduler))
        runCurrent()
        vm.toggleSummary()
        vm.requestDelete(MatchId("delete"))
        vm.confirmDelete()
        runCurrent()
        assertTrue(vm.uiState.value.deleteError)
        assertFalse(vm.uiState.value.isDeleting)
        assertEquals(MatchId("delete"), vm.uiState.value.pendingDeleteMatchId)
        assertEquals(day.value.matches, vm.uiState.value.matches)
        val failedState = vm.uiState.value
        vm.clearDeleteError()
        assertEquals(failedState.copy(deleteError = false), vm.uiState.value)
        vm.confirmDelete()
        runCurrent()
        assertTrue(vm.uiState.value.deleteError)
        vm.cancelDelete()
        assertNull(vm.uiState.value.pendingDeleteMatchId)
        assertFalse(vm.uiState.value.deleteError)
        vm.requestDelete(MatchId("delete"))
        failWrite = false
        vm.confirmDelete()
        runCurrent()
        assertEquals(List(3) { MatchId("delete") }, repo.deletions)
        assertFalse(vm.uiState.value.isDeleting)
        assertFalse(vm.uiState.value.deleteError)
        assertNull(vm.uiState.value.pendingDeleteMatchId)
        assertTrue(vm.uiState.value.summaryExpanded)
        assertEquals(day.value.matches, vm.uiState.value.matches)
        assertEquals(day.value.wins, vm.uiState.value.wins)
        assertEquals(day.value.penalties, vm.uiState.value.penalties)
    }

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

private fun deletionDay() = DayHistory(
    LocalDate.parse("2026-10-14"),
    listOf(
        HistoryMatch(MatchId("delete"), 200L, true, listOf(HistoryPlayer(PlayerId("a"), "Ana", 110, true, true))),
        HistoryMatch(MatchId("keep"), 100L, false, listOf(HistoryPlayer(PlayerId("b"), "Bruno", 0, false, false))),
    ),
    listOf(DailyStanding(PlayerNameKey("ana"), "Ana", 1)),
    listOf(DailyStanding(PlayerNameKey("ana"), "Ana", 110)),
)

private class HistoryRepository(
    private val day: MutableStateFlow<DayHistory>,
    private val deleteBlock: suspend (MatchId) -> Unit = {},
) : MatchRepository {
    val deletions = mutableListOf<MatchId>()
    override suspend fun deleteMatch(matchId: MatchId) {
        deletions += matchId
        deleteBlock(matchId)
    }
    override fun observeDay(localDate: LocalDate) = day
    override fun observeHome() = MutableStateFlow(emptyList<DaySummary>())
    override fun observeLifetimeStatistics() = MutableStateFlow(emptyList<LifetimePlayerStatistics>())
    override fun observeMatch(matchId: MatchId) = MutableStateFlow(null)
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
    override suspend fun acknowledgeResults(matchId: MatchId, revision: Long) = error("unused")
}
