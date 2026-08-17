package com.tastyradio.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.metadata.icy.IcyInfo
import com.tastyradio.data.Station
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The mixing desk. One [ExoPlayer] per active station, each with its own fader.
 *
 * This is the class the whole app exists for, and the one place where Tasty Radio stops resembling
 * every other radio app: a station is not *the* playback, it's a channel on a mix.
 *
 * Lives for the life of the process (owned by the Application), because both the UI and the
 * playback service talk to the same instance.
 *
 * ## Audio focus
 * There is exactly **one** focus owner: this class. Every player is built with
 * `handleAudioFocus = false`. If each player managed its own focus they would fight — every new
 * player requesting focus would duck or pause the others, and the app's own stations would fade
 * each other out. That bug is invisible with one station and obvious with three.
 */
@OptIn(UnstableApi::class)
class Mixer(private val context: Context) {

    enum class ChannelState { Connecting, Playing, Failed }

    /**
     * One channel of the mix. [fader] is the slider position, 0..1 — not an amplitude.
     *
     * [key] rather than the Room id, because a channel isn't always a saved station: auditioning a
     * search result plays it into the running mix *without* adding it to the collection, and every
     * unsaved station has `id = 0`. Keying on the id would make all auditioned stations collide
     * with each other.
     */
    data class Channel(
        val key: String,
        val station: Station,
        val fader: Float = DEFAULT_FADER,
        val muted: Boolean = false,
        val state: ChannelState = ChannelState.Connecting,
        /** ICY stream metadata — the current track, when the station bothers to send it. */
        val nowPlaying: String? = null,
        val error: String? = null,
    )

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val audioManager = context.getSystemService(AudioManager::class.java)

    /** One per connecting channel: a stream that never arrives has to become a visible failure. */
    private val watchdogs = mutableMapOf<String, Job>()

    /** Insertion-ordered so the mixer rows don't jump around as channels come and go. */
    private val players = LinkedHashMap<String, ExoPlayer>()

    private val _channels = MutableStateFlow<List<Channel>>(emptyList())
    val channels: StateFlow<List<Channel>> = _channels.asStateFlow()

    /** Set while another app holds transient focus and asked us to duck rather than stop. */
    private var ducking = false

    /**
     * Applied to players as they're built. Changing it mid-mix doesn't disturb what's already
     * playing — a new buffer size takes effect the next time a station starts.
     */
    @Volatile var largeBuffer: Boolean = true


    private var focusRequest: AudioFocusRequest? = null
    private var noisyReceiverRegistered = false

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            // Live radio can't be resumed from where it left off, so a real loss means stop.
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> stopAll()

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                ducking = true
                applyVolumes()
            }

            AudioManager.AUDIOFOCUS_GAIN -> {
                ducking = false
                applyVolumes()
            }
        }
    }

    /** Headphones pulled out stops the whole mix, from one receiver rather than one per player. */
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) stopAll()
        }
    }

    // ---------------------------------------------------------------- channel control

    /**
     * Adds a station to the mix. [fader] lets an audition start quietly under what's already
     * playing.
     *
     * @return false if the mix is already full, so the caller can say so.
     */
    fun play(station: Station, fader: Float = DEFAULT_FADER): Boolean {
        val key = station.channelKey()
        if (players.containsKey(key)) return true
        if (players.size >= MAX_CHANNELS) return false

        if (players.isEmpty()) {
            requestFocus()
            registerNoisyReceiver()
            startPlaybackService()
        }

        val player = buildPlayer()
        players[key] = player
        _channels.update { it + Channel(key = key, station = station, fader = fader) }

        player.addListener(ChannelListener(key))
        player.setMediaItem(MediaItem.fromUri(station.streamUrl))
        player.prepare()
        player.playWhenReady = true
        applyVolumes()
        armWatchdog(key)
        return true
    }

    fun stop(key: String) {
        watchdogs.remove(key)?.cancel()
        players.remove(key)?.release()
        _channels.update { list -> list.filterNot { it.key == key } }
        if (players.isEmpty()) releaseFocusAndReceiver()
    }

    fun stopAll() {
        watchdogs.values.forEach { it.cancel() }
        watchdogs.clear()
        players.values.forEach { it.release() }
        players.clear()
        _channels.value = emptyList()
        releaseFocusAndReceiver()
    }

    fun toggle(station: Station): Boolean {
        val key = station.channelKey()
        return if (players.containsKey(key)) {
            stop(key)
            true
        } else {
            play(station)
        }
    }

    fun isLive(station: Station) = players.containsKey(station.channelKey())

    /** Retry a channel that failed — dead stations and flaky ones look identical at first. */
    fun retry(key: String) {
        val channel = _channels.value.firstOrNull { it.key == key } ?: return
        val player = players[key] ?: return
        updateChannel(key) { it.copy(state = ChannelState.Connecting, error = null) }
        player.setMediaItem(MediaItem.fromUri(channel.station.streamUrl))
        player.prepare()
        player.playWhenReady = true
        armWatchdog(key)
    }

    /**
     * A stream that connects but never delivers audio used to sit on `Connecting…` forever, because
     * ExoPlayer keeps retrying a load that isn't erroring — it's just silent. After
     * [STALL_TIMEOUT_MS] the channel becomes an honest failure with a retry button instead.
     */
    private fun armWatchdog(key: String) {
        watchdogs.remove(key)?.cancel()
        watchdogs[key] = scope.launch {
            delay(STALL_TIMEOUT_MS)
            val channel = _channels.value.firstOrNull { it.key == key } ?: return@launch
            if (channel.state == ChannelState.Connecting) {
                players[key]?.stop()
                updateChannel(key) {
                    it.copy(state = ChannelState.Failed, error = "no audio after 20s")
                }
            }
        }
    }

    // ---------------------------------------------------------------- faders

    fun setFader(key: String, fader: Float) {
        updateChannel(key) { it.copy(fader = fader.coerceIn(0f, 1f)) }
        applyVolumes()
    }

    fun setMuted(key: String, muted: Boolean) {
        updateChannel(key) { it.copy(muted = muted) }
        applyVolumes()
    }


    private fun applyVolumes() {
        val duck = if (ducking) DUCK_FACTOR else 1f
        for (channel in _channels.value) {
            val player = players[channel.key] ?: continue
            player.volume = if (channel.muted) 0f else amplitudeFor(channel.fader) * duck
        }
    }

    // ---------------------------------------------------------------- plumbing

    private fun buildPlayer(): ExoPlayer {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(USER_AGENT)
            // Radio URLs redirect constantly, including http -> https, which is cross-protocol.
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(15_000)

        val attributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        val mediaSourceFactory = DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http))
            // Give up after a few attempts so a dead host surfaces as an error rather than an
            // indefinite silent retry loop.
            .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(3))

        return ExoPlayer.Builder(context)
            .setLoadControl(buildLoadControl())
            .setMediaSourceFactory(mediaSourceFactory)
            // false: this Mixer is the single audio-focus owner. See the class comment.
            .setAudioAttributes(attributes, false)
            // Likewise — one central becoming-noisy receiver, not one per player.
            .setHandleAudioBecomingNoisy(false)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
    }

    /**
     * A deliberately deep buffer. Radio streams on mobile data stutter, and with several playing at
     * once a stall is far more noticeable — one channel dropping out breaks the whole mix. The cost
     * is that playback takes longer to start, which is the right trade for a soundscape you leave
     * running. Roughly a minute of audio when large, ten seconds when not.
     */
    private fun buildLoadControl(): LoadControl {
        val maxMs = if (largeBuffer) 60_000 else 15_000
        val startMs = if (largeBuffer) 5_000 else 1_500
        return DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ maxMs,
                /* maxBufferMs = */ maxMs,
                /* bufferForPlaybackMs = */ startMs,
                /* bufferForPlaybackAfterRebufferMs = */ startMs * 2,
            )
            .setBackBuffer(0, false)
            .build()
    }

    private inner class ChannelListener(private val key: String) : Player.Listener {

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                watchdogs.remove(key)?.cancel()
                updateChannel(key) { it.copy(state = ChannelState.Playing, error = null) }
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_BUFFERING) {
                updateChannel(key) { it.copy(state = ChannelState.Connecting) }
            }
        }

        /**
         * One dead station must not take the mix down with it — losing a channel of three is
         * survivable, so the failure stays local to its own row.
         */
        override fun onPlayerError(error: PlaybackException) {
            updateChannel(key) {
                it.copy(state = ChannelState.Failed, error = error.errorCodeName)
            }
        }

        override fun onMetadata(metadata: Metadata) {
            for (i in 0 until metadata.length()) {
                val entry = metadata.get(i)
                if (entry is IcyInfo) {
                    val title = entry.title?.trim()?.ifEmpty { null }
                    updateChannel(key) { it.copy(nowPlaying = title) }
                }
            }
        }
    }

    private fun updateChannel(key: String, transform: (Channel) -> Channel) {
        _channels.update { list ->
            list.map { if (it.key == key) transform(it) else it }
        }
    }

    private fun requestFocus() {
        val manager = audioManager ?: return
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setOnAudioFocusChangeListener(focusListener, main)
            .setWillPauseWhenDucked(false)
            .build()
        focusRequest = request
        manager.requestAudioFocus(request)
    }

    private fun releaseFocusAndReceiver() {
        ducking = false
        focusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
        focusRequest = null
        if (noisyReceiverRegistered) {
            runCatching { context.unregisterReceiver(noisyReceiver) }
            noisyReceiverRegistered = false
        }
    }

    private fun registerNoisyReceiver() {
        if (noisyReceiverRegistered) return
        context.registerReceiver(noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        noisyReceiverRegistered = true
    }

    /**
     * Playback has to be a foreground service or it gets killed the moment the app is backgrounded.
     * Media3 promotes the service and posts the media notification once the session's player
     * reports playing; this just makes sure the service is alive to be promoted.
     */
    private fun startPlaybackService() {
        runCatching {
            context.startService(Intent(context, SoundscapeService::class.java))
        }
    }

    companion object {
        /**
         * Not a hard limit — past four the returns drop and the costs don't: four streams is
         * ~64 KB/s of sustained mobile data, four decoders, four sockets.
         */
        const val MAX_CHANNELS = 4

        const val DEFAULT_FADER = 0.75f

        private const val DUCK_FACTOR = 0.2f

        /** How long a channel may sit connecting before it's called a failure. */
        private const val STALL_TIMEOUT_MS = 20_000L

        /** radio-browser.info asks for a descriptive User-Agent, and so do some stream hosts. */
        private const val USER_AGENT = "TastyRadio/0.1 (Android; hobby project)"

        /**
         * [ExoPlayer.volume] is linear amplitude, and a fader mapped straight onto it feels broken —
         * everything useful bunches into the top of the travel. Cubing approximates a fader taper:
         * half way up lands around −18 dB, which is where half way up should be.
         */
        fun amplitudeFor(fader: Float): Float {
            val f = fader.coerceIn(0f, 1f)
            return f * f * f
        }

        /** A quiet default, so an audition slips under the running mix instead of over it. */
        const val AUDITION_FADER = 0.4f
    }
}

/**
 * Identifies a channel. Saved stations key on their row id; a station that isn't in the collection
 * — a search result being auditioned — has `id = 0`, so it keys on its stream URL instead.
 */
internal fun Station.channelKey(): String = if (id != 0L) "id:$id" else "url:$streamUrl"
