package com.maxmfonseca.pontinho.data.local.entity
import androidx.room.*

@Entity(
    tableName = "participants",
    foreignKeys = [
        ForeignKey(entity = MatchEntity::class, parentColumns = ["id"], childColumns = ["matchId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = PlayerEntity::class, parentColumns = ["id"], childColumns = ["playerId"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [Index(value = ["matchId", "playerId"], unique = true),
        Index(value = ["matchId", "rosterOrder"], unique = true),
        Index(value = ["matchId", "id"], unique = true), Index("playerId")],
)
data class MatchParticipantEntity(
    @PrimaryKey val id: String,
    val matchId: String,
    val playerId: String,
    val nameSnapshot: String,
    val rosterOrder: Int,
    val eligibleFromRoundOrdinal: Int,
    val joinedAt: Long,
    val total: Int = 0,
)
