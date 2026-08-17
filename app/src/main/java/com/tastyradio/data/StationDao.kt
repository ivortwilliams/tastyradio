package com.tastyradio.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface StationDao {

    @Query("SELECT * FROM stations ORDER BY sortOrder ASC, name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<Station>>

    @Query("SELECT COUNT(*) FROM stations")
    suspend fun count(): Int

    @Query("SELECT * FROM stations WHERE streamUrl = :streamUrl LIMIT 1")
    suspend fun findByUrl(streamUrl: String): Station?

    @Insert
    suspend fun insert(station: Station): Long

    @Insert
    suspend fun insertAll(stations: List<Station>)

    @Update
    suspend fun update(station: Station)

    @Delete
    suspend fun delete(station: Station)
}
