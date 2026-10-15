package com.maxmfonseca.pontinho.ui.about

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Locale
import org.junit.Rule
import org.junit.Test

class PrivacyLicensesScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun disclosuresRemainReachableInBothLocalesAtLargeText() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val locale = mutableStateOf("pt-BR")
        composeRule.setContent {
            val localized = base.createConfigurationContext(Configuration(base.resources.configuration).apply {
                setLocale(Locale.forLanguageTag(locale.value))
            })
            CompositionLocalProvider(
                LocalContext provides localized,
                LocalResources provides localized.resources,
                LocalDensity provides Density(base.resources.displayMetrics.density, 1.5f),
            ) {
                Box(Modifier.size(320.dp, 480.dp)) {
                    PrivacyLicensesScreen(onBack = {})
                }
            }
        }

        val expected = mapOf(
            "pt-BR" to listOf(
                "Dados no dispositivo",
                "As fotos são processadas localmente, não são enviadas e são descartadas após a leitura.",
                "Esta versão não contém anúncios, análises, contas, rastreamento nem comunicação de rede.",
                "AGPL-3.0-or-later",
                "Origem do modelo",
                "TODO:" + "Max",
            ),
            "en-US" to listOf(
                "On-device data",
                "Photos are processed locally, are not uploaded, and are discarded after scanning.",
                "This release contains no ads, analytics, accounts, tracking, or network communication.",
                "AGPL-3.0-or-later",
                "Model provenance",
                "TODO:" + "Max",
            ),
        )
        expected.forEach { (tag, texts) ->
            composeRule.runOnIdle { locale.value = tag }
            composeRule.onNodeWithTag("privacy_licenses_root").assertIsDisplayed()
            texts.forEach { text -> composeRule.onNodeWithText(text, substring = true).performScrollTo().assertIsDisplayed() }
        }
    }
}
