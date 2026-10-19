package com.maxmfonseca.pontinho.ui.statistics

import com.maxmfonseca.pontinho.domain.statistics.LifetimePlayerStatistics

enum class StatisticsSortField { NAME, MATCHES, WINS, WIN_RATE, POINTS }

enum class StatisticsSortDirection { ASCENDING, DESCENDING }

data class StatisticsUiState(
    val query: String = "",
    val players: List<LifetimePlayerStatistics> = emptyList(),
    val hasStatistics: Boolean = false,
    val sortField: StatisticsSortField = StatisticsSortField.WINS,
    val sortDirection: StatisticsSortDirection = StatisticsSortDirection.DESCENDING,
    val isLoading: Boolean = true,
)
