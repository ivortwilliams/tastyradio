package com.tastyradio.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tastyradio.share.MixLink
import kotlin.math.roundToInt

/**
 * Somebody sent you a mix.
 *
 * The link carries the stations themselves, not references into the sender's collection, so this
 * can always be played — even if you have never heard of any of them. What it deliberately does not
 * do is add anything to your collection behind your back: opening a link is not consent to have
 * someone else's stations turn up in your list. **Keep it** is the button that means that, and it
 * is the primary one because it is usually what you want.
 */
@Composable
fun SharedMixDialog(
    shared: MixLink.Shared,
    knownUrls: Set<String>,
    onDismiss: () -> Unit,
    onOpen: (keep: Boolean) -> Unit,
) {
    val fresh = shared.channels.count { it.station.streamUrl !in knownUrls }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Someone sent you a mix") },
        text = {
            Column {
                Text(
                    text = shared.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(8.dp))
                shared.channels.forEach { channel ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = channel.station.name,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "${(channel.fader * 100).roundToInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // Reverb on one channel and not the other is most of what makes a mix a mix,
                    // so say so before it is played rather than leaving it to be discovered.
                    val shaping = buildList {
                        if (channel.tone.reverb > 0.005f) add("${(channel.tone.reverb * 100).roundToInt()}% reverb")
                        if (channel.tone.delay > 0.005f) add("${(channel.tone.delay * 100).roundToInt()}% delay")
                        if (channel.tone.low != 0f || channel.tone.mid != 0f || channel.tone.high != 0f) add("EQ")
                        if (channel.muted) add("muted")
                    }
                    if (shaping.isNotEmpty()) {
                        Text(
                            text = shaping.joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(bottom = 2.dp),
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    text = if (fresh == 0) {
                        "You already have all of these stations."
                    } else {
                        "$fresh station${if (fresh == 1) "" else "s"} you don't have yet — " +
                            "keeping the mix keeps ${if (fresh == 1) "it" else "them"} too."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { onOpen(false) }) { Text("Just play it") }
                TextButton(onClick = { onOpen(true) }) { Text("Keep it") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Not now") } },
    )
}
