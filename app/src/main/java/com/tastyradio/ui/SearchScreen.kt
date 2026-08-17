package com.tastyradio.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tastyradio.data.Station
import com.tastyradio.search.SearchRepository
import com.tastyradio.search.StationIndex
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Discovery: a full page, not Transistor's popup dialog.
 *
 * The search is local — the whole corpus lives on the phone — so it's instant, offline, private, and
 * ours to rank. And it searches tags, country and language as well as the name, which is the actual
 * fix for "I typed a word that describes the station and got nothing".
 */
@Composable
fun SearchScreen(
    search: SearchRepository,
    onAudition: (Station) -> Unit,
    onAdd: (StationIndex.Hit) -> Unit,
    onSync: () -> Unit,
    onAddByUrl: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf(SearchRepository.Results(emptyList(), emptyList())) }
    var dropped by remember { mutableStateOf(setOf<String>()) }
    var filters by remember { mutableStateOf(SearchRepository.Filters()) }
    var popular by remember { mutableStateOf(listOf<String>()) }
    var searching by remember { mutableStateOf(false) }
    var expansionsExpanded by remember { mutableStateOf(false) }
    val syncState by search.syncState.collectAsStateWithLifecycle()

    LaunchedEffect(syncState) {
        if (syncState is SearchRepository.SyncState.Synced) popular = search.popularTags()
    }

    // Search-as-you-type, with just enough debounce to not run a query per keystroke.
    LaunchedEffect(query, filters, dropped, syncState) {
        if (query.isBlank()) {
            results = SearchRepository.Results(emptyList(), emptyList())
            return@LaunchedEffect
        }
        delay(180)
        searching = true
        val found = search.search(query, filters)
        results = found.copy(expansions = found.expansions.filterNot { it in dropped })
        searching = false
    }

    Column(modifier = modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            label = { Text("Search stations") },
            placeholder = { Text("religion, gregorian, techno, philosophy…") },
            singleLine = true,
            trailingIcon = {
                if (query.isNotEmpty()) {
                    TextButton(onClick = { query = "" }) { Text("✕") }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(),
        )

        SyncStatusLine(syncState = syncState, onSync = onSync)

        if (results.expansions.isNotEmpty()) {
            ExpansionRow(
                expansions = results.expansions,
                expanded = expansionsExpanded,
                onToggleExpanded = { expansionsExpanded = !expansionsExpanded },
                onRemove = { term -> dropped = dropped + term },
            )
        }

        if (query.isNotBlank()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(
                    selected = filters.httpsOnly,
                    onClick = { filters = filters.copy(httpsOnly = !filters.httpsOnly) },
                    label = { Text("HTTPS only") },
                )
                FilterChip(
                    selected = filters.includeUnreachable,
                    onClick = {
                        filters = filters.copy(includeUnreachable = !filters.includeUnreachable)
                    },
                    label = { Text("Show unreachable") },
                )
            }
        }

        when {
            query.isBlank() -> EmptyState(
                popular = popular,
                onTag = { query = it },
                onAddByUrl = onAddByUrl,
            )

            results.hits.isEmpty() && !searching -> Text(
                text = "Nothing matched. Try a broader word — the index searches tags, country and " +
                    "language as well as names.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 24.dp),
            )

            else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                item {
                    Text(
                        text = "${results.hits.size} stations",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
                items(results.hits, key = { it.uuid + it.url }) { hit ->
                    ResultRow(hit = hit, onAudition = onAudition, onAdd = onAdd)
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                }
            }
        }
    }
}

/**
 * What the query got widened to.
 *
 * The rule is that expansion must be *visible and steerable* — invisible fuzzy matching reads as a
 * broken app. But eight chips wrap to three rows and eat the results, so collapsed it's one line
 * that still names the terms, and tapping it opens the removable chips.
 */
@Composable
private fun ExpansionRow(
    expansions: List<String>,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onRemove: (String) -> Unit,
) {
    if (!expanded) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggleExpanded)
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val shown = expansions.take(3).joinToString(", ")
            val rest = expansions.size - minOf(3, expansions.size)
            Text(
                text = "also searching: $shown" + if (rest > 0) " +$rest" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = " ⌄",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = "also searching",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier
                .clickable(onClick = onToggleExpanded)
                .padding(top = 14.dp, end = 4.dp),
        )
        expansions.forEach { term ->
            InputChip(
                selected = false,
                onClick = { onRemove(term) },
                label = { Text(term) },
                trailingIcon = { Text("✕", style = MaterialTheme.typography.labelSmall) },
            )
        }
        TextButton(onClick = onToggleExpanded) { Text("⌃") }
    }
}

/**
 * A slim, non-blocking line. Never a modal, never a spinner over the whole app: the old index stays
 * fully searchable while a new sync runs.
 */
@Composable
private fun SyncStatusLine(
    syncState: SearchRepository.SyncState,
    onSync: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (syncState) {
            is SearchRepository.SyncState.NeverSynced -> {
                Text(
                    text = "Station index not downloaded yet.",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onSync) { Text("Download now") }
            }

            is SearchRepository.SyncState.Syncing -> {
                CircularProgressIndicator(modifier = Modifier.height(14.dp).width(14.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (syncState.fetched > 0) {
                        "${syncState.phase}… ${"%,d".format(syncState.fetched)}"
                    } else {
                        "${syncState.phase}…"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            is SearchRepository.SyncState.Synced -> Text(
                text = "${"%,d".format(syncState.stations)} stations · synced " +
                    relativeTime(syncState.finishedAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )

            is SearchRepository.SyncState.Failed -> {
                Text(
                    // The real reason, never a bare "error".
                    text = "Sync failed: ${syncState.reason}" +
                        if (syncState.stations > 0) " · still searching ${syncState.stations} stations" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onSync) { Text("Retry") }
            }
        }
    }
}

/** An empty search page is a wasted screen — this is where the local index pays off. */
@Composable
private fun EmptyState(
    popular: List<String>,
    onTag: (String) -> Unit,
    onAddByUrl: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedButton(onClick = onAddByUrl, modifier = Modifier.fillMaxWidth()) {
            Text("Add by URL  /  Import M3U or PLS")
        }

        if (popular.isNotEmpty()) {
            Text("Browse by tag", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                popular.forEach { tag ->
                    AssistChip(onClick = { onTag(tag) }, label = { Text(tag) })
                }
            }
        }

        Card {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("Try a word, not a name", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = "Search looks at tags, country and language too, and widens the query " +
                        "using relationships learned from the corpus — so \"religion\" finds " +
                        "Radio Vaticana even though those words share nothing.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = "▶ auditions a station straight into the running mix, quietly, without " +
                        "adding it to your collection.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun ResultRow(
    hit: StationIndex.Hit,
    onAudition: (Station) -> Unit,
    onAdd: (StationIndex.Hit) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StationArtwork(name = hit.name, imageUrl = hit.favicon.ifBlank { null }, size = 44.dp)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = buildString {
                    append(hit.name)
                    if (hit.codec.isNotBlank() || hit.bitrate > 0) {
                        append(" (")
                        append(hit.codec.ifBlank { "?" })
                        if (hit.bitrate > 0) append(" · ${hit.bitrate}k")
                        append(")")
                    }
                },
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (hit.tags.isNotBlank()) {
                // Tags explain *why* this result matched.
                Text(
                    text = hit.tags.replace(",", " · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = listOfNotNull(
                    hit.country.ifBlank { null },
                    hit.language.ifBlank { null },
                    if (!hit.lastCheckOk) "unreachable" else null,
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // Transistor's honesty about the plumbing: show the raw stream URL.
            Text(
                text = hit.url,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(onClick = { onAudition(hit.toStation()) }) { Text("▶") }
        TextButton(onClick = { onAdd(hit) }) { Text("＋") }
    }
}

/** id = 0 marks a station that isn't in the collection, which is what an audition plays. */
fun StationIndex.Hit.toStation(): Station = Station(
    id = 0,
    name = name,
    streamUrl = url,
    imageUrl = favicon.ifBlank { null },
    sourceUuid = uuid.ifBlank { null },
    source = source,
)

private fun relativeTime(timestamp: Long): String {
    if (timestamp <= 0) return "recently"
    val elapsed = System.currentTimeMillis() - timestamp
    val minutes = elapsed / 60_000
    val hours = minutes / 60
    val days = hours / 24
    return when {
        minutes < 2 -> "just now"
        minutes < 60 -> "$minutes minutes ago"
        hours < 24 -> "$hours hour${if (hours == 1L) "" else "s"} ago"
        days < 30 -> "$days day${if (days == 1L) "" else "s"} ago"
        else -> SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(timestamp))
    }
}
