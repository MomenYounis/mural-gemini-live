package chat.mural.network

import android.media.AudioManager
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import chat.mural.MainActivity
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real microphone and AudioTrack, synthetic silent PCM and no network/provider requests. */
@OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class GeminiNativeAudioDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun nativeCaptureMuteBurstPlaybackInterruptionAndDrainAllowTheNextAudioOwner() = runBlocking {
        val context = compose.activity
        assertEquals("chat.mural.android.uitest", context.packageName)
        val manager = context.getSystemService(AudioManager::class.java)
        assumeTrue("Another call owns communication audio", manager.mode == AudioManager.MODE_NORMAL)
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("pm grant chat.mural.android.uitest android.permission.RECORD_AUDIO")
            .use { descriptor -> java.io.FileInputStream(descriptor.fileDescriptor).use { it.readBytes() } }
        val control = newSingleThreadContext("gemini-offline-audio-device")
        val scope = CoroutineScope(SupervisorJob() + control)
        try {
            repeat(2) {
                val captured = CountDownLatch(1); val drained = CountDownLatch(1)
                val failures = AtomicInteger(); val chunks = AtomicInteger(); val completions = AtomicInteger()
                val audio = GeminiPcmAudio(context, scope)
                try {
                    withContext(control) {
                        audio.start(onInput = { pcm ->
                            if (pcm.isNotEmpty() && pcm.size % 2 == 0) { chunks.incrementAndGet(); captured.countDown() }
                            true
                        }, onFailure = { failures.incrementAndGet() }, onLevels = { _, _ -> })
                    }
                    assertTrue("Microphone did not deliver native PCM", captured.await(5, TimeUnit.SECONDS))
                    assertTrue(chunks.get() > 0); assertTrue(audio.inputMilliseconds > 0)
                    audio.mute(true)
                    // Allow at most one in-flight capture callback at the mute boundary.
                    delay(100)
                    val mutedCount = chunks.get(); delay(150); assertEquals(mutedCount, chunks.get())
                    assertTrue(audio.enqueue(ByteArray(4_800), 0)) // 100 ms silent 24 kHz PCM.
                    audio.finishGeneration(0) { completions.incrementAndGet(); drained.countDown() }
                    assertTrue("AudioTrack did not drain", drained.await(5, TimeUnit.SECONDS))
                    assertEquals(100L, audio.playedMilliseconds)
                    assertEquals(1, completions.get())
                    // A server can send a whole 30 s reply before AudioTrack plays it. It must
                    // survive the former 8 s queue limit and remain immediately interruptible.
                    val discardedCompletions = AtomicInteger()
                    withContext(control) {
                        assertTrue(audio.enqueue(ByteArray(30 * 24_000 * 2), 0))
                        audio.finishGeneration(0) { discardedCompletions.incrementAndGet() }
                    }
                    assertTrue(audio.queuedMilliseconds > 8_000)
                    withTimeout(5_000) { while (audio.playedMilliseconds <= 100) delay(10) }
                    audio.interrupt(1); assertFalse(audio.enqueue(ByteArray(960), 0))
                    val heardAtInterrupt = audio.playedMilliseconds
                    assertTrue(heardAtInterrupt > 100)
                    assertTrue("Discarded burst was counted as heard", heardAtInterrupt < 30_100)
                    assertEquals(0L, audio.queuedMilliseconds)
                    assertFalse(audio.playbackBacklogged)
                    // A flushed stream must also start with less than a normal 20 ms write.
                    val shortDrained = CountDownLatch(1)
                    assertTrue(audio.enqueue(ByteArray(48), 1)) // One millisecond at 24 kHz.
                    audio.finishGeneration(1) { completions.incrementAndGet(); shortDrained.countDown() }
                    assertTrue("Short AudioTrack output did not drain after flush", shortDrained.await(5, TimeUnit.SECONDS))
                    assertEquals(heardAtInterrupt + 1, audio.playedMilliseconds)
                    assertEquals(2, completions.get())
                    assertEquals(0, discardedCompletions.get())
                    audio.mute(false); delay(100); assertTrue(chunks.get() > mutedCount)
                    assertEquals(0, failures.get())
                } finally { withContext(control) { audio.close(); audio.close() } }
                assertEquals(AudioManager.MODE_NORMAL, manager.mode)
            }
        } finally { scope.cancel(); control.close() }
    }
}
