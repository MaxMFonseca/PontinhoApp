package com.maxmfonseca.pontinho.data.local.dao
import androidx.room.*
import com.maxmfonseca.pontinho.data.local.entity.PlayerEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PlayerDao {
    @Upsert suspend fun upsert(player: PlayerEntity)
    @Query("SELECT * FROM players WHERE id = :id") suspend fun get(id: String): PlayerEntity?
    @Query("SELECT * FROM players ORDER BY id") suspend fun players(): List<PlayerEntity>
    @Query("SELECT * FROM players WHERE archivedAt IS NULL ORDER BY normalizedName, id")
    fun observePlayers(): Flow<List<PlayerEntity>>
}
