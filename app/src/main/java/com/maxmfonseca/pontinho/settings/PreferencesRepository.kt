package com.maxmfonseca.pontinho.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.CancellationException

interface PreferencesRepository {
    val preferences: Flow<AppPreferences>

    suspend fun setTheme(theme: ThemePreference)
    suspend fun setLanguage(language: LanguagePreference)
    suspend fun setReducedMotion(reducedMotion: Boolean?)
    suspend fun setCameraEducationSeen(seen: Boolean)
}

class DataStorePreferencesRepository(
    private val dataStore: DataStore<Preferences>,
) : PreferencesRepository {
    override val preferences: Flow<AppPreferences> = dataStore.data
        .catch { error ->
            if (error is CancellationException) throw error
            emit(androidx.datastore.preferences.core.emptyPreferences())
        }
        .map { values ->
            AppPreferences(
                theme = values[THEME]?.toEnumOrDefault(ThemePreference.SYSTEM)
                    ?: ThemePreference.SYSTEM,
                language = values[LANGUAGE]?.toEnumOrDefault(LanguagePreference.SYSTEM)
                    ?: LanguagePreference.SYSTEM,
                reducedMotion = values[REDUCED_MOTION],
                cameraEducationSeen = runCatching { values[CAMERA_EDUCATION_SEEN] }
                    .getOrNull() ?: false,
            )
        }

    override suspend fun setTheme(theme: ThemePreference) {
        dataStore.edit { it[THEME] = theme.name }
    }

    override suspend fun setLanguage(language: LanguagePreference) {
        dataStore.edit { it[LANGUAGE] = language.name }
    }

    override suspend fun setReducedMotion(reducedMotion: Boolean?) {
        dataStore.edit {
            if (reducedMotion == null) it.remove(REDUCED_MOTION)
            else it[REDUCED_MOTION] = reducedMotion
        }
    }

    override suspend fun setCameraEducationSeen(seen: Boolean) {
        dataStore.edit { it[CAMERA_EDUCATION_SEEN] = seen }
    }

    private inline fun <reified T : Enum<T>> String.toEnumOrDefault(default: T): T =
        enumValues<T>().firstOrNull { it.name == this } ?: default

    private companion object {
        val THEME = stringPreferencesKey("theme")
        val LANGUAGE = stringPreferencesKey("language")
        val REDUCED_MOTION = booleanPreferencesKey("reduced_motion")
        val CAMERA_EDUCATION_SEEN = booleanPreferencesKey("camera_education_seen")
    }
}
