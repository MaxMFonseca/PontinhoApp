package com.maxmfonseca.pontinho.ui.match

import com.maxmfonseca.pontinho.domain.model.*
import com.maxmfonseca.pontinho.ui.components.PlayerRoundHistory

data class MatchPlayer(val id: ParticipantId, val name: String, val total: Int,
    val isDealer: Boolean, val history: List<PlayerRoundHistory>, val canAddScore: Boolean)
data class IndicatorPickerState(val participantId: ParticipantId, val selected: Indicator? = null)
data class MatchScoreDialogState(
    val participantId: ParticipantId, val playerName: String, val roundId: RoundId,
    val roundNumber: Int, val indicator: Indicator, val wentOutEnabled: Boolean, val value: String = "",
    val isCorrection: Boolean = false, val readOnly: Boolean = false,
) {
    val parsedScore: Int? get() = value.takeIf { it.isNotEmpty() && it.all { c -> c in '0'..'9' } }?.toIntOrNull()
    val canConfirm: Boolean get() = !readOnly && parsedScore != null
}
data class MatchUiState(
    val isLoading: Boolean = true, val isMissing: Boolean = false,
    val players: List<MatchPlayer> = emptyList(), val roundNumber: Int = 1,
    val indicator: Indicator? = null, val isFinished: Boolean = false,
    val isSaving: Boolean = false, val hasError: Boolean = false,
    val picker: IndicatorPickerState? = null, val scoreDialog: MatchScoreDialogState? = null,
    val canManagePlayers: Boolean = false, val playerName: String? = null,
    val pausedRoundNumbers: List<Int> = emptyList(), val isCorrectingRound: Boolean = false,
)
sealed interface MatchEvent {
    data class OpenResults(val matchId: MatchId) : MatchEvent
}
