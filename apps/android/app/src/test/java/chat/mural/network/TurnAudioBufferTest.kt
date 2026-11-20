package chat.mural.network

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sin
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnAudioBufferTest {
    private fun tonePcm16(durationMs: Int, amplitude: Int = 6_000, frequencyHz: Double = 440.0): ByteArray {
        val sampleCount = durationMs * 16
        val bytes = ByteArray(sampleCount * 2)
        for (i in 0 until sampleCount) {
            val sample = (amplitude * sin(2.0 * Math.PI * frequencyHz * i / 16_000.0)).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            bytes[i * 2] = (sample and 0xFF).toByte()
            bytes[i * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
        }
        return bytes
    }

    @Test
    fun encodePcm16ToWavWritesValidStandardHeaderAndPayload() {
        val pcm = byteArrayOf(0x34, 0x12, 0x00, 0x40, 0xFF.toByte(), 0x7F, 0x01)
        val wav = encodePcm16ToWav(pcm)

        assertEquals(44 + 6, wav.size)
        assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(wav, 8, 4, Charsets.US_ASCII))
        assertEquals("fmt ", String(wav, 12, 4, Charsets.US_ASCII))
        assertEquals("data", String(wav, 36, 4, Charsets.US_ASCII))

        val header = ByteBuffer.wrap(wav, 0, 44).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(36 + 6, header.getInt(4))
        assertEquals(16, header.getInt(16))
        assertEquals(1.toShort(), header.getShort(20))
        assertEquals(1.toShort(), header.getShort(22))
        assertEquals(16_000, header.getInt(24))
        assertEquals(32_000, header.getInt(28))
        assertEquals(2.toShort(), header.getShort(32))
        assertEquals(16.toShort(), header.getShort(34))
        assertEquals(6, header.getInt(40))

        assertArrayEquals(pcm.copyOf(6), wav.copyOfRange(44, wav.size))
    }

    @Test
    fun rollingSixtySecondCapEvictsOldestAudioAndBoundsMemory() {
        val buffer = TurnAudioBuffer()
        val oneSecondBytes = 16_000 * 2
        val firstChunk = ByteArray(oneSecondBytes) { 0x11 }
        buffer.append(firstChunk, 1_000L)
        firstChunk.fill(0)

        for (sec in 2..75) {
            val marker = (sec and 0x7F).toByte()
            buffer.append(ByteArray(oneSecondBytes) { marker }, sec * 1_000L)
        }

        assertEquals(TurnAudioBuffer.MAX_BYTES, buffer.bufferedBytes)
        assertTrue(buffer.slice(0L, 5_000L, prePadMs = 0L, postPadMs = 0L).isEmpty())

        val latest = buffer.slice(74_000L, 75_000L, prePadMs = 0L, postPadMs = 0L)
        assertEquals(oneSecondBytes, latest.size)
        assertTrue(latest.all { it == (75 and 0x7F).toByte() })

        val oversizedSlice = buffer.slice(0L, 75_000L, prePadMs = 500L, postPadMs = 300L)
        assertEquals(TurnAudioBuffer.MAX_BYTES, oversizedSlice.size)
    }

    @Test
    fun sliceExtractsTimestampRangeWithComfortablePaddingClampedToBufferedAudio() {
        val buffer = TurnAudioBuffer()
        // Append 5 seconds in 500ms chunks where each 500ms window carries a unique marker byte.
        for (i in 1..10) {
            val endMs = i * 500L
            buffer.append(ByteArray(500 * TurnAudioBuffer.BYTES_PER_MS) { i.toByte() }, endMs)
        }

        // Speech window [2000ms, 3000ms] with default 500ms pre-pad and 300ms post-pad -> [1500ms, 3300ms]
        val extracted = buffer.slice(2_000L, 3_000L)
        val expectedBytes = (3_300L - 1_500L).toInt() * TurnAudioBuffer.BYTES_PER_MS
        assertEquals(expectedBytes, extracted.size)

        // Chunk 4 is [1500..2000), Chunk 5 is [2000..2500), Chunk 6 is [2500..3000), Chunk 7 partial [3000..3300)
        assertEquals(4.toByte(), extracted.first())
        assertEquals(7.toByte(), extracted.last())

        // Clamped at 0ms when startMs < prePadMs and clamped at available end when endMs + postPadMs > 5000ms
        val fullClamped = buffer.slice(100L, 4_900L)
        assertEquals(5_000 * TurnAudioBuffer.BYTES_PER_MS, fullClamped.size)
    }

    @Test
    fun hasSpeechEnergyFiltersShortOrSilentClipsAndAcceptsSpokenAudio() {
        assertFalse(hasSpeechEnergy(ByteArray(0)))
        assertFalse(hasSpeechEnergy(tonePcm16(durationMs = 200)))
        assertFalse(hasSpeechEnergy(ByteArray(1_000 * TurnAudioBuffer.BYTES_PER_MS)))
        assertFalse(hasSpeechEnergy(tonePcm16(durationMs = 500, amplitude = 20)))

        assertTrue(hasSpeechEnergy(tonePcm16(durationMs = 300, amplitude = 2_000)))
        assertTrue(hasSpeechEnergy(tonePcm16(durationMs = 1_200, amplitude = 4_000)))
    }

    @Test
    fun clearReleasesAndWipesBufferedAudioImmediately() {
        val buffer = TurnAudioBuffer()
        val raw = tonePcm16(durationMs = 1_000)
        buffer.append(raw, 1_000L)
        assertEquals(raw.size, buffer.bufferedBytes)

        buffer.clear()
        assertEquals(0, buffer.bufferedBytes)
        assertEquals(0, buffer.slice(0L, 1_000L).size)
    }
}
