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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tastyradio.playback.Mixer
import com.tastyradio.search.SearchRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Preferences, mixer options and maintenance. The station index is the part that's real so far. */
@Composable
fun SettingsScreen(
    search: SearchRepository,
    onSync: () -> Unit,
    onClearIndex: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val syncState by search.syncState.collectAsStateWithLifecycle()
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

        Card {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Station index", style = MaterialTheme.typography.titleMedium)

                val current = stats
                Text(
                    text = when (val state = syncState) {
                        is SearchRepository.SyncState.NeverSynced ->
                            "Not downloaded. Search needs this — about 60,000 stations."
                        is SearchRepository.SyncState.Syncing ->
                            "${state.phase}… ${"%,d".format(state.fetched)} stations"
                        is SearchRepository.SyncState.Synced ->
                            "Last synced ${absoluteTime(state.finishedAt)}"
                        is SearchRepository.SyncState.Failed ->
                            "Last sync failed: ${state.reason}"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )

                if (current != null && current.total > 0) {
                    HorizontalDivider()
                    Text(
                        text = "${"%,d".format(current.total)} stations · " +
                            "${current.sizeBytes / (1024 * 1024)} MB on disk",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    // Per-source breakdown, so a source that turns out to be noise is visible.
                    current.bySource.forEach { (source, count) ->
                        Text(
                            text = "$source — ${"%,d".format(count)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = onSync,
                        enabled = syncState !is SearchRepository.SyncState.Syncing,
                    ) {
                        Text(if (stats?.total ?: 0 > 0) "Sync now" else "Download index")
                    }
                    if ((stats?.total ?: 0) > 0) {
                        TextButton(onClick = onClearIndex) { Text("Clear index") }
                    }
                }
                Text(
                    text = "Downloaded over the network once, then searched entirely on the phone: " +
                        "instant, offline, and nothing you type leaves the device.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Card {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Tasty Radio 0.1", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "Up to ${Mixer.MAX_CHANNELS} stations at once. Faders are per station; " +
                        "M mutes a channel; ✕ drops it out of the mix. The red dot records the " +
                        "whole mix to a file you can share.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "Recording asks for the audio permission because Android delivers " +
                        "playback capture through the same API as the microphone. The microphone " +
                        "is never opened — the capture is limited to this app's own sound.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "Still to come: theme choice, larger buffer, editing toggles, M3U " +
                        "export and backup/restore.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun absoluteTime(timestamp: Long): String =
    if (timestamp <= 0) {
        "recently"
    } else {
        SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(timestamp))
    }
