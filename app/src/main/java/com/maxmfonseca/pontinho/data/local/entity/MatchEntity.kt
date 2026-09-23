package com.maxmfonseca.pontinho.data.local.entity
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "matches",
    indices = [Index(value = ["creationToken"], unique = true)],
)
data class MatchEntity(
    @PrimaryKey val id: String,
    val localDate: String,
    val timeZoneId: String,
    val createdAt: Long,
    val lastPlayedAt: Long,
    val status: String = "ACTIVE",
    val finishedAt: Long? = null,
    val dealerParticipantId: String,
    val resultRevision: Long = 0,
    val acknowledgedResultRevision: Long = 0,
    val creationToken: String? = null,
    val creationFingerprint: String? = null,
)
