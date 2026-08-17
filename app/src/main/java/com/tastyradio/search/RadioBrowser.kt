package com.tastyradio.search

import android.util.JsonReader
import android.util.JsonToken
import java.io.BufferedInputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * The radio-browser.info adapter: fetch the corpus, clean it, hand back rows.
 *
 * Streamed rather than buffered — the full dump is tens of megabytes of JSON and holding it in
 * memory on a phone to parse it would be silly. [fetchAll] parses as it downloads and hands over
 * batches.
 */
object RadioBrowser {

    const val SOURCE = "radio-browser"

    private const val USER_AGENT = "TastyRadio/0.1 (Android; hobby project)"
    private const val FALLBACK_HOST = "de1.api.radio-browser.info"

    /** Big enough to keep the corpus to a handful of requests, small enough to stay a sane response. */
    private const val PAGE_SIZE = 10_000

    private const val PAGE_ATTEMPTS = 3

    private val KNOWN_MIRRORS = listOf(
        "de1.api.radio-browser.info",
        "de2.api.radio-browser.info",
        "at1.api.radio-browser.info",
        "nl1.api.radio-browser.info",
    )

    /**
     * The API asks clients to spread load across mirrors rather than hammer one host, which is what
     * the round robin on `all.api.radio-browser.info` is for.
     *
     * Returns several, because a mirror can simply be unreachable: the emulator has no IPv6 route,
     * and a host that resolves to AAAA only fails outright there. Callers try them in order.
     */
    fun mirrors(): List<String> {
        val discovered = runCatching {
            InetAddress.getAllByName("all.api.radio-browser.info")
                .mapNotNull { address ->
                    address.canonicalHostName.takeIf { it.endsWith("api.radio-browser.info") }
                }
                .distinct()
                .shuffled()
        }.getOrDefault(emptyList())

        return (discovered + KNOWN_MIRRORS).distinct().ifEmpty { listOf(FALLBACK_HOST) }
    }

    /**
     * Pulls the whole corpus, one page at a time.
     *
     * `/json/stations` silently caps at 1000 rows with no indication that it has done so — ask for
     * everything and you quietly get 1.6% of it. Paging with explicit `limit`/`offset` is the only
     * way to actually get the corpus.
     *
     * @param onBatch called with each batch of cleaned rows and the running total
     */
    fun fetchAll(
        host: String,
        batchSize: Int,
        isCancelled: () -> Boolean,
        onBatch: (List<StationIndex.Row>, Int) -> Unit,
    ) {
        var offset = 0
        var total = 0
        while (!isCancelled()) {
            // A page that fails is usually a stumble, not a dead server. Retry before giving up on
            // the whole corpus, otherwise one hiccup at page five throws away four good pages.
            var seen = -1
            var attempt = 0
            var lastError: Throwable? = null
            while (attempt < PAGE_ATTEMPTS && seen < 0) {
                try {
                    seen = fetchPage(host, offset, batchSize, isCancelled) { rows ->
                        total += rows.size
                        onBatch(rows, total)
                    }
                } catch (error: Throwable) {
                    lastError = error
                    attempt++
                    if (attempt < PAGE_ATTEMPTS) Thread.sleep(1_000L * attempt)
                }
            }
            if (seen < 0) throw lastError ?: IllegalStateException("page at offset $offset failed")
            if (seen < PAGE_SIZE) return // short page means we've reached the end
            offset += PAGE_SIZE
        }
    }

    /**
     * How many stations the server thinks it has.
     *
     * This is what makes a sync checkable rather than merely finished: if we pulled 30,000 and the
     * server says 62,000, the index is partial and the app should say so instead of quietly
     * claiming success. The first version of this code built a 998-station index and reported it
     * as a complete sync.
     */
    fun fetchExpectedCount(host: String): Int? = runCatching {
        val connection = (URL("https://$host/json/stats").openConnection() as HttpURLConnection).apply {
            setRequestProperty("User-Agent", USER_AGENT)
            connectTimeout = 15_000
            readTimeout = 20_000
        }
        try {
            var stations: Int? = null
            JsonReader(InputStreamReader(connection.inputStream, Charsets.UTF_8)).use { reader ->
                reader.beginObject()
                while (reader.hasNext()) {
                    if (reader.nextName() == "stations") stations = reader.nextIntOrZero() else reader.skipValue()
                }
                reader.endObject()
            }
            stations
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    /** Tries mirrors in turn; a dead mirror shouldn't look like a dead feature. */
    fun fetchAllFromAnyMirror(
        batchSize: Int,
        isCancelled: () -> Boolean,
        onHost: (String) -> Unit,
        onBatch: (List<StationIndex.Row>, Int) -> Unit,
    ) {
        var lastError: Throwable? = null
        for (host in mirrors()) {
            if (isCancelled()) return
            try {
                onHost(host)
                fetchAll(host, batchSize, isCancelled, onBatch)
                return
            } catch (error: Throwable) {
                lastError = error
            }
        }
        throw lastError ?: IllegalStateException("no radio-browser mirror could be reached")
    }

    /** @return how many station objects the server sent, which is how we detect the last page. */
    private fun fetchPage(
        host: String,
        offset: Int,
        batchSize: Int,
        isCancelled: () -> Boolean,
        onBatch: (List<StationIndex.Row>) -> Unit,
    ): Int {
        val url = URL("https://$host/json/stations?limit=$PAGE_SIZE&offset=$offset")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept-Encoding", "gzip")
            connectTimeout = 20_000
            readTimeout = 120_000
        }

        try {
            val encoding = connection.contentEncoding
            val raw = BufferedInputStream(connection.inputStream, 64 * 1024)
            val stream = if (encoding?.contains("gzip", ignoreCase = true) == true) {
                GZIPInputStream(raw)
            } else {
                raw
            }

            JsonReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                val batch = ArrayList<StationIndex.Row>(batchSize)
                var seen = 0
                reader.beginArray()
                while (reader.hasNext()) {
                    if (isCancelled()) return seen
                    val row = readStation(reader)
                    seen++
                    if (row != null) batch += row
                    if (batch.size >= batchSize) {
                        onBatch(ArrayList(batch))
                        batch.clear()
                    }
                }
                reader.endArray()
                if (batch.isNotEmpty()) onBatch(batch)
                return seen
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun readStation(reader: JsonReader): StationIndex.Row? {
        var uuid = ""
        var name = ""
        var url = ""
        var urlResolved = ""
        var tags = ""
        var country = ""
        var countryCode = ""
        var state = ""
        var language = ""
        var homepage = ""
        var favicon = ""
        var codec = ""
        var bitrate = 0
        var votes = 0
        var clickCount = 0
        var clickTrend = 0
        var lastCheckOk = false

        reader.beginObject()
        while (reader.hasNext()) {
            when (val field = reader.nextName()) {
                "stationuuid" -> uuid = reader.nextStringOrNull().orEmpty()
                "name" -> name = reader.nextStringOrNull().orEmpty()
                "url" -> url = reader.nextStringOrNull().orEmpty()
                "url_resolved" -> urlResolved = reader.nextStringOrNull().orEmpty()
                "tags" -> tags = reader.nextStringOrNull().orEmpty()
                "country" -> country = reader.nextStringOrNull().orEmpty()
                "countrycode" -> countryCode = reader.nextStringOrNull().orEmpty()
                "state" -> state = reader.nextStringOrNull().orEmpty()
                "language" -> language = reader.nextStringOrNull().orEmpty()
                "homepage" -> homepage = reader.nextStringOrNull().orEmpty()
                "favicon" -> favicon = reader.nextStringOrNull().orEmpty()
                "codec" -> codec = reader.nextStringOrNull().orEmpty()
                "bitrate" -> bitrate = reader.nextIntOrZero()
                "votes" -> votes = reader.nextIntOrZero()
                "clickcount" -> clickCount = reader.nextIntOrZero()
                "clicktrend" -> clickTrend = reader.nextIntOrZero()
                "lastcheckok" -> lastCheckOk = reader.nextIntOrZero() == 1
                else -> {
                    @Suppress("UNUSED_EXPRESSION") field
                    reader.skipValue()
                }
            }
        }
        reader.endObject()

        val stream = urlResolved.ifBlank { url }
        if (name.isBlank() || stream.isBlank()) return null

        return StationIndex.Row(
            uuid = uuid,
            name = name.trim(),
            url = stream.trim(),
            tags = cleanTags(tags),
            country = country.trim(),
            countryCode = countryCode.trim(),
            state = state.trim(),
            language = language.trim(),
            homepage = homepage.trim(),
            favicon = favicon.trim(),
            codec = codec.trim(),
            bitrate = bitrate,
            votes = votes,
            clickCount = clickCount,
            clickTrend = clickTrend,
            lastCheckOk = lastCheckOk,
            source = SOURCE,
        )
    }

    /**
     * Tags in this corpus are a mess: duplicated, cased inconsistently, and sometimes an entire
     * pasted sentence. Anything longer than 32 characters or more than three words is prose, not a
     * tag, and it would pollute the co-occurrence counts badly.
     *
     * Note what this does *not* do: drop stations. Duplicate stations are kept deliberately — they
     * carry different tags and different stream URLs, which is extra recall and extra resilience.
     */
    fun cleanTags(raw: String): String = raw
        .split(',')
        .asSequence()
        .map { it.trim().lowercase().replace(Regex("\\s+"), " ") }
        .filter { it.isNotEmpty() && it.length <= 32 && it.count { char -> char == ' ' } < 3 }
        .distinct()
        .take(12)
        .joinToString(",")

    private fun JsonReader.nextStringOrNull(): String? =
        if (peek() == JsonToken.NULL) {
            nextNull()
            null
        } else {
            nextString()
        }

    private fun JsonReader.nextIntOrZero(): Int = when (peek()) {
        JsonToken.NULL -> {
            nextNull()
            0
        }
        JsonToken.BOOLEAN -> if (nextBoolean()) 1 else 0
        JsonToken.STRING -> nextString().toIntOrNull() ?: 0
        else -> runCatching { nextInt() }.getOrElse {
            skipValue()
            0
        }
    }
}
