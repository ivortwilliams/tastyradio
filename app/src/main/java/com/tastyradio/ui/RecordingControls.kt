package com.tastyradio.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.tastyradio.record.Recorder
import com.tastyradio.record.RecorderService

/**
 * The consent dance that recording requires, kept in one place so the mixer bar can just call
 * `start()` and `stop()`.
 *
 * Two prompts, both unavoidable and both explained to the user rather than sprung on them:
 * `RECORD_AUDIO` (because playback capture is delivered through `AudioRecord`, not because we want
 * the microphone) and the system's own screen-capture consent dialog for `MediaProjection`.
 */
class RecordingLauncher(
    private val context: Context,
    private val requestPermission: () -> Unit,
    private val requestProjection: (Intent) -> Unit,
) {
    fun start() {
        if (!hasAudioPermission(context)) {
            requestPermission()
            return
        }
        val manager = context.getSystemService(MediaProjectionManager::class.java)
        requestProjection(manager.createScreenCaptureIntent())
    }

    fun stop() = RecorderService.stop(context)
}

fun hasAudioPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED

/**
 * Wires the permission and consent launchers into a [RecordingLauncher]. [title] names the mix, and
 * becomes part of the filename.
 */
@Composable
fun rememberRecordingLauncher(
    title: () -> String,
    onMessage: (String) -> Unit,
): RecordingLauncher {
    val context = LocalContext.current
    var awaitingPermission by remember { mutableStateOf(false) }

    val projectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode != android.app.Activity.RESULT_OK || data == null) {
            onMessage("Recording needs that permission — nothing was captured.")
        } else {
            RecorderService.start(context, result.resultCode, data, title())
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        awaitingPermission = false
        if (granted) {
            val manager = context.getSystemService(MediaProjectionManager::class.java)
            projectionLauncher.launch(manager.createScreenCaptureIntent())
        } else {
            onMessage(
                "Recording needs the audio permission. It captures this app's own playback, " +
                    "never the microphone."
            )
        }
    }

    return remember(context) {
        RecordingLauncher(
            context = context,
            requestPermission = {
                awaitingPermission = true
                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            },
            requestProjection = { intent -> projectionLauncher.launch(intent) },
        )
    }
}

/** The moment a recording stops is the moment you want to send it, so offer that immediately. */
fun shareRecording(context: Context, saved: Recorder.State.Saved) {
    val share = Intent(Intent.ACTION_SEND).apply {
        type = "audio/mp4"
        putExtra(Intent.EXTRA_STREAM, saved.uri)
        putExtra(Intent.EXTRA_SUBJECT, saved.fileName)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(share, "Share ${saved.fileName}"))
}
