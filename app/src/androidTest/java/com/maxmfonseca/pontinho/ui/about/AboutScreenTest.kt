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

    @Test fun aboutContainsOnlyKnownFactsAndSupportAddress() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.setContent {
            AboutScreen(versionName = "1.0.0", onBack = {}, onLearn = {}, onCameraEducationSeen = {})
        }

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
                    AboutScreen("1.0.0", {}, { learnClicks++ }, {})
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
                        onCameraEducationSeen = {},
                    )
                }
            }
        }

        composeRule.onNodeWithTag("about_support_email").performScrollTo().assertIsDisplayed()
    }

    @Test fun firstScannerUseRoutesToEducationWithoutRunningAnAction() {
        assertEquals(ScannerEntryRoute.Education, scannerEntryRoute(cameraEducationSeen = false))
        assertEquals(ScannerEntryRoute.Capture, scannerEntryRoute(cameraEducationSeen = true))

        var seenCalls = 0
        var continueCalls = 0
        var dismissCalls = 0
        composeRule.setContent {
            CameraEducationScreen(
                onEducationSeen = { seenCalls++ },
                onContinue = { continueCalls++ },
                onDismiss = { dismissCalls++ },
            )
        }

        composeRule.runOnIdle {
            assertEquals(0, seenCalls)
            assertEquals(0, continueCalls)
            assertEquals(0, dismissCalls)
        }
    }

    @Test fun educationMarksSeenOnlyOnContinueOrExplicitDismiss() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var seenCalls = 0
        var continueCalls = 0
        var dismissCalls = 0
        composeRule.setContent {
            CameraEducationScreen(
                onEducationSeen = { seenCalls++ },
                onContinue = { continueCalls++ },
                onDismiss = { dismissCalls++ },
            )
        }

        composeRule.onNodeWithText(context.getString(R.string.camera_education_continue)).performScrollTo().performClick()
        composeRule.runOnIdle {
            assertEquals(1, seenCalls)
            assertEquals(1, continueCalls)
            assertEquals(0, dismissCalls)
        }
    }

    @Test fun systemBackExplicitlyDismissesEducationExactlyOnce() {
        var seenCalls = 0
        var dismissCalls = 0
        composeRule.setContent {
            CameraEducationScreen(
                onEducationSeen = { seenCalls++ },
                onContinue = {},
                onDismiss = { dismissCalls++ },
            )
        }

        pressBack()
        composeRule.waitForIdle()
        pressBack()
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            assertEquals(1, seenCalls)
            assertEquals(1, dismissCalls)
        }
    }

    @Test fun aboutOpensReusableEducationWithCompleteCopyInBothLocales() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        var locale by mutableStateOf("pt-BR")
        var seenCalls = 0
        composeRule.setContent {
            val localized = base.createConfigurationContext(Configuration(base.resources.configuration).apply {
                setLocale(Locale.forLanguageTag(locale))
            })
            CompositionLocalProvider(LocalContext provides localized, LocalResources provides localized.resources) {
                AboutScreen(
                    versionName = "1.0.0",
                    onBack = {},
                    onLearn = {},
                    onCameraEducationSeen = { seenCalls++ },
                )
            }
        }

        val copies = mapOf(
            "pt-BR" to listOf(
                "A foto é processada inteiramente neste dispositivo. O aplicativo não envia nem retém a imagem.",
                "Para obter melhores resultados, use boa iluminação, evite reflexos e mantenha todas as cartas totalmente visíveis e sem sobreposição.",
                "Revise sempre as cartas reconhecidas e o total antes de salvar. Essa revisão é obrigatória.",
                "Se a leitura não funcionar, volte e digite a pontuação manualmente.",
            ),
            "en-US" to listOf(
                "The photo is processed entirely on this device. The app does not upload or retain the image.",
                "For best results, use good lighting, avoid glare, and keep every card fully visible and free of overlap.",
                "Always review the recognized cards and total before saving. This review is required.",
                "If scanning does not work, go back and enter the score manually.",
            ),
        )
        for (tag in listOf("pt-BR", "en-US")) {
            composeRule.runOnIdle { locale = tag }
            val seenBeforeOpen = seenCalls
            val localized = base.createConfigurationContext(Configuration(base.resources.configuration).apply {
                setLocale(Locale.forLanguageTag(tag))
            })
            composeRule.onNodeWithTag("about_camera_education").performScrollTo().performClick()
            copies.getValue(tag).forEach { copy ->
                composeRule.onNodeWithText(copy).performScrollTo().assertIsDisplayed()
            }
            composeRule.runOnIdle { assertEquals(seenBeforeOpen, seenCalls) }
            composeRule.onNodeWithText(localized.getString(R.string.camera_education_dismiss)).performScrollTo().performClick()
            composeRule.runOnIdle { assertEquals(seenBeforeOpen + 1, seenCalls) }
            composeRule.onNodeWithTag("about_root").assertIsDisplayed()
        }
    }
}
