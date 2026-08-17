package com.tastyradio.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * Per-channel tone shaping, applied inside the player's own audio pipeline.
 *
 * Two controls, and they do genuinely different jobs:
 *
 * **A three-band isolator.** Not a shelving EQ — shelves *tilt* the sound, and even at −12 dB you
 * can still plainly hear the band you were trying to remove. This splits the signal into three
 * bands with Linkwitz-Riley crossovers and scales each one independently, so a band at minimum is
 * multiplied by zero and is *gone*. That's what DJ mixers do, and it's the difference between
 * "quieter highs" and "no highs".
 *
 * **A filter sweep.** A single bipolar control: low-pass closing down, or high-pass opening up,
 * 24 dB/octave with a resonant peak at the cutoff.
 *
 * ## Why not `android.media.audiofx.Equalizer`
 * It's vendor-supplied — band counts and centre frequencies vary, and on some builds it silently
 * does nothing — and it sits outside our pipeline, so whether it reached the recording would be a
 * question rather than a fact. Filtering here means the EQ is provably in the file.
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

    /** −1 = low-pass fully closed, 0 = bypassed, +1 = high-pass fully closed. */
    @Volatile private var sweep: Float = 0f
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

    private var sweepA: Array<Biquad> = emptyArray()
    private var sweepB: Array<Biquad> = emptyArray()

    @Volatile private var lowGain = 1f
    @Volatile private var midGain = 1f
    @Volatile private var highGain = 1f
    @Volatile private var sweepActive = false

    fun setBands(low: Float, mid: Float, high: Float) {
        lowPosition = low.coerceIn(-1f, 1f)
        midPosition = mid.coerceIn(-1f, 1f)
        highPosition = high.coerceIn(-1f, 1f)
        dirty = true
    }

    fun setSweep(position: Float) {
        sweep = position.coerceIn(-1f, 1f)
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
        sweepA = bank(); sweepB = bank()

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

            if (sweepActive) {
                sample = sweepB[channel].process(sweepA[channel].process(sample))
            }

            output.putShort(sample.coerceIn(-32768f, 32767f).toInt().toShort())
            channel = (channel + 1) % channelCount
        }

        inputBuffer.position(inputBuffer.limit())
        output.flip()
    }

    override fun onFlush() {
        allFilters().forEach { bank -> bank.forEach { it.reset() } }
    }

    override fun onReset() {
        lowA = emptyArray(); lowB = emptyArray()
        midHighpassA = emptyArray(); midHighpassB = emptyArray()
        midLowpassA = emptyArray(); midLowpassB = emptyArray()
        highA = emptyArray(); highB = emptyArray()
        sweepA = emptyArray(); sweepB = emptyArray()
    }

    private fun allFilters() = listOf(
        lowA, lowB, midHighpassA, midHighpassB,
        midLowpassA, midLowpassB, highA, highB, sweepA, sweepB,
    )

    private fun recompute() {
        if (sampleRate == 0) return

        lowGain = bandGain(lowPosition)
        midGain = bandGain(midPosition)
        highGain = bandGain(highPosition)

        sweepActive = abs(sweep) > 0.02f
        if (!sweepActive) {
            sweepA.forEach { it.reset() }
            sweepB.forEach { it.reset() }
            return
        }

        val amount = abs(sweep)
        val nyquistLimit = sampleRate * 0.45f
        val (first, second) = if (sweep < 0) {
            val cutoff = (LP_OPEN_HZ * (LP_CLOSED_HZ / LP_OPEN_HZ).pow(amount)).coerceAtMost(nyquistLimit)
            lowPass(cutoff, Q_BUTTERWORTH, sampleRate) to lowPass(cutoff, Q_RESONANT, sampleRate)
        } else {
            val cutoff = (HP_OPEN_HZ * (HP_CLOSED_HZ / HP_OPEN_HZ).pow(amount)).coerceAtMost(nyquistLimit)
            highPass(cutoff, Q_BUTTERWORTH, sampleRate) to highPass(cutoff, Q_RESONANT, sampleRate)
        }
        for (index in sweepA.indices) {
            sweepA[index].setCoefficients(first)
            sweepB[index].setCoefficients(second)
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

        const val LP_OPEN_HZ = 20_000f
        const val LP_CLOSED_HZ = 120f
        const val HP_OPEN_HZ = 20f
        const val HP_CLOSED_HZ = 8_000f

        const val Q_BUTTERWORTH = 0.707f

        /** The resonant bite at the sweep's cutoff — the sound people mean by a DJ filter. */
        const val Q_RESONANT = 2.2f

        /** Boost available at the top of a band's travel. */
        const val MAX_BOOST_DB = 9f

        /** How far down the band falls before the bottom of the travel kills it outright. */
        const val CUT_RANGE_DB = 40f

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
