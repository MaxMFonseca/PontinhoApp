package com.maxmfonseca.pontinho.ui.navigation

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.maxmfonseca.pontinho.PontinhoApplication
import com.maxmfonseca.pontinho.R
import com.maxmfonseca.pontinho.ui.theme.PontinhoTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
