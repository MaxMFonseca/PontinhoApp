package com.maxmfonseca.pontinho.ui.match

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.maxmfonseca.pontinho.R
import com.maxmfonseca.pontinho.ui.components.EmptyCard
import com.maxmfonseca.pontinho.ui.components.JokerCard
import com.maxmfonseca.pontinho.ui.components.shortRankLabel

@Composable
fun JokerHistoryRow(jokers: List<RoundJokerUi>, activeRoundNumber: Int,
    onJokerClick: (Int) -> Unit, modifier: Modifier = Modifier) {
    val ordered = remember(jokers) { jokers.sortedBy { it.roundNumber } }
    val cardWidth = jokerCardWidth()
    val spacing = 8.dp
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.match_jokers), style = MaterialTheme.typography.labelMedium)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val contentWidth = cardWidth * ordered.size + spacing * (ordered.size - 1).coerceAtLeast(0)
            val overflowing = contentWidth > maxWidth
            val scroll = rememberScrollState()
            // Finished matches name the next ordinal without adding a future joker slot.
            val activeIndex = ordered.indexOfFirst { it.roundNumber == activeRoundNumber }
                .takeIf { it >= 0 } ?: ordered.lastIndex
            // Use the same rounded dimensions as Row layout; ScrollState clamps either end.
            // Corrections can activate an older round while later rounds remain in the history.
            val activeOffset = with(LocalDensity.current) {
                (activeIndex * (cardWidth.roundToPx() + spacing.roundToPx()) -
                    (maxWidth.roundToPx() - cardWidth.roundToPx()) / 2).coerceAtLeast(0)
            }
            LaunchedEffect(ordered, activeRoundNumber, overflowing, scroll.maxValue, activeOffset) {
                if (overflowing && activeIndex >= 0) scroll.scrollTo(activeOffset)
            }
            Row(Modifier.fillMaxWidth().testTag("joker_history")
                .then(if (overflowing) Modifier.horizontalScroll(scroll) else Modifier),
                horizontalArrangement = Arrangement.spacedBy(spacing, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically) {
                ordered.forEach { slot ->
                    key(slot.roundNumber) {
                        val indicator = slot.indicator
                        val description = if (indicator == null) stringResource(R.string.joker_round_empty, slot.roundNumber)
                            else stringResource(R.string.joker_round_defined, slot.roundNumber,
                                shortRankLabel(indicator.rank.next()), colorLabel(indicator.color))
                        Box(Modifier.width(cardWidth).heightIn(min = 48.dp)
                            .testTag("joker_round_${slot.roundNumber}")
                            .clickable(role = Role.Button) { onJokerClick(slot.roundNumber) }
                            .semantics(mergeDescendants = true) {}, contentAlignment = Alignment.Center) {
                            if (indicator == null) EmptyCard(description, Modifier.fillMaxWidth())
                            else JokerCard(indicator.rank.next(), indicator.color, description, Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }
    }
}

/** Keep the two-digit rank readable without enlarging the whole history by the font scale. */
@Composable
internal fun jokerCardWidth(): androidx.compose.ui.unit.Dp {
    val rankWidth = rememberTextMeasurer().measure(AnnotatedString("10"),
        MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold)).size.width
    return with(LocalDensity.current) { rankWidth.toDp() + 12.dp }.coerceAtLeast(48.dp)
}
