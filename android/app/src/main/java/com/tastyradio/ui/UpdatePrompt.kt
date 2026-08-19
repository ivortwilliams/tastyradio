package com.tastyradio.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tastyradio.update.Updater

/**
 * The whole update experience, for someone who has never sideloaded anything.
 *
 * It appears when there's something to say and stays out of the way otherwise: no dialog while
 * checking, none when the app is already current. The one piece of Android arcana it can't hide —
 * that a phone won't let an app install an app until you say so — is put in plain words, with the
 * button that goes straight to the right settings screen.
 */
@Composable
fun UpdatePrompt(updater: Updater) {
    val state by updater.state.collectAsStateWithLifecycle()

    when (val current = state) {
        is Updater.State.Available -> AlertDialog(
            onDismissRequest = { updater.dismiss() },
            title = { Text("Tasty Radio ${current.release.versionName}") },
            text = {
                Column {
                    Text(
                        text = current.release.notes.ifBlank { "A new version is ready." },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer8()
                    Text(
                        text = "Your stations, mixes and recordings all stay where they are.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        // Asking for the permission first would be a dialog about a dialog. Only
                        // the phones that actually need it ever see this branch.
                        if (updater.canInstall()) {
                            updater.download(current.release)
                        } else {
                            updater.requestInstallPermission()
                        }
                    },
                ) { Text(if (updater.canInstall()) "Update" else "Allow, then update") }
            },
            dismissButton = { TextButton(onClick = { updater.dismiss() }) { Text("Not now") } },
        )

        is Updater.State.Downloading -> AlertDialog(
            onDismissRequest = { },
            title = { Text("Downloading ${current.release.versionName}") },
            text = {
                Column {
                    Text(
                        text = "${current.percent}%",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer8()
                    LinearProgressIndicator(
                        progress = { current.percent / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer8()
                    Text(
                        text = "Then Android will ask you to confirm the install.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {},
        )

        is Updater.State.Failed -> if (current.loud) AlertDialog(
            onDismissRequest = { updater.dismiss() },
            title = { Text("Update failed") },
            text = { Text(current.reason) },
            confirmButton = {
                TextButton(onClick = { updater.check(force = true) }) { Text("Try again") }
            },
            dismissButton = { TextButton(onClick = { updater.dismiss() }) { Text("Close") } },
        ) else Unit

        // Checking, up to date, handed to the installer, or nothing doing — all silent.
        else -> Unit
    }
}

@Composable
private fun Spacer8() = Spacer(Modifier.height(8.dp))
