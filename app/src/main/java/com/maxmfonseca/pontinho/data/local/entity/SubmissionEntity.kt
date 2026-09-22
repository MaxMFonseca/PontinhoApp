package com.maxmfonseca.pontinho.data.local.entity
import androidx.room.*

/** Durable receipt, retained even when its score is corrected or deleted. */
@Entity(tableName = "submissions",
    foreignKeys = [ForeignKey(entity = MatchEntity::class, parentColumns = ["id"], childColumns = ["matchId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("matchId")])
data class SubmissionEntity(
    @PrimaryKey val token: String,
    val matchId: String,
    val roundId: String,
    val participantId: String,
    val points: Int,
)
