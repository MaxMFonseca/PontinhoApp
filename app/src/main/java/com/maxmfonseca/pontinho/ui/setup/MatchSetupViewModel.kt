package com.maxmfonseca.pontinho.ui.setup

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import com.maxmfonseca.pontinho.domain.model.MatchId
import com.maxmfonseca.pontinho.domain.model.PlayerId
import com.maxmfonseca.pontinho.domain.model.PlayerNameKey
import com.maxmfonseca.pontinho.domain.repository.CreateMatch
import com.maxmfonseca.pontinho.domain.repository.CreateMatchPlayer
import com.maxmfonseca.pontinho.domain.repository.MatchRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

class MatchSetupViewModel(
    private val savedStateHandle: SavedStateHandle,
    private val matchRepository: MatchRepository,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutableUiState = MutableStateFlow(MatchSetupUiState(players = restoreDraft()))
    val uiState: StateFlow<MatchSetupUiState> = mutableUiState.asStateFlow()
    private val eventChannel = Channel<MatchSetupEvent>(Channel.BUFFERED)
    val events: Flow<MatchSetupEvent> = eventChannel.receiveAsFlow()

    init {
        scope.launch {
            matchRepository.observeHome().collect { days ->
                val latest = days.flatMap { it.matches }
                    .maxWithOrNull(compareBy({ it.lastPlayedAt }, { it.createdAt }))
                val latestPlayers = latest?.participants?.sortedBy { it.rosterOrder }?.map { participant ->
                    DraftPlayer(newDraftId(), participant.playerId, participant.displayName)
                }.orEmpty()
                mutableUiState.update { it.copy(latestMatchPlayers = latestPlayers) }
            }
        }
        val committed = savedStateHandle.get<String>(COMMITTED_MATCH_ID)
        val token = savedStateHandle.get<String>(CREATION_TOKEN)
        when {
            committed != null -> scope.launch { eventChannel.send(MatchSetupEvent.OpenMatch(MatchId(committed))) }
            token != null && mutableUiState.value.players.size >= 2 -> beginCreate(token, mutableUiState.value.players)
        }
    }

    fun openAddPlayer() = updateIfEditable {
        it.copy(nameDialog = PlayerNameDialogState(editingDraftId = null, value = ""))
    }

    fun openEditPlayer(draftId: String) = updateIfEditable { state ->
        val player = state.players.firstOrNull { it.draftId == draftId } ?: return@updateIfEditable state
        state.copy(nameDialog = PlayerNameDialogState(draftId, player.displayName))
    }

    fun updateDialogName(value: String) = updateIfEditable { state ->
        val dialog = state.nameDialog ?: return@updateIfEditable state
        state.copy(nameDialog = dialog.copy(value = value, showBlankError = false, duplicateNameError = false))
    }

    fun confirmPlayerName() {
        var persisted: List<DraftPlayer>? = null
        mutableUiState.update { state ->
            if (state.isCreating) return@update state
            val dialog = state.nameDialog ?: return@update state
            val trimmed = dialog.value.trim()
            if (trimmed.isEmpty()) return@update state.copy(nameDialog = dialog.copy(showBlankError = true))
            val key = PlayerNameKey.from(trimmed)
            if (state.players.any { it.draftId != dialog.editingDraftId && PlayerNameKey.from(it.displayName) == key }) {
                return@update state.copy(nameDialog = dialog.copy(duplicateNameError = true))
            }
            val players = if (dialog.editingDraftId == null) {
                state.players + DraftPlayer(newDraftId(), null, trimmed)
            } else {
                state.players.map {
                    if (it.draftId == dialog.editingDraftId) it.copy(
                        displayName = trimmed,
                        playerId = if (PlayerNameKey.from(it.displayName) == key) it.playerId else null,
                    ) else it
                }
            }
            persisted = players
            state.copy(players = players, nameDialog = null)
        }
        persisted?.let(::persistDraft)
    }

    fun dismissPlayerName() = updateIfEditable { it.copy(nameDialog = null) }
    fun removePlayer(draftId: String) = changePlayers { players -> players.filterNot { it.draftId == draftId } }

    fun movePlayer(draftId: String, direction: Int) = changePlayers { players ->
        val from = players.indexOfFirst { it.draftId == draftId }
        val to = from + direction.coerceIn(-1, 1)
        if (from !in players.indices || to !in players.indices || from == to) players else players.toMutableList().apply {
            add(to, removeAt(from))
        }
    }

    fun requestUseLastMatch() {
        val state = mutableUiState.value
        if (state.isCreating || state.latestMatchPlayers.isEmpty()) return
        if (state.players.isEmpty()) replaceWithLatest(state.latestMatchPlayers)
        else mutableUiState.update { it.copy(showReplaceConfirmation = true) }
    }

    fun dismissUseLastMatchConfirmation() = updateIfEditable { it.copy(showReplaceConfirmation = false) }
    fun confirmUseLastMatch() {
        val latest = mutableUiState.value.latestMatchPlayers
        if (latest.isNotEmpty()) replaceWithLatest(latest)
    }

    fun cancel() { eventChannel.trySend(MatchSetupEvent.Close) }

    fun createMatch() {
        var roster: List<DraftPlayer>? = null
        mutableUiState.update { state ->
            if (!state.canCreate || savedStateHandle.get<String>(COMMITTED_MATCH_ID) != null) state
            else {
                roster = state.players
                state.copy(isCreating = true, createError = false, nameDialog = null)
            }
        }
        val requested = roster ?: return
        val token = savedStateHandle.get<String>(CREATION_TOKEN) ?: newDraftId().also { savedStateHandle[CREATION_TOKEN] = it }
        beginCreate(token, requested, stateAlreadyMarkedCreating = true)
    }

    fun navigationHandled(matchId: MatchId) {
        if (savedStateHandle.get<String>(COMMITTED_MATCH_ID) == matchId.value) {
            savedStateHandle.remove<String>(COMMITTED_MATCH_ID)
            savedStateHandle.remove<String>(CREATION_TOKEN)
        }
    }

    private fun beginCreate(token: String, roster: List<DraftPlayer>, stateAlreadyMarkedCreating: Boolean = false) {
        if (!stateAlreadyMarkedCreating) mutableUiState.update { it.copy(isCreating = true, createError = false) }
        scope.launch {
            runCatching {
                matchRepository.createMatch(CreateMatch(
                    creationToken = token,
                    players = roster.map { CreateMatchPlayer(it.draftId, it.playerId, it.displayName) },
                ))
            }.onSuccess { matchId ->
                savedStateHandle[COMMITTED_MATCH_ID] = matchId.value
                mutableUiState.update { it.copy(isCreating = false, createError = false) }
                eventChannel.send(MatchSetupEvent.OpenMatch(matchId))
            }.onFailure { failure ->
                if (failure is CancellationException) throw failure
                savedStateHandle.remove<String>(CREATION_TOKEN)
                mutableUiState.update { it.copy(isCreating = false, createError = true) }
            }
        }
    }

    private fun replaceWithLatest(latest: List<DraftPlayer>) {
        changePlayers { latest.map { it.copy(draftId = newDraftId()) } }
        mutableUiState.update { it.copy(showReplaceConfirmation = false) }
    }

    private fun changePlayers(transform: (List<DraftPlayer>) -> List<DraftPlayer>) {
        var persisted: List<DraftPlayer>? = null
        mutableUiState.update { state ->
            if (state.isCreating) state else state.copy(players = transform(state.players).also { persisted = it })
        }
        persisted?.let(::persistDraft)
    }

    private fun updateIfEditable(transform: (MatchSetupUiState) -> MatchSetupUiState) {
        mutableUiState.update { if (it.isCreating) it else transform(it) }
    }

    private fun persistDraft(players: List<DraftPlayer>) {
        savedStateHandle[DRAFT_IDS] = ArrayList(players.map { it.draftId })
        savedStateHandle[PLAYER_IDS] = ArrayList(players.map { it.playerId?.value.orEmpty() })
        savedStateHandle[PLAYER_NAMES] = ArrayList(players.map { it.displayName })
    }

    private fun restoreDraft(): List<DraftPlayer> {
        val names = savedStateHandle.get<ArrayList<String>>(PLAYER_NAMES).orEmpty()
        val playerIds = savedStateHandle.get<ArrayList<String>>(PLAYER_IDS).orEmpty()
        val draftIds = savedStateHandle.get<ArrayList<String>>(DRAFT_IDS).orEmpty()
        if (names.size != playerIds.size || names.size != draftIds.size) return emptyList()
        return names.indices.map { index ->
            DraftPlayer(draftIds[index], playerIds[index].takeIf(String::isNotBlank)?.let(::PlayerId), names[index])
        }
    }

    private fun newDraftId(): String = UUID.randomUUID().toString()
    override fun onCleared() { scope.cancel() }

    companion object {
        const val DRAFT_IDS = "match_setup_draft_ids"
        const val PLAYER_IDS = "match_setup_player_ids"
        const val PLAYER_NAMES = "match_setup_player_names"
        const val CREATION_TOKEN = "match_setup_creation_token"
        const val COMMITTED_MATCH_ID = "match_setup_committed_match_id"
    }
}
