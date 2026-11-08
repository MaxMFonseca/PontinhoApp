package com.maxmfonseca.pontinho.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE scores ADD COLUMN recognitionModelVersion TEXT")
        db.execSQL("ALTER TABLE submissions ADD COLUMN payloadFingerprint TEXT")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS card_observations (
                id TEXT NOT NULL PRIMARY KEY,
                scoreId TEXT NOT NULL,
                ordinal INTEGER NOT NULL,
                rank TEXT NOT NULL,
                suit TEXT NOT NULL,
                confidence REAL,
                correctedByUser INTEGER NOT NULL,
                `left` REAL,
                `top` REAL,
                `right` REAL,
                `bottom` REAL,
                FOREIGN KEY(scoreId) REFERENCES scores(id) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS index_card_observations_scoreId_ordinal " +
                "ON card_observations(scoreId, ordinal)",
        )
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE rounds_new (
                id TEXT NOT NULL PRIMARY KEY,
                matchId TEXT NOT NULL,
                ordinal INTEGER NOT NULL,
                indicatorRank TEXT,
                indicatorColor TEXT,
                dealerParticipantId TEXT NOT NULL,
                eligibleParticipantCount INTEGER NOT NULL,
                designatedWinnerParticipantId TEXT,
                designationOrigin TEXT,
                status TEXT NOT NULL,
                openSlot INTEGER,
                createdAt INTEGER NOT NULL,
                completedAt INTEGER,
                nextDealerParticipantId TEXT,
                isCorrection INTEGER NOT NULL,
                pauseOrder INTEGER,
                FOREIGN KEY(matchId) REFERENCES matches(id) ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(matchId, dealerParticipantId) REFERENCES participants(matchId, id) ON UPDATE NO ACTION ON DELETE RESTRICT,
                FOREIGN KEY(matchId, designatedWinnerParticipantId) REFERENCES participants(matchId, id) ON UPDATE NO ACTION ON DELETE RESTRICT
            )
        """.trimIndent())
        val columns = "id, matchId, ordinal, indicatorRank, indicatorColor, dealerParticipantId, " +
            "eligibleParticipantCount, designatedWinnerParticipantId, designationOrigin, status, openSlot, " +
            "createdAt, completedAt, nextDealerParticipantId, isCorrection, pauseOrder"
        db.execSQL("INSERT INTO rounds_new ($columns) SELECT $columns FROM rounds")
        db.execSQL("DROP TABLE rounds")
        db.execSQL("ALTER TABLE rounds_new RENAME TO rounds")
        db.execSQL("CREATE UNIQUE INDEX index_rounds_matchId_ordinal ON rounds(matchId, ordinal)")
        db.execSQL("CREATE UNIQUE INDEX index_rounds_matchId_id ON rounds(matchId, id)")
        db.execSQL("CREATE UNIQUE INDEX index_rounds_matchId_openSlot ON rounds(matchId, openSlot)")
        db.execSQL("CREATE INDEX index_rounds_matchId_dealerParticipantId ON rounds(matchId, dealerParticipantId)")
        db.execSQL("CREATE INDEX index_rounds_matchId_designatedWinnerParticipantId ON rounds(matchId, designatedWinnerParticipantId)")
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE participants ADD COLUMN isActive INTEGER NOT NULL DEFAULT 1")
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS round_participants (
                matchId TEXT NOT NULL, roundId TEXT NOT NULL, participantId TEXT NOT NULL,
                PRIMARY KEY(roundId, participantId),
                FOREIGN KEY(matchId, roundId) REFERENCES rounds(matchId, id) ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(matchId, participantId) REFERENCES participants(matchId, id) ON UPDATE NO ACTION ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS index_round_participants_matchId_roundId ON round_participants(matchId, roundId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_round_participants_matchId_participantId ON round_participants(matchId, participantId)")
        db.execSQL("""
            INSERT INTO round_participants (matchId, roundId, participantId)
            SELECT r.matchId, r.id, p.id FROM rounds r JOIN participants p
                ON p.matchId = r.matchId AND p.eligibleFromRoundOrdinal <= r.ordinal
        """.trimIndent())
    }
}
