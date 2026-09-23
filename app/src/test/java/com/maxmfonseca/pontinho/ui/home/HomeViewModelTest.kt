package com.maxmfonseca.pontinho.ui.home

import com.maxmfonseca.pontinho.domain.model.MatchId
import com.maxmfonseca.pontinho.domain.repository.DaySummary
import com.maxmfonseca.pontinho.ui.test.FakeMatchRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.time.LocalDate

class HomeViewModelTest {
    @Test
    fun dateCardsAreNewestFirst() {
        val home = MutableStateFlow(
            listOf(
                DaySummary(LocalDate.parse("2026-10-10"), emptyList()),
                DaySummary(LocalDate.parse("2026-10-14"), emptyList()),
                DaySummary(LocalDate.parse("2026-10-12"), emptyList()),
            ),
        )

        val viewModel = HomeViewModel(FakeMatchRepository(home), Dispatchers.Unconfined)

        assertEquals(
            listOf("2026-10-14", "2026-10-12", "2026-10-10"),
            viewModel.uiState.value.dateCards.map { it.localDate.toString() },
        )
        assertFalse(viewModel.uiState.value.isLoading)
    }
}
