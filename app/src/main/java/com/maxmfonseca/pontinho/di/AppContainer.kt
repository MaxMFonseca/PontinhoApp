package com.maxmfonseca.pontinho.di

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import com.maxmfonseca.pontinho.data.local.PontinhoDatabase
import com.maxmfonseca.pontinho.data.local.MIGRATION_1_2
import com.maxmfonseca.pontinho.data.repository.RoomMatchRepository
import com.maxmfonseca.pontinho.data.repository.RoomPlayerRepository
import com.maxmfonseca.pontinho.domain.repository.MatchRepository
import com.maxmfonseca.pontinho.domain.repository.PlayerRepository
import com.maxmfonseca.pontinho.domain.recognition.CardRecognitionEngineFactory
import com.maxmfonseca.pontinho.domain.recognition.CardRecognitionEngineProvider
import com.maxmfonseca.pontinho.recognition.UnavailableCardRecognitionEngineProvider
import com.maxmfonseca.pontinho.recognition.VerifiedCardRecognitionEngineProvider
import com.maxmfonseca.pontinho.recognition.SingleStageCardRecognitionEngineFactory
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
    recognitionEngineFactory: CardRecognitionEngineFactory? = singleStageRecognitionEngineFactory(),
) : AppContainer {
    private val database = Room.databaseBuilder(
        applicationContext,
        PontinhoDatabase::class.java,
        "pontinho.db",
    ).addMigrations(MIGRATION_1_2).build()

    private val preferencesDataStore = PreferenceDataStoreFactory.create {
        applicationContext.preferencesDataStoreFile("app_preferences")
    }

    override val playerRepository: PlayerRepository = RoomPlayerRepository(database)
    override val matchRepository: MatchRepository = RoomMatchRepository(database)
    override val preferencesRepository: PreferencesRepository =
        DataStorePreferencesRepository(preferencesDataStore)

    override val cardRecognitionEngineProvider: CardRecognitionEngineProvider =
        VerifiedCardRecognitionEngineProvider(
            readAssets = {
                readSingleStageRecognitionAssets(applicationContext::readOptionalAsset)
            },
            factory = recognitionEngineFactory,
        )
}

internal fun singleStageRecognitionEngineFactory(): CardRecognitionEngineFactory =
    SingleStageCardRecognitionEngineFactory()

internal fun readSingleStageRecognitionAssets(
    readAsset: (String) -> ByteArray?,
): Pair<ByteArray?, ByteArray?> =
    readAsset(DETECTOR_ASSET) to readAsset(RELEASE_ASSET)

private const val DETECTOR_ASSET = "card_detector.tflite"
private const val RELEASE_ASSET = "card_recognition.release.json"

private fun Context.readOptionalAsset(name: String): ByteArray? = try {
    assets.open(name).use { it.readBytes() }
} catch (_: IOException) {
    null
}
