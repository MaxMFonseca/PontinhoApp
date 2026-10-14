package com.maxmfonseca.pontinho.data.local

import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.maxmfonseca.pontinho.data.local.entity.*
import com.maxmfonseca.pontinho.data.repository.RoomMatchRepository
import com.maxmfonseca.pontinho.domain.model.*
import com.maxmfonseca.pontinho.domain.repository.RoundStatus
import org.json.JSONObject
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

    @Test fun versionOneMigratesThroughVersionThree() = verifyMigration(1)

    @Test fun versionTwoMigratesJokersToNullableVersionThree() = verifyMigration(2)

    @Test fun nullableRoundIndicatorMapsToNull() = runBlocking {
        seed()
        db.openHelper.writableDatabase.execSQL("UPDATE participants SET eligibleFromRoundOrdinal=1")
        db.openHelper.writableDatabase.execSQL("UPDATE rounds SET indicatorRank=NULL, indicatorColor=NULL")
        assertNull(RoomMatchRepository(db).observeMatch(MatchId("m")).first()!!.rounds.single().indicator)
    }

    @Test fun partialRoundIndicatorMapsToNull() = runBlocking {
        seed()
        db.openHelper.writableDatabase.execSQL("UPDATE participants SET eligibleFromRoundOrdinal=1")
        for ((rank, color) in listOf(null to "RED", "ACE" to null, "INVALID" to "RED", "ACE" to "INVALID")) {
            db.openHelper.writableDatabase.execSQL("UPDATE rounds SET indicatorRank=?, indicatorColor=?", arrayOf(rank, color))
            assertNull(RoomMatchRepository(db).observeMatch(MatchId("m")).first()!!.rounds.single().indicator)
        }
    }

    private fun verifyMigration(version: Int) = runBlocking {
        db.close()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "migration-$version-three-${System.nanoTime()}.db"
        context.deleteDatabase(name)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(version) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        val exported = InstrumentationRegistry.getInstrumentation().context.assets.open(
                            "com.maxmfonseca.pontinho.data.local.PontinhoDatabase/$version.json")
                            .bufferedReader().use { JSONObject(it.readText()).getJSONObject("database") }
                        assertEquals(version, exported.getInt("version"))
                        val entities = exported.getJSONArray("entities")
                        for (index in 0 until entities.length()) {
                            val entity = entities.getJSONObject(index)
                            val name = entity.getString("tableName")
                            db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", name))
                            val indices = entity.optJSONArray("indices") ?: org.json.JSONArray()
                            for (i in 0 until indices.length())
                                db.execSQL(indices.getJSONObject(i).getString("createSql").replace("\${TABLE_NAME}", name))
                        }
                        val setup = exported.getJSONArray("setupQueries")
                        for (index in 0 until setup.length()) db.execSQL(setup.getString(index))
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build(),
        )
        val legacy = helper.writableDatabase
        assertEquals(version, legacy.version)
        legacy.execSQL("INSERT INTO players VALUES ('player1','Ana','ana',1,1,NULL),('player2','Bruno','bruno',1,1,NULL)")
        legacy.execSQL("INSERT INTO matches VALUES ('m','2026-10-13','America/Sao_Paulo',1,11,'ACTIVE',NULL,'p1',0,0,NULL,NULL)")
        legacy.execSQL("INSERT INTO participants VALUES ('p1','m','player1','Ana',0,1,1,17),('p2','m','player2','Bruno',1,1,1,0)")
        legacy.execSQL("INSERT INTO rounds VALUES ('r','m',1,'ACE','RED','p1',2,'p2','INFERRED','COMPLETE',NULL,1,11,'p2',0,NULL)")
        legacy.execSQL("INSERT INTO scores (id,matchId,roundId,participantId,points,origin,submittedAt,submissionToken) VALUES ('manual','m','r','p1',17,'MANUAL',10,'token-manual')")
        legacy.execSQL("INSERT INTO scores (id,matchId,roundId,participantId,points,origin,submittedAt,submissionToken) VALUES ('winner','m','r','p2',0,'AUTO_WINNER',11,NULL)")
        legacy.execSQL("INSERT INTO submissions (token,matchId,roundId,participantId,points) VALUES ('token-manual','m','r','p1',17)")
        fun rows(database: SupportSQLiteDatabase, table: String): List<List<String?>> =
            database.query("SELECT * FROM $table").use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add((0 until cursor.columnCount).map {
                        if (cursor.isNull(it)) null else cursor.getString(it)
                    })
                }
            }
        val originalRounds = rows(legacy, "rounds")
        if (version == 2) legacy.execSQL("INSERT INTO card_observations VALUES ('existing','manual',1,'TWO','CLUBS',NULL,1,NULL,NULL,NULL,NULL)")

        helper.close()
        // Room performs the real upgrade and validates every table, index and FK.
        db = Room.databaseBuilder(context, PontinhoDatabase::class.java, name)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3).build()
        val migrated = db.openHelper.writableDatabase
        assertEquals(3, migrated.version)
        assertEquals(originalRounds, rows(migrated, "rounds"))
        migrated.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        if (version == 2) assertEquals("existing", rows(migrated, "card_observations").single().first())
        val repository = RoomMatchRepository(db)
        val snapshot = requireNotNull(repository.observeMatch(MatchId("m")).first())
        assertEquals(listOf("Ana", "Bruno"), snapshot.participants.map { it.displayName })
        assertEquals(mapOf(ParticipantId("p1") to 17, ParticipantId("p2") to 0), snapshot.totals)
        val round = snapshot.rounds.single()
        assertEquals(RoundStatus.COMPLETE, round.status)
        assertEquals(Indicator(Rank.ACE, CardColor.RED), round.indicator)
        assertEquals(17, round.draft.entries.getValue(ParticipantId("p1")).points)
        assertEquals(RoundEntryOrigin.EXPLICIT, round.draft.entries.getValue(ParticipantId("p1")).origin)
        assertEquals(RoundEntryOrigin.AUTOMATIC_WINNER, round.draft.entries.getValue(ParticipantId("p2")).origin)
        assertEquals(snapshot, repository.observeHome().first().single().matches.single())
        migrated.execSQL("UPDATE rounds SET indicatorRank=NULL, indicatorColor=NULL WHERE id='r'")
        assertNull(repository.observeMatch(MatchId("m")).first()!!.rounds.single().indicator)

        migrated.query("SELECT id, matchId, roundId, participantId, points, origin, submittedAt, submissionToken, recognitionModelVersion FROM scores ORDER BY id").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(listOf("manual", "m", "r", "p1", "17", "MANUAL", "10", "token-manual"), (0..7).map(cursor::getString))
            assertTrue(cursor.isNull(8))
            assertTrue(cursor.moveToNext())
            assertEquals(listOf("winner", "m", "r", "p2", "0", "AUTO_WINNER", "11", null), (0..7).map { if (cursor.isNull(it)) null else cursor.getString(it) })
            assertTrue(cursor.isNull(8))
        }
        migrated.query("SELECT token, matchId, roundId, participantId, points, payloadFingerprint FROM submissions").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(listOf("token-manual", "m", "r", "p1", "17"), (0..4).map(cursor::getString))
            assertTrue(cursor.isNull(5))
        }
        migrated.execSQL("INSERT INTO card_observations VALUES ('o','manual',0,'ACE','HEARTS',0.9,0,0.1,0.2,0.3,0.4)")
        try {
            migrated.execSQL("INSERT INTO card_observations VALUES ('duplicate','manual',0,'TWO','CLUBS',NULL,1,NULL,NULL,NULL,NULL)")
            fail("Expected score ordinal uniqueness rejection")
        } catch (_: android.database.sqlite.SQLiteConstraintException) { }
        try {
            migrated.execSQL("INSERT INTO card_observations VALUES ('bad','missing',0,'ACE','HEARTS',NULL,0,NULL,NULL,NULL,NULL)")
            fail("Expected score foreign key rejection")
        } catch (_: android.database.sqlite.SQLiteConstraintException) { }
        migrated.execSQL("DELETE FROM scores WHERE id='manual'")
        migrated.query("SELECT count(*) FROM card_observations").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
        db.close()
        context.deleteDatabase(name)
        db = Room.inMemoryDatabaseBuilder(context, PontinhoDatabase::class.java).build()
    }
}
