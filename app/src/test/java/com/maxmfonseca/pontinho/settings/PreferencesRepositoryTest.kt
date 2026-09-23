package com.maxmfonseca.pontinho.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.UUID

class PreferencesRepositoryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder(File("build"))

    private lateinit var scope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var repository: PreferencesRepository

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        dataStore = PreferenceDataStoreFactory.create(scope = scope) {
            temporaryFolder.root.resolve("preferences-${UUID.randomUUID()}.preferences_pb")
        }
        repository = DataStorePreferencesRepository(dataStore)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun defaultsUseSystemThemeDeviceLanguageAndSystemMotion() = runBlocking {
        assertEquals(
            AppPreferences(
                theme = ThemePreference.SYSTEM,
                language = LanguagePreference.SYSTEM,
                reducedMotion = null,
            ),
            repository.preferences.first(),
        )
    }

    @Test
    fun explicitDarkThemePersists() = runBlocking {
        repository.setTheme(ThemePreference.DARK)
        val restored = DataStorePreferencesRepository(dataStore).preferences.first()
        assertEquals(ThemePreference.DARK, restored.theme)
    }

    @Test
    fun explicitBrazilianPortuguesePersists() = runBlocking {
        repository.setLanguage(LanguagePreference.PORTUGUESE_BRAZIL)
        val restored = DataStorePreferencesRepository(dataStore).preferences.first()
        assertEquals(LanguagePreference.PORTUGUESE_BRAZIL, restored.language)
    }

    @Test
    fun explicitUnitedStatesEnglishPersists() = runBlocking {
        repository.setLanguage(LanguagePreference.ENGLISH_UNITED_STATES)
        val restored = DataStorePreferencesRepository(dataStore).preferences.first()
        assertEquals(LanguagePreference.ENGLISH_UNITED_STATES, restored.language)
    }

    @Test
    fun explicitReducedMotionPersists() = runBlocking {
        repository.setReducedMotion(true)
        val restored = DataStorePreferencesRepository(dataStore).preferences.first()
        assertEquals(true, restored.reducedMotion)
    }

    @Test
    fun unsupportedDeviceLocaleFallsBackToBrazilianPortuguese() {
        assertEquals("pt-BR", LanguagePreference.SYSTEM.resolve("fr-FR"))
        assertEquals("pt-BR", LanguagePreference.SYSTEM.resolve("pt-PT"))
        assertEquals("en-US", LanguagePreference.SYSTEM.resolve("en-US"))
        assertEquals("pt-BR", LanguagePreference.SYSTEM.resolve("pt-BR"))
        assertEquals("en-US", LanguagePreference.ENGLISH_UNITED_STATES.resolve("fr-FR"))
    }

    @Test
    fun reducedMotionUsesSystemUntilExplicitlyOverridden() {
        val defaults = AppPreferences()
        assertTrue(defaults.resolveReducedMotion(systemReducedMotion = true))
        assertFalse(defaults.resolveReducedMotion(systemReducedMotion = false))
        assertTrue(defaults.copy(reducedMotion = true).resolveReducedMotion(systemReducedMotion = false))
        assertFalse(defaults.copy(reducedMotion = false).resolveReducedMotion(systemReducedMotion = true))
    }

    @Test
    fun readErrorsEmitDefaultsInsteadOfCrashingStartup() = runBlocking {
        val failingStore = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow { throw IOException("disk unavailable") }
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
                throw IOException("disk unavailable")
        }

        val value = DataStorePreferencesRepository(failingStore).preferences.first()

        assertEquals(AppPreferences(), value)
    }

    @Test
    fun unexpectedReadErrorsAlsoEmitDefaultsInsteadOfCrashingStartup() = runBlocking {
        val failingStore = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow { throw IllegalStateException("unreadable state") }
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
                error("not used")
        }

        val value = DataStorePreferencesRepository(failingStore).preferences.first()

        assertEquals(AppPreferences(), value)
    }
}
