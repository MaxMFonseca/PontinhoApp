package com.maxmfonseca.pontinho.data.local.entity
import androidx.room.*

@Entity(tableName = "corrections",
    foreignKeys = [ForeignKey(entity = MatchEntity::class, parentColumns = ["id"], childColumns = ["matchId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("matchId")])
data class CorrectionEventEntity(
    @PrimaryKey val id: String,
    val matchId: String,
    val timestamp: Long,
    val operation: String,
    val beforePayload: String,
    val afterPayload: String,
)
