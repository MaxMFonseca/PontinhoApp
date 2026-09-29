package com.maxmfonseca.pontinho.ui.history

import androidx.lifecycle.ViewModel
import com.maxmfonseca.pontinho.domain.model.MatchId
import com.maxmfonseca.pontinho.domain.repository.MatchRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

class HistoryViewModel(
    private val localDate: LocalDate,
    private val repository: MatchRepository,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutableUiState = MutableStateFlow(HistoryUiState(localDate))
    val uiState: StateFlow<HistoryUiState> = mutableUiState.asStateFlow()

    init {
        scope.launch {
            repository.observeDay(localDate).collect { projection ->
                mutableUiState.update { it.copy(
                    matches = projection.matches,
                    wins = projection.wins,
                    penalties = projection.penalties,
                    isLoading = false,
                ) }
            }
        }
    }

    fun toggleSummary() {
        mutableUiState.update { it.copy(summaryExpanded = !it.summaryExpanded) }
    }

    fun requestDelete(matchId: MatchId) {
        mutableUiState.update { state ->
            if (state.isDeleting || state.matches.none { it.id == matchId }) state
            else state.copy(pendingDeleteMatchId = matchId, deleteError = false)
        }
    }

    fun cancelDelete() {
        mutableUiState.update {
            if (it.isDeleting) it else it.copy(pendingDeleteMatchId = null, deleteError = false)
        }
    }

    fun confirmDelete() {
        // Acquire the saving state before dispatching so consecutive taps cannot start two writes.
        while (true) {
            val state = mutableUiState.value
            val matchId = state.pendingDeleteMatchId ?: return
            if (state.isDeleting) return
            if (!mutableUiState.compareAndSet(state, state.copy(isDeleting = true, deleteError = false))) continue
            scope.launch {
                try {
                    repository.deleteMatch(matchId)
                    // Wait for the rendered projection; never remove a durable card optimistically.
                    uiState.first { it.matches.none { match -> match.id == matchId } }
                    mutableUiState.update { it.copy(pendingDeleteMatchId = null, isDeleting = false) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    mutableUiState.update { it.copy(isDeleting = false, deleteError = true) }
                }
            }
            return
        }
    }

    fun clearDeleteError() {
        mutableUiState.update { it.copy(deleteError = false) }
    }

    override fun onCleared() { scope.cancel() }
}
