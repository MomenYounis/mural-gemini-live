package chat.mural.network

import kotlin.math.roundToInt
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioNoiseGateTest {
    private fun constantPcm16(sampleValue: Short, sampleCount: Int = 320): ByteArray {
        val bytes = ByteArray(sampleCount * 2)
        val lo = (sampleValue.toInt() and 0xFF).toByte()
        val hi = ((sampleValue.toInt() shr 8) and 0xFF).toByte()
        for (i in 0 until sampleCount) {
            bytes[i * 2] = lo
            bytes[i * 2 + 1] = hi
        }
        return bytes
    }

    private fun sampleAt(pcm16: ByteArray, index: Int): Short {
        val offset = index * 2
        return ((pcm16[offset].toInt() and 0xFF) or (pcm16[offset + 1].toInt() shl 8)).toShort()
    }

    @Test
    fun staysClosedOnAmbientNoiseAndMidBandSignalsBeforeOpening() {
        val gate = AudioNoiseGate()
        // rms = 200 / 32768 ~= 0.0061 (< closeRms 0.012)
        val lowNoise = constantPcm16(200)
        val lowOut = gate.process(lowNoise)
        assertFalse(gate.isOpen)
        assertEquals(lowNoise.size, lowOut.size)
        assertArrayEquals(ByteArray(640), lowOut)

        // rms = 500 / 32768 ~= 0.0153 (between closeRms 0.012 and openRms 0.022)
        val midNoise = constantPcm16(500)
        val midOut = gate.process(midNoise)
        assertFalse(gate.isOpen)
        assertEquals(midNoise.size, midOut.size)
        assertArrayEquals(ByteArray(640), midOut)
    }

    @Test
    fun opensAboveOpenRmsAppliesSmoothFadeInAndPreservesCallerBuffer() {
        val gate = AudioNoiseGate()
        // rms = 1000 / 32768 ~= 0.0305 (>= openRms 0.022)
        val speech = constantPcm16(1_000)
        val original = speech.copyOf()

        val out = gate.process(speech)
        assertTrue(gate.isOpen)
        assertArrayEquals(original, speech)
        assertEquals(640, out.size)

        assertEquals(0.toShort(), sampleAt(out, 0))
        assertEquals((1_000.0 * (32.0 / 64.0)).roundToInt().toShort(), sampleAt(out, 32))
        assertEquals((1_000.0 * (63.0 / 64.0)).roundToInt().toShort(), sampleAt(out, 63))
        assertEquals(1_000.toShort(), sampleAt(out, 64))
        assertEquals(1_000.toShort(), sampleAt(out, 319))
    }

    @Test
    fun holdsOpenAcrossHysteresisAnd300msHangoverThenFadesOutSmoothly() {
        val gate = AudioNoiseGate()
        gate.process(constantPcm16(1_000))
        assertTrue(gate.isOpen)

        // Hysteresis zone (>= closeRms 0.012) keeps the gate open and passes audio untouched.
        val hysteresisFrame = constantPcm16(500)
        val hystOut = gate.process(hysteresisFrame)
        assertTrue(gate.isOpen)
        assertArrayEquals(hysteresisFrame, hystOut)

        // Low-RMS noise (< 0.012) holds open for frames 1..14, fades out & closes on frame 15 (300 ms).
        val lowNoise = constantPcm16(200)
        repeat(14) { frameIndex ->
            val hangoverOut = gate.process(lowNoise)
            assertTrue("Expected gate to stay open on hangover frame ${frameIndex + 1}", gate.isOpen)
            assertArrayEquals(lowNoise, hangoverOut)
        }

        val closingOut = gate.process(lowNoise)
        assertFalse(gate.isOpen)
        assertEquals(640, closingOut.size)
        assertEquals(200.toShort(), sampleAt(closingOut, 0))
        assertEquals(200.toShort(), sampleAt(closingOut, 255))
        assertEquals((200.0 * (63.0 / 64.0)).roundToInt().toShort(), sampleAt(closingOut, 256))
        assertEquals((200.0 * (31.0 / 64.0)).roundToInt().toShort(), sampleAt(closingOut, 288))
        assertEquals(0.toShort(), sampleAt(closingOut, 319))

        val postCloseOut = gate.process(lowNoise)
        assertFalse(gate.isOpen)
        assertArrayEquals(ByteArray(640), postCloseOut)
    }

    @Test
    fun signalAboveCloseRmsDuringHangoverResetsHangoverWindow() {
        val gate = AudioNoiseGate()
        gate.process(constantPcm16(1_000))
        val lowNoise = constantPcm16(200)

        repeat(10) {
            assertArrayEquals(lowNoise, gate.process(lowNoise))
            assertTrue(gate.isOpen)
        }

        // Refresh hangover window via a frame above closeRms.
        assertArrayEquals(constantPcm16(500), gate.process(constantPcm16(500)))
        assertTrue(gate.isOpen)

        repeat(14) {
            assertArrayEquals(lowNoise, gate.process(lowNoise))
            assertTrue(gate.isOpen)
        }

        // Closes on the 15th frame after the refresh.
        gate.process(lowNoise)
        assertFalse(gate.isOpen)
        assertArrayEquals(ByteArray(640), gate.process(lowNoise))
    }

    @Test
    fun resetImmediatelyClosesOpenGateAndClearsHangoverState() {
        val gate = AudioNoiseGate()
        gate.process(constantPcm16(1_000))
        gate.process(constantPcm16(200))
        assertTrue(gate.isOpen)

        gate.reset()
        assertFalse(gate.isOpen)

        // Mid-band signal (between closeRms and openRms) stays closed after reset.
        val out = gate.process(constantPcm16(500))
        assertFalse(gate.isOpen)
        assertArrayEquals(ByteArray(640), out)
    }
}
