package com.maxmfonseca.pontinho.ui.history

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
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
    onRequestDelete: (MatchId) -> Unit = {},
    onCancelDelete: () -> Unit = {},
    onConfirmDelete: () -> Unit = {},
    onClearDeleteError: () -> Unit = {},
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
                HistoryMatchCard(match, locale, onOpenMatch, onRequestDelete, !state.isDeleting,
                    Modifier.padding(horizontal = 16.dp).testTag("history_match_$index"))
            }
        }
    }
    if (state.pendingDeleteMatchId != null) {
        val cancel = {
            if (!state.isDeleting) {
                onClearDeleteError()
                onCancelDelete()
            }
        }
        AlertDialog(
            modifier = Modifier.testTag("history_delete_dialog"),
            onDismissRequest = cancel,
            properties = DialogProperties(dismissOnBackPress = !state.isDeleting, dismissOnClickOutside = !state.isDeleting),
            title = { Text(stringResource(R.string.history_delete_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.history_delete_message))
                    if (state.deleteError) Text(stringResource(R.string.history_delete_error),
                        color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("history_delete_error"))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onClearDeleteError()
                    onConfirmDelete()
                }, enabled = !state.isDeleting, modifier = Modifier.testTag("history_delete_confirm")) {
                    Text(stringResource(R.string.history_delete_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = cancel, enabled = !state.isDeleting, modifier = Modifier.testTag("history_delete_cancel")) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
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
                Row(Modifier.fillMaxWidth().testTag("daily_${metric}_${row.playerKey.value}").semantics(mergeDescendants = true) {},
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(row.value.toString(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                        modifier = Modifier.widthIn(min = 24.dp))
                    Text(row.displayName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun HistoryMatchCard(
    match: HistoryMatch,
    locale: Locale,
    onOpenMatch: (MatchId) -> Unit,
    onRequestDelete: (MatchId) -> Unit,
    deleteEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    Card(onClick = { onOpenMatch(match.id) }, modifier = modifier.fillMaxWidth().sizeIn(minHeight = 48.dp),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = BorderStroke(1.dp, if (match.isFinished) MaterialTheme.colorScheme.outlineVariant else MaterialTheme.colorScheme.tertiary),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val time = Instant.ofEpochMilli(match.createdAtEpochMillis).atZone(ZoneId.of(match.timeZoneId)).toLocalTime()
                .format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale))
            val status = stringResource(if (match.isFinished) R.string.history_completed else R.string.history_in_progress)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.history_match_header, time, status),
                    style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                IconButton(onClick = { onRequestDelete(match.id) }, enabled = deleteEnabled,
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).testTag("history_delete_${match.id.value}")) {
                    Icon(Icons.Filled.Delete, stringResource(R.string.history_delete_description, time))
                }
            }
            match.players.forEach { player ->
                val result = when {
                    !match.isFinished -> null
                    player.isWinner -> R.string.results_winner
                    player.isHighestPenalty -> R.string.results_highest_penalty
                    else -> null
                }
                val resultDescription = result?.let { stringResource(it) }
                val nameColor = when (result) {
                    R.string.results_winner -> MaterialTheme.colorScheme.primary
                    R.string.results_highest_penalty -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurface
                }
                Text(buildAnnotatedString {
                    withStyle(SpanStyle(color = nameColor)) { append(player.displayName) }
                    append(" ")
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(player.total.toString()) }
                }, style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
                        if (resultDescription != null) stateDescription = resultDescription
                    })
            }
        }
    }
}
