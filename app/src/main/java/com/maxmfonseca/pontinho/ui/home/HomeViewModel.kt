package com.maxmfonseca.pontinho.ui.home

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

class HomeViewModel(
    matchRepository: MatchRepository,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutableUiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = mutableUiState.asStateFlow()

    init {
        scope.launch {
            matchRepository.observeHome().collect { days ->
                mutableUiState.value = HomeUiState(
                    dateCards = days
                        .sortedByDescending { it.localDate }
                        .map { day ->
                            HomeDateCard(
                                localDate = day.localDate,
                                matchCount = day.matchCount,
                                activeMatchCount = day.activeMatchCount,
                                playerNames = day.matches
                                    .flatMap { match -> match.participants.sortedBy { it.rosterOrder } }
                                    .map { it.displayName }
                                    .distinct(),
                            )
                        },
                    isLoading = false,
                )
            }
        }
    }

    override fun onCleared() {
        scope.cancel()
    }
}
