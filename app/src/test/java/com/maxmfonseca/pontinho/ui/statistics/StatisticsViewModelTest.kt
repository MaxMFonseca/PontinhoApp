package com.maxmfonseca.pontinho.ui.statistics

import androidx.lifecycle.SavedStateHandle
import com.maxmfonseca.pontinho.domain.model.PlayerNameKey
import com.maxmfonseca.pontinho.domain.repository.MatchRepository
import com.maxmfonseca.pontinho.domain.statistics.LifetimePlayerStatistics
import com.maxmfonseca.pontinho.ui.test.FakeMatchRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class StatisticsViewModelTest {
    @Test fun queryIsPublishedSynchronouslyBeforeTheSourceEmits() = runTest {
        val repo = object : MatchRepository by repository() {
            override fun observeLifetimeStatistics() = emptyFlow<List<LifetimePlayerStatistics>>()
        }
        val saved = SavedStateHandle()
        val vm = StatisticsViewModel(saved, repo, StandardTestDispatcher(testScheduler))

        vm.updateQuery("  joÃO   ")

        assertEquals("  joÃO   ", vm.uiState.value.query)
        assertTrue(vm.uiState.value.isLoading)
        runCurrent()
        assertEquals("  joÃO   ", vm.uiState.value.query)
        assertTrue(vm.uiState.value.isLoading)
        val recreated = StatisticsViewModel(saved, repo, StandardTestDispatcher(testScheduler))
        assertEquals("  joÃO   ", recreated.uiState.value.query)
    }

    @Test fun clearIsPublishedSynchronouslyWhileTheSourceHasNotEmitted() = runTest {
        val repo = object : MatchRepository by repository() {
            override fun observeLifetimeStatistics() = emptyFlow<List<LifetimePlayerStatistics>>()
        }
        val saved = SavedStateHandle()
        StatisticsViewModel(saved, repo, StandardTestDispatcher(testScheduler)).updateQuery("Ana")
        val vm = StatisticsViewModel(saved, repo, StandardTestDispatcher(testScheduler))
        assertEquals("Ana", vm.uiState.value.query)
        runCurrent()

        vm.clearQuery()

        assertEquals("", vm.uiState.value.query)
        assertTrue(vm.uiState.value.isLoading)
        val recreated = StatisticsViewModel(saved, repo, StandardTestDispatcher(testScheduler))
        assertEquals("", recreated.uiState.value.query)
    }

    @Test fun queryAndClearPublishImmediatelyWhileRowsUpdateAsynchronously() = runTest {
        val vm = StatisticsViewModel(SavedStateHandle(), repository(), StandardTestDispatcher(testScheduler))
        runCurrent()

        vm.updateQuery("carla")

        assertEquals("carla", vm.uiState.value.query)
        assertEquals(listOf("ana", "carla", "bruno", "dora"), vm.keys())
        runCurrent()
        assertEquals(listOf("carla"), vm.keys())

        vm.clearQuery()

        assertEquals("", vm.uiState.value.query)
        assertEquals(listOf("carla"), vm.keys())
        runCurrent()
        assertEquals(listOf("ana", "carla", "bruno", "dora"), vm.keys())
    }

    @Test fun derivedRowsDoNotRepublishAnOlderQueryAfterAControlChange() = runTest {
        val repo = repository(emptyList())
        val vm = StatisticsViewModel(SavedStateHandle(), repo, StandardTestDispatcher(testScheduler))
        val queries = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.uiState.collect { queries += it.query }
        }
        runCurrent()

        repo.lifetimeStatistics.value = rowsThatChangeSelectionDuringFiltering { vm.updateQuery("carla") }
        runCurrent()

        val updatedQuery = queries.indexOf("carla")
        assertTrue(updatedQuery >= 0)
        assertEquals(listOf("carla"), queries.drop(updatedQuery).distinct())
        assertEquals(listOf("carla"), vm.keys())
    }

    @Test fun derivedRowsDoNotRepublishAnOlderSortAfterAControlChange() = runTest {
        val repo = repository(emptyList())
        val vm = StatisticsViewModel(SavedStateHandle(), repo, StandardTestDispatcher(testScheduler))
        val fields = mutableListOf<StatisticsSortField>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.uiState.collect { fields += it.sortField }
        }
        runCurrent()

        repo.lifetimeStatistics.value = rowsThatChangeSelectionDuringFiltering { vm.selectSort(StatisticsSortField.POINTS) }
        runCurrent()

        val updatedSort = fields.indexOf(StatisticsSortField.POINTS)
        assertTrue(updatedSort >= 0)
        assertEquals(listOf(StatisticsSortField.POINTS), fields.drop(updatedSort).distinct())
        assertEquals(listOf("bruno", "ana", "carla", "dora"), vm.keys())
    }

    @Test fun initialOrderUsesWinsDescendingAndPlayerKeyAscendingForTies() = runTest {
        val vm = StatisticsViewModel(SavedStateHandle(), repository(), StandardTestDispatcher(testScheduler))
        assertTrue(vm.uiState.value.isLoading)

        runCurrent()

        assertEquals(listOf("ana", "carla", "bruno", "dora"), vm.keys())
        assertEquals(StatisticsSortField.WINS, vm.uiState.value.sortField)
        assertEquals(StatisticsSortDirection.DESCENDING, vm.uiState.value.sortDirection)
        assertTrue(vm.uiState.value.hasStatistics)
        assertFalse(vm.uiState.value.isLoading)
    }

    @Test fun selectingEachInactiveNumericFieldStartsDescending() = runTest {
        val cases = listOf(
            StatisticsSortField.MATCHES to listOf("carla", "ana", "bruno", "dora"),
            StatisticsSortField.WINS to listOf("ana", "carla", "bruno", "dora"),
            StatisticsSortField.WIN_RATE to listOf("ana", "dora", "carla", "bruno"),
            StatisticsSortField.POINTS to listOf("bruno", "ana", "carla", "dora"),
        )
        for ((field, expected) in cases) {
            val vm = StatisticsViewModel(SavedStateHandle(), repository(), StandardTestDispatcher(testScheduler))
            runCurrent()
            vm.selectSort(StatisticsSortField.NAME)

            vm.selectSort(field)
            runCurrent()

            assertEquals(field.name, expected, vm.keys())
            assertEquals(StatisticsSortDirection.DESCENDING, vm.uiState.value.sortDirection)
        }
    }

    @Test fun selectingEachActiveNumericFieldReversesOnlyTheMetricAndKeepsTiesAscending() = runTest {
        val cases = listOf(
            StatisticsSortField.MATCHES to listOf("dora", "ana", "bruno", "carla"),
            StatisticsSortField.WINS to listOf("dora", "bruno", "ana", "carla"),
            StatisticsSortField.WIN_RATE to listOf("bruno", "carla", "ana", "dora"),
            StatisticsSortField.POINTS to listOf("dora", "ana", "carla", "bruno"),
        )
        for ((field, expected) in cases) {
            val vm = StatisticsViewModel(SavedStateHandle(), repository(), StandardTestDispatcher(testScheduler))
            runCurrent()
            vm.selectSort(StatisticsSortField.NAME)
            vm.selectSort(field)

            vm.selectSort(field)
            runCurrent()

            assertEquals(field.name, expected, vm.keys())
            assertEquals(StatisticsSortDirection.ASCENDING, vm.uiState.value.sortDirection)
        }
    }

    @Test fun nameStartsAscendingAndItsActiveSelectionReversesNames() = runTest {
        val vm = StatisticsViewModel(SavedStateHandle(), repository(), StandardTestDispatcher(testScheduler))
        runCurrent()

        vm.selectSort(StatisticsSortField.NAME)
        runCurrent()
        assertEquals(listOf("ana", "bruno", "carla", "dora"), vm.keys())
        assertEquals(StatisticsSortDirection.ASCENDING, vm.uiState.value.sortDirection)

        vm.selectSort(StatisticsSortField.NAME)
        runCurrent()
        assertEquals(listOf("dora", "carla", "bruno", "ana"), vm.keys())
        assertEquals(StatisticsSortDirection.DESCENDING, vm.uiState.value.sortDirection)

        vm.selectSort(StatisticsSortField.NAME)
        runCurrent()
        assertEquals(listOf("ana", "bruno", "carla", "dora"), vm.keys())
    }

    @Test fun winRateUsesExactRatiosBeforePlayerKeysWhenDisplayedPercentagesAreEqual() = runTest {
        val repo = repository(listOf(
            player("zeta", "Zeta", 10_000, 3_334, 0),
            player("bruno", "Bruno", 6, 2, 0),
            player("ana", "Ana", 3, 1, 0),
        ))
        val vm = StatisticsViewModel(SavedStateHandle(), repo, StandardTestDispatcher(testScheduler))
        runCurrent()

        vm.selectSort(StatisticsSortField.WIN_RATE)
        runCurrent()
        assertEquals(listOf("zeta", "ana", "bruno"), vm.keys())

        vm.selectSort(StatisticsSortField.WIN_RATE)
        runCurrent()
        assertEquals(listOf("ana", "bruno", "zeta"), vm.keys())
    }

    @Test fun winRateCrossProductsUseLongForLargeCounts() = runTest {
        val repo = repository(listOf(
            player("ana", "Ana", 2_000_000_000, 1_000_000_000, 0),
            player("zeta", "Zeta", 2_000_000_000, 1_500_000_000, 0),
        ))
        val vm = StatisticsViewModel(SavedStateHandle(), repo, StandardTestDispatcher(testScheduler))
        runCurrent()

        vm.selectSort(StatisticsSortField.WIN_RATE)
        runCurrent()
        assertEquals(listOf("zeta", "ana"), vm.keys())

        vm.selectSort(StatisticsSortField.WIN_RATE)
        runCurrent()
        assertEquals(listOf("ana", "zeta"), vm.keys())
    }

    @Test fun queryIgnoresCaseAccentsAndOuterWhitespaceWithoutMergingIdentities() = runTest {
        val repo = repository(listOf(
            player("joão", "João", 3, 1, 0),
            player("carla", "Carla", 3, 1, 0),
            player("joao", "JOAO", 3, 1, 0),
        ))
        val vm = StatisticsViewModel(SavedStateHandle(), repo, StandardTestDispatcher(testScheduler))
        runCurrent()

        vm.updateQuery("  joÃO   ")
        runCurrent()

        assertEquals(listOf("joao", "joão"), vm.keys())
        assertEquals("  joÃO   ", vm.uiState.value.query)
    }

    @Test fun queryCollapsesWhitespaceInBothTheInputAndDisplayName() = runTest {
        val repo = repository(listOf(
            player("joão   silva", "João   Silva", 3, 1, 0),
            player("joana silva", "Joana Silva", 3, 1, 0),
        ))
        val vm = StatisticsViewModel(SavedStateHandle(), repo, StandardTestDispatcher(testScheduler))
        runCurrent()

        vm.updateQuery("  JOAO\t  SILVA ")
        runCurrent()

        assertEquals(listOf("joão   silva"), vm.keys())
    }

    @Test fun queryCollapsesUnicodeSeparatorsAndNextLineWhitespace() = runTest {
        val repo = repository(listOf(
            player("joão\u00a0\u2003silva", "João\u00a0\u2003Silva", 3, 1, 0),
            player("joana silva", "Joana Silva", 3, 1, 0),
        ))
        val vm = StatisticsViewModel(SavedStateHandle(), repo, StandardTestDispatcher(testScheduler))
        runCurrent()

        vm.updateQuery("  JOAO\u202f\u0085SILVA ")
        runCurrent()

        assertEquals(listOf("joão\u00a0\u2003silva"), vm.keys())
    }

    @Test fun nextLinePaddingIsTrimmedAfterWhitespaceNormalization() = runTest {
        val repo = repository(listOf(
            player("joão", "João", 3, 1, 0),
            player("carla", "Carla", 3, 1, 0),
        ))
        val vm = StatisticsViewModel(SavedStateHandle(), repo, StandardTestDispatcher(testScheduler))
        runCurrent()

        vm.updateQuery("\u0085JOAO\u0085")
        runCurrent()

        assertEquals(listOf("joão"), vm.keys())
    }

    @Test fun nextLineOnlyQueryShowsAllRowsInTheCurrentSort() = runTest {
        val vm = StatisticsViewModel(SavedStateHandle(), repository(), StandardTestDispatcher(testScheduler))
        runCurrent()

        vm.updateQuery("\u0085\u0085")
        runCurrent()

        assertEquals(listOf("ana", "carla", "bruno", "dora"), vm.keys())
    }

    @Test fun clearQueryRestoresEveryRowInTheSelectedSort() = runTest {
        val vm = StatisticsViewModel(SavedStateHandle(), repository(), StandardTestDispatcher(testScheduler))
        runCurrent()
        vm.selectSort(StatisticsSortField.POINTS)
        vm.updateQuery("carla")
        runCurrent()
        assertEquals(listOf("carla"), vm.keys())

        vm.clearQuery()
        runCurrent()

        assertEquals("", vm.uiState.value.query)
        assertEquals(listOf("bruno", "ana", "carla", "dora"), vm.keys())
        assertEquals(StatisticsSortField.POINTS, vm.uiState.value.sortField)
    }

    @Test fun emptySourceAndFilteredNoResultsHaveDifferentHasStatisticsValues() = runTest {
        val repo = repository(emptyList())
        val vm = StatisticsViewModel(SavedStateHandle(), repo, StandardTestDispatcher(testScheduler))
        runCurrent()
        assertFalse(vm.uiState.value.hasStatistics)
        assertFalse(vm.uiState.value.isLoading)
        assertEquals(emptyList<String>(), vm.keys())

        repo.lifetimeStatistics.value = samplePlayers()
        vm.updateQuery("missing")
        runCurrent()

        assertTrue(vm.uiState.value.hasStatistics)
        assertEquals(emptyList<String>(), vm.keys())

        repo.lifetimeStatistics.value = emptyList()
        runCurrent()
        assertFalse(vm.uiState.value.hasStatistics)
    }

    @Test fun sourceUpdatesReapplyTheCurrentQueryAndSort() = runTest {
        val repo = repository()
        val vm = StatisticsViewModel(SavedStateHandle(), repo, StandardTestDispatcher(testScheduler))
        runCurrent()
        vm.selectSort(StatisticsSortField.POINTS)
        vm.updateQuery("a")
        runCurrent()
        assertEquals(listOf("ana", "carla", "dora"), vm.keys())

        repo.lifetimeStatistics.value = listOf(
            player("carla", "Carla", 10, 4, 1),
            player("ana", "Ana", 8, 4, 5),
            player("dora", "Dora", 2, 1, 10),
            player("bruno", "Bruno", 8, 2, 99),
        )
        runCurrent()

        assertEquals(listOf("dora", "ana", "carla"), vm.keys())
        assertEquals("a", vm.uiState.value.query)
    }

    @Test fun queryFieldAndDirectionRestoreFromTheSameSavedStateHandle() = runTest {
        val saved = SavedStateHandle()
        val repo = repository()
        val vm = StatisticsViewModel(saved, repo, StandardTestDispatcher(testScheduler))
        runCurrent()
        vm.updateQuery("  A ")
        vm.selectSort(StatisticsSortField.POINTS)
        vm.selectSort(StatisticsSortField.POINTS)
        runCurrent()

        val recreated = StatisticsViewModel(saved, repo, StandardTestDispatcher(testScheduler))
        runCurrent()

        assertEquals("  A ", recreated.uiState.value.query)
        assertEquals(StatisticsSortField.POINTS, recreated.uiState.value.sortField)
        assertEquals(StatisticsSortDirection.ASCENDING, recreated.uiState.value.sortDirection)
        assertEquals(listOf("dora", "ana", "carla"), recreated.keys())
    }

    private fun StatisticsViewModel.keys() = uiState.value.players.map { it.playerKey.value }

    private fun rowsThatChangeSelectionDuringFiltering(changeSelection: () -> Unit): List<LifetimePlayerStatistics> {
        val rows = samplePlayers()
        var changed = false
        return object : AbstractList<LifetimePlayerStatistics>() {
            override val size get() = rows.size
            override fun get(index: Int): LifetimePlayerStatistics {
                if (!changed) {
                    changed = true
                    changeSelection()
                }
                return rows[index]
            }
        }
    }

    private fun repository(players: List<LifetimePlayerStatistics> = samplePlayers()) =
        FakeMatchRepository(lifetimeStatistics = MutableStateFlow(players))

    private fun samplePlayers() = listOf(
        player("bruno", "Bruno", 8, 2, 4_000_000_000L),
        player("carla", "Carla", 10, 4, 30),
        player("ana", "Ana", 8, 4, 30),
        player("dora", "Dora", 2, 1, 10),
    )

    private fun player(key: String, name: String, matches: Int, wins: Int, points: Long) =
        LifetimePlayerStatistics(PlayerNameKey(key), name, matches, wins, points)
}
