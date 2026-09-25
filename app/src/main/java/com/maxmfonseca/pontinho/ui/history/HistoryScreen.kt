package com.maxmfonseca.pontinho.ui.history

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import com.maxmfonseca.pontinho.R
import com.maxmfonseca.pontinho.domain.model.MatchId
import com.maxmfonseca.pontinho.domain.repository.DailyStanding
import com.maxmfonseca.pontinho.domain.repository.HistoryMatch
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
        modifier = modifier.fillMaxSize().testTag("history_root"),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text(stringResource(R.string.match_back)) }
                TextButton(onClick = onSettings) { Text(stringResource(R.string.route_settings)) }
            }
            Text(
                state.localDate.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale)),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
        }
        item {
            Button(onClick = onNewMatch, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.home_new_match))
            }
        }
        item {
            val summaryState = stringResource(
                if (state.summaryExpanded) R.string.history_summary_expanded else R.string.history_summary_collapsed,
            )
            Card(
                onClick = onToggleSummary,
                modifier = Modifier.fillMaxWidth().testTag("daily_summary_toggle").semantics {
                    stateDescription = summaryState
                },
                shape = RoundedCornerShape(18.dp),
            ) {
                Row(Modifier.fillMaxWidth().padding(18.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.history_daily_summary), fontWeight = FontWeight.Bold)
                    Text(if (state.summaryExpanded) "⌃" else "⌄")
                }
                if (state.summaryExpanded) DailySummary(state.wins, state.penalties)
            }
        }
        if (!state.isLoading && state.matches.isEmpty()) {
            item { Text(stringResource(R.string.history_empty)) }
        } else {
            itemsIndexed(state.matches, key = { _, match -> match.id.value }) { index, match ->
                HistoryMatchCard(match, locale, onOpenMatch, Modifier.testTag("history_match_$index"))
            }
        }
    }
}

@Composable
private fun DailySummary(wins: List<DailyStanding>, penalties: List<DailyStanding>) {
    Column(Modifier.padding(start = 18.dp, end = 18.dp, bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (wins.isEmpty() && penalties.isEmpty()) Text(stringResource(R.string.history_summary_empty))
        if (wins.isNotEmpty()) {
            Text(stringResource(R.string.history_wins), style = MaterialTheme.typography.titleMedium)
            wins.forEachIndexed { index, row -> StandingRow(index, row) }
        }
        if (penalties.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.history_penalties), style = MaterialTheme.typography.titleMedium)
            penalties.forEachIndexed { index, row -> StandingRow(index, row) }
        }
    }
}

@Composable
private fun StandingRow(index: Int, row: DailyStanding) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("${index + 1}. ${row.displayName}")
        Text(row.value.toString(), fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun HistoryMatchCard(match: HistoryMatch, locale: Locale, onOpenMatch: (MatchId) -> Unit, modifier: Modifier = Modifier) {
    Card(onClick = { onOpenMatch(match.id) }, modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val time = Instant.ofEpochMilli(match.createdAtEpochMillis).atZone(ZoneId.of(match.timeZoneId)).toLocalTime()
                .format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(time, fontWeight = FontWeight.Bold)
                Text(if (match.isFinished) stringResource(R.string.history_completed) else stringResource(R.string.history_in_progress))
            }
            if (!match.isFinished) Text(stringResource(R.string.history_provisional), color = MaterialTheme.colorScheme.onSurfaceVariant)
            match.players.forEach { player ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text(player.displayName)
                        when {
                            player.isWinner -> Text(stringResource(R.string.results_winner), color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.testTag("history_winner_${player.playerId.value}"))
                            player.isHighestPenalty -> Text(stringResource(R.string.results_highest_penalty), color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.testTag("history_highest_${player.playerId.value}"))
                        }
                    }
                    Text(player.total.toString(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
