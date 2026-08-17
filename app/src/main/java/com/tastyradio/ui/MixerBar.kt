package com.tastyradio.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tastyradio.playback.Mixer

/**
 * Where Tasty Radio visibly stops being Transistor.
 *
 * Collapsed it stays close to the reference's playback pill. Expanded it is the mixing desk: one row
 * per channel, each with its own fader, mute and stop.
 */
@Composable
fun MixerBar(
    channels: List<Mixer.Channel>,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onFader: (String, Float) -> Unit,
    onMute: (String, Boolean) -> Unit,
    onStopChannel: (String) -> Unit,
    onStopAll: () -> Unit,
    onRetry: (String) -> Unit,
) {
    if (channels.isEmpty()) return

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 12.dp),
        shape = if (expanded) RoundedCornerShape(28.dp) else CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
    ) {
        Column {
            CollapsedRow(
                channels = channels,
                expanded = expanded,
                onToggleExpanded = { onExpandedChange(!expanded) },
                onStopAll = onStopAll,
            )

            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(bottom = 8.dp)) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f),
                    )
                    channels.forEach { channel ->
                        ChannelRow(
                            channel = channel,
                            onFader = { onFader(channel.key, it) },
                            onMute = { onMute(channel.key, it) },
                            onStop = { onStopChannel(channel.key) },
                            onRetry = { onRetry(channel.key) },
                        )
                    }
                    if (channels.size < Mixer.MAX_CHANNELS) {
                        Text(
                            text = "Tap another station to add it to the mix " +
                                "(${channels.size} of ${Mixer.MAX_CHANNELS}).",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CollapsedRow(
    channels: List<Mixer.Channel>,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onStopAll: () -> Unit,
) {
    val connecting = channels.any { it.state == Mixer.ChannelState.Connecting }
    val title = if (channels.size == 1) {
        channels.single().station.name
    } else {
        "${channels.size} stations"
    }
    // A real track title if any channel offers one, else name the mix — same fallback as the
    // reference, which repeats the station name when there's no metadata.
    val subtitle = channels.firstNotNullOfOrNull { it.nowPlaying }
        ?: channels.joinToString(" + ") { it.station.name }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggleExpanded)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Stacked artwork: the mix has more than one station in it, and it should look like it.
        Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            channels.take(2).forEachIndexed { index, channel ->
                Box(modifier = Modifier.padding(start = if (index == 0) 0.dp else 12.dp)) {
                    StationArtwork(
                        name = channel.station.name,
                        imageUrl = channel.station.imageUrl,
                        size = if (index == 0) 44.dp else 30.dp,
                    )
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (connecting && channels.none { it.state == Mixer.ChannelState.Playing }) {
                    "Connecting…"
                } else {
                    subtitle
                },
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = if (expanded) "▾" else "▴",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        // Master stop. Live streams stop, they don't pause.
        Box(
            modifier = Modifier
                .size(48.dp)
                .clickable(onClick = onStopAll),
            contentAlignment = Alignment.Center,
        ) {
            if (connecting) {
                ConnectingGlyph(size = 22.dp, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            } else {
                StopGlyph(size = 18.dp)
            }
        }
    }
}

@Composable
private fun ChannelRow(
    channel: Mixer.Channel,
    onFader: (Float) -> Unit,
    onMute: (Boolean) -> Unit,
    onStop: () -> Unit,
    onRetry: () -> Unit,
) {
    val failed = channel.state == Mixer.ChannelState.Failed
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = channel.station.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (channel.state == Mixer.ChannelState.Connecting) {
                    Spacer(Modifier.width(6.dp))
                    ConnectingGlyph(
                        size = 12.dp,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            Text(
                text = when {
                    // One dead station doesn't take the mix down — it just says so, on its own row.
                    failed -> "Failed: ${channel.error ?: "unknown"} · tap ↻ to retry"
                    channel.nowPlaying != null -> channel.nowPlaying
                    else -> channel.station.name
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Slider(
                value = channel.fader,
                onValueChange = onFader,
                enabled = !channel.muted && !failed,
                modifier = Modifier.height(28.dp),
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    activeTrackColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    inactiveTrackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.3f),
                ),
            )
        }
        Spacer(Modifier.width(4.dp))
        // "M" for mute is what a mixing desk actually labels it.
        Box(
            modifier = Modifier
                .size(36.dp)
                .clickable { onMute(!channel.muted) },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "M",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = if (channel.muted) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.55f)
                },
            )
        }
        if (failed) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clickable(onClick = onRetry),
                contentAlignment = Alignment.Center,
            ) {
                Text("↻", style = MaterialTheme.typography.titleMedium)
            }
        }
        Box(
            modifier = Modifier
                .size(36.dp)
                .clickable(onClick = onStop),
            contentAlignment = Alignment.Center,
        ) {
            Text("✕", style = MaterialTheme.typography.titleMedium)
        }
    }
}
