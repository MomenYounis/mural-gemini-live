package chat.mural.network

import kotlin.math.roundToInt

/**
 * Dual-threshold RMS voice noise gate with hangover and edge fades for 16 kHz mono signed PCM16 frames.
 * Silences distant background chatter below speech RMS while preserving timeline byte alignment.
 */
internal class AudioNoiseGate(
    private val openRms: Double = DEFAULT_OPEN_RMS,
    private val closeRms: Double = DEFAULT_CLOSE_RMS,
    private val hangoverFrames: Int = DEFAULT_HANGOVER_FRAMES,
    private val fadeSamples: Int = DEFAULT_FADE_SAMPLES,
) {
    init {
        require(openRms in 0.0..1.0) { "openRms must be in [0.0, 1.0]" }
        require(closeRms in 0.0..openRms) { "closeRms must be in [0.0, openRms]" }
        require(hangoverFrames >= 0) { "hangoverFrames must be non-negative" }
        require(fadeSamples >= 0) { "fadeSamples must be non-negative" }
    }

    private var open = false
    private var hangoverRemaining = 0

    val isOpen: Boolean
        @Synchronized get() = open

    @Synchronized
    fun process(pcm16: ByteArray): ByteArray {
        if (pcm16.size < 2) return ByteArray(pcm16.size)
        val rms = pcm16Rms(pcm16)
        if (!open) {
            return if (rms >= openRms) {
                open = true
                hangoverRemaining = hangoverFrames
                applyFadeIn(pcm16)
            } else {
                ByteArray(pcm16.size)
            }
        }
        return when {
            rms >= closeRms -> {
                hangoverRemaining = hangoverFrames
                pcm16
            }
            hangoverRemaining > 1 -> {
                hangoverRemaining--
                pcm16
            }
            else -> {
                hangoverRemaining = 0
                open = false
                applyFadeOut(pcm16)
            }
        }
    }

    @Synchronized
    fun reset() {
        open = false
        hangoverRemaining = 0
    }

    private fun applyFadeIn(pcm16: ByteArray): ByteArray {
        val out = pcm16.copyOf()
        val sampleCount = out.size / 2
        val count = minOf(fadeSamples, sampleCount)
        for (i in 0 until count) {
            val gain = i.toDouble() / count.toDouble()
            scaleSample(out, i * 2, gain)
        }
        return out
    }

    private fun applyFadeOut(pcm16: ByteArray): ByteArray {
        val out = pcm16.copyOf()
        val sampleCount = out.size / 2
        val count = minOf(fadeSamples, sampleCount)
        val startSample = sampleCount - count
        for (k in 0 until count) {
            val gain = (count - 1 - k).toDouble() / count.toDouble()
            scaleSample(out, (startSample + k) * 2, gain)
        }
        return out
    }

    private fun scaleSample(bytes: ByteArray, byteOffset: Int, gain: Double) {
        val sample = ((bytes[byteOffset].toInt() and 0xFF) or (bytes[byteOffset + 1].toInt() shl 8)).toShort().toDouble()
        val scaled = (sample * gain).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
        bytes[byteOffset] = (scaled and 0xFF).toByte()
        bytes[byteOffset + 1] = ((scaled shr 8) and 0xFF).toByte()
    }

    companion object {
        const val DEFAULT_OPEN_RMS = 0.022
        const val DEFAULT_CLOSE_RMS = 0.012
        const val DEFAULT_HANGOVER_FRAMES = 15
        const val SAMPLE_RATE_HZ = 16_000
        const val DEFAULT_FADE_SAMPLES = 64
    }
}
