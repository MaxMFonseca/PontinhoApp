package com.maxmfonseca.pontinho.ui.results

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.maxmfonseca.pontinho.R
import com.maxmfonseca.pontinho.ui.theme.LocalReducedMotion

@Composable
fun ResultsScreen(
    state: ResultsUiState,
    onNewMatch: () -> Unit,
    onOpenMatch: () -> Unit,
    onOpenHistory: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(enabled = state.isFinished && !state.isLeaving, onBack = onOpenHistory)
    LazyColumn(
        modifier = modifier.fillMaxSize().testTag("results_root"),
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item {
            if (!LocalReducedMotion.current && state.isFinished) {
                Text("◆  ◆  ◆", color = MaterialTheme.colorScheme.secondary, modifier = Modifier.testTag("results_celebration"))
            }
            Text(stringResource(R.string.match_finished), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Black)
            Text(stringResource(R.string.results_lower_better), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(state.players, key = { it.playerId.value }) { player ->
            val container = when {
                player.isWinner -> MaterialTheme.colorScheme.primaryContainer
                player.isHighestPenalty -> MaterialTheme.colorScheme.errorContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = container), shape = RoundedCornerShape(18.dp)) {
                Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(player.rank.toString(), style = MaterialTheme.typography.titleLarge)
                    Column(Modifier.weight(1f)) {
                        Text(player.displayName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        if (player.isWinner) Text("🏆 ${stringResource(R.string.results_winner)}",
                            modifier = Modifier.testTag("result_winner_${player.playerId.value}"))
                        if (player.isHighestPenalty) Text(stringResource(R.string.results_highest_penalty),
                            modifier = Modifier.testTag("result_highest_${player.playerId.value}"))
                    }
                    Text(player.total.toString(), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
                }
            }
        }
        item {
            Button(onClick = onNewMatch, enabled = state.isFinished && !state.isLeaving, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.results_new_match))
            }
            OutlinedButton(onClick = onOpenMatch, enabled = state.isFinished && !state.isLeaving, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.results_details))
            }
            TextButton(onClick = onOpenHistory, enabled = state.isFinished && !state.isLeaving, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.results_back_history))
            }
        }
    }
}
