package com.tastyradio.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Writes the collection out as an M3U playlist.
 *
 * The user owns their data: this is a plain text file, readable by any other player, and it's the
 * same format the app imports — so export/import is also a backup and restore.
 */
object M3uExport {

    suspend fun export(context: Context, stations: List<Station>): Uri? = withContext(Dispatchers.IO) {
        if (stations.isEmpty()) return@withContext null

        val text = buildString {
            appendLine("#EXTM3U")
            for (station in stations) {
                appendLine("#EXTINF:-1,${station.name}")
                appendLine(station.streamUrl)
            }
        }

        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "tasty-radio-stations.m3u")
            put(MediaStore.Downloads.MIME_TYPE, "audio/x-mpegurl")
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Tasty Radio")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }

        val uri = context.contentResolver.insert(
            MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            values,
        ) ?: return@withContext null

        runCatching {
            context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
            context.contentResolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                null,
                null,
            )
        }.onFailure {
            context.contentResolver.delete(uri, null, null)
            return@withContext null
        }

        uri
    }
}
