package com.maxmfonseca.pontinho.domain.repository

import com.maxmfonseca.pontinho.domain.model.Player
import com.maxmfonseca.pontinho.domain.model.PlayerId
import kotlinx.coroutines.flow.Flow

data class UpsertPlayer(val id: PlayerId? = null, val displayName: String)

interface PlayerRepository {
    fun observePlayers(): Flow<List<Player>>
    suspend fun upsert(command: UpsertPlayer): PlayerId
}
