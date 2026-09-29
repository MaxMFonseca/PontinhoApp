package com.maxmfonseca.pontinho.ui.navigation

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
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

    @Test fun homeLearnBackReplacesContentWithoutAnimationDelayOrDuplicateDestinations() {
        lateinit var controller: NavHostController
        val container = (compose.activity.application as PontinhoApplication).container
        compose.setContent {
            PontinhoTheme {
                controller = rememberNavController()
                PontinhoNavHost(container, navController = controller)
            }
        }
        compose.onAllNodesWithTag("home_root").assertCountEquals(1)
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText(compose.activity.getString(R.string.home_learn)).performClick()
        settleNavigationComposition()
        compose.onNodeWithTag("home_root").assertDoesNotExist()
        compose.onAllNodesWithTag("learn_root").assertCountEquals(1)

        compose.onNodeWithContentDescription(compose.activity.getString(R.string.match_back)).performClick()
        settleNavigationComposition()
        compose.onNodeWithTag("learn_root").assertDoesNotExist()
        compose.onAllNodesWithTag("home_root").assertCountEquals(1)
        compose.runOnUiThread {
            assertEquals(Destination.Home.route, controller.currentDestination?.route)
            assertFalse(controller.previousBackStackEntry != null)
        }
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
