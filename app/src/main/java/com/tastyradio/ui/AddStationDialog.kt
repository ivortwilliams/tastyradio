package com.tastyradio.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.tastyradio.data.PlaylistParser
import com.tastyradio.data.StationRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Two ways in, both of which the owner actually needs:
 *
 * - paste a stream URL, for stations that aren't in any directory (*Tasty Radio* itself, notably);
 * - import an M3U or PLS file, which is how the existing collection arrives — Transistor's own
 *   *Export M3U* writes exactly this.
 *
 * radio-browser.info search is a later phase; import is what gets a real list in today.
 */
@Composable
fun AddStationDialog(
    repository: StationRepository,
    onDismiss: () -> Unit,
    onResult: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var pickedFile by remember { mutableStateOf<Uri?>(null) }

    val picker = rememberLauncherForActivityResult(
        // Radio playlists get served as text/plain, audio/x-mpegurl, audio/x-scpls and octet-stream
        // depending on who wrote them, so don't filter narrowly.
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri -> pickedFile = uri },
    )

    LaunchedEffect(pickedFile) {
        val uri = pickedFile ?: return@LaunchedEffect
        val added = withContext(Dispatchers.IO) {
            val text = runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()
            if (text == null) null else repository.import(PlaylistParser.parse(text))
        }
        onResult(
            when {
                added == null -> "Couldn't read that file."
                added == 0 -> "Nothing new in that playlist."
                else -> "Imported $added station${if (added == 1) "" else "s"}."
            }
        )
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add new station") },
        text = {
            Column {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Stream URL") },
                    placeholder = { Text("http://…") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "Or bring a whole collection across:",
                    style = MaterialTheme.typography.labelMedium,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { picker.launch(arrayOf("*/*")) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Import M3U / PLS file")
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = url.isNotBlank(),
                onClick = {
                    scope.launch {
                        val station = repository.add(name = name, streamUrl = url)
                        onResult(
                            if (station == null) {
                                "Already in your collection."
                            } else {
                                "Added ${station.name}."
                            }
                        )
                        onDismiss()
                    }
                },
            ) { Text("Add") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
