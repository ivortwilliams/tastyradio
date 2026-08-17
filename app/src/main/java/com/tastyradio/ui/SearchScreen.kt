package com.tastyradio.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Discovery. A full page rather than Transistor's popup dialog, because discovery here means
 * browsing, filtering, comparing and auditioning into the running mix.
 *
 * **Not built yet — this is phase 4**, after the mixer and recording. What lives here today is the
 * one part of "add a station" that phase 1 genuinely needs: by URL, and by importing a playlist.
 * That's deliberate, not a stub for its own sake — M3U import is how the real station list arrives,
 * straight out of Transistor's own *Export M3U*, which is exactly why search isn't blocking
 * anything.
 */
@Composable
fun SearchScreen(
    modifier: Modifier = Modifier,
    onAddByUrl: () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Search", style = MaterialTheme.typography.headlineSmall)

        OutlinedButton(onClick = onAddByUrl) {
            Text("Add by URL  /  Import M3U or PLS")
        }

        Card {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Coming in phase 4", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "Searching a station directory downloaded onto the phone, rather than " +
                        "queried live per keystroke: instant, offline, and ranked here rather " +
                        "than by the API.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "It searches tags, country and language as well as the name — so " +
                        "\"religion\" finds Radio Vaticana, which name-only search never will. " +
                        "And ▶ on a result auditions it straight into the running mix, quietly, " +
                        "before you decide to keep it.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
