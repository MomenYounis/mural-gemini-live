package chat.mural.network

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.*
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlin.math.sqrt

/** Only signed mono PCM16 is accepted at this boundary. No headers or encoded audio. */
internal interface GeminiAudio {
    val inputMilliseconds: Long
    val playedMilliseconds: Long
    val queuedMilliseconds: Long get() = 0
    val playbackBacklogged: Boolean get() = false
    val playbackFailed: Boolean get() = false
    val remainingPlaybackBytes: Int get() = GeminiPlaybackQueue.MAXIMUM_PENDING_BYTES
    fun start(onInput: (ByteArray) -> Boolean, onFailure: () -> Unit, onLevels: (Double, Double) -> Unit)
    fun mute(value: Boolean)
    fun enqueue(pcm16: ByteArray, generation: Long): Boolean
    fun interrupt(generation: Long)
    fun finishGeneration(generation: Long, onDrained: () -> Unit)
    fun close()
}

/** Streaming 48 kHz downsampling averages each 3-sample window, retaining partial windows. */
internal class GeminiPcmConverter(private val inputRate: Int) {
    init { require(inputRate == 16_000 || inputRate == 48_000) }
    private var remainder = 0
    private var sum = 0
    fun convert(samples: ShortArray, count: Int): ByteArray {
        require(count in 0..samples.size)
        val factor = inputRate / 16_000
        val output = ByteArray(((count + remainder) / factor) * 2)
        var offset = 0
        for (index in 0 until count) {
            sum += samples[index].toInt(); remainder++
            if (remainder == factor) {
                val sample = (sum / factor).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                output[offset++] = sample.toByte(); output[offset++] = (sample shr 8).toByte()
                sum = 0; remainder = 0
            }
        }
        return output
    }
}

internal fun pcm16Level(bytes: ByteArray): Double {
    if (bytes.isEmpty()) return 0.0
    var squared = 0.0
    for (index in 0 until bytes.size - 1 step 2) {
        val value = ((bytes[index].toInt() and 255) or (bytes[index + 1].toInt() shl 8)).toShort().toDouble() / 32768
        squared += value * value
    }
    return (sqrt(squared / (bytes.size / 2)) * 4).coerceIn(0.0, 1.0)
}

/**
 * Gemini can generate a complete spoken turn faster than the speaker can play it. Budget one
 * minute of pending PCM, with hysteresis so the transport can defer optional follow-up replies.
 * The provider output limit and transport admission policy also bound generation ahead of speech.
 */
internal class GeminiPlaybackQueue(private val maximumBytes: Int = MAXIMUM_PENDING_BYTES) {
    companion object {
        // 60 s * 24,000 mono frames/s * 2 bytes/frame = 2.88 MB of queued PCM.
        const val MAXIMUM_PENDING_BYTES = 60 * 24_000 * 2
        private const val WRITE_BYTES = 960
    }
    init { require(maximumBytes > 0 && maximumBytes % 2 == 0) }
    data class Chunk(val generation: Long, val bytes: ByteArray, var offset: Int = 0)
    private val chunks = ArrayDeque<Chunk>()
    private val highWatermarkBytes = maximumBytes / 2
    private val lowWatermarkBytes = maximumBytes / 4
    private var bytes = 0
    @Volatile var backlogged: Boolean = false; private set
    @Volatile var generation: Long = 0; private set
    @Synchronized fun offer(pcm: ByteArray, forGeneration: Long): Boolean {
        if (forGeneration != generation || pcm.isEmpty() || pcm.size % 2 != 0 || pcm.size > maximumBytes - bytes) return false
        // Twenty millisecond writes bound flush latency even if the provider sends a large part.
        for (offset in pcm.indices step WRITE_BYTES) {
            val chunk = pcm.copyOfRange(offset, minOf(offset + WRITE_BYTES, pcm.size))
            chunks.addLast(Chunk(forGeneration, chunk)); bytes += chunk.size
        }
        updatePressure()
        return true
    }
    @Synchronized fun peek(): Chunk? = chunks.firstOrNull()
    @Synchronized fun consume(chunk: Chunk, count: Int) {
        require(count >= 0 && count % 2 == 0 && count <= chunk.bytes.size - chunk.offset)
        // An interrupt may invalidate the chunk during the nonblocking native write.
        if (chunks.firstOrNull() !== chunk) return
        chunk.offset += count; bytes -= count
        if (chunk.offset == chunk.bytes.size) chunks.removeFirst()
        updatePressure()
    }
    @Synchronized fun poll(): Chunk? = chunks.removeFirstOrNull()?.also {
        bytes -= it.bytes.size - it.offset; updatePressure()
    }
    @Synchronized fun empty() = chunks.isEmpty()
    @Synchronized fun queuedSamples() = bytes / 2L
    @Synchronized fun queuedMilliseconds() = bytes / 48L
    @Synchronized fun remainingBytes() = maximumBytes - bytes
    @Synchronized fun interrupt(nextGeneration: Long) {
        chunks.clear(); bytes = 0; backlogged = false; generation = nextGeneration
    }
    private fun updatePressure() {
        if (bytes >= highWatermarkBytes) backlogged = true
        else if (bytes <= lowWatermarkBytes) backlogged = false
    }
}

/** Maps the native playback head to real PCM, excluding silence used to prime old devices. */
internal class GeminiPlaybackProgress {
    private data class Padding(val start: Long, var end: Long)
    private val padding = ArrayDeque<Padding>()
    private var completedPadding = 0L
    private var previousHead = 0L
    private var headWrap = 0L
    var writtenFrames = 0L; private set
    var writtenAudioFrames = 0L; private set
    var headFrames = 0L; private set
    var playedAudioFrames = 0L; private set

    fun written(frames: Int, silence: Boolean = false) {
        require(frames >= 0)
        if (frames == 0) return
        if (silence) {
            val last = padding.lastOrNull()
            if (last?.end == writtenFrames) last.end += frames
            else padding.addLast(Padding(writtenFrames, writtenFrames + frames))
        } else writtenAudioFrames += frames
        writtenFrames += frames
    }

    fun update(rawHead: Int) {
        val unsigned = rawHead.toLong() and 0xffffffffL
        if (unsigned < previousHead) headWrap += 1L shl 32
        previousHead = unsigned
        headFrames = minOf(headWrap + unsigned, writtenFrames)
        while (padding.firstOrNull()?.let { it.end <= headFrames } == true) {
            val done = padding.removeFirst(); completedPadding += done.end - done.start
        }
        val partialPadding = padding.firstOrNull()?.let { (headFrames - it.start).coerceIn(0, it.end - it.start) } ?: 0
        playedAudioFrames = headFrames - completedPadding - partialPadding
    }

    fun reset() {
        padding.clear(); completedPadding = 0; previousHead = 0; headWrap = 0
        writtenFrames = 0; writtenAudioFrames = 0; headFrames = 0; playedAudioFrames = 0
    }
}

/** Only native head movement can extend the wait for outstanding audio. */
internal class GeminiPlaybackWatchdog(private val timeoutNanos: Long = 5_000_000_000) {
    private var previousHead = 0L
    private var progressedAt: Long? = null
    fun stalled(head: Long, outstanding: Boolean, nowNanos: Long): Boolean {
        if (!outstanding) { reset(); previousHead = head; return false }
        val since = progressedAt
        if (since == null || head != previousHead) {
            previousHead = head; progressedAt = nowNanos
            return false
        }
        return nowNanos - since >= timeoutNanos
    }
    fun reset() { previousHead = 0; progressedAt = null }
}

/** Native communication audio, kept alive by the existing microphone/media-playback service. */
internal class GeminiPcmAudio(context: Context, private val controlScope: CoroutineScope) : GeminiAudio {
    private val context = context.applicationContext
    private val manager = this.context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val closed = AtomicBoolean(false)
    private val trackLock = Any()
    private val queue = GeminiPlaybackQueue()
    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    private var aec: AcousticEchoCanceler? = null
    private var suppressor: NoiseSuppressor? = null
    private var captureJob: Job? = null
    private var playbackJob: Job? = null
    private var focus: AudioFocusRequest? = null
    private var callback: AudioDeviceCallback? = null
    private var legacy: LegacyCommunicationAudioRoute? = null
    private var ownsMode = false
    private var ownsFocus = false
    private var ownsRoute = false
    private var focusLost = false
    @Volatile private var muted = false
    @Volatile private var receivedSamples = 0L
    @Volatile private var playedSamples = 0L
    @Volatile private var inputLevel = 0.0
    @Volatile private var outputLevel = 0.0
    private val progress = GeminiPlaybackProgress()
    private val watchdog = GeminiPlaybackWatchdog()
    private var legacyPaddingUntil: Long? = null
    private var playedBeforeFlush = 0L
    private data class Drain(val generation: Long, val targetSamples: Long, val deadlineNanos: Long, val callback: () -> Unit)
    private val drains = ArrayDeque<Drain>()
    private var failure: (() -> Unit)? = null
    private val failed = AtomicBoolean(false)
    override val inputMilliseconds get() = receivedSamples / 16
    override val playedMilliseconds get() = playedSamples / 24
    override val queuedMilliseconds get() = queue.queuedMilliseconds()
    override val playbackBacklogged get() = queue.backlogged
    override val playbackFailed get() = closed.get() || failed.get()
    override val remainingPlaybackBytes get() = queue.remainingBytes()

    @Suppress("MissingPermission", "DEPRECATION")
    override fun start(onInput: (ByteArray) -> Boolean, onFailure: () -> Unit, onLevels: (Double, Double) -> Unit) {
        check(!closed.get() && record == null)
        check(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) { "Microphone permission is required." }
        failure = onFailure
        try {
            configureRouting()
            var inputRate = 16_000
            for (rate in listOf(16_000, 48_000)) {
                val minimum = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                if (minimum <= 0) continue
                val candidate = try { AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setBufferSizeInBytes(maxOf(minimum * 2, rate / 5 * 2)).build() } catch (_: Exception) { continue }
                if (candidate.state == AudioRecord.STATE_INITIALIZED) { record = candidate; inputRate = rate; break }
                candidate.release()
            }
            val microphone = checkNotNull(record) { "Microphone audio is unavailable." }
            // VOICE_COMMUNICATION asks the platform for its voice-processing path. Attach effects
            // when exposed by the device; some devices apply them within their communication DSP.
            if (AcousticEchoCanceler.isAvailable()) aec = runCatching { AcousticEchoCanceler.create(microphone.audioSessionId)?.also { it.enabled = true } }.getOrNull()
            if (NoiseSuppressor.isAvailable()) suppressor = runCatching { NoiseSuppressor.create(microphone.audioSessionId)?.also { it.enabled = true } }.getOrNull()
            val minimumOut = AudioTrack.getMinBufferSize(24_000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(minimumOut > 0)
            val speaker = AudioTrack.Builder().setAudioAttributes(attributes())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(24_000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(maxOf(minimumOut, 4_800))
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY).build()
            track = speaker
            check(speaker.state == AudioTrack.STATE_INITIALIZED)
            preparePlayback(speaker)
            speaker.play(); microphone.startRecording()
            check(microphone.recordingState == AudioRecord.RECORDSTATE_RECORDING)
            val converter = GeminiPcmConverter(inputRate)
            captureJob = io.launch {
                val samples = ShortArray(inputRate / 50) // 20 ms at the actual hardware rate.
                while (isActive && !closed.get() && !failed.get()) {
                    val count = try { microphone.read(samples, 0, samples.size, AudioRecord.READ_BLOCKING) } catch (_: Exception) { -1 }
                    if (closed.get()) break
                    if (count <= 0) { fail(); break }
                    val pcm = converter.convert(samples, count)
                    if (pcm.isEmpty()) continue
                    receivedSamples += pcm.size / 2
                    inputLevel = if (muted) 0.0 else pcm16Level(pcm)
                    if (!muted && !onInput(pcm)) { fail(); break }
                }
            }
            playbackJob = io.launch {
                var lastMeter = 0L
                val silence = ByteArray(960)
                while (isActive && !closed.get() && !failed.get()) {
                    val drained = mutableListOf<Drain>()
                    val wrote = synchronized(trackLock) {
                        try {
                            progress.update(speaker.playbackHeadPosition)
                            val next = queue.peek()
                            var count = 0
                            if (next != null && !closed.get() && next.generation == queue.generation) {
                                legacyPaddingUntil = null
                                val remaining = next.bytes.size - next.offset
                                // Partial/zero writes retain their bytes in the bounded queue. A
                                // stopped route can never hold trackLock inside a blocking write.
                                count = speaker.write(next.bytes, next.offset, remaining, AudioTrack.WRITE_NON_BLOCKING)
                                if (count < 0 || count > remaining || count % 2 != 0) fail()
                                else {
                                    progress.written(count / 2)
                                    queue.consume(next, count)
                                    if (count > 0) outputLevel = pcm16Level(next.bytes)
                                }
                            } else if (Build.VERSION.SDK_INT < 31 && queue.empty() &&
                                drains.firstOrNull()?.let { it.targetSamples > progress.playedAudioFrames } == true) {
                                // Before Android 12 the start/rebuffer threshold cannot be set.
                                // Prime one actual native capacity after a short final reply; the
                                // ledger keeps these silent frames out of speech time and drains.
                                val until = legacyPaddingUntil ?: (progress.writtenFrames + speaker.bufferCapacityInFrames)
                                    .also { legacyPaddingUntil = it }
                                val bytes = minOf(480L, until - progress.writtenFrames).toInt() * 2
                                if (bytes > 0) {
                                    count = speaker.write(silence, 0, bytes, AudioTrack.WRITE_NON_BLOCKING)
                                    if (count < 0 || count > bytes || count % 2 != 0) fail()
                                    else progress.written(count / 2, silence = true)
                                }
                            }
                            playedSamples = playedBeforeFlush + progress.playedAudioFrames
                            if (queue.empty() && progress.playedAudioFrames >= progress.writtenAudioFrames) outputLevel = 0.0
                            while (drains.firstOrNull()?.let { progress.playedAudioFrames >= it.targetSamples } == true) {
                                val done = drains.removeFirst()
                                if (done.generation == queue.generation) drained.add(done)
                            }
                            if (drains.isEmpty()) legacyPaddingUntil = null
                            if (watchdog.stalled(progress.headFrames,
                                    !queue.empty() || progress.playedAudioFrames < progress.writtenAudioFrames,
                                    System.nanoTime())) fail()
                            // Deadlines only fail stalled playback; success always requires the
                            // physical head to pass all real PCM belonging to the finished reply.
                            if (drains.firstOrNull()?.let { System.nanoTime() >= it.deadlineNanos } == true) fail()
                            count > 0
                        } catch (_: Exception) {
                            fail(); false
                        }
                    }
                    drained.forEach { done -> controlScope.launch {
                        if (!closed.get() && !failed.get() && done.generation == queue.generation) done.callback()
                    } }
                    val now = System.nanoTime()
                    if (now - lastMeter >= 100_000_000) {
                        lastMeter = now
                        val input = inputLevel; val output = outputLevel
                        controlScope.launch { if (!closed.get() && !failed.get()) onLevels(input, output) }
                    }
                    if (!wrote) delay(10)
                }
            }
        } catch (error: Throwable) { close(); throw error }
    }

    override fun mute(value: Boolean) { muted = value; if (value) inputLevel = 0.0 }
    override fun enqueue(pcm16: ByteArray, generation: Long) = !closed.get() && !failed.get() && queue.offer(pcm16, generation)

    override fun interrupt(generation: Long) {
        // Invalidate before taking trackLock so the writer cannot pick another old chunk.
        queue.interrupt(generation)
        synchronized(trackLock) {
            drains.clear()
            val speaker = track ?: return
            try {
                progress.update(speaker.playbackHeadPosition)
                playedBeforeFlush += progress.playedAudioFrames
                playedSamples = playedBeforeFlush
                speaker.pause(); speaker.flush()
                progress.reset(); watchdog.reset(); legacyPaddingUntil = null
                if (!closed.get()) { preparePlayback(speaker); speaker.play() }
            } catch (_: Exception) { if (!closed.get()) fail() }
            outputLevel = 0.0
        }
    }

    override fun finishGeneration(generation: Long, onDrained: () -> Unit) {
        synchronized(trackLock) {
            if (closed.get() || generation != queue.generation) return
            val queued = queue.queuedSamples()
            val outstanding = (progress.writtenFrames - progress.headFrames + queued).coerceAtLeast(0)
            drains.addLast(Drain(generation, progress.writtenAudioFrames + queued,
                System.nanoTime() + outstanding * 1_000_000_000 / 24_000 + 5_000_000_000, onDrained))
        }
    }

    private fun fail() {
        if (!closed.get() && failed.compareAndSet(false, true)) controlScope.launch {
            if (!closed.get()) failure?.invoke()
        }
    }

    private fun preparePlayback(speaker: AudioTrack) {
        // The actual native capacity can exceed the requested buffer. Its default start
        // threshold can then hold an entire short reply forever, including after a flush.
        if (Build.VERSION.SDK_INT >= 31) check(speaker.setStartThresholdInFrames(1) == 1)
    }

    @Suppress("DEPRECATION")
    private fun configureRouting() {
        check(manager.mode == AudioManager.MODE_NORMAL &&
            (Build.VERSION.SDK_INT >= 31 || !LegacyCommunicationAudioRoute.hasExistingSco(context, manager))) { "Audio is in use by another call." }
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes()).setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener({ change ->
                if (change in listOf(AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)) { focusLost = true; fail() }
            }, Handler(Looper.getMainLooper())).build()
        focus = request
        check(manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { "Audio focus is unavailable." }
        ownsFocus = true
        val previousSpeakerphone = if (Build.VERSION.SDK_INT < 31) manager.isSpeakerphoneOn else false
        manager.mode = AudioManager.MODE_IN_COMMUNICATION; ownsMode = true
        if (Build.VERSION.SDK_INT < 31) legacy = LegacyCommunicationAudioRoute(context, manager, controlScope,
            previousSpeakerphone, ::fail).also { it.start() }
        route()
        callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = reroute()
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = reroute()
            fun reroute() { controlScope.launch { if (!closed.get()) try { route() } catch (_: Exception) { fail() } } }
        }.also { manager.registerAudioDeviceCallback(it, Handler(Looper.getMainLooper())) }
    }

    private fun route() {
        if (Build.VERSION.SDK_INT >= 31) {
            val current = manager.communicationDevice
            val selected = selectCommunicationDevice(current, manager.availableCommunicationDevices,
                sameDevice = { left, right -> left.id == right.id }) { it.type }
            if (selected != null && selected.id != current?.id && manager.setCommunicationDevice(selected)) ownsRoute = true
            synchronized(trackLock) { legacyPaddingUntil = null; track?.let(::preparePlayback) }
        } else legacy?.devicesChanged()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        muted = true
        queue.interrupt(queue.generation + 1)
        try { record?.stop() } catch (_: Exception) { }
        synchronized(trackLock) {
            try { track?.pause(); track?.flush() } catch (_: Exception) { }
        }
        captureJob?.cancel(); playbackJob?.cancel()
        // Stop unblocks AudioRecord.read. Join before releasing the native object that worker owns.
        runBlocking { captureJob?.join(); playbackJob?.join() }
        try { aec?.release() } catch (_: Exception) { }
        try { suppressor?.release() } catch (_: Exception) { }
        try { record?.release() } catch (_: Exception) { }; record = null
        try { track?.release() } catch (_: Exception) { }; track = null
        try { callback?.let { manager.unregisterAudioDeviceCallback(it) } } catch (_: Exception) { }
        try {
            if (Build.VERSION.SDK_INT >= 31 && ownsRoute) manager.clearCommunicationDevice()
            else legacy?.close(restoreSpeakerphone = !focusLost)
        } catch (_: Exception) { }
        if (ownsMode) try { manager.mode = AudioManager.MODE_NORMAL } catch (_: Exception) { }
        if (ownsFocus) try { focus?.let { manager.abandonAudioFocusRequest(it) } } catch (_: Exception) { }
        focus = null; callback = null; legacy = null; drains.clear(); failure = null
        io.cancel()
    }

    private fun attributes() = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
}
