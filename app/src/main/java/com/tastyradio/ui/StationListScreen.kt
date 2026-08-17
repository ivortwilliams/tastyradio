package com.tastyradio.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
 */
@Composable
fun StationListScreen(
    repository: StationRepository,
    mixer: Mixer,
    channels: List<Mixer.Channel>,
    contentPadding: PaddingValues,
    onNotify: (String) -> Unit,
) {
    val stations by repository.stations.collectAsStateWithLifecycle(initialValue = emptyList())
    val liveKeys = channels.map { it.key }.toSet()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
    ) {
        items(stations, key = { it.id }) { station ->
            StationRow(
                station = station,
                live = station.channelKey() in liveKeys,
                onToggle = {
                    if (!mixer.toggle(station)) {
                        onNotify(
                            "The mix is full — ${Mixer.MAX_CHANNELS} stations at once. Stop one first."
                        )
                    }
                },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
        }
    }
}

@Composable
private fun StationRow(
    station: Station,
    live: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Tap anywhere on the row, as the reference's "Tap Radio Station" setting does.
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StationArtwork(name = station.name, imageUrl = station.imageUrl, live = live)
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = station.name,
                style = MaterialTheme.typography.titleMedium,
                color = if (live) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
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
            Box(modifier = Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                if (live) {
                    StopGlyph(size = 14.dp, tint = MaterialTheme.colorScheme.primary)
                } else {
                    PlayGlyph(size = 18.dp)
                }
            }
        }
    }
}
