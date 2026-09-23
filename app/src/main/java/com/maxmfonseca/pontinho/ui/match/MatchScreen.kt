package com.maxmfonseca.pontinho.ui.match

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.*
import androidx.compose.ui.graphics.graphicsLayer
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
import kotlin.math.roundToInt

@Composable
fun MatchScreen(
    state: MatchUiState,
    onBack: () -> Unit,
    onAddScore: (ParticipantId) -> Unit,
    onSelectIndicator: (Indicator) -> Unit,
    onConfirmIndicator: () -> Unit,
    onDismissDialog: () -> Unit,
    onScoreChange: (String) -> Unit,
    onConfirmScore: () -> Unit,
    onWentOut: () -> Unit,
    onViewResults: () -> Unit,
    onAddPlayer: () -> Unit = {},
    onPlayerNameChange: (String) -> Unit = {},
    onConfirmPlayerName: () -> Unit = {},
    onMovePlayer: (ParticipantId, Int) -> Unit = { _, _ -> },
    onEditScore: (ParticipantId, Int) -> Unit = { _, _ -> },
    onDeleteScore: () -> Unit = {},
) {
    val historyScroll = rememberScrollState()
    val textMeasurer = rememberTextMeasurer()
    val scoreStyle = MaterialTheme.typography.labelMedium
    val largestScoreWidth = state.players.flatMap { it.history }.mapNotNull { it.score?.toString() }
        .distinct().maxOfOrNull { textMeasurer.measure(AnnotatedString(it), scoreStyle).size.width } ?: 0
    val columnWidth = with(LocalDensity.current) { largestScoreWidth.toDp() + 12.dp }.coerceAtLeast(48.dp)
    Column(Modifier.fillMaxSize().safeDrawingPadding().testTag("match_root")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.match_back)) }
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
        }
        if (state.isLoading) {
            CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
        } else if (state.isMissing) {
            Text(stringResource(R.string.match_missing), Modifier.padding(24.dp))
        } else {
            Text(stringResource(if (state.isFinished) R.string.match_finished else R.string.match_round, state.roundNumber),
                Modifier.align(Alignment.CenterHorizontally).padding(8.dp), style = MaterialTheme.typography.titleLarge)
            if (state.isCorrectingRound) Text(stringResource(R.string.match_correcting_round, state.roundNumber), Modifier.padding(horizontal = 16.dp))
            if (state.pausedRoundNumbers.isNotEmpty()) Text(stringResource(R.string.match_paused_rounds,
                state.pausedRoundNumbers.joinToString()), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
            (state.scoreDialog?.indicator ?: state.indicator)?.let {
                Text(wildcardPreview(it), Modifier.align(Alignment.CenterHorizontally).padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodyMedium)
            }
            if (state.hasError && state.scoreDialog == null && state.picker == null) {
                Text(stringResource(R.string.match_save_error), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
            }
            if (state.isFinished) {
                Button(onClick = onViewResults, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text(stringResource(R.string.match_view_result))
                }
            }
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth().testTag("match_players"),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(state.players, key = { it.id.value }) { player ->
                    val canMove = state.canManagePlayers && !state.isSaving && state.playerName == null && state.picker == null && state.scoreDialog == null
                    val currentMove by rememberUpdatedState(onMovePlayer)
                    val currentCanMove by rememberUpdatedState(canMove)
                    val up = stringResource(R.string.setup_move_up, player.name)
                    val down = stringResource(R.string.setup_move_down, player.name)
                    val dragLabel = stringResource(R.string.player_drag, player.name)
                    var height by remember(player.id) { mutableFloatStateOf(1f) }
                    var drag by remember(player.id) { mutableFloatStateOf(0f) }
                    val index = state.players.indexOfFirst { it.id == player.id }
                    PlayerCard(player.name, player.total, player.isDealer, player.history,
                        onAddScore = if (player.canAddScore && !state.isSaving) ({ onAddScore(player.id) }) else null,
                        modifier = Modifier.fillMaxWidth().testTag("match_player_${player.id.value}")
                            .onSizeChanged { height = it.height.toFloat() }
                            .zIndex(if (drag != 0f) 1f else 0f)
                            .graphicsLayer { translationY = drag }
                            .semantics { customActions = if (canMove) buildList {
                                if (index > 0) add(CustomAccessibilityAction(up) { onMovePlayer(player.id, -1); true })
                                if (index < state.players.lastIndex) add(CustomAccessibilityAction(down) { onMovePlayer(player.id, 1); true })
                            } else emptyList() },
                        historyScrollState = historyScroll, showScoreAction = !state.isFinished,
                        historyColumnWidth = columnWidth,
                        onEditScore = if (!state.isSaving) ({ onEditScore(player.id, it) }) else null,
                        dragHandle = {
                            Box(Modifier.size(32.dp, 48.dp).testTag("match_drag_${player.id.value}")
                                .semantics { contentDescription = dragLabel; if (!canMove) disabled() }
                                .pointerInput(player.id) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = { drag = 0f }, onDragCancel = { drag = 0f },
                                        onDrag = { change, amount ->
                                            if (currentCanMove) { change.consume(); drag += amount.y }
                                        },
                                        onDragEnd = {
                                            val offset = (drag / height).roundToInt()
                                            drag = 0f
                                            if (currentCanMove && offset != 0) currentMove(player.id, offset)
                                        })
                                }, contentAlignment = Alignment.Center) {
                                Text("⠿", modifier = Modifier.clearAndSetSemantics {}, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        })
                }
                if (!state.isFinished) item(key = "add_player") {
                    OutlinedButton(onClick = onAddPlayer, enabled = state.canManagePlayers && !state.isSaving,
                        modifier = Modifier.fillMaxWidth().testTag("match_add_player")) {
                        Text(stringResource(R.string.setup_add_player))
                    }
                }
            }
        }
    }
    state.picker?.let { picker ->
        JokerPicker(picker.selected, state.isSaving, state.hasError, onSelectIndicator, onConfirmIndicator, onDismissDialog)
    }
    state.scoreDialog?.let { dialog ->
        ScoreDialog(dialog.playerName, dialog.roundNumber, wildcardPreview(dialog.indicator), dialog.value,
            onScoreChange, { onConfirmScore() }, onDismissDialog, onWentOut,
            wentOutEnabled = dialog.wentOutEnabled && !state.isSaving,
            enabled = !state.isSaving, hasSaveError = state.hasError,
            isCorrection = dialog.isCorrection, readOnly = dialog.readOnly, onDelete = onDeleteScore)
    }
    state.playerName?.let { name ->
        PlayerNameDialog(name, onPlayerNameChange, onConfirmPlayerName, onDismissDialog,
            supportingText = stringResource(R.string.match_late_player_help) +
                if (state.hasError) "\n" + stringResource(R.string.match_save_error) else "",
            enabled = !state.isSaving, confirmEnabled = name.isNotBlank())
    }
}
