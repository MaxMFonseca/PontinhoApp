package com.maxmfonseca.pontinho.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.maxmfonseca.pontinho.R

data class PlayerRoundHistory(val roundNumber: Int, val status: RoundStatusKind, val score: Int?, val canEdit: Boolean = false)

@Composable
fun PlayerCard(
    playerName: String, total: Int, isDealer: Boolean, history: List<PlayerRoundHistory>, onAddScore: (() -> Unit)?,
    modifier: Modifier = Modifier, historyScrollState: ScrollState = rememberScrollState(),
    showScoreAction: Boolean = true, historyColumnWidth: Dp = 48.dp,
    onEditScore: ((Int) -> Unit)? = null, dragHandle: (@Composable () -> Unit)? = null,
) {
    Card(modifier = modifier, shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            dragHandle?.invoke()
            Column(Modifier.weight(1f).padding(horizontal = 8.dp).testTag("player_identity")) {
                Text(playerName, style = MaterialTheme.typography.titleMedium, maxLines = 1, softWrap = false,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("player_name"))
                Text(total.toString(), style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.width(IntrinsicSize.Max).testTag("player_total"))
            }
            if (isDealer) Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.secondaryContainer) {
                Icon(painterResource(R.drawable.ic_dealer_hand), stringResource(R.string.deals_cards),
                    Modifier.padding(8.dp).size(20.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            if (showScoreAction) {
                val label = stringResource(R.string.match_add_score, playerName)
                IconButton(onClick = { onAddScore?.invoke() }, enabled = onAddScore != null,
                    modifier = Modifier.size(48.dp).testTag("player_action").semantics { contentDescription = label }) {
                    Icon(Icons.Filled.Add, null)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
            .horizontalScroll(historyScrollState).testTag("player_round_history"),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            history.forEach { round ->
                val editLabel = stringResource(R.string.match_edit_score, playerName, round.roundNumber)
                val editModifier = if (round.canEdit && onEditScore != null)
                    Modifier.clickable(onClickLabel = editLabel) { onEditScore(round.roundNumber) } else Modifier
                Column(Modifier.width(historyColumnWidth.coerceAtLeast(48.dp)).heightIn(min = 48.dp)
                    .testTag("round_cell_${round.roundNumber}").then(editModifier).semantics(mergeDescendants = true) {},
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.match_round_short, round.roundNumber), style = MaterialTheme.typography.labelSmall)
                    RoundStatus(round.status, Modifier.testTag("round_dot_${round.roundNumber}"))
                    Text(round.score?.toString() ?: "—", style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.testTag("round_score_${round.roundNumber}"))
                }
            }
        }
    }
}
