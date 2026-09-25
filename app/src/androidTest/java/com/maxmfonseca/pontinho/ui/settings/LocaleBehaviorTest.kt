package com.maxmfonseca.pontinho.ui.settings

import android.content.res.Configuration
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.maxmfonseca.pontinho.R
import com.maxmfonseca.pontinho.data.local.PontinhoDatabase
import com.maxmfonseca.pontinho.data.repository.RoomMatchRepository
import com.maxmfonseca.pontinho.domain.repository.CreateMatch
import com.maxmfonseca.pontinho.domain.repository.CreateMatchPlayer
import com.maxmfonseca.pontinho.settings.LanguagePreference
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class LocaleBehaviorTest {
    @Test fun resourcesCoverBothLocalesAndUnsupportedDevicesFallBackToPortuguese() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        fun localized(tag: String): String {
            val configuration = Configuration(context.resources.configuration).apply {
                setLocale(Locale.forLanguageTag(tag))
            }
            return context.createConfigurationContext(configuration).getString(R.string.home_new_match)
        }

        assertEquals("Nova partida", localized("pt-BR"))
        assertEquals("New match", localized("en-US"))
        assertEquals("Nova partida", localized("fr-FR"))
    }

    @Test fun changingAppLocaleDoesNotTranslateOrMutateStoredPlayerNames() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val database = Room.inMemoryDatabaseBuilder(context, PontinhoDatabase::class.java).build()
        val repository = RoomMatchRepository(database)
        val localeController = AndroidXAppLocaleController(context)
        try {
            val matchId = repository.createMatch(
                CreateMatch(
                    creationToken = "locale-data",
                    players = listOf(
                        CreateMatchPlayer("a", displayName = "Ágata"),
                        CreateMatchPlayer("b", displayName = "John"),
                    ),
                ),
            )
            val before = repository.observeMatch(matchId).first()!!.participants.map { it.displayName }

            localeController.apply(LanguagePreference.ENGLISH_UNITED_STATES)
            localeController.apply(LanguagePreference.PORTUGUESE_BRAZIL)

            assertEquals(before, repository.observeMatch(matchId).first()!!.participants.map { it.displayName })
        } finally {
            localeController.apply(LanguagePreference.SYSTEM)
            database.close()
        }
    }
}
