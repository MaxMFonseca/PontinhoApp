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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
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
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp)) {
            val cardMaxWidth = maxWidth
            val measurer = rememberTextMeasurer()
            val density = LocalDensity.current
            val nameStyle = MaterialTheme.typography.titleLarge
            val totalStyles = listOf(MaterialTheme.typography.headlineMedium, MaterialTheme.typography.headlineSmall,
                MaterialTheme.typography.titleLarge,
                MaterialTheme.typography.titleMedium, MaterialTheme.typography.bodyMedium, MaterialTheme.typography.labelMedium)
            fun textWidth(text: String, style: TextStyle) = with(density) {
                measurer.measure(text, style, softWrap = false, maxLines = 1).size.width.toDp()
            }
            val nameWidth = textWidth(playerName, nameStyle).coerceAtMost(100.dp)
            val dealerWidth = if (isDealer) 24.dp else 0.dp
            val controlsWidth = (if (dragHandle != null) 48.dp else 0.dp) +
                (if (showScoreAction) 48.dp else 0.dp)
            val compact = controlsWidth + 24.dp + nameWidth + dealerWidth + 8.dp +
                textWidth(total.toString(), totalStyles.first()) <= cardMaxWidth
            // Give the identity the full card width before using a smaller, still-scaled text style.
            val totalStyle = if (compact) totalStyles.first() else totalStyles.firstOrNull {
                textWidth(total.toString(), it) <= cardMaxWidth - 8.dp - nameWidth - dealerWidth - 8.dp
            } ?: totalStyles.last()
            val totalWidth = textWidth(total.toString(), totalStyle)
            val identity: @Composable (Modifier, Dp) -> Unit = { identityModifier, availableWidth ->
                val nameSlotWidth = (availableWidth - totalWidth - dealerWidth - 8.dp).coerceAtLeast(0.dp)
                val renderedNameWidth = textWidth(playerName, nameStyle).coerceAtMost(nameSlotWidth)
                Row(identityModifier.testTag("player_identity"), verticalAlignment = Alignment.CenterVertically) {
                    Text(playerName, style = nameStyle, maxLines = 1, softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.width(renderedNameWidth).alignByBaseline().testTag("player_name"))
                    if (isDealer) {
                        Spacer(Modifier.width(4.dp))
                        Icon(painterResource(R.drawable.ic_dealer_hand), stringResource(R.string.deals_cards),
                            Modifier.size(20.dp).testTag("dealer_hand"), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.weight(1f))
                    Text(total.toString(), style = totalStyle, maxLines = 1, softWrap = false,
                        modifier = Modifier.width(IntrinsicSize.Max).alignByBaseline().testTag("player_total"))
                }
            }
            val trailingControls: @Composable () -> Unit = {
                if (showScoreAction) {
                    val label = stringResource(R.string.match_add_score, playerName)
                    IconButton(onClick = { onAddScore?.invoke() }, enabled = onAddScore != null,
                        modifier = Modifier.size(48.dp).testTag("player_action").semantics { contentDescription = label }) {
                        Icon(Icons.Filled.Add, null)
                    }
                }
            }
            if (compact) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    dragHandle?.invoke()
                    identity(Modifier.weight(1f).padding(horizontal = 8.dp),
                        cardMaxWidth - controlsWidth - 16.dp)
                    trailingControls()
                }
            } else {
                Column(Modifier.fillMaxWidth()) {
                    identity(Modifier.fillMaxWidth().padding(horizontal = 4.dp), cardMaxWidth - 8.dp)
                    if (controlsWidth > 0.dp) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        dragHandle?.invoke()
                        Spacer(Modifier.weight(1f))
                        trailingControls()
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
            .horizontalScroll(historyScrollState).testTag("player_round_history"),
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            history.forEach { round ->
                val editLabel = stringResource(R.string.match_edit_score, playerName, round.roundNumber)
                val editModifier = if (round.canEdit && onEditScore != null)
                    Modifier.clickable(onClickLabel = editLabel) { onEditScore(round.roundNumber) } else Modifier
                Column(Modifier.width(historyColumnWidth.coerceAtLeast(48.dp)).heightIn(min = 48.dp)
                    .testTag("round_cell_${round.roundNumber}").then(editModifier).semantics(mergeDescendants = true) {},
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    RoundStatus(round.status, Modifier.testTag("round_dot_${round.roundNumber}"))
                    Text(round.score?.toString() ?: "—", style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.testTag("round_score_${round.roundNumber}"))
                }
            }
        }
    }
}
