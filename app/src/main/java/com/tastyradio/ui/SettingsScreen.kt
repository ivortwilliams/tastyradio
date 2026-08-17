package com.tastyradio.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tastyradio.playback.Mixer

/** Preferences, mixer options and maintenance. Phase 5 — this page is honest about being empty. */
@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
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
                Text("Tasty Radio 0.1", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "Up to ${Mixer.MAX_CHANNELS} stations at once. Faders are per station; " +
                        "M mutes a channel; ✕ drops it out of the mix.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "Theme follows the device, with Material You colours where the device " +
                        "supports them.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "Still to come: recording the mix (phase 3), the station index and " +
                        "search (phase 4), then theme choice, larger buffer, editing toggles, " +
                        "M3U export and backup/restore (phase 5).",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
