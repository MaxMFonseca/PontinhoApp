package com.maxmfonseca.pontinho.ui.setup

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.maxmfonseca.pontinho.R
import com.maxmfonseca.pontinho.ui.components.PlayerNameDialog
import kotlin.math.abs

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
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .testTag("setup_root"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val backLabel = stringResource(R.string.match_back)
            TextButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = backLabel }) {
                Text(
                    "←",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.clearAndSetSemantics { },
                )
            }
            Text(
                text = stringResource(R.string.route_setup),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
            )
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.tertiaryContainer,
        ) {
            Row(
                modifier = Modifier.padding(18.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("ⓘ", style = MaterialTheme.typography.titleMedium, modifier = Modifier.clearAndSetSemantics { })
                Text(
                    text = stringResource(R.string.setup_first_player_help),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
        Text(
            stringResource(R.string.setup_players_heading),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        state.players.forEachIndexed { index, player ->
            key(player.draftId) {
                SetupPlayerRow(
                    player = player,
                    index = index,
                    playerCount = state.players.size,
                    onEdit = { onEditPlayer(player.draftId) },
                    onRemove = { onRemovePlayer(player.draftId) },
                    onMove = { direction -> onMovePlayer(player.draftId, direction) },
                )
            }
        }
        OutlinedButton(onClick = onAddPlayer, modifier = Modifier.fillMaxWidth()) {
            Text("＋", modifier = Modifier.clearAndSetSemantics { })
            Text(stringResource(R.string.setup_add_player))
        }
        OutlinedButton(
            onClick = onUseLastMatch,
            enabled = state.canUseLastMatch,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("⇄", modifier = Modifier.clearAndSetSemantics { })
            Text(stringResource(R.string.setup_use_last_match))
        }
        Text(
            text = stringResource(
                if (state.showNoPreviousMatchExplanation) R.string.setup_no_previous_match
                else R.string.setup_replace_helper,
            ),
            modifier = Modifier.align(Alignment.CenterHorizontally),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("ⓘ", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.clearAndSetSemantics { })
            Text(
                text = stringResource(R.string.setup_minimum_players),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.createError) {
            Text(stringResource(R.string.setup_create_error), color = MaterialTheme.colorScheme.error)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.cancel))
            }
            Button(
                onClick = onCreateMatch,
                enabled = state.canCreate,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.setup_create_match))
            }
        }
    }

    state.nameDialog?.let { dialog ->
        PlayerNameDialog(
            value = dialog.value,
            onValueChange = onDialogNameChange,
            onConfirm = onConfirmName,
            onDismiss = onDismissName,
            supportingText = if (dialog.showBlankError) stringResource(R.string.setup_blank_name_error) else null,
            duplicateNameError = dialog.duplicateNameError,
        )
    }

    if (state.showReplaceConfirmation) {
        AlertDialog(
            onDismissRequest = onDismissReplace,
            title = { Text(stringResource(R.string.setup_replace_title)) },
            text = { Text(stringResource(R.string.setup_replace_message)) },
            confirmButton = {
                Button(onClick = onConfirmReplace) { Text(stringResource(R.string.setup_replace_action)) }
            },
            dismissButton = {
                TextButton(onClick = onDismissReplace) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun SetupPlayerRow(
    player: DraftPlayer,
    index: Int,
    playerCount: Int,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
    onMove: (Int) -> Unit,
) {
    val moveUpLabel = stringResource(R.string.setup_move_up, player.displayName)
    val moveDownLabel = stringResource(R.string.setup_move_down, player.displayName)
    val removeLabel = stringResource(R.string.setup_remove_player, player.displayName)
    val editLabel = stringResource(R.string.player_edit, player.displayName)
    val dragLabel = stringResource(R.string.player_drag, player.displayName)
    var rowHeightPx by remember(player.draftId) { mutableFloatStateOf(0f) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("setup_player_${player.draftId}")
            .semantics {
                customActions = buildList {
                    if (index > 0) add(CustomAccessibilityAction(moveUpLabel) {
                        onMove(-1)
                        true
                    })
                    if (index < playerCount - 1) add(CustomAccessibilityAction(moveDownLabel) {
                        onMove(1)
                        true
                    })
                }
            }
            .onSizeChanged { rowHeightPx = it.height.toFloat() }
            .pointerInput(player.draftId) {
                var verticalDrag = 0f
                detectDragGesturesAfterLongPress(
                    onDragStart = { verticalDrag = 0f },
                    onDrag = { change, amount ->
                        change.consume()
                        verticalDrag += amount.y
                        val threshold = rowHeightPx.takeIf { it > 0f } ?: 48.dp.toPx()
                        while (abs(verticalDrag) >= threshold) {
                            onMove(if (verticalDrag < 0) -1 else 1)
                            verticalDrag += if (verticalDrag < 0) threshold else -threshold
                        }
                    },
                )
            },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("⠿", color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clearAndSetSemantics { contentDescription = dragLabel })
            TextButton(onClick = onEdit, modifier = Modifier.weight(1f).semantics { contentDescription = editLabel }) {
                Text(player.displayName, style = MaterialTheme.typography.titleMedium)
                Text("✎", style = MaterialTheme.typography.titleMedium, modifier = Modifier.clearAndSetSemantics {})
            }
            IconButton(
                onClick = onRemove,
                modifier = Modifier.semantics { contentDescription = removeLabel },
            ) {
                Text("×", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.clearAndSetSemantics { })
            }
        }
    }
}
