package com.maxmfonseca.pontinho.ui.statistics

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.maxmfonseca.pontinho.R
import com.maxmfonseca.pontinho.domain.statistics.LifetimePlayerStatistics
import com.maxmfonseca.pontinho.ui.components.PontinhoInfoBanner
import com.maxmfonseca.pontinho.ui.components.PontinhoTopBar
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatisticsScreen(
    state: StatisticsUiState,
    onBack: () -> Unit,
    onSettings: () -> Unit,
    onQueryChange: (String) -> Unit,
    onClearQuery: () -> Unit,
    onSelectSort: (StatisticsSortField) -> Unit,
    onStartDateChange: (LocalDate?) -> Unit = {},
    onEndDateChange: (LocalDate?) -> Unit = {},
    onClearDateRange: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val localeTag = LocalLocale.current.toLanguageTag()
    val percentFormat = remember(localeTag) {
        NumberFormat.getPercentInstance(Locale.forLanguageTag(localeTag)).apply {
            minimumFractionDigits = 1
            maximumFractionDigits = 1
        }
    }
    val locale = remember(localeTag) { Locale.forLanguageTag(localeTag) }
    val dateFormat = remember(localeTag) { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale) }
    var datePickerTarget by remember { mutableStateOf<DatePickerTarget?>(null) }
    LazyColumn(
        modifier = modifier.fillMaxSize().safeDrawingPadding().testTag("statistics_root"),
        contentPadding = PaddingValues(bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { PontinhoTopBar(R.string.route_statistics, onBack = onBack, onSettings = onSettings) }
        item {
            OutlinedTextField(
                value = state.query,
                onValueChange = onQueryChange,
                label = { Text(stringResource(R.string.statistics_search)) },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                trailingIcon = if (state.query.isNotEmpty()) {
                    {
                        IconButton(onClick = onClearQuery,
                            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).testTag("statistics_clear")) {
                            Icon(Icons.Filled.Clear, stringResource(R.string.statistics_clear))
                        }
                    }
                } else null,
                singleLine = true,
                modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth().testTag("statistics_search"),
            )
        }
        item {
            DateRangeControls(
                state = state,
                dateFormat = dateFormat,
                onSelectStart = { datePickerTarget = DatePickerTarget.START },
                onSelectEnd = { datePickerTarget = DatePickerTarget.END },
                onClear = onClearDateRange,
            )
        }
        item {
            Row(Modifier.padding(horizontal = 16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                StatisticsSortField.entries.forEach { field ->
                    SortControl(field, state, { onSelectSort(field) }, Modifier.weight(1f))
                }
            }
        }
        when {
            state.isLoading -> item {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.testTag("statistics_loading"))
                }
            }
            !state.hasStatistics && state.hasDateFilter -> item {
                PontinhoInfoBanner(R.string.statistics_range_empty, Modifier.padding(horizontal = 16.dp))
            }
            !state.hasStatistics -> item {
                PontinhoInfoBanner(R.string.statistics_empty, Modifier.padding(horizontal = 16.dp))
            }
            state.players.isEmpty() -> item {
                PontinhoInfoBanner(R.string.statistics_no_results, Modifier.padding(horizontal = 16.dp))
            }
            else -> items(state.players, key = { it.playerKey.value }) { player ->
                StatisticsPlayerCard(player, percentFormat.format(player.winRate))
            }
        }
    }
    datePickerTarget?.let { target ->
        val selectedDate = if (target == DatePickerTarget.START) state.startDate else state.endDate
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = selectedDate?.toUtcEpochMillis())
        DatePickerDialog(
            onDismissRequest = { datePickerTarget = null },
            confirmButton = {
                TextButton(
                    enabled = pickerState.selectedDateMillis != null,
                    onClick = {
                        pickerState.selectedDateMillis?.toUtcLocalDate()?.let { date ->
                            if (target == DatePickerTarget.START) onStartDateChange(date) else onEndDateChange(date)
                        }
                        datePickerTarget = null
                    },
                ) { Text(stringResource(R.string.confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { datePickerTarget = null }) { Text(stringResource(R.string.cancel)) }
            },
        ) {
            DatePicker(
                state = pickerState,
                title = { Text(
                    stringResource(if (target == DatePickerTarget.START) {
                        R.string.statistics_select_start_date
                    } else R.string.statistics_select_end_date),
                    modifier = Modifier.padding(start = 24.dp, top = 16.dp, end = 12.dp),
                ) },
            )
        }
    }
}

private enum class DatePickerTarget { START, END }

@Composable
private fun DateRangeControls(
    state: StatisticsUiState,
    dateFormat: DateTimeFormatter,
    onSelectStart: () -> Unit,
    onSelectEnd: () -> Unit,
    onClear: () -> Unit,
) {
    val stack = LocalDensity.current.fontScale >= 1.5f
    val start: @Composable (Modifier) -> Unit = { itemModifier ->
        DateButton(
            label = R.string.statistics_start_date,
            date = state.startDate,
            dateFormat = dateFormat,
            onClick = onSelectStart,
            modifier = itemModifier.testTag("statistics_date_start"),
        )
    }
    val end: @Composable (Modifier) -> Unit = { itemModifier ->
        DateButton(
            label = R.string.statistics_end_date,
            date = state.endDate,
            dateFormat = dateFormat,
            onClick = onSelectEnd,
            modifier = itemModifier.testTag("statistics_date_end"),
        )
    }
    Column(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (stack) {
            start(Modifier.fillMaxWidth())
            end(Modifier.fillMaxWidth())
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                start(Modifier.weight(1f))
                end(Modifier.weight(1f))
            }
        }
        if (state.hasDateFilter) {
            TextButton(
                onClick = onClear,
                modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp).testTag("statistics_date_clear"),
            ) { Text(stringResource(R.string.statistics_clear_dates)) }
        }
    }
}

@Composable
private fun DateButton(
    @StringRes label: Int,
    date: LocalDate?,
    dateFormat: DateTimeFormatter,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    OutlinedButton(onClick = onClick, modifier = modifier.heightIn(min = 56.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(label), style = MaterialTheme.typography.labelMedium)
            Text(date?.format(dateFormat) ?: stringResource(R.string.statistics_all_dates))
        }
    }
}

private fun LocalDate.toUtcEpochMillis(): Long = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

private fun Long.toUtcLocalDate(): LocalDate = Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()

@Composable
private fun SortControl(field: StatisticsSortField, state: StatisticsUiState, onClick: () -> Unit, modifier: Modifier) {
    val isSelected = state.sortField == field
    val direction = if (isSelected) state.sortDirection else if (field == StatisticsSortField.NAME) {
        StatisticsSortDirection.ASCENDING
    } else StatisticsSortDirection.DESCENDING
    val directionLabel = stringResource(if (direction == StatisticsSortDirection.ASCENDING) {
        R.string.statistics_ascending
    } else R.string.statistics_descending)
    val label = stringResource(field.labelResource())
    val description = stringResource(
        if (isSelected) R.string.statistics_sort_selected_description else R.string.statistics_sort_description,
        label, directionLabel,
    )
    IconButton(
        onClick = onClick,
        modifier = modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .testTag("statistics_sort_${field.name.lowercase(Locale.ROOT)}")
            .semantics { selected = isSelected; contentDescription = description },
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        Box(Modifier.size(32.dp)) {
            val iconModifier = Modifier.size(24.dp).align(Alignment.Center)
            when (field) {
                StatisticsSortField.NAME -> Icon(Icons.Filled.Person, null, iconModifier)
                StatisticsSortField.MATCHES -> Icon(Icons.AutoMirrored.Filled.List, null, iconModifier)
                StatisticsSortField.WINS -> Icon(Icons.Filled.Star, null, iconModifier)
                StatisticsSortField.WIN_RATE -> Icon(painterResource(R.drawable.ic_percent), null, iconModifier)
                StatisticsSortField.POINTS -> Icon(painterResource(R.drawable.ic_points), null, iconModifier)
            }
            if (isSelected) {
                Icon(if (direction == StatisticsSortDirection.ASCENDING) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    null, Modifier.size(12.dp).align(Alignment.BottomEnd))
            }
        }
    }
}

@StringRes
private fun StatisticsSortField.labelResource(): Int = when (this) {
    StatisticsSortField.NAME -> R.string.statistics_name
    StatisticsSortField.MATCHES -> R.string.statistics_matches
    StatisticsSortField.WINS -> R.string.statistics_wins
    StatisticsSortField.WIN_RATE -> R.string.statistics_win_rate
    StatisticsSortField.POINTS -> R.string.statistics_points
}

@Composable
private fun StatisticsPlayerCard(player: LifetimePlayerStatistics, winRate: String) {
    val stackMetrics = LocalDensity.current.fontScale >= 1.5f
    Card(
        modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth().testTag("statistics_player_${player.playerKey.value}"),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(player.displayName, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            if (stackMetrics) {
                StatisticsMetric(R.string.statistics_matches, player.completedMatches.toString())
                StatisticsMetric(R.string.statistics_wins, player.wins.toString())
                StatisticsMetric(R.string.statistics_win_rate, winRate)
                StatisticsMetric(R.string.statistics_points, player.points.toString())
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    StatisticsMetric(R.string.statistics_matches, player.completedMatches.toString(), Modifier.weight(1f))
                    StatisticsMetric(R.string.statistics_wins, player.wins.toString(), Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    StatisticsMetric(R.string.statistics_win_rate, winRate, Modifier.weight(1f))
                    StatisticsMetric(R.string.statistics_points, player.points.toString(), Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun StatisticsMetric(@StringRes label: Int, value: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(label), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}
