package com.maxmfonseca.pontinho.ui.match

import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
import com.maxmfonseca.pontinho.ui.navigation.ScannerContext
import com.maxmfonseca.pontinho.ui.scanner.ScanRequest
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
    private val savedStateHandle: SavedStateHandle? = null,
    private val recognitionAvailable: () -> Boolean = { false },
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
    private var activeScanToken: String? = savedStateHandle?.get("activeScanToken")
    private var restoredDialog = false
    private var scannerLaunching = false

    init {
        scope.launch {
            repository.observeMatch(matchId).catch { failure ->
                if (failure is CancellationException) throw failure
                updateState { it.copy(isLoading = false, hasError = true) }
            }.collect { match ->
                snapshot = match
                if (!restoredDialog && match != null) {
                    restoredDialog = true
                    restoreDialog()
                }
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
            val pending = prepared
            if (pending == null) {
                updateState { it.copy(picker = IndicatorPickerState(participantId), hasError = false) }
            } else openDialog(participantId, pending.id, pending.ordinal, pending.indicator, true)
        } else openDialog(participantId, round.id, round.ordinal, round.indicator, round.draft.winner == null)
    }

    fun openScanner() {
        val dialog = uiState.value.scoreDialog ?: return
        if (!recognitionAvailable() || dialog.readOnly || uiState.value.isSaving || activeScanToken != null || scannerLaunching) return
        val token = submissionToken
        val context = scannerContext(dialog)
        scannerLaunching = true
        scope.launch {
            try {
                val current = repository.observeMatch(matchId).first()
                val request = context.resolve(current)
                if (request != null && submissionToken == token && uiState.value.scoreDialog == dialog) {
                    activeScanToken = token
                    persistDialog()
                    eventChannel.send(MatchEvent.OpenScanner(request))
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { updateState { it.copy(hasError = true) }
            } finally { scannerLaunching = false }
        }
    }

    /** The navigation collector checks again: an event can outlive the dialog that emitted it. */
    suspend fun validateScanner(context: ScannerContext, allowCommitted: Boolean = false): ScanRequest? {
        val dialog = uiState.value.scoreDialog ?: return null
        if (activeScanToken != context.token || scannerContext(dialog) != context || dialog.readOnly) return null
        val current = try { repository.observeMatch(matchId).first() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { return null }
        if (uiState.value.scoreDialog != dialog || activeScanToken != context.token) return null
        snapshot = current
        return context.resolve(current, allowCommitted)
    }

    fun scannerCancelled(token: String) {
        if (activeScanToken != token) return
        activeScanToken = null
        val dialog = uiState.value.scoreDialog
        if (dialog != null && scannerContext(dialog).resolve(snapshot) == null) {
            prepared = null
            updateState { it.copy(scoreDialog = null) }
            render()
        } else persistDialog()
    }

    suspend fun scannerSaved(token: String, points: Int) {
        val dialog = uiState.value.scoreDialog ?: return
        if (activeScanToken != token || submissionToken != token) return
        val committed = repository.observeMatch(matchId).first { match ->
            match == null || match.rounds.any { round -> round.id == dialog.roundId &&
                round.draft.entries[dialog.participantId]?.let { it.origin == RoundEntryOrigin.EXPLICIT && it.points == points } == true }
        }
        if (committed == null || activeScanToken != token || submissionToken != token) return
        snapshot = committed
        prepared = null
        activeScanToken = null
        updateState { it.copy(picker = null, scoreDialog = null) }
        render()
    }

    private fun scannerContext(dialog: MatchScoreDialogState) = ScannerContext(
        matchId.value, dialog.roundId.value, dialog.participantId.value,
        prepared?.takeIf { it.id == dialog.roundId }?.ordinal ?: 0,
        dialog.indicator.rank.name, dialog.indicator.color.name, submissionToken,
        if (dialog.isCorrection) dialog.participantId.value else null,
    )

    private fun persistDialog() {
        val state = savedStateHandle ?: return
        state["activeScanToken"] = activeScanToken
        state["scoreDialog"] = uiState.value.scoreDialog?.let { dialog ->
            listOf(dialog.participantId.value, dialog.playerName, dialog.roundId.value, dialog.roundNumber.toString(),
                dialog.indicator.rank.name, dialog.indicator.color.name, dialog.wentOutEnabled.toString(), dialog.value,
                dialog.isCorrection.toString(), dialog.readOnly.toString(), submissionToken,
                (prepared?.takeIf { it.id == dialog.roundId }?.ordinal ?: 0).toString())
        }
    }

    private fun restoreDialog() {
        val saved = savedStateHandle?.get<List<String>>("scoreDialog") ?: return
        runCatching {
            require(saved.size == 12)
            val indicator = Indicator(Rank.valueOf(saved[4]), CardColor.valueOf(saved[5]))
            submissionToken = saved[10].also { require(it.isNotBlank()) }
            prepared = saved[11].toInt().takeIf { it > 0 }?.let { PreparedRound(RoundId(saved[2]), matchId, it, indicator) }
            val dialog = MatchScoreDialogState(ParticipantId(saved[0]), saved[1], RoundId(saved[2]), saved[3].toInt(),
                indicator, saved[6].toBooleanStrict(), saved[7], saved[8].toBooleanStrict(), saved[9].toBooleanStrict())
            mutableUiState.value = mutableUiState.value.copy(scoreDialog = dialog)
        }.onFailure { prepared = null; activeScanToken = null }
    }

    fun selectIndicatorRank(rank: Rank) {
        if (uiState.value.isSaving) return
        updateState { it.copy(picker = it.picker?.copy(selectedRank = rank)) }
    }

    fun selectIndicatorColor(color: CardColor) {
        if (uiState.value.isSaving) return
        updateState { it.copy(picker = it.picker?.copy(selectedColor = color)) }
    }

    fun confirmIndicator() {
        val picker = uiState.value.picker ?: return
        val indicator = picker.indicator ?: return
        if (uiState.value.isSaving) return
        updateState { it.copy(isSaving = true, hasError = false) }
        scope.launch {
            try {
                val result = repository.chooseIndicator(ChooseIndicator(matchId, indicator))
                prepared = result
                render()
                openDialog(picker.participantId, result.id, result.ordinal, result.indicator, true)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { updateState { it.copy(hasError = true) }
            } finally { updateState { it.copy(isSaving = false) } }
        }
    }

    fun updateScore(value: String) {
        if (uiState.value.isSaving || uiState.value.scoreDialog?.readOnly == true) return
        updateState { it.copy(scoreDialog = it.scoreDialog?.copy(value = value), hasError = false) }
    }

    fun dismissDialog() {
        if (uiState.value.isSaving) return
        addPlayerToken = null
        submittedPlayerName = null
        activeScanToken = null
        updateState { it.copy(picker = null, scoreDialog = null, playerName = null, hasError = false, duplicateNameError = false) }
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
        if (!state.canAddPlayer) return
        addPlayerToken = UUID.randomUUID().toString()
        submittedPlayerName = null
        updateState { it.copy(playerName = "", hasError = false, duplicateNameError = false) }
    }

    fun updatePlayerName(value: String) {
        if (uiState.value.isSaving || uiState.value.playerName == null) return
        if (submittedPlayerName != null && submittedPlayerName != value.trim()) {
            addPlayerToken = UUID.randomUUID().toString()
            submittedPlayerName = null
        }
        updateState { it.copy(playerName = value, hasError = false, duplicateNameError = false) }
    }

    fun confirmPlayerName() {
        val state = uiState.value
        val name = state.playerName?.trim()?.takeIf { it.isNotEmpty() } ?: return
        if (!canJoinMatch() || state.isSaving || state.scoreDialog != null || state.picker != null) return
        val token = addPlayerToken ?: return
        // Retry an attempted command before duplicate validation: its own committed
        // participation may already be visible after a lost acknowledgement.
        if (submittedPlayerName == null && state.players.any { PlayerNameKey.from(it.name) == PlayerNameKey.from(name) }) {
            updateState { it.copy(duplicateNameError = true) }
            return
        }
        submittedPlayerName = name
        mutate(preservePrepared = true) {
            // Keep the token until success is observed. A lost acknowledgement or
            // failed refresh can safely replay the same atomic repository operation.
            repository.addPlayerToMatch(AddPlayerToMatch(matchId, token, name))
        }
    }

    fun movePlayer(id: ParticipantId, direction: Int) {
        val state = uiState.value
        if (!state.canReorderPlayers || direction == 0) return
        val order = state.players.map { it.id }.toMutableList()
        val from = order.indexOf(id)
        if (from < 0) return
        val to = (from.toLong() + direction).coerceIn(0, order.lastIndex.toLong()).toInt()
        if (from == to) return
        order.add(to, order.removeAt(from))
        reorderPlayers(order)
    }

    fun reorderPlayers(orderedParticipantIds: List<ParticipantId>) {
        val state = uiState.value
        if (!state.canReorderPlayers) return
        val committed = state.players.map { it.id }
        if (orderedParticipantIds.size != committed.size || orderedParticipantIds.toSet() != committed.toSet() || orderedParticipantIds == committed) return
        mutate(preservePrepared = true) { repository.reorderParticipants(ReorderParticipants(matchId, orderedParticipantIds.toList())) }
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
        updateState { it.copy(scoreDialog = it.scoreDialog?.copy(
            value = entry?.points?.toString() ?: "", isCorrection = entry != null,
            readOnly = entry?.origin == RoundEntryOrigin.AUTOMATIC_WINNER)) }
    }

    fun deleteScore() {
        val dialog = uiState.value.scoreDialog?.takeIf { it.isCorrection } ?: return
        mutate { repository.removeRoundEntry(RemoveRoundEntry(matchId, dialog.roundId, dialog.participantId)) }
    }

    private fun mutate(preservePrepared: Boolean = false, command: suspend () -> Unit) {
        if (uiState.value.isSaving) return
        updateState { it.copy(isSaving = true, hasError = false) }
        scope.launch {
            try {
                command()
                snapshot = repository.observeMatch(matchId).first()
                if (!preservePrepared) prepared = null
                addPlayerToken = null
                submittedPlayerName = null
                updateState { it.copy(playerName = null, scoreDialog = null, picker = null, duplicateNameError = false) }
                render()
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: DuplicatePlayerNameException) {
                // This command was rejected before commit, so it has no receipt
                // to recover and subsequent confirms must validate the roster again.
                submittedPlayerName = null
                updateState { it.copy(duplicateNameError = true, hasError = false) }
            } catch (_: Exception) { updateState { it.copy(hasError = true) }
            } finally { updateState { it.copy(isSaving = false) } }
        }
    }

    private fun save(dialog: MatchScoreDialogState, winner: Boolean, command: suspend () -> Unit) {
        if (uiState.value.isSaving) return
        updateState { it.copy(isSaving = true, hasError = false) }
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
                updateState { it.copy(picker = null, scoreDialog = null) }
                render()
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { updateState { it.copy(hasError = true) }
            } finally { updateState { it.copy(isSaving = false) } }
        }
    }

    private fun openDialog(id: ParticipantId, roundId: RoundId, ordinal: Int, indicator: Indicator, canWin: Boolean) {
        val name = snapshot?.participants?.singleOrNull { it.id == id }?.displayName ?: return
        submissionToken = UUID.randomUUID().toString()
        activeScanToken = null
        updateState { it.copy(picker = null, hasError = false,
            scoreDialog = MatchScoreDialogState(id, name, roundId, ordinal, indicator, canWin)) }
    }

    private fun render() {
        val match = snapshot
        if (match == null) {
            updateState { it.copy(isLoading = false, isMissing = true) }
            return
        }
        val open = match.openRound
        val nextOrdinal = (match.rounds.maxOfOrNull { it.ordinal } ?: 0) + 1
        val ordinal = open?.ordinal ?: prepared?.ordinal ?: nextOrdinal
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
        updateState { old -> old.copy(isLoading = false, isMissing = false,
            players = players, roundNumber = ordinal, indicator = open?.indicator ?: prepared?.indicator,
            isFinished = match.isFinished,
            pausedRoundNumbers = match.pausedRounds.map { it.ordinal }, isCorrectingRound = open?.isCorrection == true,
            playerName = old.playerName.takeIf { canJoinMatch() },
            picker = old.picker.takeUnless { match.isFinished },
            scoreDialog = old.scoreDialog?.takeUnless { match.isFinished && !it.isCorrection && activeScanToken == null }?.let { dialog ->
                dialog.copy(wentOutEnabled = !dialog.isCorrection && if (dialog.roundId == open?.id) open.draft.winner == null else dialog.wentOutEnabled)
            }) }
    }

    private fun canJoinMatch(): Boolean = snapshot?.let { match ->
        !match.isFinished && match.pausedRounds.isEmpty() && match.openRound?.isCorrection != true
    } == true

    private fun updateState(transform: (MatchUiState) -> MatchUiState) {
        mutableUiState.update { old ->
            val state = transform(old)
            val idle = !state.isSaving && state.playerName == null && state.picker == null && state.scoreDialog == null
            state.copy(
                scoreDialog = state.scoreDialog?.let { dialog -> dialog.copy(canScan = recognitionAvailable() &&
                    !dialog.readOnly && scannerContext(dialog).resolve(snapshot) != null) },
                canAddPlayer = canJoinMatch() && idle,
                canReorderPlayers = canJoinMatch() && snapshot?.openRound == null && idle,
            )
        }
        persistDialog()
    }

    override fun onCleared() { scope.cancel() }
}
