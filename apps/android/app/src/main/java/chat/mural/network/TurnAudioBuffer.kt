package chat.mural.network

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Strictly bounded, RAM-only rolling buffer of 16 kHz mono signed PCM16 microphone input
 * aligned along the transport's `inputMilliseconds` timeline. Never persists audio to disk.
 */
internal class TurnAudioBuffer(
    private val maxDurationMs: Long = MAX_DURATION_MS,
) {
    init {
        require(maxDurationMs > 0L)
    }

    private val maxBytes: Int = (maxDurationMs * BYTES_PER_MS).coerceAtMost(MAX_BYTES.toLong()).toInt() and 1.inv()

    private data class TimedChunk(val startMs: Long, val endMs: Long, val bytes: ByteArray)

    private val chunks = ArrayDeque<TimedChunk>()
    private var totalBytes: Int = 0
    private var lastEndMs: Long? = null

    val bufferedBytes: Int
        @Synchronized get() = totalBytes

    @Synchronized
    fun append(pcm16: ByteArray, endMs: Long) {
        val evenLen = pcm16.size and 1.inv()
        if (evenLen < BYTES_PER_SAMPLE) return

        val copy = if (evenLen > maxBytes) {
            pcm16.copyOfRange(evenLen - maxBytes, evenLen)
        } else {
            pcm16.copyOf(evenLen)
        }

        val durationMs = (copy.size / BYTES_PER_MS).toLong().coerceAtLeast(1L)
        val clampedEndMs = endMs.coerceAtLeast(0L)
        val effectiveEndMs = maxOf(clampedEndMs, (lastEndMs ?: 0L) + durationMs)
        val startMs = (effectiveEndMs - durationMs).coerceAtLeast(0L)

        chunks.addLast(TimedChunk(startMs, effectiveEndMs, copy))
        totalBytes += copy.size
        lastEndMs = effectiveEndMs

        val cutoffMs = effectiveEndMs - maxDurationMs
        while (chunks.isNotEmpty() && effectiveEndMs > maxDurationMs && chunks.first().endMs <= cutoffMs) {
            val evicted = chunks.removeFirst()
            totalBytes -= evicted.bytes.size
            evicted.bytes.fill(0)
        }

        while (totalBytes > maxBytes && chunks.isNotEmpty()) {
            val oldest = chunks.first()
            if (totalBytes - oldest.bytes.size >= maxBytes) {
                chunks.removeFirst()
                totalBytes -= oldest.bytes.size
                oldest.bytes.fill(0)
            } else {
                val excess = ((totalBytes - maxBytes + 1) and 1.inv()).coerceAtMost(oldest.bytes.size)
                val trimmed = oldest.bytes.copyOfRange(excess, oldest.bytes.size)
                val trimmedStartMs = oldest.startMs + (excess / BYTES_PER_MS)
                chunks[0] = TimedChunk(trimmedStartMs, oldest.endMs, trimmed)
                oldest.bytes.fill(0)
                totalBytes -= excess
            }
        }
    }

    @Synchronized
    fun slice(
        startMs: Long,
        endMs: Long,
        prePadMs: Long = DEFAULT_PRE_PAD_MS,
        postPadMs: Long = DEFAULT_POST_PAD_MS,
    ): ByteArray {
        if (chunks.isEmpty()) return ByteArray(0)

        val rawStart = (startMs - prePadMs).coerceAtLeast(0L)
        val rawEnd = maxOf(rawStart, endMs + postPadMs)
        val clampedStart = maxOf(rawStart, chunks.first().startMs)
        val clampedEnd = minOf(rawEnd, chunks.last().endMs)
        if (clampedEnd <= clampedStart) return ByteArray(0)
        val windowStart = if (clampedEnd - clampedStart > maxDurationMs) clampedEnd - maxDurationMs else clampedStart

        val parts = ArrayList<ByteArray>()
        var collectedBytes = 0

        for (chunk in chunks) {
            if (chunk.endMs <= windowStart || chunk.startMs >= clampedEnd) continue
            if (chunk.startMs >= windowStart && chunk.endMs <= clampedEnd) {
                parts += chunk.bytes
                collectedBytes += chunk.bytes.size
                continue
            }

            val overlapStartMs = maxOf(chunk.startMs, windowStart)
            val overlapEndMs = minOf(chunk.endMs, clampedEnd)
            if (overlapEndMs <= overlapStartMs) continue

            val startOffset = (((overlapStartMs - chunk.startMs) * BYTES_PER_MS).toInt() and 1.inv())
                .coerceIn(0, chunk.bytes.size)
            val endOffset = (((overlapEndMs - chunk.startMs) * BYTES_PER_MS).toInt() and 1.inv())
                .coerceIn(startOffset, chunk.bytes.size)
            if (endOffset > startOffset) {
                val slicePart = chunk.bytes.copyOfRange(startOffset, endOffset)
                parts += slicePart
                collectedBytes += slicePart.size
            }
        }

        if (collectedBytes < BYTES_PER_SAMPLE) return ByteArray(0)

        val combined = ByteArray(collectedBytes)
        var offset = 0
        for (part in parts) {
            part.copyInto(combined, offset)
            offset += part.size
        }

        val evenSize = combined.size and 1.inv()
        return when {
            evenSize > maxBytes -> combined.copyOfRange(evenSize - maxBytes, evenSize)
            evenSize != combined.size -> combined.copyOf(evenSize)
            else -> combined
        }
    }

    @Synchronized
    fun clear() {
        for (chunk in chunks) {
            chunk.bytes.fill(0)
        }
        chunks.clear()
        totalBytes = 0
        lastEndMs = null
    }

    companion object {
        const val SAMPLE_RATE_HZ = 16_000
        const val BYTES_PER_SAMPLE = 2
        const val BYTES_PER_MS = SAMPLE_RATE_HZ * BYTES_PER_SAMPLE / 1_000
        const val MAX_DURATION_MS = 60_000L
        const val MAX_BYTES = 60 * SAMPLE_RATE_HZ * BYTES_PER_SAMPLE
        const val DEFAULT_PRE_PAD_MS = 500L
        const val DEFAULT_POST_PAD_MS = 300L
        const val MIN_SPEECH_DURATION_MS = 300
        const val MIN_SPEECH_BYTES = MIN_SPEECH_DURATION_MS * BYTES_PER_MS
        const val MIN_SPEECH_RMS = 0.005
    }
}

/** Computes normalized root-mean-square energy in [0.0, 1.0] for 16-bit signed little-endian PCM. */
internal fun pcm16Rms(pcm16: ByteArray): Double {
    val sampleCount = pcm16.size / 2
    if (sampleCount <= 0) return 0.0
    var sumSquares = 0.0
    for (index in 0 until sampleCount * 2 step 2) {
        val sample = ((pcm16[index].toInt() and 0xFF) or (pcm16[index + 1].toInt() shl 8)).toShort().toDouble() / 32_768.0
        sumSquares += sample * sample
    }
    return sqrt(sumSquares / sampleCount).coerceIn(0.0, 1.0)
}

/** Returns true when [pcm16] is at least [minDurationMs] long and exceeds a low silence RMS floor. */
internal fun hasSpeechEnergy(
    pcm16: ByteArray,
    minDurationMs: Int = TurnAudioBuffer.MIN_SPEECH_DURATION_MS,
    minRms: Double = TurnAudioBuffer.MIN_SPEECH_RMS,
): Boolean {
    val minBytes = minDurationMs * TurnAudioBuffer.BYTES_PER_MS
    if (pcm16.size < minBytes) return false
    return pcm16Rms(pcm16) >= minRms
}

/** Wraps mono 16-bit little-endian PCM in a standard 44-byte RIFF/WAVE header. */
internal fun encodePcm16ToWav(
    pcm16: ByteArray,
    sampleRate: Int = TurnAudioBuffer.SAMPLE_RATE_HZ,
): ByteArray {
    require(sampleRate > 0) { "Sample rate must be positive" }
    val dataSize = pcm16.size and 1.inv()
    val channels = 1
    val bitsPerSample = 16
    val byteRate = sampleRate * channels * (bitsPerSample / 8)
    val blockAlign = channels * (bitsPerSample / 8)
    val headerSize = 44

    val wav = ByteArray(headerSize + dataSize)
    val buffer = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
    buffer.put('R'.code.toByte())
    buffer.put('I'.code.toByte())
    buffer.put('F'.code.toByte())
    buffer.put('F'.code.toByte())
    buffer.putInt(36 + dataSize)
    buffer.put('W'.code.toByte())
    buffer.put('A'.code.toByte())
    buffer.put('V'.code.toByte())
    buffer.put('E'.code.toByte())
    buffer.put('f'.code.toByte())
    buffer.put('m'.code.toByte())
    buffer.put('t'.code.toByte())
    buffer.put(' '.code.toByte())
    buffer.putInt(16)
    buffer.putShort(1.toShort())
    buffer.putShort(channels.toShort())
    buffer.putInt(sampleRate)
    buffer.putInt(byteRate)
    buffer.putShort(blockAlign.toShort())
    buffer.putShort(bitsPerSample.toShort())
    buffer.put('d'.code.toByte())
    buffer.put('a'.code.toByte())
    buffer.put('t'.code.toByte())
    buffer.put('a'.code.toByte())
    buffer.putInt(dataSize)
    if (dataSize > 0) {
        pcm16.copyInto(wav, headerSize, 0, dataSize)
    }
    return wav
}
