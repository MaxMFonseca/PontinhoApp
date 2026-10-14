package com.maxmfonseca.pontinho.data.local.entity
import androidx.room.*

@Entity(
    tableName = "rounds",
    foreignKeys = [
        ForeignKey(entity = MatchEntity::class, parentColumns = ["id"], childColumns = ["matchId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = MatchParticipantEntity::class, parentColumns = ["matchId", "id"], childColumns = ["matchId", "dealerParticipantId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = MatchParticipantEntity::class, parentColumns = ["matchId", "id"], childColumns = ["matchId", "designatedWinnerParticipantId"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [Index(value = ["matchId", "ordinal"], unique = true),
        Index(value = ["matchId", "id"], unique = true),
        Index(value = ["matchId", "openSlot"], unique = true),
        Index(value = ["matchId", "dealerParticipantId"]),
        Index(value = ["matchId", "designatedWinnerParticipantId"])],
)
data class RoundEntity(
    @PrimaryKey val id: String,
    val matchId: String,
    val ordinal: Int,
    val indicatorRank: String?,
    val indicatorColor: String?,
    val dealerParticipantId: String,
    val eligibleParticipantCount: Int,
    val designatedWinnerParticipantId: String? = null,
    val designationOrigin: String? = null,
    val status: String = "OPEN",
    // NULL on completed/paused rounds; unique (matchId, openSlot) permits just one open round.
    val openSlot: Int? = 1,
    val createdAt: Long,
    val completedAt: Long? = null,
    val nextDealerParticipantId: String? = null,
    // Reopened history defers results until its correction is completed, unlike an ordinary draft.
    val isCorrection: Boolean = false,
    // A persistent stack order preserves nested corrections across database reopen.
    val pauseOrder: Int? = null,
)
