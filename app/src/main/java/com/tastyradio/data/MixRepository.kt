package com.tastyradio.data

import com.tastyradio.playback.Mixer
import kotlinx.coroutines.flow.Flow

class MixRepository(private val dao: MixDao) {

    val mixes: Flow<List<MixWithChannels>> = dao.observeAll()

    /**
     * Saves the mix exactly as it currently sounds — faders, mutes and tone.
     *
     * Saving over an existing name replaces it, because "save" on a name you already used means
     * update, not duplicate. Channels that aren't saved stations (an audition from search) are
     * skipped: there's no id to point at, and silently saving a station you never added would be a
     * surprise later.
     */
    /** [replaced] is true when an existing mix of that name was overwritten rather than created. */
    data class SaveResult(val stations: Int, val replaced: Boolean)

    suspend fun save(name: String, channels: List<Mixer.Channel>): SaveResult {
        val cleanName = name.trim().ifEmpty { return SaveResult(0, false) }
        val saveable = channels.filter { it.station.id != 0L }
        if (saveable.isEmpty()) return SaveResult(0, false)

        val existing = dao.findByName(cleanName)
        val mixId = if (existing != null) {
            dao.deleteChannels(existing.id)
            existing.id
        } else {
            dao.insertMix(Mix(name = cleanName))
        }

        dao.insertChannels(
            saveable.map { channel ->
                MixChannel(
                    mixId = mixId,
                    stationId = channel.station.id,
                    fader = channel.fader,
                    muted = channel.muted,
                    toneLow = channel.tone.low,
                    toneMid = channel.tone.mid,
                    toneHigh = channel.tone.high,
                    reverb = channel.tone.reverb,
                    delay = channel.tone.delay,
                    delayMs = channel.tone.delayMs,
                )
            }
        )
        return SaveResult(stations = saveable.size, replaced = existing != null)
    }

    suspend fun rename(mix: Mix, name: String) {
        val cleanName = name.trim()
        if (cleanName.isNotEmpty()) dao.rename(mix.id, cleanName)
    }

    suspend fun delete(mix: Mix) {
        dao.deleteChannels(mix.id)
        dao.deleteMix(mix.id)
    }
}
