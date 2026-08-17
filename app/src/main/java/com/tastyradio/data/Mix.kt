package com.tastyradio.data

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * A saved soundscape: which stations, at what levels, with what tone.
 *
 * This is the feature the mixer was always heading towards — a combination you stumbled on is
 * worth nothing if you can't get back to it. Cheap to store, since it's a handful of ids and
 * floats.
 */
@Entity(tableName = "mixes")
data class Mix(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
)

/** One station's place in a saved mix: everything the mixer would need to recreate the channel. */
@Entity(tableName = "mix_channels")
data class MixChannel(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mixId: Long,
    val stationId: Long,
    val fader: Float,
    val muted: Boolean,
    val toneLow: Float,
    val toneMid: Float,
    val toneHigh: Float,
    val reverb: Float,
    val delay: Float,
    val delayMs: Float,
)

data class MixWithChannels(
    @Embedded val mix: Mix,
    @Relation(parentColumn = "id", entityColumn = "mixId")
    val channels: List<MixChannel>,
)

@Dao
interface MixDao {

    @Transaction
    @Query("SELECT * FROM mixes ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<MixWithChannels>>

    @Query("SELECT * FROM mixes WHERE name = :name LIMIT 1")
    suspend fun findByName(name: String): Mix?

    @Insert
    suspend fun insertMix(mix: Mix): Long

    @Insert
    suspend fun insertChannels(channels: List<MixChannel>)

    @Query("DELETE FROM mix_channels WHERE mixId = :mixId")
    suspend fun deleteChannels(mixId: Long)

    @Query("DELETE FROM mixes WHERE id = :mixId")
    suspend fun deleteMix(mixId: Long)

    @Query("UPDATE mixes SET name = :name WHERE id = :mixId")
    suspend fun rename(mixId: Long, name: String)
}
