package com.maxmfonseca.pontinho.ui.about

import android.content.res.Configuration
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performFirstLinkClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.maxmfonseca.pontinho.R
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import java.util.Locale

class AboutScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun aboutContainsOnlyKnownFactsAndSupportAddress() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.setContent { AboutScreen(versionName = "1.0.0", onBack = {}, onLearn = {}) }

        composeRule.onNodeWithTag("about_root").assertIsDisplayed()
        composeRule.onNodeWithText("Pontinho").assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.about_version, "1.0.0")).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.about_purpose)).assertIsDisplayed()
        composeRule.onNodeWithText("maxmfonseca12@gmail.com").assertIsDisplayed()
        composeRule.onNodeWithTag("about_learn").assertDoesNotExist()
        composeRule.onNodeWithText("Privacidade").assertDoesNotExist()
        composeRule.onNodeWithText("Licenças").assertDoesNotExist()
        composeRule.onNodeWithText("Câmera").assertDoesNotExist()
    }

    @Test fun purposeContainsExactlyOneInlineLearnLinkAcrossLocalesAndFontScales() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        var scenario by mutableStateOf("pt-BR" to 1f)
        var learnClicks = 0
        composeRule.setContent {
            val (locale, scale) = scenario
            val context = base.createConfigurationContext(Configuration(base.resources.configuration).apply {
                setLocale(Locale.forLanguageTag(locale))
            })
            CompositionLocalProvider(LocalContext provides context, LocalResources provides context.resources,
                LocalDensity provides Density(base.resources.displayMetrics.density, scale)) {
                Box(Modifier.size(320.dp, 568.dp)) {
                    AboutScreen("1.0.0", {}, { learnClicks++ })
                }
            }
        }

        val copies = listOf(
            Triple("pt-BR", "Marcador offline para partidas presenciais do jogo de cartas Pontinho. Aprenda a jogar.", "Aprenda a jogar"),
            Triple("en-US", "Offline scorekeeper for in-person games of the Pontinho card game. Learn to play.", "Learn to play"),
        )
        for ((locale, copy, phrase) in copies) for (scale in listOf(1f, 1.5f, 2f)) {
            composeRule.runOnIdle { scenario = locale to scale }
            val purpose = composeRule.onNodeWithText(copy).performScrollTo().assertIsDisplayed().assertHasNoClickAction()
            val annotated = purpose.fetchSemanticsNode().config[SemanticsProperties.Text].single()
            val link = annotated.getLinkAnnotations(0, annotated.length).single()
            assertEquals(phrase, annotated.text.substring(link.start, link.end))
            assertEquals(copy.length - 1, link.end)
            composeRule.onAllNodes(hasClickAction() and hasAnyAncestor(hasText(copy)), useUnmergedTree = true)
                .assertCountEquals(1)
            composeRule.onNodeWithTag("about_learn").assertDoesNotExist()
            val previousClicks = learnClicks
            purpose.performFirstLinkClick()
            composeRule.runOnIdle { assertEquals(previousClicks + 1, learnClicks) }
        }
    }

    @Test fun supportRemainsReachableAtOnePointFiveFontScaleOnASmallPhone() {
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.5f)) {
                Box(Modifier.size(width = 320.dp, height = 480.dp)) {
                    AboutScreen(versionName = "1.0.0", onBack = {}, onLearn = {})
                }
            }
        }

        composeRule.onNodeWithTag("about_support_email").performScrollTo().assertIsDisplayed()
    }
}
