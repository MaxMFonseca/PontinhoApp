package com.maxmfonseca.pontinho.ui.match

import androidx.lifecycle.ViewModel
import com.maxmfonseca.pontinho.domain.model.*
import com.maxmfonseca.pontinho.domain.repository.*
import com.maxmfonseca.pontinho.ui.components.PlayerRoundHistory
import com.maxmfonseca.pontinho.ui.components.RoundStatusKind
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import java.util.UUID

class MatchViewModel(
    private val matchId: MatchId,
    private val repository: MatchRepository,
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) : ViewModel() {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutableUiState = MutableStateFlow(MatchUiState())
    val uiState: StateFlow<MatchUiState> = mutableUiState.asStateFlow()
    private val eventChannel = Channel<MatchEvent>(Channel.BUFFERED)
    val events = eventChannel.receiveAsFlow()
    private var snapshot: MatchSnapshot? = null
    private var prepared: PreparedRound? = null
    private var emittedRevision: Long? = null
    private var submissionToken = ""
    private var addPlayerToken: String? = null
    private var submittedPlayerName: String? = null

    init {
        scope.launch {
            repository.observeMatch(matchId).catch { failure ->
                if (failure is CancellationException) throw failure
                mutableUiState.update { it.copy(isLoading = false, hasError = true) }
            }.collect { match ->
                snapshot = match
                render()
                if (match != null && match.hasPendingResults && emittedRevision != match.resultRevision) {
                    emittedRevision = match.resultRevision
                    eventChannel.send(MatchEvent.OpenResults(matchId))
                }
            }
        }
    }

    fun addScore(participantId: ParticipantId) {
        val state = uiState.value
        if (state.isSaving || state.picker != null || state.scoreDialog != null || state.playerName != null ||
            state.players.none { it.id == participantId && it.canAddScore }) return
        val match = snapshot ?: return
        val round = match.openRound
        if (round == null) {
            prepared = null
            mutableUiState.update { it.copy(picker = IndicatorPickerState(participantId), hasError = false) }
        } else openDialog(participantId, round.id, round.ordinal, round.indicator, round.draft.winner == null)
    }

    fun selectIndicator(indicator: Indicator) {
        if (uiState.value.isSaving) return
        mutableUiState.update { it.copy(picker = it.picker?.copy(selected = indicator)) }
    }

    fun confirmIndicator() {
        val picker = uiState.value.picker ?: return
        val indicator = picker.selected ?: return
        if (uiState.value.isSaving) return
        mutableUiState.update { it.copy(isSaving = true, hasError = false) }
        scope.launch {
            try {
                val result = repository.chooseIndicator(ChooseIndicator(matchId, indicator))
                prepared = result
                openDialog(picker.participantId, result.id, result.ordinal, result.indicator, true)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { mutableUiState.update { it.copy(hasError = true) }
            } finally { mutableUiState.update { it.copy(isSaving = false) } }
        }
    }

    fun updateScore(value: String) {
        if (uiState.value.isSaving || uiState.value.scoreDialog?.readOnly == true) return
        mutableUiState.update { it.copy(scoreDialog = it.scoreDialog?.copy(value = value), hasError = false) }
    }

    fun dismissDialog() {
        if (uiState.value.isSaving) return
        prepared = null
        addPlayerToken = null
        submittedPlayerName = null
        mutableUiState.update { it.copy(picker = null, scoreDialog = null, playerName = null, hasError = false, duplicateNameError = false) }
    }

    fun confirmScore() {
        val dialog = uiState.value.scoreDialog ?: return
        if (!dialog.canConfirm) return
        val points = dialog.parsedScore ?: return
        save(dialog, winner = false) {
            if (dialog.isCorrection) repository.updateExplicitScore(UpdateExplicitScore(matchId, dialog.roundId, dialog.participantId, points))
            else repository.submitScore(SubmitScore(matchId, dialog.roundId, dialog.participantId,
                    points, submissionToken, prepared))
        }
    }

    fun wentOut() {
        val dialog = uiState.value.scoreDialog?.takeIf { it.wentOutEnabled } ?: return
        save(dialog, winner = true) {
            repository.designateWinner(DesignateWinner(matchId, dialog.roundId, dialog.participantId, prepared))
        }
    }

    fun viewResults() {
        if (uiState.value.isFinished) eventChannel.trySend(MatchEvent.OpenResults(matchId))
    }

    fun openAddPlayer() {
        val state = uiState.value
        if (!state.canManagePlayers || state.isSaving || state.scoreDialog != null || state.picker != null) return
        addPlayerToken = UUID.randomUUID().toString()
        submittedPlayerName = null
        mutableUiState.update { it.copy(playerName = "", hasError = false, duplicateNameError = false) }
    }

    fun updatePlayerName(value: String) {
        if (uiState.value.isSaving || uiState.value.playerName == null) return
        if (submittedPlayerName != null && submittedPlayerName != value.trim()) {
            addPlayerToken = UUID.randomUUID().toString()
            submittedPlayerName = null
        }
        mutableUiState.update { it.copy(playerName = value, hasError = false, duplicateNameError = false) }
    }

    fun confirmPlayerName() {
        val state = uiState.value
        val name = state.playerName?.trim()?.takeIf { it.isNotEmpty() } ?: return
        if (!state.canManagePlayers || state.isSaving) return
        val token = addPlayerToken ?: return
        // Retry an attempted command before duplicate validation: its own committed
        // participation may already be visible after a lost acknowledgement.
        if (submittedPlayerName == null && state.players.any { PlayerNameKey.from(it.name) == PlayerNameKey.from(name) }) {
            mutableUiState.update { it.copy(duplicateNameError = true) }
            return
        }
        submittedPlayerName = name
        mutate {
            // Keep the token until success is observed. A lost acknowledgement or
            // failed refresh can safely replay the same atomic repository operation.
            repository.addPlayerToMatch(AddPlayerToMatch(matchId, token, name))
        }
    }

    fun movePlayer(id: ParticipantId, direction: Int) {
        val state = uiState.value
        if (!state.canManagePlayers || state.playerName != null || state.scoreDialog != null || state.picker != null || direction == 0) return
        val order = state.players.map { it.id }.toMutableList()
        val from = order.indexOf(id)
        if (from < 0) return
        val to = (from.toLong() + direction).coerceIn(0, order.lastIndex.toLong()).toInt()
        if (from == to) return
        order.add(to, order.removeAt(from))
        mutate { repository.reorderParticipants(ReorderParticipants(matchId, order)) }
    }

    fun editScore(id: ParticipantId, ordinal: Int) {
        val state = uiState.value
        if (state.isSaving || state.picker != null || state.scoreDialog != null || state.playerName != null) return
        val round = snapshot?.rounds?.singleOrNull { it.ordinal == ordinal } ?: return
        if (id !in round.draft.eligibleParticipants) return
        val entry = round.draft.entries[id]
        if (entry == null && (round.status != RoundStatus.OPEN || state.isFinished)) return
        prepared = null
        openDialog(id, round.id, ordinal, round.indicator, entry == null && round.draft.winner == null)
        mutableUiState.update { it.copy(scoreDialog = it.scoreDialog?.copy(
            value = entry?.points?.toString() ?: "", isCorrection = entry != null,
            readOnly = entry?.origin == RoundEntryOrigin.AUTOMATIC_WINNER)) }
    }

    fun deleteScore() {
        val dialog = uiState.value.scoreDialog?.takeIf { it.isCorrection } ?: return
        mutate { repository.removeRoundEntry(RemoveRoundEntry(matchId, dialog.roundId, dialog.participantId)) }
    }

    private fun mutate(command: suspend () -> Unit) {
        if (uiState.value.isSaving) return
        mutableUiState.update { it.copy(isSaving = true, hasError = false) }
        scope.launch {
            try {
                command()
                snapshot = repository.observeMatch(matchId).first()
                prepared = null
                addPlayerToken = null
                submittedPlayerName = null
                mutableUiState.update { it.copy(playerName = null, scoreDialog = null, picker = null, duplicateNameError = false) }
                render()
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: DuplicatePlayerNameException) {
                // This command was rejected before commit, so it has no receipt
                // to recover and subsequent confirms must validate the roster again.
                submittedPlayerName = null
                mutableUiState.update { it.copy(duplicateNameError = true, hasError = false) }
            } catch (_: Exception) { mutableUiState.update { it.copy(hasError = true) }
            } finally { mutableUiState.update { it.copy(isSaving = false) } }
        }
    }

    private fun save(dialog: MatchScoreDialogState, winner: Boolean, command: suspend () -> Unit) {
        if (uiState.value.isSaving) return
        mutableUiState.update { it.copy(isSaving = true, hasError = false) }
        scope.launch {
            try {
                command()
                // Hold the tap guard until the committed entry is visible; Room's
                // invalidation emission can trail the transaction return.
                snapshot = repository.observeMatch(matchId).first { match ->
                    match?.rounds?.any { round -> round.id == dialog.roundId &&
                        (if (winner) round.draft.designatedWinner == dialog.participantId
                        else round.draft.entries[dialog.participantId]?.points == dialog.parsedScore) } == true
                }
                prepared = null
                mutableUiState.update { it.copy(picker = null, scoreDialog = null) }
                render()
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { mutableUiState.update { it.copy(hasError = true) }
            } finally { mutableUiState.update { it.copy(isSaving = false) } }
        }
    }

    private fun openDialog(id: ParticipantId, roundId: RoundId, ordinal: Int, indicator: Indicator, canWin: Boolean) {
        val name = snapshot?.participants?.singleOrNull { it.id == id }?.displayName ?: return
        submissionToken = UUID.randomUUID().toString()
        mutableUiState.update { it.copy(picker = null, hasError = false,
            scoreDialog = MatchScoreDialogState(id, name, roundId, ordinal, indicator, canWin)) }
    }

    private fun render() {
        val match = snapshot
        if (match == null) {
            mutableUiState.update { it.copy(isLoading = false, isMissing = true) }
            return
        }
        val open = match.openRound
        val nextOrdinal = (match.rounds.maxOfOrNull { it.ordinal } ?: 0) + 1
        val ordinal = open?.ordinal ?: nextOrdinal
        val betweenRounds = open == null && match.pausedRounds.isEmpty()
        val players = match.participants.sortedBy { it.rosterOrder }.map { participant ->
            val history = match.rounds.sortedBy { it.ordinal }.map { round ->
                val entry = round.draft.entries[participant.id]
                val status = when {
                    participant.id !in round.draft.eligibleParticipants -> RoundStatusKind.NOT_PARTICIPATING
                    round.draft.designatedWinner == participant.id -> RoundStatusKind.WINNER
                    entry == null -> RoundStatusKind.EMPTY
                    round.status == RoundStatus.COMPLETE -> RoundStatusKind.LOSS
                    else -> RoundStatusKind.PENDING
                }
                PlayerRoundHistory(round.ordinal, status, entry?.points,
                    participant.id in round.draft.eligibleParticipants &&
                        (entry != null || round.status == RoundStatus.OPEN && !match.isFinished))
            }.let { history ->
                if (betweenRounds && !match.isFinished) history + PlayerRoundHistory(nextOrdinal, RoundStatusKind.EMPTY, null)
                else history
            }
            val eligible = if (open != null) participant.id in open.draft.eligibleParticipants
                else participant.eligibleFromRoundOrdinal <= ordinal
            MatchPlayer(participant.id, participant.displayName, match.totals[participant.id] ?: 0,
                participant.id == match.dealerParticipantId, history,
                !match.isFinished && eligible && (open != null || betweenRounds) &&
                    open?.draft?.entries?.containsKey(participant.id) != true && open?.draft?.designatedWinner != participant.id)
        }
        mutableUiState.update { old -> old.copy(isLoading = false, isMissing = false,
            players = players, roundNumber = ordinal, indicator = open?.indicator,
            isFinished = match.isFinished,
            canManagePlayers = betweenRounds && !match.isFinished,
            pausedRoundNumbers = match.pausedRounds.map { it.ordinal }, isCorrectingRound = open?.isCorrection == true,
            playerName = old.playerName.takeIf { betweenRounds && !match.isFinished },
            picker = old.picker.takeUnless { match.isFinished },
            scoreDialog = old.scoreDialog?.takeUnless { match.isFinished && !it.isCorrection }?.let { dialog ->
                dialog.copy(wentOutEnabled = !dialog.isCorrection && if (dialog.roundId == open?.id) open.draft.winner == null else dialog.wentOutEnabled)
            }) }
    }

    override fun onCleared() { scope.cancel() }
}
