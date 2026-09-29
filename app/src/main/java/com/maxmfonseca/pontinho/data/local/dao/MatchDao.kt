package com.maxmfonseca.pontinho.data.local.dao
import androidx.room.*
import com.maxmfonseca.pontinho.data.local.entity.*
import kotlinx.coroutines.flow.Flow

@Dao
interface MatchDao {
    @Insert suspend fun insertMatch(match: MatchEntity)
    @Update suspend fun updateMatch(match: MatchEntity)
    @Query("DELETE FROM matches WHERE id = :id") suspend fun deleteMatch(id: String): Int
    @Insert suspend fun insertParticipant(participant: MatchParticipantEntity)
    @Update suspend fun updateParticipant(participant: MatchParticipantEntity)
    @Insert suspend fun insertCorrection(event: CorrectionEventEntity)
    @Query("SELECT * FROM matches WHERE id = :id") suspend fun get(id: String): MatchEntity?
    @Query("SELECT * FROM matches WHERE creationToken = :token") suspend fun getByCreationToken(token: String): MatchEntity?
    @Query("SELECT * FROM matches ORDER BY createdAt DESC, id DESC") suspend fun matches(): List<MatchEntity>
    @Query("SELECT * FROM matches WHERE localDate = :localDate ORDER BY createdAt DESC, id DESC")
    suspend fun matchesForDate(localDate: String): List<MatchEntity>
    @Query("SELECT * FROM matches ORDER BY createdAt DESC, id DESC") fun observeMatches(): Flow<List<MatchEntity>>
    @Query("SELECT * FROM participants WHERE matchId = :matchId ORDER BY rosterOrder") suspend fun participants(matchId: String): List<MatchParticipantEntity>
    @Query("SELECT * FROM participants ORDER BY matchId, rosterOrder") suspend fun allParticipants(): List<MatchParticipantEntity>
    @Query("SELECT * FROM participants WHERE matchId = :matchId AND eligibleFromRoundOrdinal <= :ordinal ORDER BY rosterOrder")
    suspend fun eligibleParticipants(matchId: String, ordinal: Int): List<MatchParticipantEntity>
    @Query("SELECT * FROM corrections WHERE matchId = :matchId ORDER BY timestamp, id")
    suspend fun corrections(matchId: String): List<CorrectionEventEntity>
}
