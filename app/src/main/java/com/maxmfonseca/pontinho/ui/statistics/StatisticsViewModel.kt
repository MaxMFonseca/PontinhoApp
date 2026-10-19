package com.maxmfonseca.pontinho.ui.statistics

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import com.maxmfonseca.pontinho.domain.repository.MatchRepository
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.Normalizer
import java.util.Locale

class StatisticsViewModel(
    private val savedStateHandle: SavedStateHandle,
    repository: MatchRepository,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val selection = MutableStateFlow(Selection(
        query = savedStateHandle[QUERY] ?: "",
        field = StatisticsSortField.entries.firstOrNull { it.name == savedStateHandle.get<String>(FIELD) }
            ?: StatisticsSortField.WINS,
        direction = StatisticsSortDirection.entries.firstOrNull { it.name == savedStateHandle.get<String>(DIRECTION) }
            ?: StatisticsSortDirection.DESCENDING,
    ))
    private val mutableUiState = MutableStateFlow(StatisticsUiState(
        query = selection.value.query,
        sortField = selection.value.field,
        sortDirection = selection.value.direction,
    ))
    val uiState: StateFlow<StatisticsUiState> = mutableUiState.asStateFlow()

    init {
        scope.launch {
            combine(repository.observeLifetimeStatistics(), selection) { players, selected ->
                val query = normalizeSearch(selected.query)
                selected to StatisticsUiState(
                    query = selected.query,
                    players = players.filter { normalizeSearch(it.displayName).contains(query) }
                        .sortedWith(playerComparator(selected.field, selected.direction)),
                    hasStatistics = players.isNotEmpty(),
                    sortField = selected.field,
                    sortDirection = selected.direction,
                    isLoading = false,
                )
            }.collect { (selected, derivedState) ->
                mutableUiState.update { current ->
                    if (selection.value == selected) derivedState else current
                }
            }
        }
    }

    fun updateQuery(query: String) {
        updateSelection(selection.value.copy(query = query))
    }

    fun clearQuery() = updateQuery("")

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
        savedStateHandle[QUERY] = value.query
        savedStateHandle[FIELD] = value.field.name
        savedStateHandle[DIRECTION] = value.direction.name
        selection.value = value
        mutableUiState.update { current ->
            current.copy(query = value.query, sortField = value.field, sortDirection = value.direction)
        }
    }

    override fun onCleared() { scope.cancel() }

    private data class Selection(
        val query: String,
        val field: StatisticsSortField,
        val direction: StatisticsSortDirection,
    )

    private companion object {
        const val QUERY = "statisticsQuery"
        const val FIELD = "statisticsSortField"
        const val DIRECTION = "statisticsSortDirection"
        val combiningMarks = Regex("\\p{M}+")
        val whitespace = Regex("[\\s\\p{Z}\\u0085]+")

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
