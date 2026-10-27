package com.maxmfonseca.pontinho.ui.statistics

import android.annotation.SuppressLint
import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalProvidableLocaleList
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.maxmfonseca.pontinho.domain.model.PlayerNameKey
import com.maxmfonseca.pontinho.domain.statistics.LifetimePlayerStatistics
import com.maxmfonseca.pontinho.ui.theme.PontinhoTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

class StatisticsScreenTest {
    @get:Rule val compose = createComposeRule()

    private val player = LifetimePlayerStatistics(PlayerNameKey("joão"), "João", 8, 5, 3_000_000_123L)
    private val populated = StatisticsUiState(players = listOf(player), hasStatistics = true, isLoading = false)
    private val fields = listOf("name", "matches", "wins", "win_rate", "points")

    @Test fun completionDateControlsOpenPickersAndClearTheInclusiveRange() {
        var state by mutableStateOf(populated.copy(
            startDate = LocalDate.parse("2026-10-13"),
            endDate = LocalDate.parse("2026-10-14"),
        ))
        var cleared = 0
        compose.setContent {
            LocalizedSurface {
                StatisticsScreen(
                    state = state,
                    onBack = {},
                    onSettings = {},
                    onQueryChange = {},
                    onClearQuery = {},
                    onSelectSort = {},
                    onStartDateChange = {},
                    onEndDateChange = {},
                    onClearDateRange = { cleared++; state = state.copy(startDate = null, endDate = null) },
                )
            }
        }

        val root = compose.onNodeWithTag("statistics_root")
        root.performScrollToNode(hasTestTag("statistics_date_start"))
        compose.onNodeWithTag("statistics_date_start").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
            .assertTextContains("2026", substring = true).performClick()
        compose.onNodeWithText("Selecionar data inicial").assertIsDisplayed()
        compose.onNodeWithText("Cancelar").performClick()

        root.performScrollToNode(hasTestTag("statistics_date_end"))
        compose.onNodeWithTag("statistics_date_end").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
            .assertTextContains("2026", substring = true).performClick()
        compose.onNodeWithText("Selecionar data final").assertIsDisplayed()
        compose.onNodeWithText("Cancelar").performClick()

        compose.onNodeWithTag("statistics_date_clear").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, cleared) }
        compose.onNodeWithTag("statistics_date_clear").assertDoesNotExist()
        compose.onNodeWithTag("statistics_date_start").assertTextContains("Todas")
        compose.onNodeWithTag("statistics_date_end").assertTextContains("Todas")
    }

    @Test fun emptyCompletionRangeHasSpecificFeedback() {
        val state = StatisticsUiState(
            startDate = LocalDate.parse("2026-10-13"),
            endDate = LocalDate.parse("2026-10-14"),
            isLoading = false,
        )
        compose.setContent {
            LocalizedSurface {
                StatisticsScreen(state, {}, {}, {}, {}, {}, {}, {}, {})
            }
        }

        compose.onNodeWithTag("statistics_root")
            .performScrollToNode(hasText("Nenhuma partida concluída no período selecionado."))
        compose.onNodeWithText("Nenhuma partida concluída no período selecionado.").assertIsDisplayed()
    }

    @Test fun searchAndVisibleClearForwardTheirEvents() {
        var state by mutableStateOf(populated)
        val queries = mutableListOf<String>()
        var cleared = 0
        compose.setContent {
            LocalizedSurface {
                StatisticsScreen(state, {}, {}, { queries += it; state = state.copy(query = it) },
                    { cleared++; state = state.copy(query = "") }, {})
            }
        }
        compose.onNodeWithTag("statistics_clear").assertDoesNotExist()
        compose.onNodeWithTag("statistics_search").performScrollTo().performTextInput("Jo")
        compose.runOnIdle { assertEquals("Jo", queries.last()) }
        compose.onNodeWithTag("statistics_clear").assertIsDisplayed()
            .assertContentDescriptionEquals("Limpar busca").performClick()
        compose.runOnIdle { assertEquals(1, cleared) }
        compose.onNodeWithTag("statistics_search").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        compose.onNodeWithTag("statistics_clear").assertDoesNotExist()
    }

    @Test fun fiveAccessibleSortTargetsShareOneRowAndForwardTheCorrectField() {
        val selected = mutableListOf<StatisticsSortField>()
        compose.setContent { LocalizedSurface { StatisticsScreen(populated, {}, {}, {}, {}, selected::add) } }
        val root = compose.onNodeWithTag("statistics_root")
        root.performScrollToNode(hasTestTag("statistics_sort_name"))
        compose.onAllNodes(SemanticsMatcher("sort control") {
            it.config.getOrElse(SemanticsProperties.TestTag) { "" }
                .startsWith("statistics_sort_")
        }).assertCountEquals(5)
        val bounds = fields.map { field ->
            val control = compose.onNodeWithTag("statistics_sort_$field")
                .assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
            control.performClick()
            control.fetchSemanticsNode().boundsInRoot
        }
        assertTrue(bounds.all { it.top == bounds.first().top && it.bottom == bounds.first().bottom })
        assertTrue(bounds.zipWithNext().all { (left, right) -> left.right <= right.left })
        compose.runOnIdle {
            assertEquals(listOf(StatisticsSortField.NAME, StatisticsSortField.MATCHES, StatisticsSortField.WINS,
                StatisticsSortField.WIN_RATE, StatisticsSortField.POINTS), selected)
        }
    }

    @Test fun selectedControlAnnouncesTheProvidedFieldAndDirection() {
        var state by mutableStateOf(populated)
        compose.setContent { LocalizedSurface { StatisticsScreen(state, {}, {}, {}, {}, {}) } }
        compose.onNodeWithTag("statistics_root").performScrollToNode(hasTestTag("statistics_sort_wins"))
        compose.onNodeWithTag("statistics_sort_wins").assertIsSelected()
            .assertContentDescriptionEquals("Ordenar por Vitórias, decrescente, selecionado")
        compose.onNodeWithTag("statistics_sort_name").assertIsNotSelected()
            .assertContentDescriptionEquals("Ordenar por Nome, crescente")
        compose.onNodeWithTag("statistics_sort_points").assertIsNotSelected()
            .assertContentDescriptionEquals("Ordenar por Pontos acumulados, decrescente")
        compose.runOnIdle { state = state.copy(sortField = StatisticsSortField.POINTS, sortDirection = StatisticsSortDirection.ASCENDING) }
        compose.onNodeWithTag("statistics_sort_wins").assertIsNotSelected()
        compose.onNodeWithTag("statistics_sort_points").assertIsSelected()
            .assertContentDescriptionEquals("Ordenar por Pontos acumulados, crescente, selecionado")
    }

    @Test fun loadingSourceEmptyAndFilteredEmptyHaveDistinctFeedback() {
        var state by mutableStateOf(StatisticsUiState())
        compose.setContent { LocalizedSurface { StatisticsScreen(state, {}, {}, {}, {}, {}) } }
        val root = compose.onNodeWithTag("statistics_root")
        root.performScrollToNode(hasTestTag("statistics_loading"))
        compose.onNodeWithTag("statistics_loading").assertIsDisplayed()
        compose.onNodeWithText("As estatísticas aparecerão após finalizar uma partida.").assertDoesNotExist()
        compose.runOnIdle { state = state.copy(isLoading = false) }
        root.performScrollToNode(hasText("As estatísticas aparecerão após finalizar uma partida."))
        compose.onNodeWithText("As estatísticas aparecerão após finalizar uma partida.").assertIsDisplayed()
        compose.onNodeWithTag("statistics_loading").assertDoesNotExist()
        compose.runOnIdle { state = populated.copy(query = "Nobody", players = emptyList()) }
        root.performScrollToNode(hasText("Nenhum jogador encontrado."))
        compose.onNodeWithText("Nenhum jogador encontrado.").assertIsDisplayed()
        compose.onNodeWithText("As estatísticas aparecerão após finalizar uma partida.").assertDoesNotExist()
        root.performScrollToNode(hasTestTag("statistics_search"))
        compose.onNodeWithTag("statistics_search").assertIsDisplayed()
        root.performScrollToNode(hasTestTag("statistics_sort_name"))
        fields.forEach { compose.onNodeWithTag("statistics_sort_$it").assertIsDisplayed() }
    }

    @Test fun compactLocalizedCardsKeepEveryMetricReadableAtSupportedFontScales() {
        var scenario by mutableStateOf("pt-BR" to 1f)
        compose.setContent {
            LocalizedSurface(scenario.first, scenario.second) {
                StatisticsScreen(populated, {}, {}, {}, {}, {})
            }
        }
        for (language in listOf("pt-BR", "en-US")) for (scale in listOf(1f, 1.5f, 2f)) {
            compose.runOnIdle { scenario = language to scale }
            val root = compose.onNodeWithTag("statistics_root")
            root.performScrollToNode(hasTestTag("statistics_sort_name"))
            fields.forEach {
                compose.onNodeWithTag("statistics_sort_$it").assertIsDisplayed()
                    .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
            }
            compose.onNodeWithTag("statistics_sort_wins").assertIsSelected().assertContentDescriptionEquals(
                if (language == "pt-BR") "Ordenar por Vitórias, decrescente, selecionado" else "Sort by Wins, descending, selected")
            root.performScrollToNode(hasTestTag("statistics_player_joão"))
            compose.onAllNodesWithTag("statistics_player_joão").assertCountEquals(1)
            val expected = if (language == "pt-BR") {
                listOf("João", "Partidas concluídas", "8", "Vitórias", "5", "Taxa de vitórias", "62,5%", "Pontos acumulados", "3000000123")
            } else {
                listOf("João", "Completed matches", "8", "Wins", "5", "Win rate", "62.5%", "Accumulated points", "3000000123")
            }
            expected.forEach { text ->
                val node = compose.onNodeWithText(text).performScrollTo().assertIsDisplayed()
                val layouts = mutableListOf<TextLayoutResult>()
                node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                assertFalse("$text must not be clipped in $language at $scale", layouts.single().hasVisualOverflow)
            }
        }
    }

    @SuppressLint("RestrictedApi") // Compose locale injection for a fixed, repeatable test surface.
    @Composable
    private fun LocalizedSurface(language: String = "pt-BR", scale: Float = 1f, content: @Composable () -> Unit) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val localized = base.createConfigurationContext(Configuration(base.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(language))
        })
        CompositionLocalProvider(
            LocalContext provides localized, LocalResources provides localized.resources,
            LocalProvidableLocaleList provides androidx.compose.ui.text.intl.LocaleList(language),
            LocalDensity provides Density(base.resources.displayMetrics.density, scale),
        ) {
            PontinhoTheme { Surface { Box(Modifier.size(320.dp, 568.dp)) { content() } } }
        }
    }
}
