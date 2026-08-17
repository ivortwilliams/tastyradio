package com.tastyradio.search

import android.content.Context
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import java.io.File
import kotlin.math.ln

/**
 * The local station index: every station we know about, searchable offline.
 *
 * **Not Room, and not Android's SQLite.** Android's own SQLite is compiled without FTS5 — verified
 * on API 36, where `fts5` and `bm25()` are both missing — so this opens its own SQLite through
 * `androidx.sqlite:sqlite-bundled` (3.50.1) where they exist. Room's FTS annotations only cover
 * FTS3/4 anyway, so the table is raw SQL either way.
 *
 * The connection is not thread-safe: everything here is called from one dispatcher, guarded by the
 * repository's mutex.
 */
class StationIndex(context: Context) {

    private val file = File(context.filesDir, "station-index.db")
    private var connection: SQLiteConnection? = null

    data class Row(
        val uuid: String,
        val name: String,
        val url: String,
        val tags: String,
        val country: String,
        val countryCode: String,
        val state: String,
        val language: String,
        val homepage: String,
        val favicon: String,
        val codec: String,
        val bitrate: Int,
        val votes: Int,
        val clickCount: Int,
        val clickTrend: Int,
        val lastCheckOk: Boolean,
        val source: String,
    )

    data class Hit(
        val name: String,
        val url: String,
        val tags: String,
        val country: String,
        val language: String,
        val codec: String,
        val bitrate: Int,
        val favicon: String,
        val uuid: String,
        val source: String,
        val lastCheckOk: Boolean,
        val score: Double,
    )

    fun open(): SQLiteConnection = connection ?: BundledSQLiteDriver()
        .open(file.absolutePath)
        .also { db ->
            connection = db
            db.execSQL("PRAGMA journal_mode = WAL")
            db.execSQL("PRAGMA synchronous = NORMAL")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS station (
                    id INTEGER PRIMARY KEY,
                    uuid TEXT, name TEXT, url TEXT, tags TEXT,
                    country TEXT, countrycode TEXT, state TEXT, language TEXT,
                    homepage TEXT, favicon TEXT, codec TEXT,
                    bitrate INTEGER, votes INTEGER, clickcount INTEGER, clicktrend INTEGER,
                    lastcheckok INTEGER, source TEXT
                )
                """.trimIndent()
            )
            // The tokenizer chain is FTS5-only: porter for stemming, unicode61 to fold diacritics
            // so "Vaticana" is findable without typing accents.
            db.execSQL(
                """
                CREATE VIRTUAL TABLE IF NOT EXISTS station_fts USING fts5(
                    name, tags, country, state, language, homepage,
                    tokenize = "porter unicode61 remove_diacritics 2"
                )
                """.trimIndent()
            )
            db.execSQL("CREATE TABLE IF NOT EXISTS tag_count (tag TEXT PRIMARY KEY, n INTEGER)")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS tag_neighbour (
                    tag TEXT, neighbour TEXT, score REAL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_neighbour ON tag_neighbour(tag)")
            db.execSQL("CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT)")
        }

    // ------------------------------------------------------------------ ingest

    fun clearSource(source: String) {
        val db = open()
        db.execSQL("DELETE FROM station_fts WHERE rowid IN (SELECT id FROM station WHERE source = '$source')")
        db.execSQL("DELETE FROM station WHERE source = '$source'")
    }

    fun clearAll() {
        val db = open()
        db.execSQL("DELETE FROM station_fts")
        db.execSQL("DELETE FROM station")
        db.execSQL("DELETE FROM tag_neighbour")
        db.execSQL("DELETE FROM tag_count")
        db.execSQL("DELETE FROM meta")
    }

    /** Batched inside one transaction: 60k individual inserts would take minutes otherwise. */
    fun insertBatch(rows: List<Row>) {
        val db = open()
        db.execSQL("BEGIN")
        try {
            val station = db.prepare(
                """
                INSERT INTO station (uuid, name, url, tags, country, countrycode, state, language,
                    homepage, favicon, codec, bitrate, votes, clickcount, clicktrend, lastcheckok, source)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """.trimIndent()
            )
            val fts = db.prepare(
                "INSERT INTO station_fts (rowid, name, tags, country, state, language, homepage) " +
                    "VALUES (?,?,?,?,?,?,?)"
            )
            station.use { insertStation ->
                fts.use { insertFts ->
                    for (row in rows) {
                        insertStation.bindText(1, row.uuid)
                        insertStation.bindText(2, row.name)
                        insertStation.bindText(3, row.url)
                        insertStation.bindText(4, row.tags)
                        insertStation.bindText(5, row.country)
                        insertStation.bindText(6, row.countryCode)
                        insertStation.bindText(7, row.state)
                        insertStation.bindText(8, row.language)
                        insertStation.bindText(9, row.homepage)
                        insertStation.bindText(10, row.favicon)
                        insertStation.bindText(11, row.codec)
                        insertStation.bindLong(12, row.bitrate.toLong())
                        insertStation.bindLong(13, row.votes.toLong())
                        insertStation.bindLong(14, row.clickCount.toLong())
                        insertStation.bindLong(15, row.clickTrend.toLong())
                        insertStation.bindLong(16, if (row.lastCheckOk) 1 else 0)
                        insertStation.bindText(17, row.source)
                        insertStation.step()
                        insertStation.reset()

                        val id = lastInsertRowId(db)
                        insertFts.bindLong(1, id)
                        insertFts.bindText(2, row.name)
                        insertFts.bindText(3, row.tags)
                        insertFts.bindText(4, row.country)
                        insertFts.bindText(5, row.state)
                        insertFts.bindText(6, row.language)
                        insertFts.bindText(7, row.homepage)
                        insertFts.step()
                        insertFts.reset()
                    }
                }
            }
            db.execSQL("COMMIT")
        } catch (error: Throwable) {
            runCatching { db.execSQL("ROLLBACK") }
            throw error
        }
    }

    private fun lastInsertRowId(db: SQLiteConnection): Long =
        db.prepare("SELECT last_insert_rowid()").use { if (it.step()) it.getLong(0) else 0L }

    fun writeTagCounts(counts: Map<String, Int>) {
        val db = open()
        db.execSQL("BEGIN")
        db.execSQL("DELETE FROM tag_count")
        db.prepare("INSERT INTO tag_count (tag, n) VALUES (?,?)").use { statement ->
            for ((tag, n) in counts) {
                statement.bindText(1, tag)
                statement.bindLong(2, n.toLong())
                statement.step()
                statement.reset()
            }
        }
        db.execSQL("COMMIT")
    }

    fun readAllTagLists(block: (String) -> Unit) {
        open().prepare("SELECT tags FROM station WHERE tags <> ''").use { statement ->
            while (statement.step()) block(statement.getText(0))
        }
    }

    fun writeNeighbours(neighbours: Map<String, List<Pair<String, Double>>>) {
        val db = open()
        db.execSQL("BEGIN")
        db.execSQL("DELETE FROM tag_neighbour")
        db.prepare("INSERT INTO tag_neighbour (tag, neighbour, score) VALUES (?,?,?)").use { st ->
            for ((tag, list) in neighbours) {
                for ((neighbour, score) in list) {
                    st.bindText(1, tag)
                    st.bindText(2, neighbour)
                    st.bindDouble(3, score)
                    st.step()
                    st.reset()
                }
            }
        }
        db.execSQL("COMMIT")
    }

    // ------------------------------------------------------------------ query

    fun neighboursOf(tag: String, limit: Int): List<String> =
        open().prepare(
            "SELECT neighbour FROM tag_neighbour WHERE tag = ? ORDER BY score DESC LIMIT ?"
        ).use { statement ->
            statement.bindText(1, tag)
            statement.bindLong(2, limit.toLong())
            buildList { while (statement.step()) add(statement.getText(0)) }
        }

    fun knownTag(tag: String): Boolean =
        open().prepare("SELECT 1 FROM tag_count WHERE tag = ? LIMIT 1").use { statement ->
            statement.bindText(1, tag)
            statement.step()
        }

    fun popularTags(limit: Int): List<String> =
        open().prepare("SELECT tag FROM tag_count ORDER BY n DESC LIMIT ?").use { statement ->
            statement.bindLong(1, limit.toLong())
            buildList { while (statement.step()) add(statement.getText(0)) }
        }

    /**
     * Ranking is deliberately split: SQLite does the BM25 relevance ordering, and popularity and
     * reachability are folded in afterwards in Kotlin. Popularity is a multiplier, never a filter —
     * obscure stations are half the point of this app, they just shouldn't outrank the obvious
     * answer to a vague query.
     */
    fun search(match: String, limit: Int, httpsOnly: Boolean, includeUnreachable: Boolean): List<Hit> {
        val db = open()
        val sql = buildString {
            append(
                """
                SELECT s.name, s.url, s.tags, s.country, s.language, s.codec, s.bitrate,
                       s.favicon, s.uuid, s.source, s.lastcheckok, s.votes, s.clickcount,
                       bm25(station_fts, 10.0, 6.0, 3.0, 3.0, 3.0, 1.0) AS bm
                FROM station_fts
                JOIN station s ON s.id = station_fts.rowid
                WHERE station_fts MATCH ?
                """.trimIndent()
            )
            if (httpsOnly) append(" AND s.url LIKE 'https://%'")
            if (!includeUnreachable) append(" AND s.lastcheckok = 1")
            append(" ORDER BY bm LIMIT ?")
        }

        return db.prepare(sql).use { statement ->
            statement.bindText(1, match)
            statement.bindLong(2, limit.toLong())
            buildList {
                while (statement.step()) {
                    val votes = statement.getLong(11)
                    val clicks = statement.getLong(12)
                    val ok = statement.getLong(10) == 1L
                    val bm = statement.getDouble(13)
                    // bm25 is negative, better matches more so; flip it into a positive relevance.
                    val relevance = -bm
                    val popularity = ln((clicks + votes + 10).toDouble())
                    val reachability = if (ok) 1.0 else 0.15
                    add(
                        Hit(
                            name = statement.getText(0),
                            url = statement.getText(1),
                            tags = statement.getText(2),
                            country = statement.getText(3),
                            language = statement.getText(4),
                            codec = statement.getText(5),
                            bitrate = statement.getLong(6).toInt(),
                            favicon = statement.getText(7),
                            uuid = statement.getText(8),
                            source = statement.getText(9),
                            lastCheckOk = ok,
                            score = relevance * popularity * reachability,
                        )
                    )
                }
            }
        }
    }

    /**
     * Reclaim the space a re-sync leaves behind. SQLite doesn't shrink the file when rows are
     * deleted, so without this the index looks like it grows every refresh — and the size shown in
     * Settings would be a lie.
     */
    fun compact() {
        val db = open()
        db.execSQL("PRAGMA wal_checkpoint(TRUNCATE)")
        db.execSQL("VACUUM")
        // Again afterwards: VACUUM rewrites the whole database through the write-ahead log, so
        // skipping this leaves a -wal file bigger than the database it just compacted.
        db.execSQL("PRAGMA wal_checkpoint(TRUNCATE)")
    }

    /** Exact stream-URL lookup, for filling in what a saved station doesn't know about itself. */
    fun findByUrl(url: String): Hit? =
        open().prepare(
            """
            SELECT name, url, tags, country, language, codec, bitrate, favicon, uuid, source,
                   lastcheckok
            FROM station WHERE url = ? LIMIT 1
            """.trimIndent()
        ).use { statement ->
            statement.bindText(1, url)
            if (!statement.step()) return null
            Hit(
                name = statement.getText(0),
                url = statement.getText(1),
                tags = statement.getText(2),
                country = statement.getText(3),
                language = statement.getText(4),
                codec = statement.getText(5),
                bitrate = statement.getLong(6).toInt(),
                favicon = statement.getText(7),
                uuid = statement.getText(8),
                source = statement.getText(9),
                lastCheckOk = statement.getLong(10) == 1L,
                score = 0.0,
            )
        }

    fun count(): Int =
        open().prepare("SELECT COUNT(*) FROM station").use { if (it.step()) it.getLong(0).toInt() else 0 }

    fun countBySource(): List<Pair<String, Int>> =
        open().prepare("SELECT source, COUNT(*) FROM station GROUP BY source").use { statement ->
            buildList { while (statement.step()) add(statement.getText(0) to statement.getLong(1).toInt()) }
        }

    fun sizeOnDiskBytes(): Long = file.length() +
        File(file.absolutePath + "-wal").length() +
        File(file.absolutePath + "-shm").length()

    fun putMeta(key: String, value: String) {
        open().prepare("INSERT OR REPLACE INTO meta (key, value) VALUES (?,?)").use { statement ->
            statement.bindText(1, key)
            statement.bindText(2, value)
            statement.step()
        }
    }

    fun getMeta(key: String): String? =
        open().prepare("SELECT value FROM meta WHERE key = ?").use { statement ->
            statement.bindText(1, key)
            if (statement.step()) statement.getText(0) else null
        }
}
