package com.maxmfonseca.pontinho.data.local.dao
import androidx.room.*
import com.maxmfonseca.pontinho.data.local.entity.*

@Dao
interface RoundDao {
    @Insert suspend fun insertRound(round: RoundEntity)
    @Update suspend fun updateRound(round: RoundEntity)
    @Insert suspend fun insertScore(score: RoundScoreEntity)
    @Update suspend fun updateScore(score: RoundScoreEntity)
    @Insert suspend fun insertSubmission(submission: SubmissionEntity)
    @Query("SELECT * FROM submissions WHERE token = :token") suspend fun submission(token: String): SubmissionEntity?
    @Query("SELECT * FROM rounds WHERE matchId = :matchId ORDER BY ordinal") suspend fun rounds(matchId: String): List<RoundEntity>
    @Query("SELECT * FROM rounds WHERE id = :id") suspend fun get(id: String): RoundEntity?
    @Query("SELECT * FROM scores WHERE roundId = :roundId ORDER BY participantId") suspend fun scores(roundId: String): List<RoundScoreEntity>
    @Query("DELETE FROM scores WHERE id = :id") suspend fun deleteScore(id: String)
}
