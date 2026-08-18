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

    /**
     * A fresh install arrives with the shipped soundscapes already built ([SeedMixes]), so the point
     * of the app is one tap away rather than something you have to assemble first.
     *
     * Runs only when there are no mixes at all, which also means deleting every mix brings the
     * shipped ones back on the next launch — the same bargain the station list makes.
     *
     * [stationByUrl] resolves each preset channel to the row the station seeder just inserted. A
     * preset whose stations aren't there is skipped rather than saved half-built.
     */
    suspend fun seedIfEmpty(stationByUrl: suspend (String) -> Station?) {
        if (dao.count() > 0) return
        val now = System.currentTimeMillis()
        SeedMixes.LIST.forEachIndexed { index, preset ->
            val resolved = preset.channels.mapNotNull { channel ->
                stationByUrl(channel.streamUrl)?.let { station -> station to channel }
            }
            if (resolved.size != preset.channels.size) return@forEachIndexed

            // The Mixes page is newest-first, so stagger the timestamps to keep the shipped order.
            val mixId = dao.insertMix(Mix(name = preset.name, createdAt = now - index))
            dao.insertChannels(
                resolved.map { (station, channel) ->
                    MixChannel(
                        mixId = mixId,
                        stationId = station.id,
                        fader = channel.fader,
                        muted = false,
                        toneLow = channel.toneLow,
                        toneMid = channel.toneMid,
                        toneHigh = channel.toneHigh,
                        reverb = channel.reverb,
                        delay = channel.delay,
                        delayMs = channel.delayMs,
                    )
                }
            )
        }
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
