package com.maxmfonseca.pontinho.ui.match

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.maxmfonseca.pontinho.R
import com.maxmfonseca.pontinho.domain.model.ParticipantId
import com.maxmfonseca.pontinho.ui.components.PontinhoTopBar

@Composable
fun ManagePlayersScreen(state: ManagePlayersState, onBack: () -> Unit, onSelect: (ParticipantId) -> Unit,
    onCancel: () -> Unit, onConfirm: () -> Unit) {
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        PontinhoTopBar(R.string.match_remove_player, onBack = onBack)
        if (!state.canRemove && state.players.size <= 2) Text(stringResource(R.string.match_minimum_players), Modifier.padding(16.dp))
        if (state.hasError) Text(stringResource(R.string.match_save_error), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(state.players, key = { it.id.value }) { player ->
                Card(onClick = { onSelect(player.id) }, enabled = state.canRemove && !state.isSaving && state.selected == null,
                    modifier = Modifier.fillMaxWidth().testTag("remove_player_${player.id.value}")) {
                    Text(player.displayName, Modifier.padding(16.dp), style = MaterialTheme.typography.titleLarge)
                }
            }
        }
    }
    state.selected?.let { player ->
        AlertDialog(onDismissRequest = onCancel, title = { Text(stringResource(R.string.match_remove_title)) },
            text = { Column { Text(stringResource(R.string.match_remove_message, player.displayName)); if (state.hasError) Text(stringResource(R.string.match_save_error), color = MaterialTheme.colorScheme.error) } },
            confirmButton = { TextButton(onClick = onConfirm, enabled = !state.isSaving) { Text(stringResource(R.string.match_remove_confirm)) } },
            dismissButton = { TextButton(onClick = onCancel, enabled = !state.isSaving) { Text(stringResource(R.string.cancel)) } })
    }
}
