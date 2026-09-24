package com.maxmfonseca.pontinho.ui.history

import androidx.lifecycle.ViewModel
import com.maxmfonseca.pontinho.domain.repository.MatchRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

class HistoryViewModel(
    private val localDate: LocalDate,
    repository: MatchRepository,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutableUiState = MutableStateFlow(HistoryUiState(localDate))
    val uiState: StateFlow<HistoryUiState> = mutableUiState.asStateFlow()

    init {
        scope.launch {
            repository.observeDay(localDate).collect { projection ->
                mutableUiState.value = mutableUiState.value.copy(
                    matches = projection.matches,
                    wins = projection.wins,
                    penalties = projection.penalties,
                    isLoading = false,
                )
            }
        }
    }

    fun toggleSummary() {
        mutableUiState.value = mutableUiState.value.copy(summaryExpanded = !mutableUiState.value.summaryExpanded)
    }

    override fun onCleared() { scope.cancel() }
}
