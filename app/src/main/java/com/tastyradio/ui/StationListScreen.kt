package com.tastyradio.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tastyradio.data.Station
import com.tastyradio.data.StationRepository
import com.tastyradio.playback.Mixer
import com.tastyradio.playback.channelKey

/**
 * The collection. Transistor's list, with one behavioural difference that is the whole point of the
 * app: tapping play **adds to the mix** rather than replacing what's already playing.
 *
 * Gestures follow the reference: **long-press to edit**, **swipe left to remove**.
 */
@Composable
fun StationListScreen(
    repository: StationRepository,
    mixer: Mixer,
    channels: List<Mixer.Channel>,
    contentPadding: PaddingValues,
    onNotify: (String) -> Unit,
    onMixChanged: () -> Unit = {},
) {
    val stations by repository.stations.collectAsStateWithLifecycle(initialValue = emptyList())
    val liveKeys = channels.map { it.key }.toSet()

    var editing by remember { mutableStateOf<Station?>(null) }
    var removing by remember { mutableStateOf<Station?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
    ) {
        items(stations, key = { it.id }) { station ->
            SwipeableStationRow(
                station = station,
                live = station.channelKey() in liveKeys,
                onToggle = {
                    onMixChanged()
                    if (!mixer.toggle(station)) {
                        onNotify(
                            "The mix is full — ${Mixer.MAX_CHANNELS} stations at once. Stop one first."
                        )
                    }
                },
                onEdit = { editing = station },
                onRemove = { removing = station },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
        }

        item {
            Text(
                text = "Long-press a station to edit it · swipe left to remove it",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
            )
        }
    }

    editing?.let { station ->
        EditStationDialog(
            station = station,
            repository = repository,
            onDismiss = { editing = null },
            onResult = onNotify,
        )
    }

    removing?.let { station ->
        RemoveStationDialog(
            station = station,
            repository = repository,
            onDismiss = { removing = null },
            onResult = onNotify,
        )
    }
}

@Composable
private fun SwipeableStationRow(
    station: Station,
    live: Boolean,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
) {
    val dismissState = rememberSwipeToDismissBoxState(
        // Never delete on the gesture alone: ask, then delete. Returning false snaps the row back
        // so the list looks right whichever way the question is answered.
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) onRemove()
            false
        }
    )

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = true,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(horizontal = 28.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Text(
                    text = "Remove",
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        },
    ) {
        StationRow(station = station, live = live, onToggle = onToggle, onEdit = onEdit)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StationRow(
    station: Station,
    live: Boolean,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            // Tap anywhere on the row, as the reference's "Tap Radio Station" setting does;
            // long-press to edit, as its "Edit Stations" setting does.
            .combinedClickable(onClick = onToggle, onLongClick = onEdit)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StationArtwork(name = station.name, imageUrl = station.imageUrl, live = live)
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            // Wrapped, not truncated: station names carry their distinguishing detail at the end
            // ("… Nhulunbuy NT", "… Gregorian Chants"), so cutting them hides the useful half.
            Text(
                text = station.name,
                style = MaterialTheme.typography.titleMedium,
                color = if (live) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            // Same card as a search result, minus the add button — tags first because they say
            // what the station *is*, then the plumbing.
            station.tags?.takeIf { it.isNotBlank() }?.let { tags ->
                Text(
                    text = tags.replace(",", " · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val facts = listOfNotNull(
                station.codec?.ifBlank { null },
                station.bitrate?.takeIf { it > 0 }?.let { "${it}k" },
                station.country?.ifBlank { null },
                station.language?.ifBlank { null },
            )
            if (facts.isNotEmpty()) {
                Text(
                    text = facts.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = station.streamUrl,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (live) {
                Text(
                    text = "in the mix",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        IconButton(onClick = onToggle) {
            Box(
                modifier = Modifier.size(36.dp).clip(CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (live) {
                    StopGlyph(size = 14.dp, tint = MaterialTheme.colorScheme.primary)
                } else {
                    PlayGlyph(size = 18.dp)
                }
            }
        }
    }
}
