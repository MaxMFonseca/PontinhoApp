package com.maxmfonseca.pontinho.ui.home

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.test.platform.app.InstrumentationRegistry
import com.maxmfonseca.pontinho.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertTrue
import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.maxmfonseca.pontinho.settings.ThemePreference
import com.maxmfonseca.pontinho.ui.theme.PontinhoTheme
import java.util.Locale
import java.io.FileInputStream
import java.time.LocalDate

class HomeScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test fun compactHomeKeepsActionsAndRecentDayReachableAcrossLocalesThemesAndFontScales() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        var scenario by mutableStateOf(Triple("pt-BR", ThemePreference.LIGHT, 1f))
        var opened: LocalDate? = null
        var learnClicks = 0
        var statisticsClicks = 0
        var safeTop = 0
        composeRule.setContent {
            val (locale, theme, scale) = scenario
            val context = base.createConfigurationContext(Configuration(base.resources.configuration).apply { setLocale(Locale.forLanguageTag(locale)) })
            CompositionLocalProvider(LocalContext provides context, LocalResources provides context.resources,
                LocalDensity provides Density(base.resources.displayMetrics.density, scale)) {
                val inset = WindowInsets.safeDrawing.getTop(LocalDensity.current)
                SideEffect { safeTop = inset }
                PontinhoTheme(theme) {
                    Surface { Box(Modifier.size(320.dp, 568.dp)) {
                        HomeScreen(HomeUiState(isLoading = false, dateCards = listOf(
                            HomeDateCard(LocalDate.of(2026, 10, 14), 2, 1, listOf("Alexandre Maximiliano de Albuquerque", "Bruno")),
                        )), {}, { learnClicks++ }, { opened = it }, onStatistics = { statisticsClicks++ })
                    } }
                }
            }
        }
        for (locale in listOf("pt-BR", "en-US")) for (theme in listOf(ThemePreference.LIGHT, ThemePreference.DARK)) for (scale in listOf(1f, 1.5f, 2f)) {
            composeRule.runOnIdle { scenario = Triple(locale, theme, scale) }
            val list = composeRule.onNodeWithTag("home_root")
            list.performScrollToIndex(0)
            val localized = base.createConfigurationContext(Configuration(base.resources.configuration).apply { setLocale(Locale.forLanguageTag(locale)) })
            val settings = composeRule.onNodeWithContentDescription(localized.getString(R.string.home_settings)).fetchSemanticsNode().boundsInRoot
            assertTrue("Settings must be below the system status area", settings.top >= safeTop)
            val learnLabel = localized.getString(R.string.home_learn)
            composeRule.onAllNodesWithContentDescription(learnLabel).assertCountEquals(1)
            val learn = composeRule.onNodeWithContentDescription(learnLabel)
                .assertIsDisplayed().assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
            val learnBounds = learn.fetchSemanticsNode().boundsInRoot
            assertTrue("Learn must precede Settings in the top bar", learnBounds.right <= settings.left)
            assertEquals(settings.center.y, learnBounds.center.y, 1f)
            assertTrue("Learn must be below the system status area", learnBounds.top >= safeTop)
            val previousClicks = learnClicks
            learn.performClick()
            composeRule.runOnIdle { assertEquals(previousClicks + 1, learnClicks) }
            composeRule.onNodeWithTag("home_learn").assertDoesNotExist()
            composeRule.onNodeWithText(learnLabel).assertDoesNotExist()
            listOf("home_new_match", "home_statistics").forEach { tag ->
                list.performScrollToNode(hasTestTag(tag))
                val node = composeRule.onNodeWithTag(tag).assertIsDisplayed().assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
                val button = node.fetchSemanticsNode().boundsInRoot
                val textTag = "${tag}_label"
                val label = composeRule.onNodeWithTag(textTag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                assertTrue("$tag must contain its label at $scenario", button.contains(label.topLeft) && button.contains(label.bottomRight))
            }
            composeRule.onNodeWithTag("home_statistics_label", useUnmergedTree = true)
                .assertTextEquals(localized.getString(R.string.home_statistics))
            val previousStatisticsClicks = statisticsClicks
            composeRule.onNodeWithTag("home_statistics").performClick()
            composeRule.runOnIdle { assertEquals(previousStatisticsClicks + 1, statisticsClicks) }
            list.performScrollToNode(hasTestTag("home_day_0"))
            composeRule.onNodeWithTag("home_day_0").assertIsDisplayed().performClick()
            composeRule.runOnIdle { assertEquals(LocalDate.of(2026, 10, 14), opened) }
            if (locale == "pt-BR" && theme == ThemePreference.LIGHT && scale == 1f ||
                locale == "en-US" && theme == ThemePreference.DARK && scale == 2f) {
                list.performScrollToIndex(0)
                val capture = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
                    "screencap -p /sdcard/Download/task4-home-$locale-$scale.png")
                FileInputStream(capture.fileDescriptor).use { it.readBytes() }
                capture.close()
            }
        }
    }

    @Test
    fun emptyHomeShowsFullWidthStatisticsImmediatelyAfterNewMatchAndInvokesItsCallback() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var newMatchClicks = 0
        var learnClicks = 0
        var statisticsClicks = 0
        composeRule.setContent {
            HomeScreen(
                state = HomeUiState(isLoading = false),
                onNewMatch = { newMatchClicks++ },
                onLearn = { learnClicks++ },
                onOpenDay = {},
                onStatistics = { statisticsClicks++ },
            )
        }

        composeRule.onNodeWithText(context.getString(R.string.home_new_match)).performClick()
        val newMatch = composeRule.onNodeWithTag("home_new_match").assertHeightIsAtLeast(56.dp)
            .fetchSemanticsNode().boundsInRoot
        val statistics = composeRule.onNodeWithTag("home_statistics")
            .assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        statistics.assertTextEquals(context.getString(R.string.home_statistics))
        val statisticsBounds = statistics.fetchSemanticsNode().boundsInRoot
        assertEquals(newMatch.left, statisticsBounds.left, 1f)
        assertEquals(newMatch.right, statisticsBounds.right, 1f)
        assertTrue("Statistics must immediately follow New Match", statisticsBounds.top >= newMatch.bottom)
        val density = context.resources.displayMetrics.density
        assertTrue("No other content belongs between the actions", statisticsBounds.top - newMatch.bottom <= 8 * density)
        statistics.performClick()
        composeRule.onAllNodesWithContentDescription(context.getString(R.string.home_learn)).assertCountEquals(1)
        composeRule.onNodeWithContentDescription(context.getString(R.string.home_learn)).performClick()
        composeRule.onNodeWithTag("home_learn").assertDoesNotExist()
        composeRule.onNodeWithText(context.getString(R.string.home_empty_title)).assertIsDisplayed()
        composeRule.onNodeWithTag("advertisement").assertDoesNotExist()
        composeRule.runOnIdle {
            assertEquals(1, newMatchClicks)
            assertEquals(1, learnClicks)
            assertEquals(1, statisticsClicks)
        }
    }

    @Test
    fun populatedHomeShowsNewestDateCardFirst() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.setContent {
            HomeScreen(
                state = HomeUiState(
                    dateCards = listOf(
                        HomeDateCard(LocalDate.parse("2026-10-14"), 2, 1, listOf("Ana", "Bruno")),
                        HomeDateCard(LocalDate.parse("2026-10-12"), 1, 0, listOf("Carla")),
                    ),
                    isLoading = false,
                ),
                onNewMatch = {},
                onLearn = {},
                onOpenDay = {},
                onStatistics = {},
            )
        }

        composeRule.onNodeWithTag("home_day_0").assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.home_today)).assertIsDisplayed()
        composeRule.onNodeWithText("Ana · Bruno").assertIsDisplayed()
    }

    @Test fun settingsGlyphHasLocalizedTalkBackDescription() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.setContent {
            HomeScreen(HomeUiState(isLoading = false), {}, {}, {}, onStatistics = {}, onSettings = {})
        }
        composeRule.onNodeWithContentDescription(context.getString(R.string.home_settings))
            .assertContentDescriptionEquals(context.getString(R.string.home_settings))
    }
}
