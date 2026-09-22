package com.maxmfonseca.pontinho.data.local.entity
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "players")
data class PlayerEntity(
    @PrimaryKey val id: String,
    val displayName: String,
    val normalizedName: String,
    val createdAt: Long,
    val updatedAt: Long,
    val archivedAt: Long? = null,
)
