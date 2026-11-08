package com.maxmfonseca.pontinho.ui.match

import com.maxmfonseca.pontinho.domain.model.MatchParticipant

data class ManagePlayersState(
    val players: List<MatchParticipant> = emptyList(), val selected: MatchParticipant? = null,
    val canRemove: Boolean = false, val isSaving: Boolean = false, val hasError: Boolean = false,
)

sealed interface ManagePlayersEvent {
    data object ReturnToMatch : ManagePlayersEvent
}
