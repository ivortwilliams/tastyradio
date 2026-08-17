package com.tastyradio.search

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Owns the station index: syncing it, and searching it.
 *
 * **Sync state is a first-class observable thing**, not a boolean buried in a worker. A background
 * job silently mutating the thing you search is what makes an app feel untrustworthy — especially
 * when results change and you can't tell why. Everything the UI needs to be honest about sync is on
 * [syncState].
 */
class SearchRepository(private val context: Context) {

    sealed interface SyncState {
        data object NeverSynced : SyncState
        data class Syncing(val phase: String, val fetched: Int, val source: String) : SyncState
        data class Synced(
            val finishedAt: Long,
            val stations: Int,
            /** Change since the previous sync — "+37" is what proves a refresh did something. */
            val added: Int?,
            /** What the server said it had, so "complete" is checkable rather than assumed. */
            val expected: Int?,
        ) : SyncState {
            val complete: Boolean get() = expected == null || stations >= expected * 0.95
        }

        data class Failed(
            val reason: String,
            val lastGoodAt: Long?,
            val stations: Int,
        ) : SyncState
    }

    data class Filters(
        val httpsOnly: Boolean = false,
        val includeUnreachable: Boolean = false,
    )

    data class Results(
        val hits: List<StationIndex.Hit>,
        /** Shown as removable chips: never expand a query invisibly. */
        val expansions: List<String>,
    )

    data class Stats(
        val total: Int,
        val bySource: List<Pair<String, Int>>,
        val sizeBytes: Long,
        val lastSyncFinishedAt: Long?,
        val lastSyncStartedAt: Long?,
        /** "ok" or the failure reason, persisted, so the last run is always accountable. */
        val lastResult: String?,
        val expected: Int?,
    )

    private val index = StationIndex(context)
    private val expander = QueryExpander(context, index)

    /** The index connection is single-threaded; this is the gate. */
    private val lock = Mutex()

    private val _syncState = MutableStateFlow<SyncState>(SyncState.NeverSynced)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    @Volatile private var cancelRequested = false

    fun restoreState(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            lock.withLock {
                val count = runCatching { index.count() }.getOrDefault(0)
                val finishedAt = runCatching { index.getMeta(KEY_LAST_SYNC)?.toLongOrNull() }.getOrNull()
                val expected = runCatching { index.getMeta(KEY_EXPECTED)?.toIntOrNull() }.getOrNull()
                _syncState.value = when {
                    count > 0 -> SyncState.Synced(
                        finishedAt = finishedAt ?: 0,
                        stations = count,
                        added = null,
                        expected = expected,
                    )
                    else -> SyncState.NeverSynced
                }
            }
        }
    }

    val isSyncing: Boolean get() = _syncState.value is SyncState.Syncing

    fun cancelSync() {
        cancelRequested = true
    }

    /**
     * Pulls the corpus, rebuilds the index, then computes tag co-occurrence.
     *
     * The old index stays searchable right up until the swap, because a refresh shouldn't take the
     * feature away while it runs.
     */
    suspend fun sync() = withContext(Dispatchers.IO) {
        if (isSyncing) return@withContext
        cancelRequested = false
        val previous = _syncState.value
        val lastGoodAt = (previous as? SyncState.Synced)?.finishedAt

        try {
            _syncState.value = SyncState.Syncing("Contacting radio-browser", 0, RadioBrowser.SOURCE)

            val tagCounts = HashMap<String, Int>(1 shl 14)
            var staged = 0
            var expected: Int? = null
            val startedAt = System.currentTimeMillis()
            val before = runCatching { lock.withLock { index.count() } }.getOrDefault(0)

            lock.withLock {
                index.open()
                index.putMeta(KEY_LAST_STARTED, startedAt.toString())
                index.clearSource(RadioBrowser.SOURCE)

                RadioBrowser.fetchAllFromAnyMirror(
                    batchSize = BATCH,
                    isCancelled = { cancelRequested },
                    onHost = { host ->
                        // Ask the server what it has, so "did I get everything?" has an answer.
                        expected = RadioBrowser.fetchExpectedCount(host)
                        _syncState.value = SyncState.Syncing("Contacting $host", 0, RadioBrowser.SOURCE)
                    },
                ) { rows, total ->
                    index.insertBatch(rows)
                    for (row in rows) {
                        if (row.tags.isEmpty()) continue
                        for (tag in row.tags.split(',')) {
                            if (tag.isNotEmpty()) tagCounts[tag] = (tagCounts[tag] ?: 0) + 1
                        }
                    }
                    staged = total
                    _syncState.value = SyncState.Syncing(
                        phase = "Downloading stations",
                        fetched = total,
                        source = RadioBrowser.SOURCE,
                    )
                }

                if (cancelRequested) throw IllegalStateException("cancelled")

                _syncState.value = SyncState.Syncing("Indexing tags", staged, RadioBrowser.SOURCE)
                index.writeTagCounts(tagCounts)

                _syncState.value = SyncState.Syncing("Learning tag relationships", staged, RadioBrowser.SOURCE)
                val tagLists = ArrayList<List<String>>(staged.coerceAtMost(80_000))
                index.readAllTagLists { tags -> tagLists += tags.split(',') }
                val neighbours = QueryExpander.computeNeighbours(
                    tagCounts = tagCounts,
                    tagLists = tagLists.asSequence(),
                    totalStations = staged,
                )
                index.writeNeighbours(neighbours)

                _syncState.value = SyncState.Syncing("Compacting", staged, RadioBrowser.SOURCE)
                runCatching { index.compact() }

                val finishedAt = System.currentTimeMillis()
                val total = index.count()
                index.putMeta(KEY_LAST_SYNC, finishedAt.toString())
                index.putMeta(KEY_LAST_RESULT, "ok")
                index.putMeta(KEY_COUNT, total.toString())
                expected?.let { index.putMeta(KEY_EXPECTED, it.toString()) }

                _syncState.value = SyncState.Synced(
                    finishedAt = finishedAt,
                    stations = total,
                    added = if (before > 0) total - before else null,
                    expected = expected,
                )
                Log.i(
                    TAG,
                    "sync complete: $total stations (expected ${expected ?: "?"}, " +
                        "was $before), ${neighbours.size} tags with neighbours",
                )
            }
        } catch (error: Throwable) {
            Log.e(TAG, "sync failed", error)
            val reason = if (cancelRequested) {
                "cancelled"
            } else {
                error.message ?: error.javaClass.simpleName
            }
            val count = runCatching {
                lock.withLock {
                    // Persist the failure too: a run that went wrong should still be accountable
                    // next time the app opens, not just while the screen is still up.
                    index.putMeta(KEY_LAST_RESULT, reason)
                    index.count()
                }
            }.getOrDefault(0)
            _syncState.value = SyncState.Failed(
                reason = reason,
                lastGoodAt = lastGoodAt,
                stations = count,
            )
        }
    }

    suspend fun search(
        query: String,
        filters: Filters = Filters(),
        limit: Int = 120,
    ): Results = withContext(Dispatchers.IO) {
        val tokens = tokenise(query)
        if (tokens.isEmpty()) return@withContext Results(emptyList(), emptyList())

        lock.withLock {
            if (runCatching { index.count() }.getOrDefault(0) == 0) {
                return@withContext Results(emptyList(), emptyList())
            }
            val expansions = runCatching { expander.expand(tokens) }.getOrDefault(emptyList())
            val match = buildMatchQuery(tokens, expansions)
            val hits = runCatching {
                index.search(
                    match = match,
                    limit = limit * 3,
                    httpsOnly = filters.httpsOnly,
                    includeUnreachable = filters.includeUnreachable,
                )
            }.getOrElse {
                Log.w(TAG, "search failed for '$match'", it)
                emptyList()
            }

            // A row that matched what you actually typed beats one that only matched an expansion.
            val ranked = hits
                .map { hit ->
                    val direct = tokens.any { token ->
                        hit.name.contains(token, ignoreCase = true) ||
                            hit.tags.contains(token, ignoreCase = true)
                    }
                    hit to if (direct) hit.score * DIRECT_BOOST else hit.score
                }
                .sortedByDescending { it.second }
                .take(limit)
                .map { it.first }

            Results(hits = ranked, expansions = expansions)
        }
    }

    suspend fun popularTags(limit: Int = 24): List<String> = withContext(Dispatchers.IO) {
        lock.withLock { runCatching { index.popularTags(limit) }.getOrDefault(emptyList()) }
    }

    suspend fun stats(): Stats = withContext(Dispatchers.IO) {
        lock.withLock {
            runCatching {
                Stats(
                    total = index.count(),
                    bySource = index.countBySource(),
                    sizeBytes = index.sizeOnDiskBytes(),
                    lastSyncFinishedAt = index.getMeta(KEY_LAST_SYNC)?.toLongOrNull(),
                    lastSyncStartedAt = index.getMeta(KEY_LAST_STARTED)?.toLongOrNull(),
                    lastResult = index.getMeta(KEY_LAST_RESULT),
                    expected = index.getMeta(KEY_EXPECTED)?.toIntOrNull(),
                )
            }.getOrDefault(Stats(0, emptyList(), 0, null, null, null, null))
        }
    }

    suspend fun clearIndex() = withContext(Dispatchers.IO) {
        lock.withLock {
            runCatching {
                index.open()
                index.clearAll()
            }
            _syncState.value = SyncState.NeverSynced
        }
    }

    private fun tokenise(query: String): List<String> = query
        .lowercase()
        .split(Regex("[^\\p{L}\\p{N}]+"))
        .filter { it.length >= 2 }
        .distinct()
        .take(6)

    /**
     * FTS5 query syntax. Typed terms get a prefix match so search-as-you-type feels alive;
     * expansions are exact, because a prefix on a guessed synonym is a guess on a guess.
     */
    private fun buildMatchQuery(tokens: List<String>, expansions: List<String>): String {
        val typed = tokens.joinToString(" OR ") { "\"${it.escapeFts()}\"*" }
        if (expansions.isEmpty()) return typed
        val expanded = expansions.joinToString(" OR ") { "\"${it.escapeFts()}\"" }
        return "$typed OR $expanded"
    }

    private fun String.escapeFts(): String = replace("\"", "")

    private companion object {
        const val TAG = "SearchRepository"
        const val KEY_LAST_SYNC = "lastSyncFinishedAt"
        const val KEY_LAST_STARTED = "lastSyncStartedAt"
        const val KEY_LAST_RESULT = "lastSyncResult"
        const val KEY_COUNT = "stationCount"
        const val KEY_EXPECTED = "expectedStations"
        const val BATCH = 2_000

        /** How much better a direct hit is than an expansion-only hit. */
        const val DIRECT_BOOST = 4.0
    }
}
