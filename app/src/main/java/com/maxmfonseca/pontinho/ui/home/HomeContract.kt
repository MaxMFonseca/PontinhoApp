package com.maxmfonseca.pontinho.ui.home

import java.time.LocalDate

data class HomeDateCard(
    val localDate: LocalDate,
    val matchCount: Int,
    val activeMatchCount: Int,
    val playerNames: List<String>,
)

data class HomeUiState(
    val dateCards: List<HomeDateCard> = emptyList(),
    val isLoading: Boolean = true,
)
