package com.maxmfonseca.pontinho.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "card_observations",
    foreignKeys = [
        ForeignKey(
            entity = RoundScoreEntity::class,
            parentColumns = ["id"],
            childColumns = ["scoreId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["scoreId", "ordinal"], unique = true),
    ],
)
data class CardObservationEntity(
    @PrimaryKey val id: String,
    val scoreId: String,
    val ordinal: Int,
    val rank: String,
    val suit: String,
    val confidence: Float?,
    val correctedByUser: Boolean,
    val left: Float?,
    val top: Float?,
    val right: Float?,
    val bottom: Float?,
)
