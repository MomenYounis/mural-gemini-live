package chat.mural.network

import org.junit.Assert.*
import org.junit.Test

class GeminiPcmAudioTest {
    @Test fun conversionProducesSignedLittleEndianMonoAt16k() {
        val result = GeminiPcmConverter(16_000).convert(shortArrayOf(0x1234, Short.MIN_VALUE, Short.MAX_VALUE, -1), 4)
        assertArrayEquals(byteArrayOf(0x34, 0x12, 0, -128, -1, 0x7f, -1, -1), result)
    }
    @Test fun hardware48kIsResampledWithCarryAcrossShortReads() {
        val converter = GeminiPcmConverter(48_000)
        assertEquals(0, converter.convert(shortArrayOf(300, 600), 2).size)
        assertArrayEquals(byteArrayOf(0x58, 0x02, 0, 0), converter.convert(shortArrayOf(900, -300, 0, 300), 4))
        assertEquals(640, GeminiPcmConverter(48_000).convert(ShortArray(960), 960).size)
    }
    @Test fun queueIsBoundedAndRejectsCancelledGenerationChunks() {
        val queue = GeminiPlaybackQueue(1_920)
        assertTrue(queue.offer(ByteArray(1_920), 0))
        assertFalse(queue.offer(ByteArray(2), 0))
        assertEquals(960, queue.poll()!!.bytes.size)
        queue.interrupt(1)
        assertNull(queue.poll()); assertTrue(queue.empty())
        assertFalse(queue.offer(ByteArray(960), 0))
        assertTrue(queue.offer(ByteArray(960), 1))
        assertFalse(queue.offer(ByteArray(3), 1))
    }
    @Test fun actualPcmLevelsDistinguishSilenceAndSpeech() {
        assertEquals(0.0, pcm16Level(ByteArray(640)), 0.0)
        assertTrue(pcm16Level(byteArrayOf(0, 0x40, 0, -64)) > 0.5)
    }
    @Test fun partialAndZeroWritesRetainTheExactUnwrittenPcmAndFinishTarget() {
        val pcm = ByteArray(1_920) { it.toByte() }
        val queue = GeminiPlaybackQueue(1_920)
        val progress = GeminiPlaybackProgress()
        assertTrue(queue.offer(pcm, 0))
        val target = queue.queuedSamples()
        val first = queue.peek()!!
        queue.consume(first, 2); progress.written(1)
        assertEquals(2, queue.peek()!!.offset)
        assertEquals(target, progress.writtenAudioFrames + queue.queuedSamples())
        assertFalse(queue.offer(ByteArray(4), 0))
        // A zero native write must leave both ordering and the drain target intact.
        queue.consume(first, 0)
        assertSame(first, queue.peek())
        assertArrayEquals(pcm.copyOfRange(2, 960), first.bytes.copyOfRange(first.offset, first.bytes.size))
        queue.consume(first, 958); progress.written(479)
        val second = queue.peek()!!
        assertArrayEquals(pcm.copyOfRange(960, 1_920), second.bytes)
        assertEquals(target, progress.writtenAudioFrames + queue.queuedSamples())
        queue.consume(second, second.bytes.size); progress.written(480)
        assertTrue(queue.empty())
        progress.update(959)
        assertTrue(progress.playedAudioFrames < target)
        progress.update(960)
        assertEquals(target, progress.playedAudioFrames)
    }
    @Test fun thirtySecondProviderBurstRetainsEverySampleUntilTheNativeHeadHearsIt() {
        val queue = GeminiPlaybackQueue()
        val progress = GeminiPlaybackProgress()
        val pcm = ByteArray(30 * 24_000 * 2) { index -> (index * 31 + index / 960).toByte() }
        // The server can deliver all of a normal response before the speaker has consumed any.
        for (offset in pcm.indices step 24_000) {
            assertTrue(queue.offer(pcm.copyOfRange(offset, minOf(offset + 24_000, pcm.size)), 0))
        }
        assertEquals(30_000L, queue.queuedMilliseconds())
        assertTrue(queue.backlogged)
        assertEquals(0L, progress.playedAudioFrames)
        val received = ByteArray(pcm.size)
        val target = queue.queuedSamples()
        val writes = intArrayOf(0, 2, 46, 240, 672)
        var output = 0
        var write = 0
        while (!queue.empty()) {
            val chunk = queue.peek()!!
            assertTrue(chunk.bytes.size <= 960)
            val count = minOf(writes[write++ % writes.size], chunk.bytes.size - chunk.offset)
            chunk.bytes.copyInto(received, output, chunk.offset, chunk.offset + count)
            queue.consume(chunk, count); progress.written(count / 2)
            output += count
            assertEquals(target, progress.writtenAudioFrames + queue.queuedSamples())
        }
        assertArrayEquals(pcm, received)
        assertFalse(queue.backlogged)
        assertEquals(0L, queue.queuedMilliseconds())
        // Accepted/enqueued/native-written audio must never be reported as heard prematurely.
        assertEquals(0L, progress.playedAudioFrames)
        progress.update(target.toInt() - 1)
        assertTrue(progress.playedAudioFrames < target)
        progress.update(target.toInt())
        assertEquals(target, progress.playedAudioFrames)
    }
    @Test fun pendingDurationHasAHardBoundAndReclaimedCapacityKeepsItsExactOrder() {
        val queue = GeminiPlaybackQueue()
        val second = ByteArray(24_000 * 2) { it.toByte() }
        repeat(60) { assertTrue(queue.offer(second, 0)) }
        assertEquals(60_000L, queue.queuedMilliseconds())
        assertEquals(GeminiPlaybackQueue.MAXIMUM_PENDING_BYTES / 2L, queue.queuedSamples())
        assertFalse(queue.offer(byteArrayOf(7, 9), 0))
        assertEquals(60_000L, queue.queuedMilliseconds())
        val first = queue.peek()!!
        queue.consume(first, 2)
        assertTrue(queue.offer(byteArrayOf(7, 9), 0))
        assertSame(first, queue.peek())
        assertEquals(2, first.offset)
        assertEquals(60_000L, queue.queuedMilliseconds())
        var last: GeminiPlaybackQueue.Chunk? = null
        while (!queue.empty()) last = queue.poll()
        assertArrayEquals(byteArrayOf(7, 9), last!!.bytes)
        assertEquals(0L, queue.queuedMilliseconds())
    }
    @Test fun playbackPressureUsesThirtySecondHighAndFifteenSecondLowWatermarks() {
        val queue = GeminiPlaybackQueue()
        repeat(29) { assertTrue(queue.offer(ByteArray(48_000), 0)) }
        assertFalse(queue.backlogged)
        assertTrue(queue.offer(ByteArray(48_000), 0))
        assertTrue(queue.backlogged)
        repeat(50) { queue.poll() } // Consume one second: pressure stays high below the high mark.
        assertEquals(29_000L, queue.queuedMilliseconds())
        assertTrue(queue.backlogged)
        repeat(14 * 50 - 1) { queue.poll() }
        assertEquals(15_020L, queue.queuedMilliseconds())
        assertTrue(queue.backlogged)
        queue.poll()
        assertEquals(15_000L, queue.queuedMilliseconds())
        assertFalse(queue.backlogged)
        // A session may stream many minutes; consumed bytes do not accumulate toward its cap.
        repeat(20) {
            assertTrue(queue.offer(ByteArray(15 * 48_000), 0))
            assertTrue(queue.backlogged)
            repeat(15 * 50) { queue.poll() }
            assertEquals(15_000L, queue.queuedMilliseconds())
            assertFalse(queue.backlogged)
        }
    }
    @Test fun interruptionDropsTheWholeBurstAndPressureBeforeAcceptingOnlyTheNewGeneration() {
        val queue = GeminiPlaybackQueue()
        assertTrue(queue.offer(ByteArray(45 * 48_000), 0))
        assertTrue(queue.backlogged)
        val old = queue.peek()!!
        queue.consume(old, 2)
        queue.interrupt(1)
        assertTrue(queue.empty()); assertFalse(queue.backlogged)
        assertEquals(0L, queue.queuedMilliseconds())
        assertFalse(queue.offer(ByteArray(960), 0))
        assertTrue(queue.offer(byteArrayOf(7, 9), 1))
        queue.consume(old, old.bytes.size - old.offset)
        assertArrayEquals(byteArrayOf(7, 9), queue.peek()!!.bytes)
        assertEquals(1L, queue.queuedSamples())
    }
    @Test fun interruptDuringNativeWriteCannotConsumeTheReplacementGeneration() {
        val queue = GeminiPlaybackQueue(960)
        assertTrue(queue.offer(ByteArray(960), 0))
        val stale = queue.peek()!!
        queue.interrupt(1)
        assertTrue(queue.offer(ByteArray(48), 1))
        queue.consume(stale, 960)
        assertEquals(24L, queue.queuedSamples())
        assertEquals(1L, queue.peek()!!.generation)
        assertEquals(0, queue.peek()!!.offset)
    }
    @Test fun nativePaddingNeverCountsAsHeardSpeechOrCompletesTheFollowingTurn() {
        val progress = GeminiPlaybackProgress()
        progress.written(100) // Provider PCM, including provider silence, is real audio.
        val firstTarget = progress.writtenAudioFrames
        progress.written(400, silence = true); progress.written(500, silence = true)
        progress.written(200)
        val followingTarget = progress.writtenAudioFrames
        val positions = intArrayOf(50, 100, 999, 1_000, 1_100, 1_200)
        val heard = longArrayOf(50, 100, 100, 100, 200, 300)
        positions.forEachIndexed { index, head ->
            progress.update(head)
            assertEquals("native head $head", heard[index], progress.playedAudioFrames)
            assertEquals(head >= 100, progress.playedAudioFrames >= firstTarget)
            assertEquals(head >= 1_200, progress.playedAudioFrames >= followingTarget)
        }
    }
    @Test fun interruptDuringPaddingPreservesOnlyActuallyPlayedPcmBeforeTheNewStream() {
        val progress = GeminiPlaybackProgress()
        progress.written(100); progress.written(900, silence = true); progress.written(200)
        progress.update(500)
        val heardBeforeFlush = progress.playedAudioFrames
        assertEquals(100L, heardBeforeFlush)
        progress.reset()
        progress.written(24)
        progress.update(0)
        assertEquals(heardBeforeFlush, heardBeforeFlush + progress.playedAudioFrames)
        progress.update(24)
        assertEquals(124L, heardBeforeFlush + progress.playedAudioFrames)
    }
    @Test fun unsignedNativeHeadWrapDoesNotLosePlayedPcm() {
        val progress = GeminiPlaybackProgress()
        progress.written(Int.MAX_VALUE); progress.written(Int.MAX_VALUE); progress.written(100)
        progress.update(-2)
        assertEquals(4_294_967_294L, progress.playedAudioFrames)
        progress.update(24)
        assertEquals(4_294_967_320L, progress.playedAudioFrames)
    }
    @Test fun stalledPlaybackFailsWithoutWaitingForProviderTurnCompletion() {
        val watchdog = GeminiPlaybackWatchdog(timeoutNanos = 500)
        assertFalse(watchdog.stalled(head = 0, outstanding = true, nowNanos = 1_000))
        assertFalse(watchdog.stalled(head = 0, outstanding = true, nowNanos = 1_499))
        // Continued zero/partial writes and later enqueue do not move the native head.
        assertTrue(watchdog.stalled(head = 0, outstanding = true, nowNanos = 1_500))
        assertTrue(watchdog.stalled(head = 0, outstanding = true, nowNanos = 2_000))
    }
    @Test fun onlyPlaybackProgressIdleOrAnExplicitFlushResetsTheWatchdog() {
        val watchdog = GeminiPlaybackWatchdog(timeoutNanos = 500)
        assertFalse(watchdog.stalled(head = 0, outstanding = true, nowNanos = 1_000))
        assertFalse(watchdog.stalled(head = 24, outstanding = true, nowNanos = 1_450))
        assertFalse(watchdog.stalled(head = 24, outstanding = true, nowNanos = 1_949))
        assertTrue(watchdog.stalled(head = 24, outstanding = true, nowNanos = 1_950))
        assertFalse(watchdog.stalled(head = 24, outstanding = false, nowNanos = 10_000))
        assertFalse(watchdog.stalled(head = 24, outstanding = true, nowNanos = 20_000))
        watchdog.reset()
        assertFalse(watchdog.stalled(head = 0, outstanding = true, nowNanos = 30_000))
        assertFalse(watchdog.stalled(head = 1, outstanding = true, nowNanos = 30_499))
    }
}
