package com.maxmfonseca.pontinho.ui.scanner

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/** Exercises the resource IDs consumed by the screen against both installed app locales. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ScannerMessageLocalizationTest {
    @Test fun allMessagesResolveInPortugueseIncludingCaptureLimitsAndSpecificRecovery() {
        verify("pt-BR", mapOf(
            ScannerMessage.CaptureGuidance to listOf("no máximo 10", "separadas", "inteiras", "reflexos", "sombras", "duas fotos"),
            ScannerMessage.NoCards to listOf("Nenhuma carta", "outra foto"),
            ScannerMessage.TooManyCards to listOf("Mais de 10", "no máximo 10", "outra foto"),
            ScannerMessage.IncompleteCards to listOf("cortadas", "inteiras"),
            ScannerMessage.ImageTooLarge to listOf("resolução menor", "legíveis"),
            ScannerMessage.Unavailable to listOf("indisponível", "Volte"),
            ScannerMessage.RecognitionFailed to listOf("reconhecer", "outra foto"),
            ScannerMessage.SaveFailed to listOf("gravação", "novamente"),
            ScannerMessage.SaveInterrupted to listOf("interrompida", "Confirme"),
            ScannerMessage.ImageExpired to listOf("não está mais disponível", "outra foto"),
        ))
    }

    @Test fun allMessagesResolveInEnglishIncludingCaptureLimitsAndSpecificRecovery() {
        verify("en-US", mapOf(
            ScannerMessage.CaptureGuidance to listOf("at most 10", "separated", "fully inside", "glare", "shadows", "two photos"),
            ScannerMessage.NoCards to listOf("No cards", "another photo"),
            ScannerMessage.TooManyCards to listOf("More than 10", "at most 10", "another photo"),
            ScannerMessage.IncompleteCards to listOf("cut off", "fully inside"),
            ScannerMessage.ImageTooLarge to listOf("lower resolution", "readable"),
            ScannerMessage.Unavailable to listOf("unavailable", "Go back"),
            ScannerMessage.RecognitionFailed to listOf("recognize", "another photo"),
            ScannerMessage.SaveFailed to listOf("save", "again"),
            ScannerMessage.SaveInterrupted to listOf("interrupted", "Confirm"),
            ScannerMessage.ImageExpired to listOf("no longer available", "another photo"),
        ))
    }

    @Test fun recoveryMessagesDoNotOfferRemovedGalleryOrManualActions() {
        listOf("pt-BR", "en-US").forEach { locale ->
            val context = ApplicationProvider.getApplicationContext<Context>()
            val config = Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(locale)) }
            val localized = context.createConfigurationContext(config)
            ScannerMessage.entries.filterNot { it == ScannerMessage.CaptureGuidance }.forEach { message ->
                val text = localized.getString(message.stringResourceId()).lowercase()
                listOf("manualmente", "manually", "escolha uma foto", "choose a photo").forEach { removed ->
                    assertFalse("$locale $message still offers removed action '$removed': $text", text.contains(removed))
                }
            }
        }
    }

    private fun verify(locale: String, meanings: Map<ScannerMessage, List<String>>) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val config = Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(locale)) }
        val localized = context.createConfigurationContext(config)
        meanings.forEach { (message, fragments) ->
            val text = localized.getString(message.stringResourceId())
            fragments.forEach { assertTrue("$locale $message must include $it: $text", text.contains(it)) }
        }
    }
}
