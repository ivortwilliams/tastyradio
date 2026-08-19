package com.tastyradio.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A station the owner has chosen to keep. Curated, not browsed — once it's here, it's yours.
 *
 * [streamUrl] is deliberately editable and shown to the user: this audience is trusted with the
 * plumbing.
 */
@Entity(tableName = "stations")
data class Station(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val streamUrl: String,
    /** Artwork from the directory's `favicon` field. ICY stream metadata is text only. */
    val imageUrl: String? = null,
    val sortOrder: Int = 0,
    /**
     * Provenance, carried from whichever directory this came from. Present in schema v1 on purpose,
     * before it's used: `POST /json/url/{stationuuid}` on play is what feeds radio-browser's
     * clickcount — the popularity signal search ranks on — and adding the column later costs a
     * migration for nothing.
     */
    val sourceUuid: String? = null,
    /** Which directory: `radio-browser`, a curated pack name, `somafm`, `manual`, `import`. */
    val source: String? = null,
    /** Comma-separated, carried over when a station is added from search. Null for older rows. */
    val tags: String? = null,
    /**
     * The rest of what the directory knows, so a saved station reads the same as it did in search
     * results. Null until filled — either on add, or by the backfill that matches older rows
     * against the index by stream URL.
     */
    val codec: String? = null,
    val bitrate: Int? = null,
    val country: String? = null,
    val language: String? = null,
)
