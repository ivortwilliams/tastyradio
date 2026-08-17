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
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Per-channel tone and space, applied inside the player's own audio pipeline.
 *
 * Three things, in signal order:
 *
 * 1. **A three-band isolator.** Not a shelving EQ — shelves *tilt* the sound and even at −12 dB you
 *    can still plainly hear the band you were trying to remove. This splits the signal with
 *    Linkwitz-Riley crossovers and scales each band, so a band at minimum is multiplied by zero and
 *    is genuinely gone.
 * 2. **A delay.** Feedback echo, from a slapback to a long wash.
 * 3. **A reverb.** Schroeder/Freeverb topology — parallel comb filters into series allpasses —
 *    which is the cheap classic and sounds like a room rather than a spring.
 *
 * ## Why our own DSP rather than `android.media.audiofx`
 * The platform offers `Equalizer` and `PresetReverb`, and both were rejected for the same two
 * reasons: they're vendor-implemented, so they vary by device and on some builds silently do
 * nothing; and they sit outside our pipeline, so whether they reached the recording would be a
 * question rather than a fact. Everything here runs before the audio reaches the sink, which is
 * what puts it in the file.
 *
 * Values are set from the UI thread and read on the audio thread, hence `@Volatile` and a
 * recompute-on-dirty flag rather than locking in the render loop.
 */
@OptIn(UnstableApi::class)
class ChannelFilters : BaseAudioProcessor() {

    /** Band positions, −1 (killed) … 0 (unity) … +1 (boosted). */
    @Volatile private var lowPosition: Float = 0f
    @Volatile private var midPosition: Float = 0f
    @Volatile private var highPosition: Float = 0f

    /** 0…1 wet amount. */
    @Volatile private var reverbAmount: Float = 0f
    @Volatile private var delayAmount: Float = 0f
    @Volatile private var delayTimeMs: Float = DEFAULT_DELAY_MS

    @Volatile private var dirty: Boolean = true

    private var channelCount = 0
    private var sampleRate = 0

    // Linkwitz-Riley 4th order = two cascaded Butterworth sections at the same frequency.
    private var lowA: Array<Biquad> = emptyArray()
    private var lowB: Array<Biquad> = emptyArray()
    private var midHighpassA: Array<Biquad> = emptyArray()
    private var midHighpassB: Array<Biquad> = emptyArray()
    private var midLowpassA: Array<Biquad> = emptyArray()
    private var midLowpassB: Array<Biquad> = emptyArray()
    private var highA: Array<Biquad> = emptyArray()
    private var highB: Array<Biquad> = emptyArray()

    private var delays: Array<DelayLine> = emptyArray()
    private var reverbs: Array<Reverb> = emptyArray()

    @Volatile private var lowGain = 1f
    @Volatile private var midGain = 1f
    @Volatile private var highGain = 1f
    @Volatile private var delayActive = false
    @Volatile private var reverbActive = false

    fun setBands(low: Float, mid: Float, high: Float) {
        lowPosition = low.coerceIn(-1f, 1f)
        midPosition = mid.coerceIn(-1f, 1f)
        highPosition = high.coerceIn(-1f, 1f)
        dirty = true
    }

    fun setSpace(reverb: Float, delay: Float, delayMs: Float) {
        reverbAmount = reverb.coerceIn(0f, 1f)
        delayAmount = delay.coerceIn(0f, 1f)
        delayTimeMs = delayMs.coerceIn(MIN_DELAY_MS, MAX_DELAY_MS)
        dirty = true
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        channelCount = inputAudioFormat.channelCount
        sampleRate = inputAudioFormat.sampleRate

        fun bank() = Array(channelCount) { Biquad() }
        lowA = bank(); lowB = bank()
        midHighpassA = bank(); midHighpassB = bank()
        midLowpassA = bank(); midLowpassB = bank()
        highA = bank(); highB = bank()

        val maxDelaySamples = (MAX_DELAY_MS / 1000f * sampleRate).toInt() + 1
        delays = Array(channelCount) { DelayLine(maxDelaySamples) }
        // The stereo spread offset is what stops a two-channel reverb collapsing to the middle.
        reverbs = Array(channelCount) { channel ->
            Reverb(sampleRate, if (channel % 2 == 0) 0 else STEREO_SPREAD)
        }

        val nyquistLimit = sampleRate * 0.45f
        val lowSplit = CROSSOVER_LOW_HZ.coerceAtMost(nyquistLimit)
        val highSplit = CROSSOVER_HIGH_HZ.coerceAtMost(nyquistLimit)
        val lowPassLow = lowPass(lowSplit, Q_BUTTERWORTH, sampleRate)
        val highPassLow = highPass(lowSplit, Q_BUTTERWORTH, sampleRate)
        val lowPassHigh = lowPass(highSplit, Q_BUTTERWORTH, sampleRate)
        val highPassHigh = highPass(highSplit, Q_BUTTERWORTH, sampleRate)
        for (index in 0 until channelCount) {
            lowA[index].setCoefficients(lowPassLow)
            lowB[index].setCoefficients(lowPassLow)
            midHighpassA[index].setCoefficients(highPassLow)
            midHighpassB[index].setCoefficients(highPassLow)
            midLowpassA[index].setCoefficients(lowPassHigh)
            midLowpassB[index].setCoefficients(lowPassHigh)
            highA[index].setCoefficients(highPassHigh)
            highB[index].setCoefficients(highPassHigh)
        }

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

        val delaySamples = (delayTimeMs / 1000f * sampleRate).roundToInt().coerceAtLeast(1)
        val delayFeedback = DELAY_MIN_FEEDBACK + delayAmount * DELAY_FEEDBACK_RANGE
        val delayWet = delayAmount
        val reverbWet = reverbAmount

        var channel = 0
        while (input.hasRemaining()) {
            val x = input.get().toFloat()

            // Split, scale, recombine. A band scaled by zero contributes nothing at all.
            val lowBand = lowB[channel].process(lowA[channel].process(x))
            val midBand = midLowpassB[channel].process(
                midLowpassA[channel].process(
                    midHighpassB[channel].process(midHighpassA[channel].process(x))
                )
            )
            val highBand = highB[channel].process(highA[channel].process(x))

            var sample = lowBand * lowGain + midBand * midGain + highBand * highGain

            if (delayActive) {
                val echo = delays[channel].process(sample, delaySamples, delayFeedback)
                sample += echo * delayWet
            }
            if (reverbActive) {
                val tail = reverbs[channel].process(sample)
                sample = sample * (1f - reverbWet * DRY_DUCK) + tail * reverbWet
            }

            output.putShort(sample.coerceIn(-32768f, 32767f).toInt().toShort())
            channel = (channel + 1) % channelCount
        }

        inputBuffer.position(inputBuffer.limit())
        output.flip()
    }

    override fun onFlush() {
        listOf(lowA, lowB, midHighpassA, midHighpassB, midLowpassA, midLowpassB, highA, highB)
            .forEach { bank -> bank.forEach { it.reset() } }
        delays.forEach { it.reset() }
        reverbs.forEach { it.reset() }
    }

    override fun onReset() {
        lowA = emptyArray(); lowB = emptyArray()
        midHighpassA = emptyArray(); midHighpassB = emptyArray()
        midLowpassA = emptyArray(); midLowpassB = emptyArray()
        highA = emptyArray(); highB = emptyArray()
        delays = emptyArray(); reverbs = emptyArray()
    }

    private fun recompute() {
        if (sampleRate == 0) return

        lowGain = bandGain(lowPosition)
        midGain = bandGain(midPosition)
        highGain = bandGain(highPosition)

        val wasDelayActive = delayActive
        val wasReverbActive = reverbActive
        delayActive = delayAmount > 0.005f
        reverbActive = reverbAmount > 0.005f

        // Clear the tails when an effect is switched off, so turning it back on doesn't replay
        // whatever was left hanging in the buffer.
        if (wasDelayActive && !delayActive) delays.forEach { it.reset() }
        if (wasReverbActive && !reverbActive) reverbs.forEach { it.reset() }

        // Bigger amount, bigger room: one control that behaves like turning up a space.
        val roomSize = ROOM_MIN + reverbAmount * ROOM_RANGE
        reverbs.forEach { it.setRoom(roomSize, DAMPING) }
    }

    /** A feedback echo. Reads behind the write head in a circular buffer. */
    private class DelayLine(size: Int) {
        private val buffer = FloatArray(size)
        private var writeIndex = 0

        fun reset() {
            buffer.fill(0f)
            writeIndex = 0
        }

        fun process(input: Float, delaySamples: Int, feedback: Float): Float {
            val delay = delaySamples.coerceIn(1, buffer.size - 1)
            var readIndex = writeIndex - delay
            if (readIndex < 0) readIndex += buffer.size
            val echo = buffer[readIndex]
            buffer[writeIndex] = input + echo * feedback
            writeIndex = (writeIndex + 1) % buffer.size
            return echo
        }
    }

    /**
     * Freeverb: eight parallel comb filters, damped, into four allpasses in series. Cheap, and it
     * sounds like a room. The tunings are the published ones, scaled from their original 44.1 kHz.
     */
    private class Reverb(sampleRate: Int, spread: Int) {
        private val combs = COMB_TUNINGS.map { tuning ->
            Comb(scale(tuning + spread, sampleRate))
        }
        private val allpasses = ALLPASS_TUNINGS.map { tuning ->
            Allpass(scale(tuning + spread, sampleRate))
        }

        fun setRoom(roomSize: Float, damping: Float) {
            val feedback = roomSize * 0.28f + 0.7f
            combs.forEach { it.set(feedback, damping * 0.4f) }
        }

        fun reset() {
            combs.forEach { it.reset() }
            allpasses.forEach { it.reset() }
        }

        fun process(input: Float): Float {
            val fed = input * FIXED_GAIN
            var out = 0f
            for (comb in combs) out += comb.process(fed)
            for (allpass in allpasses) out = allpass.process(out)
            return out
        }

        private class Comb(size: Int) {
            private val buffer = FloatArray(size)
            private var index = 0
            private var store = 0f
            private var feedback = 0.84f
            private var damp1 = 0.2f
            private var damp2 = 0.8f

            fun set(feedback: Float, damping: Float) {
                this.feedback = feedback
                damp1 = damping
                damp2 = 1f - damping
            }

            fun reset() {
                buffer.fill(0f)
                store = 0f
                index = 0
            }

            fun process(input: Float): Float {
                val output = buffer[index]
                store = output * damp2 + store * damp1
                buffer[index] = input + store * feedback
                index = (index + 1) % buffer.size
                return output
            }
        }

        private class Allpass(size: Int) {
            private val buffer = FloatArray(size)
            private var index = 0

            fun reset() {
                buffer.fill(0f)
                index = 0
            }

            fun process(input: Float): Float {
                val buffered = buffer[index]
                val output = -input + buffered
                buffer[index] = input + buffered * 0.5f
                index = (index + 1) % buffer.size
                return output
            }
        }

        private companion object {
            val COMB_TUNINGS = intArrayOf(1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617)
            val ALLPASS_TUNINGS = intArrayOf(556, 441, 341, 225)
            const val FIXED_GAIN = 0.015f

            fun scale(tuning: Int, sampleRate: Int): Int =
                (tuning.toLong() * sampleRate / 44_100).toInt().coerceAtLeast(8)
        }
    }

    /** Direct-form I biquad. Coefficients arrive already normalised by a0. */
    private class Biquad {
        private var b0 = 1f
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

    companion object {
        /** Where the bands meet. Bass/body and body/air, roughly where a DJ isolator puts them. */
        const val CROSSOVER_LOW_HZ = 250f
        const val CROSSOVER_HIGH_HZ = 3_000f
        const val Q_BUTTERWORTH = 0.707f

        /** Boost available at the top of a band's travel. */
        const val MAX_BOOST_DB = 9f

        /** How far down the band falls before the bottom of the travel kills it outright. */
        const val CUT_RANGE_DB = 40f

        const val MIN_DELAY_MS = 60f
        const val MAX_DELAY_MS = 1_500f
        const val DEFAULT_DELAY_MS = 400f

        /** Enough repeats to be a texture, never enough to run away. */
        const val DELAY_MIN_FEEDBACK = 0.15f
        const val DELAY_FEEDBACK_RANGE = 0.55f

        const val ROOM_MIN = 0.45f
        const val ROOM_RANGE = 0.5f
        const val DAMPING = 0.4f

        /** How much dry signal a full-wet reverb pulls back, so the source stays present. */
        const val DRY_DUCK = 0.4f

        const val STEREO_SPREAD = 23

        /**
         * Position to linear gain. The bottom of the travel is **silence**, not a small number:
         * that's the whole point of an isolator, and −40 dB is still audible on a loud stream.
         */
        fun bandGain(position: Float): Float = when {
            position <= -0.995f -> 0f
            position < 0f -> 10f.pow(position * CUT_RANGE_DB / 20f)
            else -> 10f.pow(position * MAX_BOOST_DB / 20f)
        }

        fun lowPass(frequency: Float, q: Float, sampleRate: Int): FloatArray {
            val w0 = 2.0 * Math.PI * frequency / sampleRate
            val cosW0 = cos(w0)
            val alpha = sin(w0) / (2.0 * q)
            return normalise(
                (1 - cosW0) / 2, 1 - cosW0, (1 - cosW0) / 2,
                1 + alpha, -2 * cosW0, 1 - alpha,
            )
        }

        fun highPass(frequency: Float, q: Float, sampleRate: Int): FloatArray {
            val w0 = 2.0 * Math.PI * frequency / sampleRate
            val cosW0 = cos(w0)
            val alpha = sin(w0) / (2.0 * q)
            return normalise(
                (1 + cosW0) / 2, -(1 + cosW0), (1 + cosW0) / 2,
                1 + alpha, -2 * cosW0, 1 - alpha,
            )
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
