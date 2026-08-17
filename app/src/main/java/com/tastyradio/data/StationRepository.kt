package com.tastyradio.data

import kotlinx.coroutines.flow.Flow

class StationRepository(private val dao: StationDao) {

    val stations: Flow<List<Station>> = dao.observeAll()

    suspend fun add(name: String, streamUrl: String, imageUrl: String? = null): Station? {
        val url = streamUrl.trim()
        if (url.isEmpty()) return null
        dao.findByUrl(url)?.let { return null } // already collected; adding it twice is never wanted
        val station = Station(
            name = name.trim().ifEmpty { url.removePrefix("http://").removePrefix("https://").substringBefore('/') },
            streamUrl = url,
            imageUrl = imageUrl,
        )
        return station.copy(id = dao.insert(station))
    }

    /** Returns how many were new — duplicates are skipped rather than piling up. */
    suspend fun import(entries: List<PlaylistParser.Entry>): Int {
        var added = 0
        for (entry in entries) {
            if (add(entry.name, entry.url) != null) added++
        }
        return added
    }

    suspend fun rename(station: Station, name: String) = dao.update(station.copy(name = name.trim()))

    suspend fun setStreamUrl(station: Station, url: String) = dao.update(station.copy(streamUrl = url.trim()))

    suspend fun delete(station: Station) = dao.delete(station)

    /**
     * First run gets the owner's own station list, so the app is useful before anything is typed
     * into it. Stream URLs came from radio-browser.info; *Tasty Radio* itself isn't listed there,
     * so that one has to be added by URL or M3U import.
     */
    suspend fun seedIfEmpty() {
        if (dao.count() > 0) return
        dao.insertAll(SeedStations.LIST)
    }
}
