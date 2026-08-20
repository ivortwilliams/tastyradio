package com.tastyradio.ui

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.tastyradio.TastyRadioApp
import com.tastyradio.playback.SoundscapeService
import com.tastyradio.share.MixLink
import com.tastyradio.ui.theme.TastyRadioTheme

class MainActivity : ComponentActivity() {

    /**
     * A mix somebody sent, waiting to be offered.
     *
     * Held here rather than acted on immediately: the intent arrives before there is any UI, and
     * loading someone else's soundscape over what you are listening to is a question, not something
     * to do quietly on their behalf.
     */
    private val sharedMix = mutableStateOf<MixLink.Shared?>(null)

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* either way, play on */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Without this the media notification is silently dropped on API 33+, which makes the
        // foreground service look broken.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        sharedMix.value = MixLink.from(intent)

        val app = application as TastyRadioApp
        setContent {
            TastyRadioTheme {
                RootScreen(
                    repository = app.repository,
                    mixRepository = app.mixRepository,
                    mixer = app.mixer,
                    recorder = app.recorder,
                    search = app.search,
                    settings = app.settings,
                    updater = app.updater,
                    sharedMix = sharedMix.value,
                    onSharedMixHandled = { sharedMix.value = null },
                )
            }
        }
    }

    /**
     * A link tapped while the app was already running. `singleTask` in the manifest is what routes
     * it here instead of starting a second copy of the app on top of a playing mix.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        MixLink.from(intent)?.let { sharedMix.value = it }
    }

    /**
     * Connect a controller to the playback session.
     *
     * The UI doesn't drive playback through this — it talks to the [com.tastyradio.playback.Mixer]
     * directly. The connection exists because binding a controller is what makes Media3 wire up the
     * media notification and promote the service to the foreground. Merely starting the service
     * isn't enough: verified on API 36, where nothing was posted until a controller connected.
     */
    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, SoundscapeService::class.java))
        controller = MediaController.Builder(this, token).buildAsync()
    }

    override fun onStop() {
        controller?.let(MediaController::releaseFuture)
        controller = null
        super.onStop()
    }

    private var controller: ListenableFuture<MediaController>? = null
}
