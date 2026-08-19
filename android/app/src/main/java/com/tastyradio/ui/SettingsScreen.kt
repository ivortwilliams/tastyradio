package com.tastyradio.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tastyradio.data.Settings
import com.tastyradio.playback.Mixer
import com.tastyradio.search.SearchRepository
import com.tastyradio.update.Updater
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Only the settings that change how the app behaves. No theme picker — it follows the device.
 */
@Composable
fun SettingsScreen(
    search: SearchRepository,
    settings: Settings.Values,
    updater: Updater,
    onLargeBuffer: (Boolean) -> Unit,
    onRefresh: (Settings.RefreshFrequency) -> Unit,
    onSync: () -> Unit,
    onClearIndex: () -> Unit,
    onExportM3u: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val syncState by search.syncState.collectAsStateWithLifecycle()
    val updateState by updater.state.collectAsStateWithLifecycle()
    var stats by remember { mutableStateOf<SearchRepository.Stats?>(null) }

    LaunchedEffect(syncState) { stats = search.stats() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)

        // ---------------------------------------------------------------- station index
        Card {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Station index", style = MaterialTheme.typography.titleMedium)

                val current = stats
                if (current != null && current.total > 0) {
                    Text(
                        text = "%,d stations".format(current.total),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    // The server's own count, so "did I get everything?" has a real answer.
                    current.expected?.let { expected ->
                        val complete = current.total >= expected * 0.95
                        Text(
                            text = if (complete) {
                                "Complete — radio-browser reports %,d".format(expected)
                            } else {
                                "⚠ Partial — radio-browser reports %,d. Sync again.".format(expected)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (complete) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                        )
                    }
                } else {
                    Text(
                        text = "Not downloaded yet. Search needs this — around 62,000 stations, " +
                            "a few megabytes, then everything is local.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                Text(
                    text = when (val state = syncState) {
                        is SearchRepository.SyncState.NeverSynced -> "Never synced."
                        is SearchRepository.SyncState.Syncing ->
                            "${state.phase}… %,d".format(state.fetched)
                        is SearchRepository.SyncState.Synced -> buildString {
                            append("Last sync succeeded ")
                            append(absoluteTime(state.finishedAt))
                            state.added?.let { added ->
                                append(
                                    when {
                                        added > 0 -> " · $added added since the previous sync"
                                        added < 0 -> " · ${-added} removed since the previous sync"
                                        else -> " · no change"
                                    }
                                )
                            }
                        }
                        is SearchRepository.SyncState.Failed ->
                            "Last sync FAILED: ${state.reason}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (syncState is SearchRepository.SyncState.Failed) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )

                if (current != null && current.total > 0) {
                    HorizontalDivider()
                    current.bySource.forEach { (source, count) ->
                        Text(
                            text = "$source — %,d".format(count),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    Text(
                        text = "${current.sizeBytes / (1024 * 1024)} MB on disk",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Text("Refresh automatically", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Settings.RefreshFrequency.entries.forEach { frequency ->
                        FilterChip(
                            selected = settings.refresh == frequency,
                            onClick = { onRefresh(frequency) },
                            label = { Text(frequency.label) },
                        )
                    }
                }
                Text(
                    text = "Runs on Wi-Fi while charging, so new stations arrive without you " +
                        "thinking about it.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onSync,
                        enabled = syncState !is SearchRepository.SyncState.Syncing,
                    ) {
                        Text(if ((stats?.total ?: 0) > 0) "Sync now" else "Download index")
                    }
                    if ((stats?.total ?: 0) > 0) {
                        TextButton(onClick = onClearIndex) { Text("Clear index") }
                    }
                }
            }
        }

        // ---------------------------------------------------------------- playback
        Card {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("Playback", style = MaterialTheme.typography.titleMedium)

                SettingSwitch(
                    title = "Large buffer",
                    description = "About a minute of audio held ahead, instead of ten seconds. " +
                        "Far fewer dropouts on patchy mobile data — one channel stalling breaks " +
                        "the whole mix — at the cost of taking longer to start. Applies to " +
                        "stations started from now on.",
                    checked = settings.largeBuffer,
                    onCheckedChange = onLargeBuffer,
                )
            }
        }

        // ---------------------------------------------------------------- your data
        Card {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Your stations", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "Export writes a plain M3U playlist to Downloads. Any other player can " +
                        "read it, and Tasty Radio can import it back — so it doubles as a backup.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(onClick = onExportM3u) { Text("Export M3U") }
                Text(
                    text = "Long-press a station to edit its name, artwork or stream URL. " +
                        "Swipe it left to remove it.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Card {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "Tasty Radio ${updater.installedVersionName}",
                    style = MaterialTheme.typography.titleMedium,
                )
                // This app arrives as a file from a friend, not from a store, so the only thing
                // that tells you it's out of date is this line.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = when (val update = updateState) {
                            is Updater.State.Checking -> "Checking for updates…"
                            is Updater.State.UpToDate -> "Up to date"
                            is Updater.State.Available -> "Version ${update.release.versionName} is available"
                            is Updater.State.Downloading -> "Downloading… ${update.percent}%"
                            is Updater.State.Ready -> "Ready to install"
                            is Updater.State.Failed -> update.reason
                            Updater.State.Idle -> "Updates come straight to the app"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (updateState is Updater.State.Available) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        enabled = updateState !is Updater.State.Checking &&
                            updateState !is Updater.State.Downloading,
                        onClick = { updater.check(force = true) },
                    ) { Text("Check now") }
                }
                Text(
                    text = "Up to ${Mixer.MAX_CHANNELS} stations at once. Faders are per station; " +
                        "M mutes a channel; ✕ drops it. The red dot records the mix to a file in " +
                        "Recordings, ready to share.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = "Recording asks for the audio permission because Android delivers " +
                        "playback capture through the same API as the microphone. The microphone " +
                        "is never opened.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = description,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private fun absoluteTime(timestamp: Long): String =
    if (timestamp <= 0) {
        "recently"
    } else {
        SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(timestamp))
    }
