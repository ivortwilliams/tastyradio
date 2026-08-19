package com.tastyradio.playback

import android.content.Intent
import android.os.Bundle
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
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

    private val mixer get() = (application as TastyRadioApp).mixer

    override fun onCreate() {
        super.onCreate()

        /**
         * A real stop button. Media3's default notification renders `COMMAND_PLAY_PAUSE` as a pause
         * icon, which is the wrong promise for live radio — there is nothing to resume into. This
         * puts an explicit stop next to it.
         */
        val stopButton = CommandButton.Builder(CommandButton.ICON_STOP)
            .setDisplayName("Stop")
            .setSessionCommand(SessionCommand(ACTION_STOP_ALL, Bundle.EMPTY))
            .build()

        session = MediaSession.Builder(this, SoundscapePlayer(mixer, scope))
            .setMediaButtonPreferences(ImmutableList.of(stopButton))
            .setCallback(SoundscapeCallback())
            .build()
    }

    private inner class SoundscapeCallback : MediaSession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult =
            MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(
                    MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                        .add(SessionCommand(ACTION_STOP_ALL, Bundle.EMPTY))
                        .build()
                )
                .build()

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == ACTION_STOP_ALL) {
                mixer.stopAll()
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** Swiping the app away with a silent mix should not leave a service lying around. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (mixer.channels.value.isEmpty()) {
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

    private companion object {
        const val ACTION_STOP_ALL = "com.tastyradio.STOP_ALL"
    }
}
