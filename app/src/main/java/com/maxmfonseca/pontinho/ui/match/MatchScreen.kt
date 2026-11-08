package com.maxmfonseca.pontinho.ui.match

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.zIndex
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.maxmfonseca.pontinho.R
import com.maxmfonseca.pontinho.domain.model.*
import com.maxmfonseca.pontinho.ui.components.PlayerCard
import com.maxmfonseca.pontinho.ui.components.ScoreDialog
import com.maxmfonseca.pontinho.ui.components.PlayerNameDialog
import com.maxmfonseca.pontinho.ui.components.ImmediateDragHandle
import com.maxmfonseca.pontinho.ui.components.PontinhoTopBar
import com.maxmfonseca.pontinho.ui.components.rememberReorderableListState

@Composable
fun MatchScreen(
    state: MatchUiState,
    onBack: () -> Unit,
    onAddScore: (ParticipantId) -> Unit,
    onSelectIndicatorRank: (Rank) -> Unit,
    onConfirmIndicator: () -> Unit,
    onDismissDialog: () -> Unit,
    onScoreChange: (String) -> Unit,
    onConfirmScore: () -> Unit,
    onWentOut: () -> Unit,
    onViewResults: () -> Unit,
    onAddPlayer: () -> Unit = {},
    onPlayerNameChange: (String) -> Unit = {},
    onConfirmPlayerName: () -> Unit = {},
    onReorderPlayers: (List<ParticipantId>) -> Unit = {},
    onEditScore: (ParticipantId, Int) -> Unit = { _, _ -> },
    onDeleteScore: () -> Unit = {},
    onSelectIndicatorColor: (CardColor) -> Unit = {},
    onCamera: () -> Unit = {},
    onEditJoker: (Int) -> Unit = {},
    onRemoveIndicator: () -> Unit = {},
    onBeginDealerSelection: () -> Unit = {},
    onSelectDealer: (ParticipantId) -> Unit = {},
    onManagePlayers: () -> Unit = {}, onRequestReactivation: (ParticipantId) -> Unit = {},
    onCancelReactivation: () -> Unit = {}, onConfirmReactivation: () -> Unit = {},
) {
    val historyScroll = rememberScrollState()
    val textMeasurer = rememberTextMeasurer()
    val scoreStyle = MaterialTheme.typography.labelMedium
    val largestScoreWidth = state.players.flatMap { it.history }.mapNotNull { it.score?.toString() }
        .distinct().maxOfOrNull { textMeasurer.measure(AnnotatedString(it), scoreStyle).size.width } ?: 0
    val columnWidth = with(LocalDensity.current) { largestScoreWidth.toDp() + 12.dp }.coerceAtLeast(48.dp)
    val listState = rememberLazyListState()
    var listTop by remember { mutableFloatStateOf(0f) }
    val canMove = state.canReorderPlayers && !state.isSelectingDealer && !state.isFinished && !state.isCorrectingRound &&
        state.pausedRoundNumbers.isEmpty() && !state.isSaving && state.playerName == null && state.picker == null && state.scoreDialog == null
    val reorderState = rememberReorderableListState(listState, state.players.filter { it.isActive }.map { it.id },
        enabled = canMove, itemKey = { it.value }, onReorder = onReorderPlayers)
    val playersById = state.players.associateBy { it.id }
    val visualPlayers = reorderState.previewOrder?.let { order -> order.mapNotNull { playersById[it] } + state.players.filter { !it.isActive } } ?: state.players
    fun movePlayer(id: ParticipantId, direction: Int) {
        if (!canMove) return
        val order = state.players.filter { it.isActive }.map { it.id }.toMutableList()
        val from = order.indexOf(id)
        val to = (from + direction).coerceIn(order.indices)
        if (from >= 0 && from != to) onReorderPlayers(order.apply { add(to, removeAt(from)) })
    }
    Column(Modifier.fillMaxSize().safeDrawingPadding().testTag("match_root")) {
        PontinhoTopBar(R.string.app_name, onBack = onBack)
        if (state.isLoading) {
            CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
        } else if (state.isMissing) {
            Text(stringResource(R.string.match_missing), Modifier.padding(24.dp))
        } else {
            val status = when (state.phase) {
                MatchPhase.NOT_STARTED -> R.string.match_round_not_started
                MatchPhase.COUNTING -> R.string.match_round_counting
                MatchPhase.EDITING -> R.string.match_round_editing
                MatchPhase.FINISHED -> R.string.match_finished
            }
            Text(stringResource(status, state.roundNumber),
                Modifier.align(Alignment.CenterHorizontally).padding(horizontal = 16.dp, vertical = 8.dp).testTag("match_status"),
                style = MaterialTheme.typography.titleSmall, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            JokerHistoryRow(state.jokers, state.roundNumber, onEditJoker,
                Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            if (state.pausedRoundNumbers.isNotEmpty()) Text(stringResource(R.string.match_paused_rounds,
                state.pausedRoundNumbers.joinToString()), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
            if (state.hasError && state.scoreDialog == null && state.picker == null) {
                Text(stringResource(R.string.match_save_error), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
            }
            if (state.isFinished) {
                Button(onClick = onViewResults, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text(stringResource(R.string.match_view_result))
                }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth().testTag("match_players")
                    .onGloballyPositioned { listTop = it.positionInRoot().y },
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                userScrollEnabled = reorderState.draggedKey == null,
            ) {
                itemsIndexed(visualPlayers, key = { _, player -> player.id.value }) { index, player ->
                    val up = stringResource(R.string.setup_move_up, player.name)
                    val down = stringResource(R.string.setup_move_down, player.name)
                    val dragLabel = stringResource(R.string.player_drag, player.name)
                    val dragging = reorderState.draggedKey == player.id
                    var handleTop by remember(player.id) { mutableFloatStateOf(0f) }
                    PlayerCard(player.name, player.total, player.isDealer, player.history,
                        onAddScore = if (player.isActive && player.canAddScore && !state.isSaving && !state.isSelectingDealer)
                            ({ onAddScore(player.id) }) else null,
                        modifier = Modifier.fillMaxWidth().testTag("match_player_${player.id.value}")
                            .zIndex(if (dragging) 1f else 0f)
                            .graphicsLayer {
                                translationY = reorderState.translationY(player.id)
                            }
                            .semantics { customActions = if (canMove && player.isActive) buildList {
                                if (index > 0) add(CustomAccessibilityAction(up) { movePlayer(player.id, -1); true })
                                if (index < state.players.count { it.isActive } - 1) add(CustomAccessibilityAction(down) { movePlayer(player.id, 1); true })
                            } else emptyList() },
                        historyScrollState = historyScroll, showScoreAction = !state.isFinished,
                        historyColumnWidth = columnWidth,
                        onEditScore = if (!state.isSaving && player.isActive) ({ onEditScore(player.id, it) }) else null,
                        dealerSelectionActive = state.isSelectingDealer && player.isActive,
                        isActive = player.isActive,
                        onInactiveClick = if (!player.isActive && state.canManagePlayers) ({ onRequestReactivation(player.id) }) else null,
                        onDealerClick = when {
                            state.isSelectingDealer && player.isActive -> ({ onSelectDealer(player.id) })
                            player.isActive && player.isDealer && state.canChangeDealer -> onBeginDealerSelection
                            else -> null
                        },
                        dragHandle = {
                            if (!player.isActive) Spacer(Modifier.size(48.dp)) else ImmediateDragHandle(canMove, dragLabel,
                                onDragStart = { offset ->
                                    reorderState.startDrag(player.id, handleTop + offset.y)
                                },
                                onDragDelta = reorderState::dragBy,
                                onDragEnd = reorderState::endDrag,
                                onDragCancel = reorderState::cancelDrag,
                                modifier = Modifier.testTag("match_drag_${player.id.value}")
                                    .onGloballyPositioned { handleTop = it.positionInRoot().y - listTop })
                        })
                }
                if (!state.isFinished) item(key = "player_actions") {
                    val addEnabled = state.canAddPlayer && !state.isSelectingDealer
                    val addColor = MaterialTheme.colorScheme.primary
                    val removeColor = MaterialTheme.colorScheme.error
                    Row(Modifier.fillMaxWidth().testTag("match_player_actions"),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onAddPlayer, enabled = addEnabled,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = addColor,
                                disabledContentColor = addColor.copy(alpha = 0.38f)),
                            border = BorderStroke(1.dp, addColor.copy(alpha = if (addEnabled) 1f else 0.38f)),
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("match_add_player")) {
                            Text(stringResource(R.string.setup_add_player), maxLines = 1)
                        }
                        OutlinedButton(onClick = onManagePlayers, enabled = state.canManagePlayers,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = removeColor,
                                disabledContentColor = removeColor.copy(alpha = 0.38f)),
                            border = BorderStroke(1.dp, removeColor.copy(alpha = if (state.canManagePlayers) 1f else 0.38f)),
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("match_remove_player")) {
                            Text(stringResource(R.string.match_remove_player), maxLines = 1)
                        }
                    }
                }
            }
        }
    }
    state.reactivationTarget?.let { player ->
        AlertDialog(onDismissRequest = onCancelReactivation,
            title = { Text(stringResource(R.string.match_reactivate_title)) },
            text = { Column { Text(stringResource(R.string.match_reactivate_message, player.name)); if (state.hasError) Text(stringResource(R.string.match_save_error), color = MaterialTheme.colorScheme.error) } },
            confirmButton = { TextButton(onClick = onConfirmReactivation, enabled = !state.isSaving) { Text(stringResource(R.string.match_reactivate_confirm)) } },
            dismissButton = { TextButton(onClick = onCancelReactivation, enabled = !state.isSaving) { Text(stringResource(R.string.cancel)) } })
    }
    state.picker?.let { picker ->
        JokerPicker(picker.selectedRank, picker.selectedColor, state.isSaving, state.hasError,
            onSelectIndicatorRank, onSelectIndicatorColor, onConfirmIndicator, onDismissDialog,
            canRemove = picker.canRemove, onRemove = onRemoveIndicator)
    }
    state.scoreDialog?.takeIf { state.picker == null }?.let { dialog ->
        ScoreDialog(dialog.playerName, dialog.roundNumber, dialog.indicator?.let { wildcardPreview(it) }, dialog.value,
            onScoreChange, { onConfirmScore() }, onDismissDialog, onWentOut,
            onCamera = onCamera.takeIf { dialog.canScan },
            wentOutEnabled = dialog.wentOutEnabled && !state.isSaving,
            enabled = !state.isSaving, hasSaveError = state.hasError,
            isCorrection = dialog.isCorrection, readOnly = dialog.readOnly, onDelete = onDeleteScore)
    }
    state.playerName?.let { name ->
        PlayerNameDialog(name, onPlayerNameChange, onConfirmPlayerName, onDismissDialog,
            supportingText = stringResource(R.string.match_late_player_help) +
                if (state.hasError) "\n" + stringResource(R.string.match_save_error) else "",
            enabled = !state.isSaving, confirmEnabled = name.isNotBlank(),
            duplicateNameError = state.duplicateNameError)
    }
}
