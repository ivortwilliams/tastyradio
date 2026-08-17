package com.tastyradio.playback

import android.content.Intent
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.tastyradio.TastyRadioApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Keeps the soundscape alive when the app isn't on screen, and owns the media notification.
 *
 * The [Mixer] itself belongs to the Application, not to this service — the UI and the service talk
 * to the same instance, and the mix shouldn't evaporate because the service was recreated. What the
 * service provides is the foreground-service promotion and the single MediaSession.
 */
class SoundscapeService : MediaSessionService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val mixer = (application as TastyRadioApp).mixer
        session = MediaSession.Builder(this, SoundscapePlayer(mixer, scope)).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** Swiping the app away with a silent mix should not leave a service lying around. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if ((application as TastyRadioApp).mixer.channels.value.isEmpty()) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        session?.let { session ->
            session.player.release()
            session.release()
        }
        session = null
        scope.cancel()
        super.onDestroy()
    }
}
