package com.maxmfonseca.pontinho.ui.statistics

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import com.maxmfonseca.pontinho.domain.repository.MatchRepository
import com.maxmfonseca.pontinho.domain.statistics.CompletedDateRange
import com.maxmfonseca.pontinho.domain.statistics.LifetimePlayerStatistics
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.Normalizer
import java.time.LocalDate
import java.util.Locale

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class StatisticsViewModel(
    private val savedStateHandle: SavedStateHandle,
    repository: MatchRepository,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val restoredDateRange = restoreDateRange(savedStateHandle)
    private val selection = MutableStateFlow(Selection(
        query = savedStateHandle[QUERY] ?: "",
        field = StatisticsSortField.entries.firstOrNull { it.name == savedStateHandle.get<String>(FIELD) }
            ?: StatisticsSortField.WINS,
        direction = StatisticsSortDirection.entries.firstOrNull { it.name == savedStateHandle.get<String>(DIRECTION) }
            ?: StatisticsSortDirection.DESCENDING,
        completedDateRange = restoredDateRange,
    ))
    private val mutableUiState = MutableStateFlow(StatisticsUiState(
        query = selection.value.query,
        sortField = selection.value.field,
        sortDirection = selection.value.direction,
        startDate = restoredDateRange.startInclusive,
        endDate = restoredDateRange.endInclusive,
    ))
    val uiState: StateFlow<StatisticsUiState> = mutableUiState.asStateFlow()

    init {
        scope.launch {
            val statisticsByRange = selection.map { it.completedDateRange }
                .distinctUntilChanged()
                .flatMapLatest { range ->
                    repository.observeLifetimeStatistics(range).map { players -> range to players }
                }
            combine(statisticsByRange, selection) { source, selected ->
                val (sourceRange, players) = source
                val query = normalizeSearch(selected.query)
                Triple(sourceRange, selected, StatisticsUiState(
                    query = selected.query,
                    players = players.filter { normalizeSearch(it.displayName).contains(query) }
                        .sortedWith(playerComparator(selected.field, selected.direction)),
                    hasStatistics = players.isNotEmpty(),
                    sortField = selected.field,
                    sortDirection = selected.direction,
                    startDate = selected.completedDateRange.startInclusive,
                    endDate = selected.completedDateRange.endInclusive,
                    isLoading = false,
                ))
            }.collect { (sourceRange, selected, derivedState) ->
                mutableUiState.update { current ->
                    if (selection.value == selected && sourceRange == selected.completedDateRange) derivedState else current
                }
            }
        }
    }

    fun updateQuery(query: String) {
        updateSelection(selection.value.copy(query = query))
    }

    fun clearQuery() = updateQuery("")

    fun setStartDate(date: LocalDate?) {
        val current = selection.value
        val end = current.completedDateRange.endInclusive?.let { if (date != null && date.isAfter(it)) date else it }
        updateSelection(current.copy(completedDateRange = CompletedDateRange(date, end)))
    }

    fun setEndDate(date: LocalDate?) {
        val current = selection.value
        val start = current.completedDateRange.startInclusive?.let { if (date != null && date.isBefore(it)) date else it }
        updateSelection(current.copy(completedDateRange = CompletedDateRange(start, date)))
    }

    fun clearDateRange() {
        updateSelection(selection.value.copy(completedDateRange = CompletedDateRange()))
    }

    fun selectSort(field: StatisticsSortField) {
        val current = selection.value
        val direction = when {
            field == current.field -> if (current.direction == StatisticsSortDirection.ASCENDING) {
                StatisticsSortDirection.DESCENDING
            } else {
                StatisticsSortDirection.ASCENDING
            }
            field == StatisticsSortField.NAME -> StatisticsSortDirection.ASCENDING
            else -> StatisticsSortDirection.DESCENDING
        }
        updateSelection(current.copy(field = field, direction = direction))
    }

    private fun updateSelection(value: Selection) {
        val dateRangeChanged = selection.value.completedDateRange != value.completedDateRange
        savedStateHandle[QUERY] = value.query
        savedStateHandle[FIELD] = value.field.name
        savedStateHandle[DIRECTION] = value.direction.name
        savedStateHandle[START_DATE] = value.completedDateRange.startInclusive?.toString()
        savedStateHandle[END_DATE] = value.completedDateRange.endInclusive?.toString()
        selection.value = value
        mutableUiState.update { current ->
            current.copy(
                query = value.query,
                sortField = value.field,
                sortDirection = value.direction,
                startDate = value.completedDateRange.startInclusive,
                endDate = value.completedDateRange.endInclusive,
                players = if (dateRangeChanged) emptyList() else current.players,
                hasStatistics = if (dateRangeChanged) false else current.hasStatistics,
                isLoading = if (dateRangeChanged) true else current.isLoading,
            )
        }
    }

    override fun onCleared() { scope.cancel() }

    private data class Selection(
        val query: String,
        val field: StatisticsSortField,
        val direction: StatisticsSortDirection,
        val completedDateRange: CompletedDateRange,
    )

    private companion object {
        const val QUERY = "statisticsQuery"
        const val FIELD = "statisticsSortField"
        const val DIRECTION = "statisticsSortDirection"
        const val START_DATE = "statisticsStartDate"
        const val END_DATE = "statisticsEndDate"
        val combiningMarks = Regex("\\p{M}+")
        val whitespace = Regex("[\\s\\p{Z}\\u0085]+")

        fun restoreDateRange(savedStateHandle: SavedStateHandle): CompletedDateRange {
            val first = savedStateHandle.get<String>(START_DATE)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            val second = savedStateHandle.get<String>(END_DATE)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            if (first == null || second == null || !first.isAfter(second)) return CompletedDateRange(first, second)
            return CompletedDateRange(second, first)
        }

        fun normalizeSearch(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(combiningMarks, "")
            .lowercase(Locale.ROOT)
            .replace(whitespace, " ")
            .trim()

        fun playerComparator(
            field: StatisticsSortField,
            direction: StatisticsSortDirection,
        ): Comparator<LifetimePlayerStatistics> {
            val byName = compareBy<LifetimePlayerStatistics> { it.playerKey.value }
            if (field == StatisticsSortField.NAME) {
                return if (direction == StatisticsSortDirection.ASCENDING) byName else byName.reversed()
            }
            val metric = Comparator<LifetimePlayerStatistics> { left, right ->
                when (field) {
                    StatisticsSortField.MATCHES -> left.completedMatches.compareTo(right.completedMatches)
                    StatisticsSortField.WINS -> left.wins.compareTo(right.wins)
                    StatisticsSortField.WIN_RATE -> (left.wins.toLong() * right.completedMatches)
                        .compareTo(right.wins.toLong() * left.completedMatches)
                    StatisticsSortField.POINTS -> left.points.compareTo(right.points)
                    StatisticsSortField.NAME -> error("Name sorting is handled separately")
                }
            }
            val directed = if (direction == StatisticsSortDirection.ASCENDING) metric else metric.reversed()
            return directed.then(byName)
        }
    }
}
