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
import com.maxmfonseca.pontinho.settings.DataStorePreferencesRepository
import com.maxmfonseca.pontinho.settings.PreferencesRepository

interface AppContainer {
    val playerRepository: PlayerRepository
    val matchRepository: MatchRepository
    val preferencesRepository: PreferencesRepository
}

class DefaultAppContainer(applicationContext: Context) : AppContainer {
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
}
