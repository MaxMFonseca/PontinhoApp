package com.maxmfonseca.pontinho.ui.about

import android.content.res.Configuration
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
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
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.espresso.Espresso.pressBack
import com.maxmfonseca.pontinho.R
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import java.util.Locale

class AboutScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun aboutExposesPrivacyAndLicensesAction() {
        var opens = 0
        composeRule.setContent {
            AboutScreen(
                versionName = "1.0.0",
                onBack = {},
                onLearn = {},
                onPrivacyLicenses = { opens++ },
                experimentalCardScanningEnabled = false,
                onExperimentalCardScanningEnabledChange = {},
            )
        }

        composeRule.onNodeWithTag("about_privacy_licenses").performScrollTo().performClick()
        composeRule.runOnIdle { assertEquals(1, opens) }
    }

    @Test fun aboutContainsOnlyKnownFactsAndSupportAddress() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.setContent {
            AboutScreen("1.0.0", {}, {}, false, {}, onPrivacyLicenses = {})
        }

        composeRule.onNodeWithTag("about_root").assertIsDisplayed()
        composeRule.onNodeWithText("Pontinho").assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.about_version, "1.0.0")).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.about_purpose)).assertIsDisplayed()
        composeRule.onNodeWithText("maxmfonseca12@gmail.com").assertIsDisplayed()
        composeRule.onNodeWithTag("about_learn").assertDoesNotExist()
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
                    AboutScreen("1.0.0", {}, { learnClicks++ }, false, {})
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
                    AboutScreen(
                        versionName = "1.0.0",
                        onBack = {},
                        onLearn = {},
                        onPrivacyLicenses = {},
                        experimentalCardScanningEnabled = false,
                        onExperimentalCardScanningEnabledChange = {},
                    )
                }
            }
        }

        composeRule.onNodeWithTag("about_support_email").performScrollTo().assertIsDisplayed()
    }

    @Test fun educationRequiresExplicitOptInAndCanBeDisabledAgain() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var enabled by mutableStateOf(false)
        val changes = mutableListOf<Boolean>()
        composeRule.setContent {
            CameraEducationScreen(
                experimentalCardScanningEnabled = enabled,
                onExperimentalCardScanningEnabledChange = { changes += it; enabled = it },
                onBack = {},
            )
        }

        composeRule.onNodeWithText(context.getString(R.string.camera_education_experimental)).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("camera_experimental_toggle").performScrollTo().assertIsOff().performClick()
        composeRule.onNodeWithTag("camera_experimental_toggle").assertIsOn().performClick()
        composeRule.onNodeWithTag("camera_experimental_toggle").assertIsOff()
        composeRule.runOnIdle { assertEquals(listOf(true, false), changes) }
    }

    @Test fun systemBackLeavesExperimentalPreferenceUnchanged() {
        var backCalls = 0
        var preferenceWrites = 0
        composeRule.setContent {
            CameraEducationScreen(
                experimentalCardScanningEnabled = false,
                onExperimentalCardScanningEnabledChange = { preferenceWrites++ },
                onBack = { backCalls++ },
            )
        }

        pressBack()
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            assertEquals(1, backCalls)
            assertEquals(0, preferenceWrites)
        }
    }

    @Test fun aboutOpensReusableEducationWithCompleteCopyInBothLocales() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        var locale by mutableStateOf("pt-BR")
        var preferenceWrites = 0
        composeRule.setContent {
            val localized = base.createConfigurationContext(Configuration(base.resources.configuration).apply {
                setLocale(Locale.forLanguageTag(locale))
            })
            CompositionLocalProvider(LocalContext provides localized, LocalResources provides localized.resources) {
                AboutScreen(
                    versionName = "1.0.0",
                    onBack = {},
                    onLearn = {},
                    onPrivacyLicenses = {},
                    experimentalCardScanningEnabled = false,
                    onExperimentalCardScanningEnabledChange = { preferenceWrites++ },
                )
            }
        }

        val copies = mapOf(
            "pt-BR" to listOf(
                "A foto é processada inteiramente neste dispositivo. O aplicativo não envia nem retém a imagem.",
                "Para obter melhores resultados, use boa iluminação, evite reflexos e mantenha todas as cartas totalmente visíveis e sem sobreposição.",
                "Revise sempre as cartas reconhecidas e o total antes de salvar. Essa revisão é obrigatória.",
                "Se a leitura não funcionar, volte e digite a pontuação manualmente.",
                "Recurso experimental. O reconhecimento foi treinado com variedade limitada de baralhos e pode errar cartas e totais. Ative somente se você concorda em conferir e corrigir todas as cartas antes de confirmar.",
            ),
            "en-US" to listOf(
                "The photo is processed entirely on this device. The app does not upload or retain the image.",
                "For best results, use good lighting, avoid glare, and keep every card fully visible and free of overlap.",
                "Always review the recognized cards and total before saving. This review is required.",
                "If scanning does not work, go back and enter the score manually.",
                "Experimental feature. Recognition was trained with a limited variety of decks and may get cards and totals wrong. Enable it only if you agree to review and correct every card before confirming.",
            ),
        )
        for (tag in listOf("pt-BR", "en-US")) {
            composeRule.runOnIdle { locale = tag }
            val writesBeforeOpen = preferenceWrites
            val localized = base.createConfigurationContext(Configuration(base.resources.configuration).apply {
                setLocale(Locale.forLanguageTag(tag))
            })
            composeRule.onNodeWithTag("about_camera_education").performScrollTo().performClick()
            copies.getValue(tag).forEach { copy ->
                composeRule.onNodeWithText(copy).performScrollTo().assertIsDisplayed()
            }
            composeRule.runOnIdle { assertEquals(writesBeforeOpen, preferenceWrites) }
            composeRule.onNodeWithText(localized.getString(R.string.camera_education_done)).performScrollTo().performClick()
            composeRule.runOnIdle { assertEquals(writesBeforeOpen, preferenceWrites) }
            composeRule.onNodeWithTag("about_root").assertIsDisplayed()
        }
    }
}
