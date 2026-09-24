package com.maxmfonseca.pontinho.ui.results

import com.maxmfonseca.pontinho.domain.model.MatchId
import com.maxmfonseca.pontinho.domain.model.PlayerId
import java.time.LocalDate

data class ResultPlayer(
    val playerId: PlayerId,
    val displayName: String,
    val total: Int,
    val rank: Int,
    val isWinner: Boolean,
    val isHighestPenalty: Boolean,
)

data class ResultsUiState(
    val matchId: MatchId,
    val localDate: LocalDate? = null,
    val players: List<ResultPlayer> = emptyList(),
    val resultRevision: Long = 0,
    val isFinished: Boolean = false,
    val isLoading: Boolean = true,
    val isLeaving: Boolean = false,
)

sealed interface ResultsEvent {
    data object NewMatch : ResultsEvent
    data class OpenMatch(val matchId: MatchId) : ResultsEvent
    data class OpenHistory(val localDate: LocalDate) : ResultsEvent
}
