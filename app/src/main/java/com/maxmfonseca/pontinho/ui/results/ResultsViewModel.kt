package com.maxmfonseca.pontinho.ui.results

import androidx.lifecycle.ViewModel
import com.maxmfonseca.pontinho.domain.model.MatchId
import com.maxmfonseca.pontinho.domain.repository.MatchRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

class ResultsViewModel(
    private val matchId: MatchId,
    private val repository: MatchRepository,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutableUiState = MutableStateFlow(ResultsUiState(matchId))
    val uiState: StateFlow<ResultsUiState> = mutableUiState.asStateFlow()
    private val eventChannel = Channel<ResultsEvent>(Channel.BUFFERED)
    val events = eventChannel.receiveAsFlow()

    init {
        scope.launch {
            repository.observeMatch(matchId).collect { match ->
                if (match == null) {
                    mutableUiState.value = mutableUiState.value.copy(isLoading = false, isFinished = false)
                } else {
                    val ordered = match.participants.map { participant ->
                        participant to match.totals.getValue(participant.id)
                    }.sortedWith(compareBy<Pair<com.maxmfonseca.pontinho.domain.model.MatchParticipant, Int>> { it.second }
                        .thenBy { it.first.rosterOrder })
                    var previousTotal: Int? = null
                    var rank = 0
                    val resultPlayers = ordered.mapIndexed { index, (participant, total) ->
                        if (total != previousTotal) rank = index + 1
                        previousTotal = total
                        ResultPlayer(
                            playerId = participant.playerId,
                            displayName = participant.displayName,
                            total = total,
                            rank = rank,
                            isWinner = match.isFinished && participant.id in match.outcome?.winners.orEmpty(),
                            isHighestPenalty = match.isFinished && participant.id in match.outcome?.highestScorers.orEmpty(),
                        )
                    }
                    mutableUiState.value = mutableUiState.value.copy(
                        localDate = match.localDate,
                        players = resultPlayers,
                        resultRevision = match.resultRevision,
                        isFinished = match.isFinished,
                        isLoading = false,
                    )
                }
            }
        }
    }

    fun newMatch() = leave(ResultsEvent.NewMatch)
    fun openMatch() = leave(ResultsEvent.OpenMatch(matchId))
    fun openHistory() {
        mutableUiState.value.localDate?.let { leave(ResultsEvent.OpenHistory(it)) }
    }

    private fun leave(event: ResultsEvent) {
        val state = mutableUiState.value
        if (state.isLeaving || !state.isFinished) return
        mutableUiState.value = state.copy(isLeaving = true)
        scope.launch {
            runCatching { repository.acknowledgeResults(matchId, state.resultRevision) }
                .onSuccess { eventChannel.send(event) }
                .onFailure { mutableUiState.value = mutableUiState.value.copy(isLeaving = false) }
        }
    }

    override fun onCleared() { scope.cancel() }
}
