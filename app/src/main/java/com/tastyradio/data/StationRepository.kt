package com.tastyradio.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

class StationRepository(private val dao: StationDao) {

    val stations: Flow<List<Station>> = dao.observeAll()

    suspend fun add(
        name: String,
        streamUrl: String,
        imageUrl: String? = null,
        sourceUuid: String? = null,
        source: String? = null,
        tags: String? = null,
    ): Station? {
        val url = streamUrl.trim()
        if (url.isEmpty()) return null
        dao.findByUrl(url)?.let { return null } // already collected; adding it twice is never wanted
        val station = Station(
            name = name.trim().ifEmpty { url.removePrefix("http://").removePrefix("https://").substringBefore('/') },
            streamUrl = url,
            imageUrl = imageUrl,
            sourceUuid = sourceUuid,
            source = source,
            tags = tags?.trim()?.ifEmpty { null },
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

    suspend fun update(station: Station) = dao.update(
        station.copy(name = station.name.trim(), streamUrl = station.streamUrl.trim())
    )

    suspend fun delete(station: Station) = dao.delete(station)

    /**
     * Copies a picked image into our own storage and returns a `file://` URI for it.
     *
     * The picker hands back a temporary permission that dies with the process, so pointing the
     * station at that URI would give you artwork that works today and a blank circle next week.
     */
    suspend fun saveArtwork(context: Context, station: Station, source: Uri): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val folder = File(context.filesDir, "station-images").apply { mkdirs() }
                val destination = File(folder, "station-${station.id}-${System.currentTimeMillis()}.img")
                context.contentResolver.openInputStream(source)?.use { input ->
                    destination.outputStream().use { output -> input.copyTo(output) }
                } ?: return@runCatching null

                // Drop the previous copy so edits don't quietly accumulate files.
                station.imageUrl
                    ?.removePrefix("file://")
                    ?.let { previous -> File(previous).takeIf { it.exists() && it != destination }?.delete() }

                "file://${destination.absolutePath}"
            }.getOrNull()
        }

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
