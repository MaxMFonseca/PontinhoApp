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
