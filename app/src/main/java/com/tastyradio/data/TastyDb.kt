package com.tastyradio.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [Station::class], version = 1, exportSchema = false)
abstract class TastyDb : RoomDatabase() {

    abstract fun stations(): StationDao

    companion object {
        fun build(context: Context): TastyDb =
            Room.databaseBuilder(context, TastyDb::class.java, "tasty.db").build()
    }
}
