package com.tastyradio.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.tastyradio.data.Station
import com.tastyradio.data.StationRepository
import kotlinx.coroutines.launch

/**
 * Long-press a station to edit it: name, artwork, and the stream URL itself.
 *
 * The stream URL is editable on purpose. This audience is trusted with the plumbing — a station
 * whose feed moves is a station you can fix rather than delete and re-add.
 */
@Composable
fun EditStationDialog(
    station: Station,
    repository: StationRepository,
    onDismiss: () -> Unit,
    onResult: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var name by remember(station.id) { mutableStateOf(station.name) }
    var url by remember(station.id) { mutableStateOf(station.streamUrl) }
    var artwork by remember(station.id) { mutableStateOf(station.imageUrl) }
    var pickedImage by remember(station.id) { mutableStateOf<Uri?>(null) }

    // The photo picker needs no storage permission at all, on any supported version.
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) pickedImage = uri }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit station") },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StationArtwork(
                        name = name,
                        imageUrl = pickedImage?.toString() ?: artwork,
                        size = 56.dp,
                    )
                    Spacer(Modifier.width(16.dp))
                    Column {
                        OutlinedButton(
                            onClick = {
                                picker.launch(
                                    PickVisualMediaRequest(
                                        ActivityResultContracts.PickVisualMedia.ImageOnly
                                    )
                                )
                            }
                        ) {
                            Text(if (artwork == null && pickedImage == null) "Add image" else "Change image")
                        }
                        if (artwork != null || pickedImage != null) {
                            TextButton(
                                onClick = {
                                    pickedImage = null
                                    artwork = null
                                }
                            ) {
                                Text("Use initials instead")
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Stream URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Changing the URL takes effect the next time this station starts.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && url.isNotBlank(),
                onClick = {
                    scope.launch {
                        // Copy the picked image into our own storage: the picker's permission dies
                        // with the process, so the URI itself is not something to persist.
                        val savedArtwork = pickedImage
                            ?.let { repository.saveArtwork(context, station, it) }
                            ?: artwork

                        repository.update(
                            station.copy(
                                name = name,
                                streamUrl = url,
                                imageUrl = savedArtwork,
                            )
                        )
                        onResult("Saved ${name.trim()}.")
                        onDismiss()
                    }
                },
            ) { Text("Save changes") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Asks before removing, because there is no undo and the collection is hand-built. */
@Composable
fun RemoveStationDialog(
    station: Station,
    repository: StationRepository,
    onDismiss: () -> Unit,
    onResult: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remove this station?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("— ${station.name}", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = station.streamUrl,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    scope.launch {
                        repository.delete(station)
                        onResult("Removed ${station.name}.")
                        onDismiss()
                    }
                }
            ) { Text("Remove") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
