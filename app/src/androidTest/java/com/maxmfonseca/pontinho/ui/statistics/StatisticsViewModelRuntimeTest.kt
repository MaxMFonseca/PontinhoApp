package com.maxmfonseca.pontinho.ui.statistics

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maxmfonseca.pontinho.domain.model.MatchId
import com.maxmfonseca.pontinho.domain.model.PlayerNameKey
import com.maxmfonseca.pontinho.domain.repository.*
import com.maxmfonseca.pontinho.domain.statistics.LifetimePlayerStatistics
import com.maxmfonseca.pontinho.domain.statistics.CompletedDateRange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class StatisticsViewModelRuntimeTest {
    @Test fun viewModelConstructsAndNormalizesUnicodeWhitespaceOnAndroid() = runBlocking {
        val repository = RuntimeStatisticsRepository(listOf(
            LifetimePlayerStatistics(PlayerNameKey("joão\u00a0\u2003silva"), "João\u00a0\u2003Silva", 3, 1, 10L),
            LifetimePlayerStatistics(PlayerNameKey("joana silva"), "Joana Silva", 3, 1, 10L),
        ))
        val store = ViewModelStore()
        try {
            // Construction itself catches regex flags rejected by Android's ICU engine.
            val viewModel = StatisticsViewModel(SavedStateHandle(), repository, Dispatchers.Unconfined)
            store.put("statistics", viewModel)
            withTimeout(5_000) { viewModel.uiState.first { !it.isLoading } }

            viewModel.updateQuery("  JOAO\u202f\u0085SILVA ")
            val state = withTimeout(5_000) {
                viewModel.uiState.first { it.query == "  JOAO\u202f\u0085SILVA " && it.players.size == 1 }
            }

            assertEquals(listOf("joão\u00a0\u2003silva"), state.players.map { it.playerKey.value })
        } finally {
            store.clear()
        }
    }
}

private class RuntimeStatisticsRepository(private val players: List<LifetimePlayerStatistics>) : MatchRepository {
    override fun observeLifetimeStatistics(completedDateRange: CompletedDateRange) = MutableStateFlow(players)
    override fun observeHome() = MutableStateFlow(emptyList<DaySummary>())
    override fun observeDay(localDate: LocalDate) = MutableStateFlow(DayHistory(localDate, emptyList(), emptyList(), emptyList()))
    override fun observeMatch(matchId: MatchId) = MutableStateFlow<MatchSnapshot?>(null)
    override suspend fun createMatch(command: CreateMatch) = error("unused")
    override suspend fun deleteMatch(matchId: MatchId) = error("unused")
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
