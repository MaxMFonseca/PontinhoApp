package com.maxmfonseca.pontinho.ui.history

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.maxmfonseca.pontinho.R
import com.maxmfonseca.pontinho.domain.model.MatchId
import com.maxmfonseca.pontinho.domain.repository.DailyStanding
import com.maxmfonseca.pontinho.domain.repository.HistoryMatch
import com.maxmfonseca.pontinho.ui.components.PontinhoInfoBanner
import com.maxmfonseca.pontinho.ui.components.PontinhoTopBar
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@Composable
fun HistoryScreen(
    state: HistoryUiState,
    onBack: () -> Unit,
    onSettings: () -> Unit,
    onNewMatch: () -> Unit,
    onOpenMatch: (MatchId) -> Unit,
    onToggleSummary: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val locale = Locale.forLanguageTag(LocalLocale.current.toLanguageTag())
    LazyColumn(
        modifier = modifier.fillMaxSize().safeDrawingPadding().testTag("history_root"),
        contentPadding = PaddingValues(bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { PontinhoTopBar(R.string.route_day_history, onBack = onBack, onSettings = onSettings) }
        item {
            Text(state.localDate.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale)),
                style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 20.dp))
        }
        item {
            Button(onClick = onNewMatch, modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth().sizeIn(minHeight = 48.dp)) {
                Icon(Icons.Filled.Add, null, Modifier.padding(end = 8.dp))
                Text(stringResource(R.string.home_new_match))
            }
        }
        item {
            val summaryState = stringResource(if (state.summaryExpanded) R.string.history_summary_expanded else R.string.history_summary_collapsed)
            OutlinedCard(onClick = onToggleSummary,
                modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth().sizeIn(minHeight = 48.dp)
                    .testTag("daily_summary_toggle").semantics { stateDescription = summaryState },
                shape = MaterialTheme.shapes.large) {
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.history_daily_summary), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Icon(if (state.summaryExpanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, null)
                }
            }
        }
        if (state.summaryExpanded) {
            if (state.wins.isEmpty() && state.penalties.isEmpty()) {
                item { PontinhoInfoBanner(R.string.history_summary_empty, Modifier.padding(horizontal = 16.dp)) }
            } else {
                item { DailyMetricCard(state.wins, victories = true) }
                item { DailyMetricCard(state.penalties, victories = false) }
            }
        }
        if (!state.isLoading && state.matches.isEmpty()) {
            item { PontinhoInfoBanner(R.string.history_empty, Modifier.padding(horizontal = 16.dp)) }
        } else {
            itemsIndexed(state.matches, key = { _, match -> match.id.value }) { index, match ->
                HistoryMatchCard(match, locale, onOpenMatch, Modifier.padding(horizontal = 16.dp).testTag("history_match_$index"))
            }
        }
    }
}

@Composable
private fun DailyMetricCard(rows: List<DailyStanding>, victories: Boolean) {
    val metric = if (victories) "wins" else "points"
    val label = stringResource(if (victories) R.string.history_wins else R.string.history_penalties)
    Card(Modifier.padding(horizontal = 16.dp).fillMaxWidth().testTag("daily_${metric}_card"),
        colors = CardDefaults.cardColors(
            containerColor = if (victories) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = if (victories) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onTertiaryContainer,
        ), shape = MaterialTheme.shapes.large) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (victories) Icons.Filled.Star else Icons.AutoMirrored.Filled.List, null, Modifier.size(24.dp))
                Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).semantics { heading() })
            }
            rows.forEach { row ->
                Column(Modifier.fillMaxWidth().testTag("daily_${metric}_${row.playerKey.value}").semantics(mergeDescendants = true) {},
                    verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(row.displayName, style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.history_metric_value, label, row.value), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun HistoryMatchCard(match: HistoryMatch, locale: Locale, onOpenMatch: (MatchId) -> Unit, modifier: Modifier = Modifier) {
    Card(onClick = { onOpenMatch(match.id) }, modifier = modifier.fillMaxWidth().sizeIn(minHeight = 48.dp),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = BorderStroke(1.dp, if (match.isFinished) MaterialTheme.colorScheme.outlineVariant else MaterialTheme.colorScheme.tertiary),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val time = Instant.ofEpochMilli(match.createdAtEpochMillis).atZone(ZoneId.of(match.timeZoneId)).toLocalTime()
                .format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale))
            Text(time, style = MaterialTheme.typography.titleMedium)
            Text(stringResource(if (match.isFinished) R.string.history_completed else R.string.history_in_progress),
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!match.isFinished) PontinhoInfoBanner(R.string.history_provisional)
            match.players.forEach { player ->
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(player.displayName, style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.results_total, player.total), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                    if (player.isWinner) Text(stringResource(R.string.results_winner), color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelLarge, modifier = Modifier.testTag("history_winner_${player.playerId.value}"))
                    if (player.isHighestPenalty) Text(stringResource(R.string.results_highest_penalty), color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelLarge, modifier = Modifier.testTag("history_highest_${player.playerId.value}"))
                }
            }
        }
    }
}
