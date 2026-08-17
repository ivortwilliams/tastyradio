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
        data class Synced(val finishedAt: Long, val stations: Int) : SyncState
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
                _syncState.value = when {
                    count > 0 && finishedAt != null -> SyncState.Synced(finishedAt, count)
                    count > 0 -> SyncState.Synced(0, count)
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

            lock.withLock {
                index.open()
                index.clearSource(RadioBrowser.SOURCE)

                RadioBrowser.fetchAllFromAnyMirror(
                    batchSize = BATCH,
                    isCancelled = { cancelRequested },
                    onHost = { host ->
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

                val finishedAt = System.currentTimeMillis()
                index.putMeta(KEY_LAST_SYNC, finishedAt.toString())
                val total = index.count()
                _syncState.value = SyncState.Synced(finishedAt, total)
                Log.i(TAG, "sync complete: $total stations, ${neighbours.size} tags with neighbours")
            }
        } catch (error: Throwable) {
            Log.e(TAG, "sync failed", error)
            val count = runCatching { lock.withLock { index.count() } }.getOrDefault(0)
            _syncState.value = SyncState.Failed(
                reason = if (cancelRequested) "cancelled" else (error.message ?: error.javaClass.simpleName),
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
                )
            }.getOrDefault(Stats(0, emptyList(), 0, null))
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
        const val BATCH = 2_000

        /** How much better a direct hit is than an expansion-only hit. */
        const val DIRECT_BOOST = 4.0
    }
}
