package com.maxmfonseca.pontinho.ui.setup

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.maxmfonseca.pontinho.R
import com.maxmfonseca.pontinho.ui.components.ImmediateDragHandle
import com.maxmfonseca.pontinho.ui.components.PlayerNameDialog
import com.maxmfonseca.pontinho.ui.components.PontinhoInfoBanner
import com.maxmfonseca.pontinho.ui.components.PontinhoTopBar
import kotlinx.coroutines.isActive

@Composable
fun MatchSetupScreen(
    state: MatchSetupUiState,
    onBack: () -> Unit,
    onAddPlayer: () -> Unit,
    onEditPlayer: (String) -> Unit,
    onRemovePlayer: (String) -> Unit,
    onMovePlayer: (String, Int) -> Unit,
    onUseLastMatch: () -> Unit,
    onDismissReplace: () -> Unit,
    onConfirmReplace: () -> Unit,
    onDialogNameChange: (String) -> Unit,
    onDismissName: () -> Unit,
    onConfirmName: () -> Unit,
    onCreateMatch: () -> Unit,
    modifier: Modifier = Modifier,
    onReorderPlayers: (List<String>) -> Unit = {},
) {
    val listState = rememberLazyListState()
    var previewIds by remember { mutableStateOf<List<String>?>(null) }
    var draggedId by remember { mutableStateOf<String?>(null) }
    var draggedTop by remember { mutableFloatStateOf(0f) }
    var draggedHeight by remember { mutableFloatStateOf(0f) }
    var pointerY by remember { mutableFloatStateOf(0f) }
    var firstDragDelta by remember { mutableStateOf(false) }
    val edgeSize = with(LocalDensity.current) { 64.dp.toPx() }
    val maxScrollPerFrame = with(LocalDensity.current) { 18.dp.toPx() }
    val handleHalfHeight = with(LocalDensity.current) { 24.dp.toPx() }
    val playersById = state.players.associateBy { it.draftId }
    val visualPlayers = previewIds?.mapNotNull { playersById[it] } ?: state.players

    fun cancelDrag() { draggedId = null; previewIds = null }

    fun updatePreview() {
        val id = draggedId ?: return
        val order = previewIds ?: return
        val from = order.indexOf(id)
        val center = draggedTop + draggedHeight / 2f
        val crossed = listState.layoutInfo.visibleItemsInfo.filter { item ->
            val index = order.indexOf(item.key)
            index >= 0 && index != from &&
                if (index > from) center > item.offset + item.size / 2f
                else center < item.offset + item.size / 2f
        }
        val target = crossed.minByOrNull { kotlin.math.abs(center - (it.offset + it.size / 2f)) } ?: return
        val to = order.indexOf(target.key)
        // Keep the viewport stationary when its first visible player's index changes.
        listState.requestScrollToItem(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
        previewIds = order.toMutableList().apply { add(to, removeAt(from)) }
    }

    LaunchedEffect(state.players, state.isCreating) {
        if (state.isCreating || previewIds?.toSet()?.let { it != playersById.keys } == true) cancelDrag()
    }
    val scrollSpeed by remember(edgeSize, maxScrollPerFrame) { derivedStateOf {
        val layout = listState.layoutInfo
        val top = layout.viewportStartOffset + edgeSize
        val bottom = layout.viewportEndOffset - edgeSize
        when {
            draggedId == null -> 0f
            pointerY < top -> -((top - pointerY) / edgeSize).coerceIn(0f, 1f) * maxScrollPerFrame
            pointerY > bottom -> ((pointerY - bottom) / edgeSize).coerceIn(0f, 1f) * maxScrollPerFrame
            else -> 0f
        }
    } }
    LaunchedEffect(draggedId, scrollSpeed) {
        if (draggedId == null || scrollSpeed == 0f) return@LaunchedEffect
        while (isActive) {
            withFrameNanos { }
            if (listState.scrollBy(scrollSpeed) == 0f) break
            updatePreview()
        }
    }

    Column(modifier.fillMaxSize().safeDrawingPadding()) {
        if (state.reorderError) Text(
            stringResource(R.string.setup_reorder_error),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
        )
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).testTag("setup_root"),
            contentPadding = PaddingValues(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            userScrollEnabled = draggedId == null,
        ) {
            item(key = "top_bar") { PontinhoTopBar(R.string.route_setup, onBack = onBack, enabled = !state.isCreating) }
            item(key = "help") { PontinhoInfoBanner(R.string.setup_first_player_help, Modifier.padding(horizontal = 16.dp)) }
            item(key = "heading") {
                Text(stringResource(R.string.setup_players_heading), style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 20.dp).semantics { heading() })
            }
            itemsIndexed(visualPlayers, key = { _, player -> player.draftId }) { index, player ->
                val dragging = draggedId == player.draftId
                SetupPlayerRow(
                    player = player, index = index, playerCount = visualPlayers.size, enabled = !state.isCreating,
                    onEdit = { onEditPlayer(player.draftId) }, onRemove = { onRemovePlayer(player.draftId) },
                    onMove = { onMovePlayer(player.draftId, it) },
                    onDragStart = { offsetY ->
                        val item = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == player.draftId }
                        if (item != null) {
                            draggedId = player.draftId
                            previewIds = state.players.map { it.draftId }
                            draggedTop = item.offset.toFloat()
                            draggedHeight = item.size.toFloat()
                            pointerY = item.offset + item.size / 2f + offsetY - handleHalfHeight
                            firstDragDelta = true
                        }
                    },
                    onDragDelta = { amount ->
                        draggedTop += amount
                        // onDragStart already receives the slop-triggering pointer position.
                        if (firstDragDelta) firstDragDelta = false else pointerY += amount
                        updatePreview()
                    },
                    onDragEnd = {
                        val order = previewIds
                        cancelDrag()
                        if (order != null) onReorderPlayers(order)
                    },
                    onDragCancel = ::cancelDrag,
                    modifier = Modifier.zIndex(if (dragging) 1f else 0f)
                        .graphicsLayer {
                            val rowOffset = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == player.draftId }?.offset ?: 0
                            translationY = if (dragging) draggedTop - rowOffset else 0f
                        }
                        .padding(horizontal = 16.dp),
                )
            }
            item(key = "add") {
                OutlinedButton(onClick = onAddPlayer, enabled = !state.isCreating,
                    modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth().sizeIn(minHeight = 48.dp).testTag("setup_add_player")) {
                    Icon(Icons.Filled.Add, null, Modifier.padding(end = 8.dp))
                    Text(stringResource(R.string.setup_add_player))
                }
            }
            item(key = "reuse") {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onUseLastMatch, enabled = state.canUseLastMatch && !state.isCreating,
                        modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp).testTag("setup_use_last_match")) {
                        Icon(Icons.Filled.Refresh, null, Modifier.padding(end = 8.dp))
                        Text(stringResource(R.string.setup_use_last_match), modifier = Modifier.weight(1f))
                    }
                    Text(stringResource(if (state.showNoPreviousMatchExplanation) R.string.setup_no_previous_match else R.string.setup_replace_helper),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item(key = "create") {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.setup_minimum_players), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (state.createError) Text(stringResource(R.string.setup_create_error), color = MaterialTheme.colorScheme.error)
                    Button(onClick = onCreateMatch, enabled = state.canCreate,
                        modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 56.dp).testTag("setup_create_match")) {
                        if (state.isCreating) CircularProgressIndicator(Modifier.size(24.dp).padding(end = 8.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.setup_create_match), style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.testTag("setup_create_match_label"))
                    }
                    TextButton(onClick = onBack, enabled = !state.isCreating,
                        modifier = Modifier.align(Alignment.CenterHorizontally).sizeIn(minHeight = 48.dp)) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            }
        }
    }

    state.nameDialog?.let { dialog ->
        PlayerNameDialog(value = dialog.value, onValueChange = onDialogNameChange,
            onConfirm = onConfirmName, onDismiss = onDismissName,
            supportingText = if (dialog.showBlankError) stringResource(R.string.setup_blank_name_error) else null,
            duplicateNameError = dialog.duplicateNameError)
    }
    if (state.showReplaceConfirmation) {
        AlertDialog(onDismissRequest = onDismissReplace,
            title = { Text(stringResource(R.string.setup_replace_title)) },
            text = { Text(stringResource(R.string.setup_replace_message)) },
            confirmButton = { Button(onClick = onConfirmReplace) { Text(stringResource(R.string.setup_replace_action)) } },
            dismissButton = { TextButton(onClick = onDismissReplace) { Text(stringResource(R.string.cancel)) } })
    }
}

@Composable
private fun SetupPlayerRow(
    player: DraftPlayer,
    index: Int,
    playerCount: Int,
    enabled: Boolean,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
    onMove: (Int) -> Unit,
    onDragStart: (Float) -> Unit,
    onDragDelta: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val moveUpLabel = stringResource(R.string.setup_move_up, player.displayName)
    val moveDownLabel = stringResource(R.string.setup_move_down, player.displayName)
    val removeLabel = stringResource(R.string.setup_remove_player, player.displayName)
    val editLabel = stringResource(R.string.player_edit, player.displayName)
    Card(modifier.fillMaxWidth().testTag("setup_player_${player.draftId}").semantics {
        customActions = if (!enabled) emptyList() else buildList {
            if (index > 0) add(CustomAccessibilityAction(moveUpLabel) { onMove(-1); true })
            if (index < playerCount - 1) add(CustomAccessibilityAction(moveDownLabel) { onMove(1); true })
        }
    }, shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            ImmediateDragHandle(enabled, stringResource(R.string.player_drag, player.displayName),
                onDragStart = { onDragStart(it.y) }, onDragDelta = onDragDelta, onDragEnd = onDragEnd, onDragCancel = onDragCancel)
            TextButton(onClick = onEdit, enabled = enabled, shape = MaterialTheme.shapes.medium,
                modifier = Modifier.weight(1f).sizeIn(minHeight = 48.dp).semantics { contentDescription = editLabel }) {
                Text(player.displayName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Icon(Icons.Filled.Edit, null, Modifier.padding(start = 8.dp).size(18.dp))
            }
            IconButton(onClick = onRemove, enabled = enabled,
                modifier = Modifier.size(48.dp).semantics { contentDescription = removeLabel }) {
                Icon(Icons.Filled.Close, null, tint = MaterialTheme.colorScheme.error.copy(alpha = if (enabled) 1f else 0.38f))
            }
        }
    }
}
