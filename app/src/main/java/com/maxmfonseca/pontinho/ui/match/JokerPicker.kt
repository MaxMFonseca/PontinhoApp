package com.maxmfonseca.pontinho.ui.match

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.maxmfonseca.pontinho.R
import com.maxmfonseca.pontinho.domain.model.*

@Composable
fun JokerPicker(selected: Indicator?, isSaving: Boolean, hasError: Boolean,
    onSelect: (Indicator) -> Unit, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        title = { Text(stringResource(R.string.indicator_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LazyColumn(Modifier.heightIn(max = 360.dp).testTag("joker_options")) {
                    CardColor.entries.forEach { color ->
                        item { Text(colorLabel(color), style = MaterialTheme.typography.titleMedium) }
                        items(Rank.entries.chunked(4)) { ranks ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                ranks.forEach { rank ->
                                    val indicator = Indicator(rank, color)
                                    val label = "${rankLabel(rank)} ${colorLabel(color)}"
                                    FilterChip(selected == indicator, { onSelect(indicator) },
                                        label = { Text(rankLabel(rank)) }, enabled = !isSaving,
                                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                                            .testTag("indicator_${color.name}_${rank.name}")
                                            .semantics { contentDescription = label })
                                }
                                repeat(4 - ranks.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                }
                selected?.let { Text(wildcardPreview(it), Modifier.testTag("joker_preview")) }
                if (hasError) Text(stringResource(R.string.match_save_error), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { Button(onClick = onConfirm, enabled = selected != null && !isSaving) { Text(stringResource(R.string.confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !isSaving) { Text(stringResource(R.string.cancel)) } },
    )
}

internal fun rankLabel(rank: Rank): String = when (rank) {
    Rank.ACE -> "A"
    Rank.JACK -> "J"
    Rank.QUEEN -> "Q"
    Rank.KING -> "K"
    else -> rank.canonicalValue.toString()
}

@Composable internal fun colorLabel(color: CardColor) = stringResource(
    if (color == CardColor.RED) R.string.indicator_red else R.string.indicator_black)

@Composable internal fun wildcardPreview(indicator: Indicator): String {
    val rank = rankLabel(indicator.rank.next())
    val first = if (indicator.color == CardColor.RED) "$rank♥" else "$rank♠"
    val second = if (indicator.color == CardColor.RED) "$rank♦" else "$rank♣"
    return stringResource(R.string.indicator_preview, rankLabel(indicator.rank), colorLabel(indicator.color), first, second)
}
