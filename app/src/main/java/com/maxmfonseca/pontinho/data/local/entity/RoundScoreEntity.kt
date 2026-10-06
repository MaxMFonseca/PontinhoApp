package com.maxmfonseca.pontinho.data.local.entity
import androidx.room.*

@Entity(
    tableName = "scores",
    foreignKeys = [
        ForeignKey(entity = RoundEntity::class, parentColumns = ["matchId", "id"], childColumns = ["matchId", "roundId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = MatchParticipantEntity::class, parentColumns = ["matchId", "id"], childColumns = ["matchId", "participantId"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [Index(value = ["roundId", "participantId"], unique = true),
        Index(value = ["submissionToken"], unique = true),
        Index(value = ["matchId", "roundId"]), Index(value = ["matchId", "participantId"])],
)
data class RoundScoreEntity(
    @PrimaryKey val id: String,
    val matchId: String,
    val roundId: String,
    val participantId: String,
    val points: Int,
    val origin: String,
    val submittedAt: Long,
    val submissionToken: String?,
    val recognitionModelVersion: String? = null,
)
