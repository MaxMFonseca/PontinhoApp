package com.maxmfonseca.pontinho.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import com.maxmfonseca.pontinho.R

data class PlayerRoundHistory(
    val roundNumber: Int,
    val status: RoundStatusKind,
    val score: Int?,
    val canEdit: Boolean = false,
)

@Composable
fun PlayerCard(
    playerName: String,
    total: Int,
    isDealer: Boolean,
    history: List<PlayerRoundHistory>,
    onAddScore: (() -> Unit)?,
    modifier: Modifier = Modifier,
    historyScrollState: ScrollState = rememberScrollState(),
    showScoreAction: Boolean = true,
    historyColumnWidth: Dp = 48.dp,
    onEditScore: ((Int) -> Unit)? = null,
    dragHandle: (@Composable () -> Unit)? = null,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (dragHandle != null) dragHandle() else Text(
                text = "⠿",
                modifier = Modifier.padding(end = 8.dp).clearAndSetSemantics {},
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(
                modifier = Modifier
                    .width(132.dp)
                    .testTag("player_identity"),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = playerName,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("player_name"),
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = total.toString(),
                        modifier = Modifier
                            .width(IntrinsicSize.Max)
                            .testTag("player_total"),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
                if (isDealer) Text(
                    text = "☝ ${stringResource(R.string.deals_cards)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(historyScrollState)
                    .testTag("player_round_history"),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                history.forEach { round ->
                    val editLabel = stringResource(R.string.match_edit_score, playerName, round.roundNumber)
                    val editModifier = if (round.canEdit && onEditScore != null)
                        Modifier.clickable(onClickLabel = editLabel) { onEditScore(round.roundNumber) } else Modifier
                    Column(Modifier.width(historyColumnWidth), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.match_round_short, round.roundNumber), style = MaterialTheme.typography.labelSmall)
                        RoundStatus(round.status, Modifier.testTag("round_dot_${round.roundNumber}").then(editModifier))
                        Text(round.score?.toString() ?: "—", style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.testTag("round_score_${round.roundNumber}").then(editModifier))
                    }
                }
            }
            if (showScoreAction) {
                val actionLabel = stringResource(R.string.match_add_score, playerName)
                IconButton(
                    onClick = { onAddScore?.invoke() },
                    enabled = onAddScore != null,
                    modifier = Modifier.testTag("player_action").semantics { contentDescription = actionLabel },
                ) {
                    Text("+", style = MaterialTheme.typography.headlineSmall)
                }
            }
        }
    }
}
