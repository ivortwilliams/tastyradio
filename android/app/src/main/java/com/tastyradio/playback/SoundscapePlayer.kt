package com.tastyradio.playback

import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Presents the whole mix to Android as a single logical player.
 *
 * The media notification, lockscreen, headset buttons and Bluetooth controls all assume one thing
 * is playing. They shouldn't have to choose a station: pressing pause on a headset means "stop the
 * soundscape". So there is one [androidx.media3.session.MediaSession], attached to this, and the
 * several real [androidx.media3.exoplayer.ExoPlayer]s live behind it inside the [Mixer].
 *
 * `SimpleBasePlayer` exists for exactly this — a `Player` whose state we compute rather than own.
 */
@OptIn(UnstableApi::class)
class SoundscapePlayer(
    private val mixer: Mixer,
    scope: CoroutineScope,
) : SimpleBasePlayer(Looper.getMainLooper()) {

    init {
        // Any change to the mix is a change to this player's state.
        scope.launch {
            mixer.channels.collectLatest { invalidateState() }
        }
    }

    override fun getState(): State {
        val channels = mixer.channels.value
        val builder = State.Builder().setAvailableCommands(COMMANDS)

        if (channels.isEmpty()) {
            return builder
                .setPlaybackState(Player.STATE_IDLE)
                .setPlayWhenReady(false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
                .build()
        }

        val anyPlaying = channels.any { it.state == Mixer.ChannelState.Playing }
        return builder
            .setPlaylist(listOf(mediaItemData(channels)))
            .setCurrentMediaItemIndex(0)
            .setPlaybackState(if (anyPlaying) Player.STATE_READY else Player.STATE_BUFFERING)
            .setPlayWhenReady(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setContentPositionMs(0)
            .build()
    }

    private fun mediaItemData(channels: List<Mixer.Channel>): MediaItemData {
        val title = if (channels.size == 1) {
            channels.single().station.name
        } else {
            "${channels.size} stations"
        }
        // Prefer a real track title from any channel that supplies one; otherwise name the mix.
        val subtitle = channels.firstNotNullOfOrNull { it.nowPlaying }
            ?: channels.joinToString(" + ") { it.station.name }

        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(subtitle)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .build()

        return MediaItemData.Builder(MEDIA_ID)
            .setMediaItem(MediaItem.Builder().setMediaId(MEDIA_ID).setMediaMetadata(metadata).build())
            .setMediaMetadata(metadata)
            .setIsSeekable(false)
            .setIsDynamic(true)
            .setDurationUs(C.TIME_UNSET)
            .build()
    }

    override fun handlePrepare(): ListenableFuture<*> = Futures.immediateVoidFuture()

    /**
     * Stop, not pause — these are live streams, there is nothing to resume into. Asking to play
     * with an empty mix does nothing on purpose: which station would it pick?
     */
    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (!playWhenReady) mixer.stopAll()
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        mixer.stopAll()
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        mixer.stopAll()
        return Futures.immediateVoidFuture()
    }

    private companion object {
        const val MEDIA_ID = "soundscape"

        val COMMANDS: Player.Commands = Player.Commands.Builder()
            .addAll(
                Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_STOP,
                Player.COMMAND_PREPARE,
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_METADATA,
                Player.COMMAND_GET_TIMELINE,
                Player.COMMAND_RELEASE,
            )
            .build()
    }
}
