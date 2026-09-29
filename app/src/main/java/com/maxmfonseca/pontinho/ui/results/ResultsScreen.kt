package com.maxmfonseca.pontinho.ui.results

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.maxmfonseca.pontinho.R
import com.maxmfonseca.pontinho.ui.components.PontinhoTopBar

@Composable
fun ResultsScreen(
    state: ResultsUiState,
    onNewMatch: () -> Unit,
    onOpenMatch: () -> Unit,
    onOpenHistory: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val actionsEnabled = state.isFinished && !state.isLeaving
    BackHandler(enabled = actionsEnabled, onBack = onOpenHistory)
    LazyColumn(
        modifier = modifier.fillMaxSize().safeDrawingPadding().testTag("results_root"),
        contentPadding = PaddingValues(bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { PontinhoTopBar(R.string.match_finished) }
        item {
            Text(stringResource(R.string.results_lower_better), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp))
        }
        items(state.players, key = { it.playerId.value }) { player ->
            val accent = when {
                player.isHighestPenalty -> MaterialTheme.colorScheme.error
                player.isWinner -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            Card(Modifier.padding(horizontal = 16.dp).fillMaxWidth().testTag("result_player_${player.playerId.value}"),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                border = BorderStroke(1.dp, if (player.isWinner || player.isHighestPenalty) accent else MaterialTheme.colorScheme.outlineVariant),
                shape = MaterialTheme.shapes.large) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                        Text(player.rank.toString(), style = MaterialTheme.typography.titleMedium, color = accent)
                        Text(player.displayName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    }
                    Text(stringResource(R.string.results_total, player.total), style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold, color = accent)
                    if (player.isWinner) ResultStatus(stringResource(R.string.results_winner),
                        Modifier.testTag("result_winner_${player.playerId.value}"), highestPenalty = false)
                    if (player.isHighestPenalty) ResultStatus(stringResource(R.string.results_highest_penalty),
                        Modifier.testTag("result_highest_${player.playerId.value}"), highestPenalty = true)
                }
            }
        }
        item {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onNewMatch, enabled = actionsEnabled, modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp)) {
                    Icon(Icons.Filled.Add, null, Modifier.padding(end = 8.dp))
                    Text(stringResource(R.string.results_new_match))
                }
                OutlinedButton(onClick = onOpenMatch, enabled = actionsEnabled, modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp)) {
                    Text(stringResource(R.string.results_details))
                }
                TextButton(onClick = onOpenHistory, enabled = actionsEnabled, modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp)) {
                    Text(stringResource(R.string.results_back_history))
                }
            }
        }
    }
}

@Composable
private fun ResultStatus(label: String, modifier: Modifier, highestPenalty: Boolean) {
    val color = if (highestPenalty) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (highestPenalty) Icons.Filled.Info else Icons.Filled.CheckCircle, null, Modifier.size(20.dp), tint = color)
        Text(label, modifier, style = MaterialTheme.typography.labelLarge, color = color)
    }
}
