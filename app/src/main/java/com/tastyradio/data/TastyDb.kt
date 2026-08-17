package com.tastyradio.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

@Database(entities = [Station::class, Mix::class, MixChannel::class], version = 4, exportSchema = false)
abstract class TastyDb : RoomDatabase() {

    abstract fun stations(): StationDao

    abstract fun mixes(): MixDao

    companion object {
        /**
         * Adds the tags column. A real migration rather than a destructive one: by the time this
         * shipped there were collections on real phones, and a hand-built station list is not
         * something to throw away for a text column.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE stations ADD COLUMN tags TEXT")
            }
        }

        /** The rest of the directory's fields, so saved stations can read like search results. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE stations ADD COLUMN codec TEXT")
                connection.execSQL("ALTER TABLE stations ADD COLUMN bitrate INTEGER")
                connection.execSQL("ALTER TABLE stations ADD COLUMN country TEXT")
                connection.execSQL("ALTER TABLE stations ADD COLUMN language TEXT")
            }
        }

        /** Saved mixes: a set of stations with their levels and tone. */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS mixes (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        createdAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                connection.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS mix_channels (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        mixId INTEGER NOT NULL,
                        stationId INTEGER NOT NULL,
                        fader REAL NOT NULL,
                        muted INTEGER NOT NULL,
                        toneLow REAL NOT NULL,
                        toneMid REAL NOT NULL,
                        toneHigh REAL NOT NULL,
                        toneFilter REAL NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        fun build(context: Context): TastyDb =
            Room.databaseBuilder(context, TastyDb::class.java, "tasty.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build()
    }
}
