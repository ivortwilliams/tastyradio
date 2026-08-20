package com.tastyradio.data

import com.tastyradio.playback.Mixer
import kotlinx.coroutines.flow.Flow

class MixRepository(
    private val dao: MixDao,
    /** Saving a mix can need to *create* stations, so this layer owns the collection too. */
    private val stations: StationRepository,
) {

    val mixes: Flow<List<MixWithChannels>> = dao.observeAll()

    /**
     * Saves the mix exactly as it currently sounds — faders, mutes and tone.
     *
     * Saving over an existing name replaces it, because "save" on a name you already used means
     * update, not duplicate.
     *
     * **A channel auditioned from search is collected on the way in.** A saved mix is a list of
     * station rows, so a channel with no row cannot be in one; this used to drop those channels
     * silently, and the mix came back a station short next session. Asking to save the mix *is*
     * asking to keep what is in it, so anything not yet in the collection gets added — matched by
     * stream URL, so a station you already have is never duplicated.
     */
    /**
     * [replaced] is true when an existing mix of that name was overwritten rather than created.
     * [added] counts stations that joined the collection to make this mix saveable.
     */
    data class SaveResult(val stations: Int, val replaced: Boolean, val added: Int = 0)

    suspend fun save(name: String, channels: List<Mixer.Channel>): SaveResult {
        val cleanName = name.trim().ifEmpty { return SaveResult(0, false) }
        if (channels.isEmpty()) return SaveResult(0, false)

        var added = 0
        val saveable = channels.mapNotNull { channel ->
            val station = collect(channel.station) ?: return@mapNotNull null
            if (channel.station.id == 0L && station.id != 0L) added++
            station.id to channel
        }
        if (saveable.isEmpty()) return SaveResult(0, false)

        val existing = dao.findByName(cleanName)
        val mixId = if (existing != null) {
            dao.deleteChannels(existing.id)
            existing.id
        } else {
            dao.insertMix(Mix(name = cleanName))
        }

        dao.insertChannels(
            saveable.map { (stationId, channel) ->
                MixChannel(
                    mixId = mixId,
                    stationId = stationId,
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
        return SaveResult(stations = saveable.size, replaced = existing != null, added = added)
    }

    /**
     * The station row this channel should point at.
     *
     * A channel that came from the collection already knows. One auditioned from search carries
     * `id = 0` and everything the directory said about it, so it is looked up by stream URL first —
     * which also covers the case where it was auditioned *and* added with ＋, leaving the running
     * channel holding a stale copy with no id.
     */
    private suspend fun collect(station: Station): Station? {
        if (station.id != 0L) return station
        stations.find(station.streamUrl)?.let { return it }
        return stations.add(
            name = station.name,
            streamUrl = station.streamUrl,
            imageUrl = station.imageUrl,
            sourceUuid = station.sourceUuid,
            source = station.source,
            tags = station.tags,
            codec = station.codec,
            bitrate = station.bitrate,
            country = station.country,
            language = station.language,
        )
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

    /**
     * A name that won't collide with a mix you already have.
     *
     * [save] treats a name you already used as *update this mix*, which is right when you are
     * tweaking your own levels and saving again — and wrong for a mix somebody sent you. Everybody
     * starts with the same three shipped soundscapes, so a friend sending you their *Ritual
     * Gregorian* would otherwise quietly replace yours with theirs. Keeping a shared mix keeps it
     * alongside, never on top.
     */
    suspend fun availableName(preferred: String): String {
        val clean = preferred.trim().ifEmpty { "A shared mix" }
        if (dao.findByName(clean) == null) return clean
        var attempt = 2
        while (dao.findByName("$clean ($attempt)") != null) attempt++
        return "$clean ($attempt)"
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
