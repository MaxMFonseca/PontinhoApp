package com.maxmfonseca.pontinho.di

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import com.maxmfonseca.pontinho.data.local.PontinhoDatabase
import com.maxmfonseca.pontinho.data.repository.RoomMatchRepository
import com.maxmfonseca.pontinho.data.repository.RoomPlayerRepository
import com.maxmfonseca.pontinho.domain.repository.MatchRepository
import com.maxmfonseca.pontinho.domain.repository.PlayerRepository
import com.maxmfonseca.pontinho.domain.recognition.CardRecognitionEngineFactory
import com.maxmfonseca.pontinho.domain.recognition.CardRecognitionEngineProvider
import com.maxmfonseca.pontinho.recognition.AssetGatedCardRecognitionEngineFactory
import com.maxmfonseca.pontinho.recognition.MediaPipeCardRecognitionEngineFactory
import com.maxmfonseca.pontinho.recognition.ProcessLifetimeCardRecognitionEngineProvider
import com.maxmfonseca.pontinho.recognition.RecognitionAssetVerifier
import com.maxmfonseca.pontinho.recognition.UnavailableCardRecognitionEngineProvider
import com.maxmfonseca.pontinho.settings.DataStorePreferencesRepository
import com.maxmfonseca.pontinho.settings.PreferencesRepository
import java.io.IOException

interface AppContainer {
    val playerRepository: PlayerRepository
    val matchRepository: MatchRepository
    val preferencesRepository: PreferencesRepository
    val cardRecognitionEngineProvider: CardRecognitionEngineProvider
        get() = UnavailableCardRecognitionEngineProvider
}

class DefaultAppContainer internal constructor(
    applicationContext: Context,
    recognitionEngineFactory: CardRecognitionEngineFactory? = MediaPipeCardRecognitionEngineFactory(applicationContext),
) : AppContainer {
    private val database = Room.databaseBuilder(
        applicationContext,
        PontinhoDatabase::class.java,
        "pontinho.db",
    ).build()

    private val preferencesDataStore = PreferenceDataStoreFactory.create {
        applicationContext.preferencesDataStoreFile("app_preferences")
    }

    override val playerRepository: PlayerRepository = RoomPlayerRepository(database)
    override val matchRepository: MatchRepository = RoomMatchRepository(database)
    override val preferencesRepository: PreferencesRepository =
        DataStorePreferencesRepository(preferencesDataStore)

    override val cardRecognitionEngineProvider: CardRecognitionEngineProvider =
        ProcessLifetimeCardRecognitionEngineProvider {
            AssetGatedCardRecognitionEngineFactory(
                verifier = RecognitionAssetVerifier(),
                delegate = recognitionEngineFactory,
            ).create(
                model = applicationContext.readOptionalAsset(MODEL_ASSET),
                releaseJson = applicationContext.readOptionalAsset(RELEASE_ASSET),
            )
        }

    private companion object {
        const val MODEL_ASSET = "card_detector.tflite"
        const val RELEASE_ASSET = "card_detector.release.json"
    }
}

private fun Context.readOptionalAsset(name: String): ByteArray? = try {
    assets.open(name).use { it.readBytes() }
} catch (_: IOException) {
    null
}
