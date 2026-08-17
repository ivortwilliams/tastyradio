package com.tastyradio.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A three-band tone control, applied inside the player's own audio pipeline.
 *
 * ## Why not `android.media.audiofx.Equalizer`
 * The obvious route is the platform equaliser attached to the player's audio session. It was tried
 * and rejected for two reasons:
 *
 * 1. **It's vendor-supplied.** Band counts and centre frequencies vary by device, and on some builds
 *    it silently does nothing — a tone control that works on one phone and not another is worse than
 *    none.
 * 2. **It sits outside our pipeline.** Recording captures this app's playback, and the whole promise
 *    of the recording feature is that the file is what you heard. Filtering here, before the audio
 *    ever reaches the sink, means the EQ is *provably* in the recording rather than probably.
 *
 * So: three biquads per channel, standard RBJ cookbook shapes — a low shelf, a peaking mid, and a
 * high shelf. About a dozen multiply-adds per sample, which is nothing next to decoding the stream
 * it's filtering.
 *
 * Gains are set from the UI thread and read on the audio thread, hence [@Volatile] and the
 * recompute-on-dirty flag rather than locking in the render loop.
 */
@OptIn(UnstableApi::class)
class ThreeBandEqualiser : BaseAudioProcessor() {

    /** Each band runs −[MAX_GAIN_DB]…+[MAX_GAIN_DB], where 0 is flat. */
    @Volatile private var lowDb: Float = 0f
    @Volatile private var midDb: Float = 0f
    @Volatile private var highDb: Float = 0f
    @Volatile private var dirty: Boolean = true

    private var channelCount = 0
    private var sampleRate = 0

    /** One filter per band per channel: stereo state can't be shared. */
    private var low: Array<Biquad> = emptyArray()
    private var mid: Array<Biquad> = emptyArray()
    private var high: Array<Biquad> = emptyArray()

    fun setGains(lowDb: Float, midDb: Float, highDb: Float) {
        this.lowDb = lowDb.coerceIn(-MAX_GAIN_DB, MAX_GAIN_DB)
        this.midDb = midDb.coerceIn(-MAX_GAIN_DB, MAX_GAIN_DB)
        this.highDb = highDb.coerceIn(-MAX_GAIN_DB, MAX_GAIN_DB)
        dirty = true
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        // 16-bit PCM only. Anything else and we bow out rather than corrupt the stream.
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        channelCount = inputAudioFormat.channelCount
        sampleRate = inputAudioFormat.sampleRate
        low = Array(channelCount) { Biquad() }
        mid = Array(channelCount) { Biquad() }
        high = Array(channelCount) { Biquad() }
        dirty = true
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (dirty) {
            recompute()
            dirty = false
        }

        val size = inputBuffer.remaining()
        val output = replaceOutputBuffer(size).order(ByteOrder.nativeOrder())
        val input = inputBuffer.asShortBuffer()

        var channel = 0
        while (input.hasRemaining()) {
            var sample = input.get().toFloat()
            sample = low[channel].process(sample)
            sample = mid[channel].process(sample)
            sample = high[channel].process(sample)
            // Boosting can push past full scale; clamp rather than let it wrap into noise.
            output.putShort(sample.coerceIn(-32768f, 32767f).toInt().toShort())
            channel = (channel + 1) % channelCount
        }

        inputBuffer.position(inputBuffer.limit())
        output.flip()
    }

    override fun onFlush() {
        low.forEach { it.reset() }
        mid.forEach { it.reset() }
        high.forEach { it.reset() }
    }

    override fun onReset() {
        low = emptyArray()
        mid = emptyArray()
        high = emptyArray()
    }

    private fun recompute() {
        if (sampleRate == 0) return
        val lowCoefficients = lowShelf(LOW_HZ, lowDb, sampleRate)
        val midCoefficients = peaking(MID_HZ, MID_Q, midDb, sampleRate)
        val highCoefficients = highShelf(HIGH_HZ, highDb, sampleRate)
        for (index in low.indices) {
            low[index].setCoefficients(lowCoefficients)
            mid[index].setCoefficients(midCoefficients)
            high[index].setCoefficients(highCoefficients)
        }
    }

    /** Direct-form I biquad. Coefficients arrive already normalised by a0. */
    private class Biquad {
        private var b0 = 1.0f
        private var b1 = 0f
        private var b2 = 0f
        private var a1 = 0f
        private var a2 = 0f
        private var x1 = 0f
        private var x2 = 0f
        private var y1 = 0f
        private var y2 = 0f

        fun setCoefficients(c: FloatArray) {
            b0 = c[0]; b1 = c[1]; b2 = c[2]; a1 = c[3]; a2 = c[4]
        }

        fun reset() {
            x1 = 0f; x2 = 0f; y1 = 0f; y2 = 0f
        }

        fun process(x: Float): Float {
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x
            y2 = y1; y1 = y
            return y
        }
    }

    private companion object {
        const val MAX_GAIN_DB = 12f

        /** Bass, presence, air — chosen so the mid lands on voice and the shelves stay out of it. */
        const val LOW_HZ = 250f
        const val MID_HZ = 1_500f
        const val MID_Q = 0.9f
        const val HIGH_HZ = 4_000f

        // Robert Bristow-Johnson's audio EQ cookbook, normalised by a0.
        fun lowShelf(frequency: Float, gainDb: Float, sampleRate: Int): FloatArray {
            val a = 10.0.pow(gainDb / 40.0)
            val w0 = 2.0 * Math.PI * frequency / sampleRate
            val cosW0 = cos(w0)
            val alpha = sin(w0) / 2.0 * sqrt(2.0)
            val twoSqrtAAlpha = 2.0 * sqrt(a) * alpha

            val b0 = a * ((a + 1) - (a - 1) * cosW0 + twoSqrtAAlpha)
            val b1 = 2 * a * ((a - 1) - (a + 1) * cosW0)
            val b2 = a * ((a + 1) - (a - 1) * cosW0 - twoSqrtAAlpha)
            val a0 = (a + 1) + (a - 1) * cosW0 + twoSqrtAAlpha
            val a1 = -2 * ((a - 1) + (a + 1) * cosW0)
            val a2 = (a + 1) + (a - 1) * cosW0 - twoSqrtAAlpha
            return normalise(b0, b1, b2, a0, a1, a2)
        }

        fun highShelf(frequency: Float, gainDb: Float, sampleRate: Int): FloatArray {
            val a = 10.0.pow(gainDb / 40.0)
            val w0 = 2.0 * Math.PI * frequency / sampleRate
            val cosW0 = cos(w0)
            val alpha = sin(w0) / 2.0 * sqrt(2.0)
            val twoSqrtAAlpha = 2.0 * sqrt(a) * alpha

            val b0 = a * ((a + 1) + (a - 1) * cosW0 + twoSqrtAAlpha)
            val b1 = -2 * a * ((a - 1) + (a + 1) * cosW0)
            val b2 = a * ((a + 1) + (a - 1) * cosW0 - twoSqrtAAlpha)
            val a0 = (a + 1) - (a - 1) * cosW0 + twoSqrtAAlpha
            val a1 = 2 * ((a - 1) - (a + 1) * cosW0)
            val a2 = (a + 1) - (a - 1) * cosW0 - twoSqrtAAlpha
            return normalise(b0, b1, b2, a0, a1, a2)
        }

        fun peaking(frequency: Float, q: Float, gainDb: Float, sampleRate: Int): FloatArray {
            val a = 10.0.pow(gainDb / 40.0)
            val w0 = 2.0 * Math.PI * frequency / sampleRate
            val cosW0 = cos(w0)
            val alpha = sin(w0) / (2.0 * q)

            val b0 = 1 + alpha * a
            val b1 = -2 * cosW0
            val b2 = 1 - alpha * a
            val a0 = 1 + alpha / a
            val a1 = -2 * cosW0
            val a2 = 1 - alpha / a
            return normalise(b0, b1, b2, a0, a1, a2)
        }

        fun normalise(
            b0: Double,
            b1: Double,
            b2: Double,
            a0: Double,
            a1: Double,
            a2: Double,
        ): FloatArray = floatArrayOf(
            (b0 / a0).toFloat(),
            (b1 / a0).toFloat(),
            (b2 / a0).toFloat(),
            (a1 / a0).toFloat(),
            (a2 / a0).toFloat(),
        )
    }
}
