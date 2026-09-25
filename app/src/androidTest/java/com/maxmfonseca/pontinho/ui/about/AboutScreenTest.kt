package com.maxmfonseca.pontinho.ui.about

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.maxmfonseca.pontinho.R
import org.junit.Rule
import org.junit.Test

class AboutScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun aboutContainsOnlyKnownFactsAndSupportAddress() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.setContent { AboutScreen(versionName = "1.0.0", onBack = {}) }

        composeRule.onNodeWithTag("about_root").assertIsDisplayed()
        composeRule.onNodeWithText("Pontinho").assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.about_version, "1.0.0")).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.about_purpose)).assertIsDisplayed()
        composeRule.onNodeWithText("maxmfonseca12@gmail.com").assertIsDisplayed()
        composeRule.onNodeWithText("Privacidade").assertDoesNotExist()
        composeRule.onNodeWithText("Licenças").assertDoesNotExist()
        composeRule.onNodeWithText("Câmera").assertDoesNotExist()
    }

    @Test fun supportRemainsReachableAtOnePointFiveFontScaleOnASmallPhone() {
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.5f)) {
                Box(Modifier.size(width = 320.dp, height = 480.dp)) {
                    AboutScreen(versionName = "1.0.0", onBack = {})
                }
            }
        }

        composeRule.onNodeWithTag("about_support_email").performScrollTo().assertIsDisplayed()
    }
}
