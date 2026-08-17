package com.tastyradio.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tastyradio.data.Mix
import com.tastyradio.data.MixWithChannels
import com.tastyradio.data.Station

/**
 * Saved soundscapes. Tap one and it replaces whatever is playing with those stations at those
 * levels — the point being that a combination you found once is a combination you can get back to.
 */
@Composable
fun MixesScreen(
    mixes: List<MixWithChannels>,
    stations: List<Station>,
    liveMixName: String?,
    contentPadding: PaddingValues,
    onPlay: (MixWithChannels) -> Unit,
    onRename: (Mix, String) -> Unit,
    onDelete: (Mix) -> Unit,
) {
    var renaming by remember { mutableStateOf<Mix?>(null) }
    var deleting by remember { mutableStateOf<Mix?>(null) }
    val stationsById = remember(stations) { stations.associateBy { it.id } }

    if (mixes.isEmpty()) {
        EmptyMixes(contentPadding)
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
    ) {
        items(mixes, key = { it.mix.id }) { entry ->
            SwipeableMixCard(
                entry = entry,
                stationsById = stationsById,
                live = liveMixName == entry.mix.name,
                onPlay = { onPlay(entry) },
                onRename = { renaming = entry.mix },
                onDelete = { deleting = entry.mix },
            )
        }

        item {
            Text(
                text = "Long-press a mix to rename it · swipe left to delete it",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
            )
        }
    }

    renaming?.let { mix ->
        var name by remember(mix.id) { mutableStateOf(mix.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename mix") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank(),
                    onClick = {
                        onRename(mix, name)
                        renaming = null
                    },
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }

    deleting?.let { mix ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete this mix?") },
            text = {
                Text("— ${mix.name}\n\nThe stations stay in your collection; only the saved levels go.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete(mix)
                        deleting = null
                    },
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

/** Same gestures as the station list: long-press to rename, swipe left to delete. */
@Composable
private fun SwipeableMixCard(
    entry: MixWithChannels,
    stationsById: Map<Long, Station>,
    live: Boolean,
    onPlay: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val dismissState = rememberSwipeToDismissBoxState(
        // Ask, then delete — and snap back either way, so the list looks right whichever the answer.
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) onDelete()
            false
        }
    )

    Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
        SwipeToDismissBox(
            state = dismissState,
            enableDismissFromStartToEnd = false,
            enableDismissFromEndToStart = true,
            backgroundContent = {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .padding(horizontal = 24.dp),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    Text(
                        text = "Delete",
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            },
        ) {
            MixCard(
                entry = entry,
                stationsById = stationsById,
                live = live,
                onPlay = onPlay,
                onRename = onRename,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MixCard(
    entry: MixWithChannels,
    stationsById: Map<Long, Station>,
    live: Boolean,
    onPlay: () -> Unit,
    onRename: () -> Unit,
) {
    val members = entry.channels.mapNotNull { stationsById[it.stationId] }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onPlay, onLongClick = onRename),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Stacked artwork of the stations in it: a mix should look like more than one thing.
            Box(modifier = Modifier.size(56.dp), contentAlignment = Alignment.CenterStart) {
                members.take(3).forEachIndexed { index, station ->
                    Box(modifier = Modifier.padding(start = (index * 13).dp)) {
                        StationArtwork(
                            name = station.name,
                            imageUrl = station.imageUrl,
                            size = 30.dp,
                        )
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                // Uncapped: mix names are prefilled from the station names and get long, and the
                // whole point of a name is telling one mix from another.
                Text(
                    text = entry.mix.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (live) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                Text(
                    text = members.joinToString(" + ") { it.name },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
                // A mix whose stations were deleted would otherwise just quietly play less.
                val missing = entry.channels.size - members.size
                if (missing > 0) {
                    Text(
                        text = "$missing station${if (missing == 1) "" else "s"} no longer in your collection",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Box(
                modifier = Modifier.size(44.dp).clickable(onClick = onPlay),
                contentAlignment = Alignment.Center,
            ) {
                PlayGlyph(size = 20.dp, tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun EmptyMixes(contentPadding: PaddingValues) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("No saved mixes yet", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "Start a few stations, balance them how you want, then open the mixer and tap " +
                "Save mix. It remembers the stations, their levels, mutes, tone, reverb and " +
                "delay — tap it here later and the whole thing comes back.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = "Saving under a name you've already used replaces that mix, so you can tweak " +
                "the levels and save again without collecting duplicates.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
