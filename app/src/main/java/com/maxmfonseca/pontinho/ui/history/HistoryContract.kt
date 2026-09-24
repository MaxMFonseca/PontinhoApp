package com.maxmfonseca.pontinho.ui.history

import com.maxmfonseca.pontinho.domain.repository.DailyStanding
import com.maxmfonseca.pontinho.domain.repository.HistoryMatch
import java.time.LocalDate

data class HistoryUiState(
    val localDate: LocalDate,
    val matches: List<HistoryMatch> = emptyList(),
    val wins: List<DailyStanding> = emptyList(),
    val penalties: List<DailyStanding> = emptyList(),
    val summaryExpanded: Boolean = false,
    val isLoading: Boolean = true,
)
