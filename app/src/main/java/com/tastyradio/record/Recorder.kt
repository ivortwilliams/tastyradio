package com.tastyradio.record

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.projection.MediaProjection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Process
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Records what the app is playing — the whole mix, exactly as heard — into a shareable `.m4a`.
 *
 * The system has already mixed our channels and applied their faders, so instead of writing an
 * audio mixer we ask Android for our own app's playback via `AudioPlaybackCapture` and encode that.
 * That's the entire reason recording is a few hundred lines and not a DSP project.
 *
 * Two consequences of the approach, both unavoidable and both visible to the user:
 * - `MediaProjection` requires a system consent dialog, so recording starts with one.
 * - It arrives through `AudioRecord`, so `RECORD_AUDIO` is required even though the microphone is
 *   never opened. The UI says so when it asks.
 */
class Recorder(private val context: Context) {

    sealed interface State {
        data object Idle : State
        data class Recording(val startedAtMs: Long, val fileName: String) : State
        data class Saved(val uri: Uri, val fileName: String, val durationMs: Long) : State
        data class Failed(val reason: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile private var stopRequested = false
    private var thread: Thread? = null

    val isRecording: Boolean get() = _state.value is State.Recording

    /**
     * @param projection a live projection obtained after the consent dialog
     * @param title what the mix is, used for the filename — station names, because
     *              `recording_003.m4a` tells you nothing about which happy accident you caught
     */
    @SuppressLint("MissingPermission") // callers check RECORD_AUDIO first; see RecorderService
    fun start(projection: MediaProjection, title: String) {
        if (isRecording) return
        stopRequested = false

        val stamp = SimpleDateFormat("yyyy-MM-dd HHmm", Locale.getDefault()).format(Date())
        val fileName = sanitise("$stamp — $title") + ".m4a"
        val startedAt = System.currentTimeMillis()
        _state.value = State.Recording(startedAt, fileName)

        thread = Thread({ record(projection, fileName, startedAt) }, "TastyRadioRecorder").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    fun stop() {
        stopRequested = true
    }

    /** Dismiss a finished/failed result so the UI goes back to an idle record button. */
    fun acknowledge() {
        if (_state.value !is State.Recording) _state.value = State.Idle
    }

    private fun record(projection: MediaProjection, fileName: String, startedAt: Long) {
        var audioRecord: AudioRecord? = null
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var uri: Uri? = null
        var trackIndex = -1
        var muxing = false

        try {
            val config = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUid(Process.myUid()) // our own playback and nothing else
                .build()

            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()

            val minBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT,
            ).coerceAtLeast(BUFFER_BYTES)

            audioRecord = AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(minBuffer * 2)
                .setAudioPlaybackCaptureConfig(config)
                .build()

            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                val encoderFormat = MediaFormat.createAudioFormat(
                    MediaFormat.MIMETYPE_AUDIO_AAC,
                    SAMPLE_RATE,
                    CHANNELS,
                ).apply {
                    setInteger(
                        MediaFormat.KEY_AAC_PROFILE,
                        MediaCodecInfo.CodecProfileLevel.AACObjectLC,
                    )
                    setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, BUFFER_BYTES)
                }
                configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }

            uri = createMediaStoreEntry(fileName)
                ?: throw IllegalStateException("could not create the output file")

            val descriptor = context.contentResolver.openFileDescriptor(uri, "rw")
                ?: throw IllegalStateException("could not open the output file")
            muxer = descriptor.use {
                MediaMuxer(it.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            }

            audioRecord.startRecording()

            val pcm = ByteArray(BUFFER_BYTES)
            val bufferInfo = MediaCodec.BufferInfo()
            var totalBytes = 0L
            var sawEos = false

            while (!sawEos) {
                // ---- feed the encoder from the capture -------------------------------------
                val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                if (inputIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inputIndex)!!
                    inputBuffer.clear()
                    val read = if (stopRequested) 0 else audioRecord.read(pcm, 0, pcm.size)
                    if (read > 0) {
                        inputBuffer.put(pcm, 0, read)
                        codec.queueInputBuffer(
                            inputIndex,
                            0,
                            read,
                            presentationTimeUs(totalBytes),
                            0,
                        )
                        totalBytes += read
                    } else {
                        codec.queueInputBuffer(
                            inputIndex,
                            0,
                            0,
                            presentationTimeUs(totalBytes),
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                        )
                    }
                }

                // ---- drain the encoder into the file ---------------------------------------
                var outputIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                while (outputIndex >= 0) {
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        bufferInfo.size = 0 // codec config goes in the track format, not the stream
                    }
                    if (bufferInfo.size > 0 && muxing) {
                        val encoded: ByteBuffer = codec.getOutputBuffer(outputIndex)!!
                        encoded.position(bufferInfo.offset)
                        encoded.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(trackIndex, encoded, bufferInfo)
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        sawEos = true
                        break
                    }
                    outputIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                }
                if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED && !muxing) {
                    trackIndex = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxing = true
                }
            }

            val durationMs = System.currentTimeMillis() - startedAt
            finish(audioRecord, codec, muxer, muxing)
            audioRecord = null
            codec = null
            muxer = null
            projection.stop()

            publish(uri)
            _state.value = State.Saved(uri = uri, fileName = fileName, durationMs = durationMs)
        } catch (error: Throwable) {
            Log.e(TAG, "recording failed", error)
            runCatching { finish(audioRecord, codec, muxer, muxing) }
            runCatching { projection.stop() }
            // A half-written file is worse than none: drop it rather than leave a broken entry.
            uri?.let { runCatching { context.contentResolver.delete(it, null, null) } }
            _state.value = State.Failed(error.message ?: error.javaClass.simpleName)
        }
    }

    private fun finish(
        audioRecord: AudioRecord?,
        codec: MediaCodec?,
        muxer: MediaMuxer?,
        muxing: Boolean,
    ) {
        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        if (muxing) runCatching { muxer?.stop() }
        runCatching { muxer?.release() }
    }

    /** Timestamps derived from bytes written, which is the only clock the encoder trusts. */
    private fun presentationTimeUs(totalBytes: Long): Long =
        totalBytes * 1_000_000L / (SAMPLE_RATE.toLong() * CHANNELS * BYTES_PER_SAMPLE)

    /**
     * Written straight into the media collection so it's visible to every other app with no storage
     * permission at all — the point is sending it to a friend.
     */
    private fun createMediaStoreEntry(fileName: String): Uri? {
        val folder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Environment.DIRECTORY_RECORDINGS + "/Tasty Radio"
        } else {
            Environment.DIRECTORY_MUSIC + "/Tasty Radio"
        }
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
            put(MediaStore.Audio.Media.RELATIVE_PATH, folder)
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        return context.contentResolver.insert(
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            values,
        )
    }

    private fun publish(uri: Uri) {
        val values = ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }
        context.contentResolver.update(uri, values, null, null)
    }

    private fun sanitise(name: String): String =
        name.replace(Regex("""[\\/:*?"<>|]"""), "-").take(120).trim()

    private companion object {
        const val TAG = "Recorder"
        const val SAMPLE_RATE = 48_000
        const val CHANNELS = 2
        const val BYTES_PER_SAMPLE = 2
        const val BIT_RATE = 128_000
        const val BUFFER_BYTES = 8192
        const val TIMEOUT_US = 10_000L
    }
}
