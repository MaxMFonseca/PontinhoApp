package com.maxmfonseca.pontinho.data.repository

import androidx.room.withTransaction
import com.maxmfonseca.pontinho.data.local.PontinhoDatabase
import com.maxmfonseca.pontinho.data.local.entity.PlayerEntity
import com.maxmfonseca.pontinho.data.local.mapper.toDomain
import com.maxmfonseca.pontinho.domain.model.PlayerId
import com.maxmfonseca.pontinho.domain.repository.*
import kotlinx.coroutines.flow.map
import java.time.Clock
import java.util.Locale
import java.util.UUID

class RoomPlayerRepository(private val db: PontinhoDatabase, private val clock: Clock = Clock.systemDefaultZone()) : PlayerRepository {
    override fun observePlayers() = db.playerDao().observePlayers().map { rows -> rows.map { it.toDomain() } }

    override suspend fun upsert(command: UpsertPlayer): PlayerId = db.withTransaction {
        val name = command.displayName.trim()
        require(name.isNotEmpty()) { "Player name must not be blank" }
        val id = command.id ?: PlayerId(UUID.randomUUID().toString())
        val old = db.playerDao().get(id.value)
        val now = clock.millis()
        db.playerDao().upsert(PlayerEntity(id.value, name, name.lowercase(Locale.ROOT), old?.createdAt ?: now, now, old?.archivedAt))
        id
    }
}
