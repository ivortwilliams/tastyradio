package com.tastyradio.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

@Database(entities = [Station::class], version = 2, exportSchema = false)
abstract class TastyDb : RoomDatabase() {

    abstract fun stations(): StationDao

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

        fun build(context: Context): TastyDb =
            Room.databaseBuilder(context, TastyDb::class.java, "tasty.db")
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
