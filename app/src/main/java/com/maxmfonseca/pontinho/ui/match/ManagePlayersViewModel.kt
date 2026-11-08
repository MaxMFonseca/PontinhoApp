package com.maxmfonseca.pontinho.ui.match

import androidx.lifecycle.ViewModel
import com.maxmfonseca.pontinho.domain.model.*
import com.maxmfonseca.pontinho.domain.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

class ManagePlayersViewModel(private val matchId: MatchId, private val repository: MatchRepository,
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate) : ViewModel() {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutableState = MutableStateFlow(ManagePlayersState())
    val uiState = mutableState.asStateFlow()
    private val eventChannel = Channel<ManagePlayersEvent>(Channel.BUFFERED)
    val events = eventChannel.receiveAsFlow()
    init {
        scope.launch {
            repository.observeMatch(matchId).catch { if (it is CancellationException) throw it; mutableState.update { state -> state.copy(hasError = true) } }
                .collect { match ->
                    val players = match?.participants?.filter { it.isActive }?.sortedBy { it.rosterOrder }.orEmpty()
                    val allowed = match != null && !match.isFinished && match.openRound?.isCorrection != true && players.size > 2
                    mutableState.update { it.copy(players = players, canRemove = allowed, selected = it.selected?.takeIf { selected -> allowed && players.any { p -> p.id == selected.id } }) }
                }
        }
    }
    fun select(id: ParticipantId) {
        mutableState.update { state -> if (!state.canRemove || state.isSaving || state.selected != null) state else
            state.copy(selected = state.players.singleOrNull { it.id == id }, hasError = false) }
    }
    fun cancel() { mutableState.update { if (it.isSaving) it else it.copy(selected = null, hasError = false) } }
    fun confirm() {
        val state = uiState.value
        val selected = state.selected ?: return
        if (!state.canRemove || state.isSaving) return
        mutableState.update { it.copy(isSaving = true, hasError = false) }
        scope.launch {
            try {
                repository.deactivateParticipant(DeactivateParticipant(matchId, selected.id))
                mutableState.update { it.copy(selected = null) }
                eventChannel.trySend(ManagePlayersEvent.ReturnToMatch)
            }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutableState.update { it.copy(hasError = true) } }
            finally { mutableState.update { it.copy(isSaving = false) } }
        }
    }
    override fun onCleared() { scope.cancel() }
}
