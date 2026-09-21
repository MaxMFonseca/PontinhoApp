package com.maxmfonseca.pontinho

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProjectConfigurationTest {
    @Test
    fun homeTitleUsesPortugueseEnglishAndPortugueseFallback() {
        val application = ApplicationProvider.getApplicationContext<Context>()

        assertEquals("Início", application.localizedString("pt-BR", R.string.home_title))
        assertEquals("Home", application.localizedString("en-US", R.string.home_title))
        assertEquals("Início", application.localizedString("es-ES", R.string.home_title))
    }

    private fun Context.localizedString(languageTag: String, stringId: Int): String {
        val localizedConfiguration = Configuration(resources.configuration).apply {
            setLocales(LocaleList(Locale.forLanguageTag(languageTag)))
        }
        return createConfigurationContext(localizedConfiguration).getString(stringId)
    }
}
