package com.maxmfonseca.pontinho.data.local
import androidx.room.Database
import androidx.room.RoomDatabase
import com.maxmfonseca.pontinho.data.local.dao.*
import com.maxmfonseca.pontinho.data.local.entity.*

@Database(entities = [PlayerEntity::class, MatchEntity::class, MatchParticipantEntity::class,
    RoundEntity::class, RoundScoreEntity::class, CorrectionEventEntity::class, SubmissionEntity::class,
    CardObservationEntity::class],
    version = 3, exportSchema = true)
abstract class PontinhoDatabase : RoomDatabase() {
    abstract fun playerDao(): PlayerDao
    abstract fun matchDao(): MatchDao
    abstract fun roundDao(): RoundDao
}
