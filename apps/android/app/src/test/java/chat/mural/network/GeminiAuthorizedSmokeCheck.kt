package chat.mural.network

import chat.mural.BuildConfig
import chat.mural.core.LanguageRegistry
import chat.mural.core.LearnerState
import chat.mural.core.Speaker
import chat.mural.core.TeachingPolicy
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.system.exitProcess
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Explicit, separately authorized provider check. There are deliberately no test annotations.
 * Launch with a terminal, optionally with --meaning-only. Credentials use only its hidden console
 * prompt; the process exits within 75 seconds of reading them. Output contains measurements only.
 * Synthetic audio consumes received PCM without opening a microphone or speaker.
 */
object GeminiAuthorizedSmokeCheck {
    @JvmStatic fun main(args: Array<String>) {
        val meaningOnly = when {
            args.isEmpty() -> false
            args.size == 1 && args[0] == "--meaning-only" -> true
            else -> stop("arguments")
        }
        val console = System.console() ?: stop("console_required")
        val credential = try { console.readPassword("Gemini key (hidden): ") } catch (_: Exception) { null }
            ?: stop("console_input")
        if (credential.isEmpty() || credential.size > 512 || credential.any(Char::isWhitespace)) {
            credential.fill('\u0000')
            stop("credential_format")
        }
        val deadline = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "gemini-smoke-deadline").apply { isDaemon = true }
        }
        deadline.schedule({
            credential.fill('\u0000')
            stop("deadline")
        }, 75, TimeUnit.SECONDS)
        var exitCode = 1
        try {
            val measurements = runBlocking { withTimeout(70_000) { runCheck(credential, meaningOnly) } }
            println(measurements)
            exitCode = 0
        } catch (error: Throwable) {
            println(buildJsonObject {
                put("ok", false)
                put("error_type", safeType(error))
                if (error is GeminiException.Http) put("http_status", error.status)
            })
        } finally {
            credential.fill('\u0000')
            deadline.shutdownNow()
        }
        // Provider clients use pooled worker threads; do not leave credential-bearing JVM state alive.
        exitProcess(exitCode)
    }

    private suspend fun runCheck(credential: CharArray, meaningOnly: Boolean): String {
        if (meaningOnly) return checkMeaning(credential, liveChecked = false)
        val control = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "gemini-smoke-control").apply { isDaemon = true }
        }.asCoroutineDispatcher()
        val callbacks = CoroutineScope(SupervisorJob() + control)
        val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(20, TimeUnit.SECONDS).build()
        val counters = Counters()
        val audio = CountingAudio()
        val events = Channel<Signal>(Channel.UNLIMITED)
        val transport = GeminiLiveTransport(callbacks, CountingFactory(OkHttpGeminiSocketFactory(client), counters),
            { audio }, control, GeminiLiveProtocol.Configuration(BuildConfig.GEMINI_LIVE_MODEL, BuildConfig.GEMINI_LIVE_VOICE))
        transport.onFailure = { events.trySend(Signal.Failed) }
        transport.onEvent = { event ->
            when (event) {
                LiveEvent.Ready -> events.trySend(Signal.Ready)
                LiveEvent.PlaybackComplete -> events.trySend(Signal.Drained)
                LiveEvent.Reconnecting, is LiveEvent.Failure -> events.trySend(Signal.Failed)
                is LiveEvent.Transcript -> if (event.speaker == Speaker.assistant) {
                    counters.transcripts.incrementAndGet()
                    if (Regex("\\bwent\\b", RegexOption.IGNORE_CASE).containsMatchIn(event.text)) counters.corrected.set(true)
                }
                is LiveEvent.ToolCall -> {
                    counters.toolCalls.incrementAndGet()
                    events.trySend(Signal.UnexpectedTool)
                }
                is LiveEvent.Usage -> {
                    counters.inputTokens.set(event.inputTokens.toLong())
                    counters.outputTokens.set(event.outputTokens.toLong())
                }
                else -> Unit
            }
        }
        try {
            val english = LanguageRegistry.get("en") ?: throw SmokeFailure("language_configuration")
            val readCredential = { String(credential) }
            val instructions = TeachingPolicy.voice(english, LearnerState(0, 0, "", emptyList(), emptyList()), null, "", "Arabic")
            transport.connect(readCredential, instructions, language = english.id)
            await(events, Signal.Ready)
            val initialTranscripts = counters.transcripts.get()
            if (!transport.send(LiveOperation.RequestReply(TeachingPolicy.greeting(english)))) throw SmokeFailure("greeting_send")
            await(events, Signal.Drained)
            val greetingBytes = audio.receivedBytes.get()
            val greetingTranscript = counters.transcripts.get() > initialTranscripts
            if (greetingBytes == 0L || !greetingTranscript) throw SmokeFailure("greeting_incomplete")

            counters.corrected.set(false)
            val beforeTyped = counters.transcripts.get()
            if (!transport.send(LiveOperation.TypedTurn("I goed to the cafe yesterday."))) throw SmokeFailure("typed_send")
            await(events, Signal.Drained)
            val typedBytes = audio.receivedBytes.get() - greetingBytes
            val typedTranscript = counters.transcripts.get() > beforeTyped
            if (typedBytes == 0L || !typedTranscript) throw SmokeFailure("typed_incomplete")
            // Publish completed Live evidence before the separately bounded meaning request.
            transport.disconnect()
            transport.awaitDisconnected()
            println(buildJsonObject {
                put("live_ok", true)
                put("setup_ready", true)
                put("greeting_audio_bytes", greetingBytes)
                put("greeting_output_transcript", greetingTranscript)
                put("typed_audio_bytes", typedBytes)
                put("typed_output_transcript", typedTranscript)
                put("typed_correction_detected", counters.corrected.get())
                put("binary_frames", counters.binaryFrames.get())
                put("text_frames", counters.textFrames.get())
                put("outbound_frames", counters.outboundFrames.get())
                put("connections", counters.connections.get())
                put("audio_chunks", audio.chunks.get())
                put("synthetic_drains", audio.drains.get())
                put("microphone_used", false)
                put("speaker_used", false)
                put("tool_calls", counters.toolCalls.get())
                put("live_input_tokens", counters.inputTokens.get())
                put("live_output_tokens", counters.outputTokens.get())
            })
            return checkMeaning(credential, liveChecked = true)
        } finally {
            withContext(NonCancellable) {
                withTimeoutOrNull(4_000) {
                    transport.disconnect()
                    transport.awaitDisconnected()
                }
            }
            callbacks.cancel()
            events.close()
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdownNow()
            control.close()
        }
    }

    private suspend fun checkMeaning(credential: CharArray, liveChecked: Boolean): String {
        val english = LanguageRegistry.get("en") ?: throw SmokeFailure("language_configuration")
        val client = GeminiTeachingClient({ String(credential) }, BuildConfig.GEMINI_TEACHING_MODEL)
        var attempts = 0
        var chunks = 0
        suspend fun request(): APIResult {
            attempts++
            chunks = 0
            return client.streamMeaning(TeachingPolicy.translation(english, "Arabic"),
                "I went to the cafe yesterday.") { chunks++ }
        }
        val meaning = try {
            request()
        } catch (error: GeminiException.Http) {
            if (error.status != 503) throw error
            println(buildJsonObject { put("meaning_retry", true); put("http_status", 503) })
            delay(1_000)
            request()
        }
        val arabic = meaning.text.any { it.isLetter() && Character.UnicodeScript.of(it.code) == Character.UnicodeScript.ARABIC }
        if (meaning.text.isBlank() || !arabic) throw SmokeFailure("meaning_incomplete")
        return buildJsonObject {
            put("ok", true)
            put("live_checked", liveChecked)
            put("meaning_ok", true)
            put("meaning_attempts", attempts)
            put("meaning_stream_chunks", chunks)
            put("meaning_characters", meaning.text.length)
            put("meaning_contains_arabic", arabic)
        }.toString()
    }

    private suspend fun await(events: Channel<Signal>, expected: Signal) {
        when (events.receive()) {
            expected -> Unit
            Signal.UnexpectedTool -> throw SmokeFailure("unexpected_tool_call")
            Signal.Failed -> throw SmokeFailure("voice_connection")
            else -> throw SmokeFailure("unexpected_event")
        }
    }

    private class CountingFactory(private val delegate: GeminiSocketFactory, private val counters: Counters) : GeminiSocketFactory {
        override fun open(request: Request, listener: GeminiSocketListener): GeminiSocket {
            counters.connections.incrementAndGet()
            val socket = delegate.open(request, object : GeminiSocketListener {
                override fun opened() = listener.opened()
                override fun message(text: String) = listener.message(text)
                override fun message(text: String, binary: Boolean) {
                    if (binary) counters.binaryFrames.incrementAndGet() else counters.textFrames.incrementAndGet()
                    listener.message(text, binary)
                }
                override fun failed(httpCode: Int?) = listener.failed(httpCode)
                override fun protocolFailed() = listener.protocolFailed()
                override fun closed(code: Int) = listener.closed(code)
            })
            return object : GeminiSocket {
                override fun send(text: String): Boolean = socket.send(text).also { if (it) counters.outboundFrames.incrementAndGet() }
                override fun queueSize() = socket.queueSize()
                override fun close() = socket.close()
                override fun cancel() = socket.cancel()
            }
        }
    }

    private class CountingAudio : GeminiAudio {
        val receivedBytes = AtomicLong()
        val chunks = AtomicLong()
        val drains = AtomicLong()
        private val pending = AtomicLong()
        private val drained = AtomicLong()
        private val closed = AtomicBoolean()
        override val inputMilliseconds = 0L
        override val playedMilliseconds: Long get() = drained.get() / 48
        override fun start(onInput: (ByteArray) -> Boolean, onFailure: () -> Unit, onLevels: (Double, Double) -> Unit) = Unit
        override fun mute(value: Boolean) = Unit
        override fun enqueue(pcm16: ByteArray, generation: Long): Boolean {
            if (closed.get()) return false
            receivedBytes.addAndGet(pcm16.size.toLong())
            pending.addAndGet(pcm16.size.toLong())
            chunks.incrementAndGet()
            return true
        }
        override fun interrupt(generation: Long) { pending.set(0) }
        override fun finishGeneration(generation: Long, onDrained: () -> Unit) {
            drained.addAndGet(pending.getAndSet(0))
            drains.incrementAndGet()
            onDrained()
        }
        override fun close() { closed.set(true); pending.set(0) }
    }

    private class Counters {
        val binaryFrames = AtomicLong()
        val textFrames = AtomicLong()
        val outboundFrames = AtomicLong()
        val connections = AtomicLong()
        val transcripts = AtomicLong()
        val inputTokens = AtomicLong()
        val outputTokens = AtomicLong()
        val toolCalls = AtomicLong()
        val corrected = AtomicBoolean()
    }

    private enum class Signal { Ready, Drained, Failed, UnexpectedTool }
    private class SmokeFailure(val type: String) : Exception()
    private fun safeType(error: Throwable): String = when (error) {
        is SmokeFailure -> error.type
        is TimeoutCancellationException -> "timeout"
        is CancellationException -> "cancelled"
        is GeminiException.Http -> "gemini_http"
        is GeminiException -> "gemini_response"
        is GeminiLiveTransport.ConnectionException -> "voice_connection"
        else -> "runtime"
    }
    private fun stop(type: String): Nothing {
        println(buildJsonObject { put("ok", false); put("error_type", type) })
        exitProcess(1)
    }
}
