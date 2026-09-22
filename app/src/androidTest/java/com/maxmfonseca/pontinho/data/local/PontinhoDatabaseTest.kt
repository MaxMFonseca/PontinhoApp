package com.maxmfonseca.pontinho.data.local

import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maxmfonseca.pontinho.data.local.entity.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PontinhoDatabaseTest {
    private lateinit var db: PontinhoDatabase

    @Before fun open() { db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), PontinhoDatabase::class.java).build() }
    @After fun close() { db.close() }

    private suspend fun seed() {
        db.playerDao().upsert(PlayerEntity("p1", "Ana", "ana", 1, 1))
        db.playerDao().upsert(PlayerEntity("p2", "Ana", "ana", 1, 1))
        db.matchDao().insertMatch(MatchEntity("m", "2026-10-13", "America/Sao_Paulo", 1, 1, dealerParticipantId = "a"))
        db.matchDao().insertParticipant(MatchParticipantEntity("a", "m", "p1", "Ana", 0, 1, 1))
        db.matchDao().insertParticipant(MatchParticipantEntity("b", "m", "p2", "Ana", 1, 2, 1))
        db.roundDao().insertRound(RoundEntity("r", "m", 1, "ACE", "RED", "a", 2, createdAt = 1))
    }

    private suspend fun constraint(block: suspend () -> Unit) {
        try { block(); fail("Expected a database constraint") } catch (_: SQLiteConstraintException) { }
    }

    @Test fun rosterIdentityAndOrderAreUniqueButNamesAreNot() = runBlocking {
        seed()
        constraint { db.matchDao().insertParticipant(MatchParticipantEntity("c", "m", "p1", "Other", 2, 1, 1)) }
        db.playerDao().upsert(PlayerEntity("p3", "Ana", "ana", 1, 1))
        constraint { db.matchDao().insertParticipant(MatchParticipantEntity("c", "m", "p3", "Ana", 1, 1, 1)) }
        assertEquals(listOf("a", "b"), db.matchDao().participants("m").map { it.id })
    }

    @Test fun roundOrdinalAndScoreParticipantAreUniqueAndForeignKeysAreEnforced() = runBlocking {
        seed()
        constraint { db.roundDao().insertRound(RoundEntity("r2", "m", 1, "ACE", "RED", "a", 2, createdAt = 1)) }
        db.roundDao().insertScore(RoundScoreEntity("s", "m", "r", "a", 0, "MANUAL", 1, "t"))
        constraint { db.roundDao().insertScore(RoundScoreEntity("s2", "m", "r", "a", 5, "MANUAL", 1, "t2")) }
        constraint { db.roundDao().insertScore(RoundScoreEntity("s3", "m", "missing", "a", 5, "MANUAL", 1, "t3")) }
        constraint { db.roundDao().insertScore(RoundScoreEntity("s4", "m", "r", "missing", 5, "MANUAL", 1, "t4")) }
    }

    @Test fun eligibleQueryOmitsLatePlayersWithoutMakingHistoricalScores() = runBlocking {
        seed()
        assertEquals(listOf("a"), db.matchDao().eligibleParticipants("m", 1).map { it.id })
        assertEquals(listOf("a", "b"), db.matchDao().eligibleParticipants("m", 2).map { it.id })
        assertTrue(db.roundDao().scores("r").isEmpty())
    }

    @Test fun playerAndMatchFlowsHaveStableOrdering() = runBlocking {
        seed()
        db.playerDao().upsert(PlayerEntity("p3", "Zoe", "zoe", 2, 2))
        db.matchDao().insertMatch(MatchEntity("m2", "2026-10-14", "UTC", 2, 2, dealerParticipantId = "c"))
        assertEquals(listOf("p1", "p2", "p3"), db.playerDao().observePlayers().first().map { it.id })
        assertEquals(listOf("m2", "m"), db.matchDao().observeMatches().first().map { it.id })
    }

    @Test fun onlyOneRoundMayBeOpenAndCrossMatchScoresAreRejected() = runBlocking {
        seed()
        constraint { db.roundDao().insertRound(RoundEntity("r2", "m", 2, "ACE", "RED", "a", 2, createdAt = 2)) }
        db.matchDao().insertMatch(MatchEntity("other", "2026-10-13", "UTC", 1, 1, dealerParticipantId = "c"))
        db.matchDao().insertParticipant(MatchParticipantEntity("c", "other", "p1", "Ana", 0, 1, 1))
        constraint { db.roundDao().insertScore(RoundScoreEntity("s", "m", "r", "c", 5, "MANUAL", 1, "cross")) }
    }
}
