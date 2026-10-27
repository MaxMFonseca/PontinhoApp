package com.maxmfonseca.pontinho.ui.navigation

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.maxmfonseca.pontinho.PontinhoApplication
import com.maxmfonseca.pontinho.R
import com.maxmfonseca.pontinho.di.AppContainer
import com.maxmfonseca.pontinho.domain.model.PlayerNameKey
import com.maxmfonseca.pontinho.domain.repository.MatchRepository
import com.maxmfonseca.pontinho.domain.statistics.CompletedDateRange
import com.maxmfonseca.pontinho.domain.statistics.LifetimePlayerStatistics
import com.maxmfonseca.pontinho.ui.theme.PontinhoTheme
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class NavigationMotionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun homeLearnBackFadesThroughWithin150msWithoutDuplicateDestinations() {
        homeLearnBack(reducedMotion = false)
    }

    @Test fun reducedMotionHomeLearnBackSettlesWithoutAnimationTime() {
        homeLearnBack(reducedMotion = true)
    }

    @Test fun homeStatisticsSettingsAndBackKeepTheSuppliedRepositoryAndSearchSortState() {
        lateinit var controller: NavHostController
        val applicationContainer = (compose.activity.application as PontinhoApplication).container
        val repository = object : MatchRepository by applicationContainer.matchRepository {
            override fun observeLifetimeStatistics(completedDateRange: CompletedDateRange) = flowOf(listOf(
                LifetimePlayerStatistics(PlayerNameKey.from("Ana"), "Ana", 3, 1, 30),
                LifetimePlayerStatistics(PlayerNameKey.from("Bruno"), "Bruno", 3, 2, 15),
            ))
        }
        val container = object : AppContainer by applicationContainer {
            override val matchRepository = repository
        }
        compose.setContent {
            PontinhoTheme(reducedMotion = true) {
                controller = rememberNavController()
                PontinhoNavHost(container, navController = controller)
            }
        }
        compose.onNodeWithTag("home_statistics").assertIsDisplayed().performClick()
        compose.onAllNodesWithTag("statistics_root").assertCountEquals(1)
        compose.onNodeWithTag("home_root").assertDoesNotExist()
        compose.runOnUiThread { assertEquals("statistics", controller.currentDestination?.route) }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("statistics_player_bruno").fetchSemanticsNodes().size == 1
        }
        val ana = compose.onNodeWithTag("statistics_player_ana")
        val bruno = compose.onNodeWithTag("statistics_player_bruno")
        assertTrue(bruno.fetchSemanticsNode().boundsInRoot.top < ana.fetchSemanticsNode().boundsInRoot.top)

        compose.onNodeWithTag("statistics_sort_name").performClick()
        compose.waitUntil(5_000) {
            ana.fetchSemanticsNode().boundsInRoot.top < bruno.fetchSemanticsNode().boundsInRoot.top
        }
        compose.onNodeWithTag("statistics_sort_name").assertIsSelected()
        compose.onNodeWithTag("statistics_search").performTextInput("Ana")
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("statistics_player_bruno").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.home_settings)).performClick()
        compose.runOnUiThread { assertEquals(Destination.Settings.route, controller.currentDestination?.route) }
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.match_back)).performClick()
        compose.onNodeWithTag("statistics_search").assertTextContains("Ana")
        compose.onNodeWithTag("statistics_sort_name").assertIsSelected()
        compose.onNodeWithTag("statistics_player_bruno").assertDoesNotExist()
        compose.onNodeWithTag("statistics_clear").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("statistics_player_bruno").fetchSemanticsNodes().size == 1
        }
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.match_back)).performClick()
        compose.onNodeWithTag("statistics_root").assertDoesNotExist()
        compose.onAllNodesWithTag("home_root").assertCountEquals(1)
        compose.runOnUiThread {
            assertEquals(Destination.Home.route, controller.currentDestination?.route)
            assertFalse(controller.previousBackStackEntry != null)
        }
    }

    private fun homeLearnBack(reducedMotion: Boolean) {
        lateinit var controller: NavHostController
        val container = (compose.activity.application as PontinhoApplication).container
        compose.setContent {
            PontinhoTheme(reducedMotion = reducedMotion) {
                controller = rememberNavController()
                PontinhoNavHost(container, navController = controller)
            }
        }
        compose.onAllNodesWithTag("home_root").assertCountEquals(1)
        val destinationBounds = compose.onNodeWithTag("home_root").fetchSemanticsNode().boundsInRoot
        compose.mainClock.autoAdvance = false
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.home_learn)).performClick()
        settleNavigationComposition()
        if (!reducedMotion) {
            compose.onAllNodesWithTag("home_root").assertCountEquals(1)
            finishFadeThrough()
        }
        compose.onNodeWithTag("home_root").assertDoesNotExist()
        compose.onAllNodesWithTag("learn_root").assertCountEquals(1)
        assertEquals(destinationBounds, compose.onNodeWithTag("learn_root").fetchSemanticsNode().boundsInRoot)

        compose.onNodeWithContentDescription(compose.activity.getString(R.string.match_back)).performClick()
        settleNavigationComposition()
        if (!reducedMotion) {
            compose.onAllNodesWithTag("learn_root").assertCountEquals(1)
            finishFadeThrough()
        }
        compose.onNodeWithTag("learn_root").assertDoesNotExist()
        compose.onAllNodesWithTag("home_root").assertCountEquals(1)
        assertEquals(destinationBounds, compose.onNodeWithTag("home_root").fetchSemanticsNode().boundsInRoot)
        compose.runOnUiThread {
            assertEquals(Destination.Home.route, controller.currentDestination?.route)
            assertFalse(controller.previousBackStackEntry != null)
        }
    }

    private fun finishFadeThrough() {
        // Start the animation budget after Navigation's composition/seekable-state
        // setup, also required by the None case. Round only to a display frame;
        // never auto-advance to idle, which could hide a longer transition.
        compose.mainClock.advanceTimeBy(150)
        compose.waitForIdle()
    }

    private fun settleNavigationComposition() {
        // Navigation 2.10.2 uses a SeekableTransitionState even for None. Four
        // ticks deliver the route, compose the target, complete its zero-duration
        // transition, and dispose the outgoing content. Do not auto-advance until
        // idle: that would also finish an unwanted default 700ms scene animation.
        repeat(4) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
    }
}
