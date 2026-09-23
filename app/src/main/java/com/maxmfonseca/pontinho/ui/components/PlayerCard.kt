package com.maxmfonseca.pontinho.ui.components

import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.unit.dp
import com.maxmfonseca.pontinho.R

data class PlayerRoundHistory(
    val roundNumber: Int,
    val status: RoundStatusKind,
    val score: Int?,
)

@Composable
fun PlayerCard(
    playerName: String,
    total: Int,
    isDealer: Boolean,
    history: List<PlayerRoundHistory>,
    onAddScore: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "⠿",
                modifier = Modifier.padding(end = 8.dp),
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
                    .horizontalScroll(rememberScrollState())
                    .testTag("player_round_history"),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                history.forEach { round ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        RoundStatus(round.status)
                        round.score?.let { Text(it.toString(), style = MaterialTheme.typography.labelMedium) }
                    }
                }
            }
            IconButton(
                onClick = { onAddScore?.invoke() },
                enabled = onAddScore != null,
                modifier = Modifier.testTag("player_action"),
            ) {
                Text("+", style = MaterialTheme.typography.headlineSmall)
            }
        }
    }
}
