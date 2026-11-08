package com.maxmfonseca.pontinho.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "round_participants",
    primaryKeys = ["roundId", "participantId"],
    foreignKeys = [
        ForeignKey(entity = RoundEntity::class, parentColumns = ["matchId", "id"], childColumns = ["matchId", "roundId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = MatchParticipantEntity::class, parentColumns = ["matchId", "id"], childColumns = ["matchId", "participantId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["matchId", "roundId"]), Index(value = ["matchId", "participantId"])],
)
data class RoundParticipantEntity(val matchId: String, val roundId: String, val participantId: String)
