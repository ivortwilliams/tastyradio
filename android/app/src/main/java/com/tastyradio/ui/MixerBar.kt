package com.tastyradio.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.pow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tastyradio.playback.ChannelFilters
import com.tastyradio.playback.Mixer
import com.tastyradio.record.Recorder

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
    recording: Recorder.State,
    onToggleRecording: () -> Unit,
    onTone: (String, Mixer.Tone) -> Unit,
    onSaveMix: () -> Unit,
    onShareMix: () -> Unit,
) {
    if (channels.isEmpty()) return
    // One channel's tone controls open at a time: four channels' worth at once would fill the
    // screen, and you balance one against the others anyway.
    var toneOpenFor by remember { mutableStateOf<String?>(null) }

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
                recording = recording,
                onToggleExpanded = { onExpandedChange(!expanded) },
                onStopAll = onStopAll,
                onToggleRecording = onToggleRecording,
            )

            AnimatedVisibility(visible = expanded) {
                // Capped and scrollable: four channels with a set of effect sliders open is taller
                // than the screen, and now that the navigation bar stays put the sheet has to live
                // within a budget rather than growing until something falls off the top.
                Column(
                    modifier = Modifier
                        .heightIn(max = 340.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = 8.dp)
                ) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f),
                    )
                    channels.forEach { channel ->
                        ChannelRow(
                            channel = channel,
                            toneOpen = toneOpenFor == channel.key,
                            onToggleTone = {
                                toneOpenFor = if (toneOpenFor == channel.key) null else channel.key
                            },
                            onFader = { onFader(channel.key, it) },
                            onMute = { onMute(channel.key, it) },
                            onStop = { onStopChannel(channel.key) },
                            onRetry = { onRetry(channel.key) },
                        )
                        AnimatedVisibility(visible = toneOpenFor == channel.key) {
                            ToneControls(
                                tone = channel.tone,
                                onTone = { onTone(channel.key, it) },
                            )
                        }
                    }
                    // Saving lives here because this is where you are when it finally sounds right
                    // — and so does sending it, for the same reason.
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = onShareMix) {
                            Text("Share mix", style = MaterialTheme.typography.labelLarge)
                        }
                        TextButton(onClick = onSaveMix) {
                            Text("Save mix", style = MaterialTheme.typography.labelLarge)
                        }
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
    recording: Recorder.State,
    onToggleExpanded: () -> Unit,
    onStopAll: () -> Unit,
    onToggleRecording: () -> Unit,
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
            // The elapsed time lives on this line rather than in a row of its own: an extra row
            // grows the sheet, and because the sheet is anchored to the bottom of the screen that
            // moves the record button out from under your thumb mid-take.
            Text(
                text = when {
                    recording is Recorder.State.Recording -> "● ${elapsed(recording.startedAtMs)} · $subtitle"
                    connecting && channels.none { it.state == Mixer.ChannelState.Playing } -> "Connecting…"
                    else -> subtitle
                },
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = if (expanded) "▾" else "▴",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        // Record. Elapsed time lives in the expanded sheet; here it's just a blinking red dot.
        Box(
            modifier = Modifier
                .size(44.dp)
                .clickable(onClick = onToggleRecording),
            contentAlignment = Alignment.Center,
        ) {
            if (recording is Recorder.State.Recording) {
                val blink by rememberInfiniteTransition(label = "rec").animateFloat(
                    initialValue = 1f,
                    targetValue = 0.25f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(700),
                        repeatMode = RepeatMode.Reverse,
                    ),
                    label = "recAlpha",
                )
                StopGlyph(size = 15.dp, tint = Color(0xFFFF5252).copy(alpha = blink))
            } else {
                RecordGlyph(size = 15.dp)
            }
        }
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

/** Ticking elapsed time for the take in progress. */
@Composable
private fun elapsed(startedAtMs: Long): String {
    var now by remember(startedAtMs) { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(startedAtMs) {
        while (true) {
            now = System.currentTimeMillis()
            delay(500)
        }
    }
    val seconds = ((now - startedAtMs) / 1000).coerceAtLeast(0)
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

/**
 * Low / mid / high, each −12…+12 dB. Detents at zero so "flat" is findable by thumb, and a Flat
 * button because undoing three sliders by hand is annoying.
 */
@Composable
private fun ToneControls(
    tone: Mixer.Tone,
    onTone: (Mixer.Tone) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 12.dp, bottom = 10.dp),
    ) {
        ToneSlider("LOW", tone.low) { onTone(tone.copy(low = it)) }
        ToneSlider("MID", tone.mid) { onTone(tone.copy(mid = it)) }
        ToneSlider("HIGH", tone.high) { onTone(tone.copy(high = it)) }
        AmountSlider("REV", tone.reverb) { onTone(tone.copy(reverb = it)) }
        AmountSlider("DLY", tone.delay) { onTone(tone.copy(delay = it)) }
        // Time is only meaningful once there's an echo to time, so it greys out until there is.
        TimeSlider(
            valueMs = tone.delayMs,
            enabled = tone.delay > 0f,
            onValue = { onTone(tone.copy(delayMs = it)) },
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(
                onClick = { onTone(Mixer.Tone()) },
                enabled = !tone.isFlat,
            ) {
                Text("Flat", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/** A plain 0…100% amount: reverb and delay wet level. */
@Composable
private fun AmountSlider(
    label: String,
    value: Float,
    onValue: (Float) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = if (value > 0f) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
            },
            modifier = Modifier.width(40.dp),
        )
        Slider(
            value = value,
            onValueChange = onValue,
            valueRange = 0f..1f,
            steps = 19,
            modifier = Modifier.weight(1f).height(24.dp),
            colors = mixerSliderColors(),
        )
        Text(
            text = if (value <= 0f) "off" else "%.0f%%".format(value * 100),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.width(46.dp),
        )
    }
}

/** Delay time, in milliseconds — a slapback at one end, a long wash at the other. */
@Composable
private fun TimeSlider(
    valueMs: Float,
    enabled: Boolean,
    onValue: (Float) -> Unit,
) {
    val alpha = if (enabled) 1f else 0.4f
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "TIME",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = alpha * 0.9f),
            modifier = Modifier.width(40.dp),
        )
        Slider(
            value = valueMs,
            onValueChange = onValue,
            enabled = enabled,
            valueRange = ChannelFilters.MIN_DELAY_MS..ChannelFilters.MAX_DELAY_MS,
            modifier = Modifier.weight(1f).height(24.dp),
            colors = mixerSliderColors(),
        )
        Text(
            text = "%.0f ms".format(valueMs),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = alpha),
            modifier = Modifier.width(46.dp),
        )
    }
}

@Composable
private fun mixerSliderColors() = SliderDefaults.colors(
    thumbColor = MaterialTheme.colorScheme.onPrimaryContainer,
    activeTrackColor = MaterialTheme.colorScheme.onPrimaryContainer,
    inactiveTrackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.3f),
    activeTickColor = Color.Transparent,
    inactiveTickColor = Color.Transparent,
)

/**
 * One band of the isolator. The bottom of the travel is a full kill, not a −12 dB cut, so the
 * readout says "kill" rather than a number you'd have to interpret.
 */
@Composable
private fun ToneSlider(
    label: String,
    value: Float,
    onValue: (Float) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = if (value != 0f) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
            },
            modifier = Modifier.width(40.dp),
        )
        Slider(
            value = value,
            onValueChange = onValue,
            valueRange = -1f..1f,
            // An even number of steps puts a stop exactly on unity, so "flat" is findable by thumb.
            steps = 39,
            modifier = Modifier.weight(1f).height(24.dp),
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.onPrimaryContainer,
                activeTrackColor = MaterialTheme.colorScheme.onPrimaryContainer,
                inactiveTrackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.3f),
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent,
            ),
        )
        Text(
            text = bandLabel(value),
            style = MaterialTheme.typography.labelSmall,
            color = if (value <= -0.995f) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onPrimaryContainer
            },
            modifier = Modifier.width(46.dp),
        )
    }
}

private fun bandLabel(value: Float): String = when {
    value <= -0.995f -> "kill"
    value == 0f -> "0"
    value < 0f -> "%.0f".format(value * ChannelFilters.CUT_RANGE_DB)
    else -> "+%.0f".format(value * ChannelFilters.MAX_BOOST_DB)
}

@Composable
private fun ChannelRow(
    channel: Mixer.Channel,
    toneOpen: Boolean,
    onToggleTone: () -> Unit,
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
        // "EQ" opens this channel's tone controls; it lights up when the channel isn't flat, so a
        // shaped channel is visible without opening it.
        Box(
            modifier = Modifier
                .size(36.dp)
                .clickable(onClick = onToggleTone),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "EQ",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = if (toneOpen || !channel.tone.isFlat) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.55f)
                },
            )
        }
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
