package com.maxmfonseca.pontinho.ui.setup

import com.maxmfonseca.pontinho.domain.model.MatchId
import com.maxmfonseca.pontinho.domain.model.PlayerId
import com.maxmfonseca.pontinho.domain.model.PlayerNameKey

data class DraftPlayer(
    val draftId: String,
    val playerId: PlayerId?,
    val displayName: String,
)

data class PlayerNameDialogState(
    val editingDraftId: String?,
    val value: String,
    val showBlankError: Boolean = false,
    val duplicateNameError: Boolean = false,
)

data class MatchSetupUiState(
    val players: List<DraftPlayer> = emptyList(),
    val nameDialog: PlayerNameDialogState? = null,
    val latestMatchPlayers: List<DraftPlayer> = emptyList(),
    val showReplaceConfirmation: Boolean = false,
    val isCreating: Boolean = false,
    val createError: Boolean = false,
) {
    val minimumPlayers: Int get() = 2
    val canCreate: Boolean get() = players.size >= minimumPlayers && !isCreating &&
        players.map { PlayerNameKey.from(it.displayName) }.distinct().size == players.size
    val canUseLastMatch: Boolean get() = latestMatchPlayers.isNotEmpty()
    val showNoPreviousMatchExplanation: Boolean get() = !canUseLastMatch
}

sealed interface MatchSetupEvent {
    data object Close : MatchSetupEvent
    data class OpenMatch(val matchId: MatchId) : MatchSetupEvent
}
