package chat.mural.network

import android.content.Context
import chat.mural.BuildConfig
import chat.mural.core.Speaker
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.ByteString

/** Small socket seam makes ordering, reconnects and late callbacks testable without a paid call. */
internal interface GeminiSocket {
    fun send(text: String): Boolean
    fun queueSize(): Long
    fun close()
    fun cancel()
}
internal interface GeminiSocketListener {
    fun opened()
    fun message(text: String)
    fun message(text: String, binary: Boolean) { message(text) }
    fun failed(httpCode: Int? = null)
    fun protocolFailed() { failed() }
    fun closed(code: Int)
}
internal fun interface GeminiSocketFactory { fun open(request: Request, listener: GeminiSocketListener): GeminiSocket }

internal class OkHttpGeminiSocketFactory(
    private val client: OkHttpClient = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS).build(),
) : GeminiSocketFactory {
    override fun open(request: Request, listener: GeminiSocketListener): GeminiSocket {
        val socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = listener.opened()
            override fun onMessage(webSocket: WebSocket, text: String) = listener.message(text, binary = false)
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                // Google also carries its JSON envelopes in binary WebSocket frames.
                // Bound bytes before allocating/decoding, and never silently replace invalid UTF-8.
                if (bytes.size > GeminiLiveProtocol.MAX_FRAME_BYTES) {
                    listener.protocolFailed()
                    return
                }
                val text = try {
                    Charsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
                } catch (_: CharacterCodingException) {
                    listener.protocolFailed()
                    return
                }
                listener.message(text, binary = true)
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                // Never forward t, response bodies, Request or authenticated URLs to the UI/logs.
                listener.failed(response?.code)
                response?.close()
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = listener.closed(code)
        })
        return object : GeminiSocket {
            override fun send(text: String) = socket.send(text)
            override fun queueSize() = socket.queueSize()
            override fun close() { socket.close(1000, null) }
            override fun cancel() = socket.cancel()
            override fun toString() = "GeminiSocket([redacted])"
        }
    }
}

/** Personal-key only. There are deliberately no hosted endpoint, reservation or receipt operations. */
internal class GeminiLiveTransport private constructor(
    private val callbackScope: CoroutineScope,
    private val sockets: GeminiSocketFactory,
    private val audioFactory: (CoroutineScope) -> GeminiAudio,
    private val configuration: GeminiLiveProtocol.Configuration,
    private val control: CoroutineDispatcher,
) {
    constructor(context: Context, scope: CoroutineScope) : this(scope, OkHttpGeminiSocketFactory(),
        { GeminiPcmAudio(context, it) }, GeminiLiveProtocol.Configuration(BuildConfig.GEMINI_LIVE_MODEL, BuildConfig.GEMINI_LIVE_VOICE), CONTROL)

    internal constructor(scope: CoroutineScope, sockets: GeminiSocketFactory, audio: () -> GeminiAudio,
        dispatcher: CoroutineDispatcher, configuration: GeminiLiveProtocol.Configuration = GeminiLiveProtocol.Configuration()) :
        this(scope, sockets, { audio() }, configuration, dispatcher)

    var onEvent: ((LiveEvent) -> Unit)? = null
    var onFailure: ((String) -> Unit)? = null
    var onLevels: ((Double, Double) -> Unit)? = null
    private val worker = CoroutineScope(SupervisorJob() + control)
    private val generation = AtomicLong()
    @Volatile private var active: Attempt? = null
    val started: Boolean get() = active?.ready == true
    val isMuted: Boolean get() = active?.muted == true

    suspend fun connect(readCredential: () -> String?, request: LiveSessionRequest) {
        require(request.offer == LiveOffer.NativePCM) { "Gemini Live requires native PCM" }
        connect(readCredential, request.instructions, request.history, request.language)
    }

    @Suppress("UNUSED_PARAMETER")
    suspend fun connect(readCredential: () -> String?, instructions: String, history: JsonArray = JsonArray(emptyList()),
        language: String? = null) = withContext(control) {
        retire()
        val attempt = Attempt(generation.incrementAndGet(), readCredential, instructions, history, audioFactory(worker))
        active = attempt
        try {
            withTimeout(20_000) { open(attempt, resumed = false) }
            requireCurrent(attempt)
            attempt.audio.start(onInput = { pcm -> sendAudio(attempt, pcm) }, onFailure = {
                worker.launch { fail(attempt, "The microphone or communication audio stopped. Check the audio route and try again.") }
            }, onLevels = { input, output -> callbackScope.launch { if (current(attempt)) onLevels?.invoke(input, output) } })
            attempt.startedAudio = true
            attempt.scopeCompletion = callbackScope.coroutineContext[Job]?.invokeOnCompletion {
                worker.launch { if (current(attempt)) retire() }
            }
            attempt.ready = true
            emit(attempt, LiveEvent.Ready)
        } catch (_: TimeoutCancellationException) {
            if (current(attempt)) retire()
            throw ConnectionException("Gemini voice setup timed out. Check your network and try again.")
        } catch (error: CancellationException) {
            if (current(attempt)) retire()
            throw error
        } catch (error: Exception) {
            if (current(attempt)) retire()
            // All network/native exceptions may contain sensitive request state; only safe text escapes.
            throw ConnectionException(attempt.openFailure ?: (error as? ConnectionException)?.message ?: "The Gemini voice connection could not start. Check microphone permission, your network and API project.")
        }
    }

    fun send(operation: LiveOperation): Boolean {
        val attempt = active ?: return false
        if (!current(attempt) || !attempt.ready || attempt.closing.get()) return false
        val content = when (operation) {
            is LiveOperation.UpdateContext -> operation.content
            is LiveOperation.RequestReply -> operation.content
            is LiveOperation.TypedTurn -> operation.text
            is LiveOperation.ToolResult -> operation.text
        }
        if (content.isBlank() || content.length > GeminiLiveProtocol.MAX_TEXT_CHARS) return false
        // Revocation/replacement and locked storage are checked again at an authorized app operation.
        if (!authorized(attempt)) { worker.launch { fail(attempt, "Your Gemini credential is unavailable or changed. Save a key and start a new conversation.") }; return false }
        worker.launch {
            if (!current(attempt) || !attempt.ready || attempt.closing.get()) return@launch
            val optionalReply = operation is LiveOperation.RequestReply || operation is LiveOperation.UpdateContext && operation.respond
            if (operation is LiveOperation.UpdateContext && !operation.respond && outputBusy(attempt)) {
                deferContext(attempt, operation.content)
                return@launch
            }
            if (optionalReply && outputBusy(attempt)) {
                // Greeting/check-in timers and teaching updates can race server turnComplete,
                // which commonly arrives before playback. Keep just the newest optional reply.
                (attempt.pendingReply as? LiveOperation.UpdateContext)?.let { deferContext(attempt, it.content) }
                attempt.pendingReply = operation
                return@launch
            }
            val message = when (operation) {
                is LiveOperation.UpdateContext -> GeminiLiveProtocol.text("Tutoring context update (do not read this metadata aloud):\n${operation.content}", operation.respond)
                is LiveOperation.RequestReply -> GeminiLiveProtocol.text(operation.content, true)
                is LiveOperation.TypedTurn -> {
                    // Completed clientContent interrupts this model. Flush playback immediately;
                    // only this single Live user turn generates the spoken answer.
                    interrupt(attempt)
                    attempt.ignoreUntilTurnComplete = attempt.modelActive
                    GeminiLiveProtocol.text(operation.text, true)
                }
                is LiveOperation.ToolResult -> {
                    if (attempt.pendingTools.remove(operation.id) != operation.name) return@launch
                    attempt.toolTimeouts.remove(operation.id)?.cancel()
                    GeminiLiveProtocol.toolResult(operation.id, operation.name, operation.text).also {
                        rememberToolResult(attempt, operation.id, it)
                    }
                }
            }
            if (!sendNow(attempt, message)) networkFailed(attempt, null)
            else if (optionalReply || operation is LiveOperation.TypedTurn) attempt.replyRequested = true
        }
        return true
    }

    fun mute(muted: Boolean) {
        val attempt = active ?: return
        attempt.muted = muted
        // Stop microphone submission synchronously, before the queued audioStreamEnd message.
        attempt.audio.mute(muted)
        worker.launch {
            if (current(attempt) && muted && attempt.ready && !sendNow(attempt, GeminiLiveProtocol.audioStreamEnd())) networkFailed(attempt, null)
        }
    }

    fun close() {
        val attempt = active ?: return
        if (!attempt.closing.compareAndSet(false, true)) return
        attempt.ready = false; attempt.audio.mute(true)
        worker.launch {
            if (!current(attempt)) return@launch
            val closingGeneration = retire()
            callbackScope.launch { if (generation.get() == closingGeneration && active == null) onEvent?.invoke(LiveEvent.Closed) }
        }
    }

    fun disconnect() {
        active?.let { it.closing.set(true); it.ready = false; it.audio.mute(true) }
        worker.launch { retire() }
    }

    /** Cross-provider barrier: release this worker's native mode before another transport owns it. */
    suspend fun awaitDisconnected() = withContext(control) { if (active != null) retire(); Unit }

    private suspend fun open(attempt: Attempt, resumed: Boolean) {
        requireCurrent(attempt)
        val credential = attempt.readCredential()?.trim()?.takeIf { it.isNotEmpty() && it.length <= 512 && it.none(Char::isWhitespace) }
            ?: throw ConnectionException("A Gemini key is required. Enter your own key in Settings.")
        val fingerprint = digest(credential)
        if (attempt.credentialFingerprint == null) attempt.credentialFingerprint = fingerprint
        else if (!MessageDigest.isEqual(attempt.credentialFingerprint, fingerprint)) throw ConnectionException("Your Gemini key changed. Start a new conversation.")
        val epoch = ++attempt.socketEpoch
        val setupDone = CompletableDeferred<Unit>()
        attempt.setupDone = setupDone
        attempt.openFailure = null
        // HttpUrl encodes reserved characters. The endpoint, path, TLS and host are not configurable.
        val url = GeminiLiveProtocol.ENDPOINT.toHttpUrl().newBuilder().addQueryParameter("key", credential).build()
        val request = Request.Builder().url(url).build()
        attempt.socket = sockets.open(request, object : GeminiSocketListener {
            override fun opened() { worker.launch {
                if (!current(attempt, epoch)) return@launch
                val setup = GeminiLiveProtocol.setup(configuration, attempt.instructions,
                    if (resumed) attempt.resumptionHandle else null, !resumed && attempt.history.isNotEmpty())
                if (!sendNow(attempt, setup, requireReady = false)) setupDone.completeExceptionally(ConnectionException("Google could not accept the voice setup."))
            } }
            override fun message(text: String) {
                if (text.length <= 64 && text.trim() == "{}") return
                if (text.length > GeminiLiveProtocol.MAX_FRAME_BYTES || !attempt.inbound.tryAcquire()) {
                    if (attempt.inboundRejected.compareAndSet(false, true)) worker.launch {
                        if (current(attempt, epoch)) fail(attempt, "Google sent an invalid or oversized voice message, or voice input exceeded its safe queue.")
                    }
                    return
                }
                worker.launch {
                try {
                if (!current(attempt, epoch)) return@launch
                for (message in GeminiLiveProtocol.decode(text)) {
                    if (!current(attempt, epoch)) break
                    if (message == GeminiLiveProtocol.Message.SetupComplete) {
                        if (!setupDone.isCompleted) {
                            if (!resumed && attempt.history.isNotEmpty()) {
                                if (!sendNow(attempt, GeminiLiveProtocol.history(attempt.history), requireReady = false)) {
                                    setupDone.completeExceptionally(ConnectionException("The Gemini conversation history could not be restored.")); continue
                                }
                                attempt.history = JsonArray(emptyList())
                            }
                            setupDone.complete(Unit)
                        }
                    } else if (message is GeminiLiveProtocol.Message.Error && !setupDone.isCompleted) {
                        attempt.openFailure = message.message
                        setupDone.completeExceptionally(ConnectionException(message.message))
                    } else if (setupDone.isCompleted && !setupDone.isCancelled) handle(attempt, message)
                    // Ordinary media arriving before setupComplete is never accepted.
                }
                } catch (_: IllegalArgumentException) {
                    fail(attempt, "Google sent a voice transcript or message that exceeded its safe limits.")
                } catch (_: IllegalStateException) {
                    fail(attempt, "The Gemini voice message could not be processed. Start a new conversation.")
                } finally { attempt.inbound.release() }
            } }
            override fun protocolFailed() { worker.launch {
                if (!current(attempt, epoch)) return@launch
                val message = "Google sent an invalid voice message. Please start a new conversation."
                if (!setupDone.isCompleted) {
                    attempt.openFailure = message
                    setupDone.completeExceptionally(ConnectionException(message))
                } else fail(attempt, message)
            } }
            override fun failed(httpCode: Int?) { worker.launch {
                if (!current(attempt, epoch)) return@launch
                if (!setupDone.isCompleted) {
                    attempt.openFailure = GeminiLiveProtocol.safeError(httpCode)
                    setupDone.completeExceptionally(ConnectionException(attempt.openFailure!!))
                } else networkFailed(attempt, httpCode)
            } }
            override fun closed(code: Int) { worker.launch {
                if (!current(attempt, epoch) || attempt.closing.get()) return@launch
                if (!setupDone.isCompleted) setupDone.completeExceptionally(ConnectionException("The Gemini voice setup closed before readiness."))
                else networkFailed(attempt, if (code == 1008) 403 else null)
            } }
        })
        setupDone.await()
        requireCurrent(attempt)
    }

    private fun handle(attempt: Attempt, message: GeminiLiveProtocol.Message) {
        when (message) {
            GeminiLiveProtocol.Message.SetupComplete -> Unit
            is GeminiLiveProtocol.Message.Audio -> {
                if (attempt.ignoreUntilTurnComplete) return
                attempt.modelActive = true
                attempt.turnReceivedAudio = true
                attempt.inputFinalizer?.cancel()
                attempt.assembler.finishInput()?.let { emitFragment(attempt, it) }
                val audioGeneration = attempt.audioGeneration
                if (!attempt.audio.enqueue(message.bytes, audioGeneration) && current(attempt) &&
                    !attempt.closing.get() && attempt.audioGeneration == audioGeneration) {
                    val failure = when {
                        attempt.audio.playbackFailed -> "The microphone or communication audio stopped. Check the audio route and try again."
                        message.bytes.isEmpty() || message.bytes.size % 2 != 0 -> "Google sent invalid voice audio."
                        message.bytes.size > attempt.audio.remainingPlaybackBytes -> "Gemini voice playback exceeded its safe buffer. Start a new conversation."
                        else -> "Gemini voice playback could not accept audio. Check the audio route and start a new conversation."
                    }
                    fail(attempt, failure)
                }
            }
            is GeminiLiveProtocol.Message.Transcription -> {
                if (message.speaker == Speaker.assistant && attempt.ignoreUntilTurnComplete) return
                val timeline = maxOf(attempt.audio.inputMilliseconds, attempt.audio.playedMilliseconds)
                val fragment = attempt.assembler.append(message, timeline)
                if (message.speaker == Speaker.assistant) attempt.modelActive = true
                // Captions are visible immediately. Assistant evidence is finalized only after
                // actual native playback, so unheard queued speech never becomes evidence.
                emitFragment(attempt, if (message.speaker == Speaker.assistant) fragment.copy(interim = true) else fragment)
                if (message.speaker == Speaker.user) {
                    attempt.inputFinalizer?.cancel()
                    if (message.finished) attempt.assembler.finishInput()
                    else if (!message.interim) attempt.inputFinalizer = worker.launch {
                        // Input transcripts are independent of output, including after an idle
                        // notification. A bounded commit avoids waiting for a tutor response.
                        delay(750)
                        if (current(attempt)) attempt.assembler.finishInput()?.let { emitFragment(attempt, it) }
                    }
                }
            }
            GeminiLiveProtocol.Message.Interrupted -> {
                // A typed barge-in already discarded the previous optional request. Its later
                // server acknowledgement must preserve the reservation for the new typed reply.
                interrupt(attempt, preserveRequestedReply = attempt.ignoreUntilTurnComplete && attempt.replyRequested)
                attempt.ignoreUntilTurnComplete = true
            }
            GeminiLiveProtocol.Message.GenerationComplete -> emit(attempt, LiveEvent.GenerationComplete)
            GeminiLiveProtocol.Message.TurnComplete -> {
                attempt.modelActive = false
                if (!attempt.ignoreUntilTurnComplete) attempt.replyRequested = false
                attempt.ignoreUntilTurnComplete = false
                attempt.inputFinalizer?.cancel()
                attempt.assembler.finishInput()?.let { emitFragment(attempt, it) }
                emit(attempt, LiveEvent.TurnComplete)
                val audioGeneration = attempt.audioGeneration
                val pendingOutput = attempt.assembler.takeOutput()
                val receivedAudio = attempt.turnReceivedAudio
                attempt.turnReceivedAudio = false
                if (receivedAudio) attempt.pendingDrains++
                attempt.audio.finishGeneration(audioGeneration) { worker.launch {
                    if (!current(attempt) || attempt.audioGeneration != audioGeneration) return@launch
                    if (receivedAudio) attempt.pendingDrains = (attempt.pendingDrains - 1).coerceAtLeast(0)
                    pendingOutput?.takeIf { receivedAudio }?.copy(interim = false,
                        endMS = if (pendingOutput.estimatedTiming) maxOf(pendingOutput.startMS,
                            maxOf(attempt.audio.inputMilliseconds, attempt.audio.playedMilliseconds)) else pendingOutput.endMS)
                        ?.let { emitFragment(attempt, it) }
                    emit(attempt, LiveEvent.PlaybackComplete)
                    flushContext(attempt)
                } }
                // A text/tool-only turn can be idle immediately without waiting for native I/O.
                if (!receivedAudio) flushContext(attempt)
            }
            GeminiLiveProtocol.Message.WaitingForInput -> { emit(attempt, LiveEvent.WaitingForInput); flushContext(attempt) }
            GeminiLiveProtocol.Message.Processing -> { attempt.modelActive = true; emit(attempt, LiveEvent.Processing) }
            is GeminiLiveProtocol.Message.VoiceActivity -> {
                if (message.started) {
                    val generating = attempt.modelActive
                    interrupt(attempt)
                    attempt.ignoreUntilTurnComplete = generating
                }
            }
            is GeminiLiveProtocol.Message.Usage -> emit(attempt, LiveEvent.Usage(message.inputTokens.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), message.outputTokens.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()))
            is GeminiLiveProtocol.Message.ToolCall -> {
                attempt.modelActive = true
                if (!attempt.seenTools.add(message.id)) {
                    val previous = attempt.completedTools[message.id]
                    if (previous?.first == attempt.socketEpoch) sendNow(attempt, previous.second)
                    else if (message.id !in attempt.pendingTools) sendNow(attempt, GeminiLiveProtocol.toolResult(message.id, message.name,
                        "This earlier helper action cannot be repeated. Ask again with a new request."))
                    return // Never replay a tool side effect.
                }
                if (attempt.seenTools.size > 256) { fail(attempt, "This Gemini conversation reached its tool limit. Start a new conversation."); return }
                val request = message.args.geminiString("request")
                if (message.name != GeminiLiveProtocol.TOOL_NAME || request.isNullOrBlank() || request.length > 4_000 || message.args.keys != setOf("request") || attempt.pendingTools.size >= 8) {
                    val response = GeminiLiveProtocol.toolResult(message.id, message.name, "This teaching request is unavailable or invalid.")
                    rememberToolResult(attempt, message.id, response); sendNow(attempt, response); return
                }
                attempt.pendingTools[message.id] = message.name
                attempt.toolTimeouts[message.id] = worker.launch {
                    delay(25_000)
                    if (current(attempt) && attempt.pendingTools.remove(message.id) != null) {
                        emit(attempt, LiveEvent.ToolCancelled(listOf(message.id)))
                        val response = GeminiLiveProtocol.toolResult(message.id, message.name, "The teaching helper timed out. Continue with a short question without this result.")
                        rememberToolResult(attempt, message.id, response); sendNow(attempt, response)
                    }
                    attempt.toolTimeouts.remove(message.id)
                }
                emit(attempt, LiveEvent.ToolCall(message.id, message.name, message.args))
            }
            is GeminiLiveProtocol.Message.ToolCancelled -> {
                message.ids.forEach { attempt.pendingTools.remove(it); attempt.toolTimeouts.remove(it)?.cancel() }
                emit(attempt, LiveEvent.ToolCancelled(message.ids))
            }
            is GeminiLiveProtocol.Message.Resumption -> {
                attempt.resumable = message.resumable
                if (message.handle != null) { attempt.resumptionHandle = message.handle; attempt.handleAtNanos = System.nanoTime() }
            }
            is GeminiLiveProtocol.Message.GoAway -> {
                attempt.goAway?.cancel()
                attempt.goAway = worker.launch {
                    delay((message.timeLeftMS - 2_000).coerceAtLeast(0))
                    if (current(attempt)) networkFailed(attempt, null)
                }
            }
            is GeminiLiveProtocol.Message.Error -> fail(attempt, message.message)
        }
    }

    private fun interrupt(attempt: Attempt, preserveRequestedReply: Boolean = false) {
        attempt.audioGeneration++
        attempt.audio.interrupt(attempt.audioGeneration)
        attempt.assembler.interrupt()
        attempt.turnReceivedAudio = false; attempt.pendingDrains = 0
        if (!preserveRequestedReply) { attempt.replyRequested = false; attempt.pendingReply = null }
        emit(attempt, LiveEvent.Interrupted)
    }

    private fun outputBusy(attempt: Attempt) = attempt.replyRequested || attempt.modelActive ||
        attempt.turnReceivedAudio || attempt.pendingDrains > 0 || attempt.audio.queuedMilliseconds > 0 || attempt.audio.playbackBacklogged

    private fun deferContext(attempt: Attempt, content: String) {
        attempt.pendingContext = ((attempt.pendingContext?.plus("\n") ?: "") + content)
            .takeLast(GeminiLiveProtocol.MAX_TEXT_CHARS - 100)
    }

    private fun flushContext(attempt: Attempt) {
        if (!attempt.ready || outputBusy(attempt)) return
        if (attempt.pendingContext == null && attempt.pendingReply == null) return
        if (!authorized(attempt)) {
            fail(attempt, "Your Gemini credential is unavailable or changed. Save a key and start a new conversation."); return
        }
        attempt.pendingContext?.let { context ->
            attempt.pendingContext = null
            if (!sendNow(attempt, GeminiLiveProtocol.text("Tutoring context update (do not read aloud):\n$context", false))) {
                networkFailed(attempt, null); return
            }
        }
        val operation = attempt.pendingReply ?: return
        attempt.pendingReply = null
        val text = when (operation) {
            is LiveOperation.RequestReply -> operation.content
            is LiveOperation.UpdateContext -> "Tutoring context update (do not read this metadata aloud):\n${operation.content}"
            else -> return
        }
        if (!sendNow(attempt, GeminiLiveProtocol.text(text, true))) networkFailed(attempt, null)
        else attempt.replyRequested = true
    }

    private fun rememberToolResult(attempt: Attempt, id: String, response: JsonObject) {
        attempt.completedTools[id] = attempt.socketEpoch to response
        while (attempt.completedTools.size > 16) attempt.completedTools.remove(attempt.completedTools.keys.first())
    }

    private fun networkFailed(attempt: Attempt, code: Int?) {
        if (!current(attempt) || attempt.closing.get() || attempt.reconnect?.isActive == true) return
        attempt.ready = false
        if (code in listOf(400, 401, 403, 404, 429)) { fail(attempt, GeminiLiveProtocol.safeError(code)); return }
        if (!attempt.resumable || attempt.resumptionHandle == null || System.nanoTime() - attempt.handleAtNanos > TimeUnit.HOURS.toNanos(2)) {
            fail(attempt, "Gemini disconnected without a usable resumption state. Your local conversation is saved; start a new conversation to continue."); return
        }
        attempt.socketEpoch++ // Reject queued callbacks from the retired socket immediately.
        emit(attempt, LiveEvent.Reconnecting)
        interrupt(attempt)
        attempt.inputFinalizer?.cancel()
        attempt.assembler.resumed(maxOf(attempt.audio.inputMilliseconds, attempt.audio.playedMilliseconds))
        attempt.reconnect = worker.launch {
            attempt.goAway?.cancel(); attempt.goAway = null
            // Cancel pending tool work; neither calls nor results are replayed after a gap.
            val pendingIDs = attempt.pendingTools.keys.toList()
            attempt.pendingTools.clear(); attempt.toolTimeouts.values.forEach { it.cancel() }; attempt.toolTimeouts.clear()
            if (pendingIDs.isNotEmpty()) emit(attempt, LiveEvent.ToolCancelled(pendingIDs))
            var recovered = false
            for (backoff in listOf(250L, 750L, 1_500L)) {
                if (!current(attempt) || attempt.closing.get()) return@launch
                attempt.socketEpoch++; attempt.socket?.cancel(); attempt.socket = null
                delay(backoff)
                try {
                    withTimeout(10_000) { open(attempt, resumed = true) }
                    requireCurrent(attempt)
                    recovered = true; break
                } catch (error: CancellationException) {
                    if (error !is TimeoutCancellationException) throw error
                } catch (_: Exception) {
                    if (attempt.openFailure?.contains("key", ignoreCase = true) == true || attempt.openFailure?.contains("configuration", ignoreCase = true) == true) break
                }
            }
            if (recovered) {
                attempt.ignoreUntilTurnComplete = false; attempt.modelActive = false; attempt.replyRequested = false; attempt.ready = true
                emit(attempt, LiveEvent.Resumed) // No Ready, history, greeting or microphone replay.
            } else fail(attempt, attempt.openFailure ?: "Gemini could not resume this connection. Your local conversation is saved; start a new conversation to continue.")
        }
    }

    private fun sendAudio(attempt: Attempt, pcm: ByteArray): Boolean {
        if (!current(attempt) || attempt.closing.get()) return true
        if (!attempt.ready || attempt.muted) return true // Explicit recoverable gap, never replay captured PCM.
        val socket = attempt.socket ?: return true
        if (socket.queueSize() > MAX_SEND_QUEUE_BYTES) {
            worker.launch { fail(attempt, "The network cannot keep up with microphone audio. Check your connection and try again.") }
            return false
        }
        if (!socket.send(GeminiLiveProtocol.audio(pcm).toString())) {
            // A broken network is a transport gap, not an AudioRecord failure. Keep capture
            // alive during bounded resumption and discard uncertain media instead of replaying it.
            worker.launch { networkFailed(attempt, null) }
        }
        return true
    }

    private fun sendNow(attempt: Attempt, message: JsonObject, requireReady: Boolean = true): Boolean {
        if (!current(attempt) || attempt.closing.get() || (requireReady && !attempt.setupDone.isCompleted)) return false
        val socket = attempt.socket ?: return false
        return socket.queueSize() <= MAX_SEND_QUEUE_BYTES && socket.send(message.toString())
    }

    private fun authorized(attempt: Attempt): Boolean {
        val credential = try { attempt.readCredential() } catch (_: Exception) { null } ?: return false
        return attempt.credentialFingerprint?.let { MessageDigest.isEqual(it, digest(credential.trim())) } == true
    }

    private fun emitFragment(attempt: Attempt, fragment: GeminiTranscriptAssembler.Fragment) = emit(attempt,
        LiveEvent.Transcript(fragment.id, fragment.speaker, fragment.text, fragment.startMS.coerceIn(0, Int.MAX_VALUE.toLong()).toInt(),
            fragment.endMS.coerceIn(0, Int.MAX_VALUE.toLong()).toInt(), fragment.interim, fragment.estimatedTiming))
    private fun emit(attempt: Attempt, event: LiveEvent) { callbackScope.launch { if (current(attempt) && !attempt.closing.get()) onEvent?.invoke(event) } }
    private fun current(attempt: Attempt, epoch: Long? = null) = active === attempt && generation.get() == attempt.id && !attempt.cleaned.get() && (epoch == null || attempt.socketEpoch == epoch)
    private fun requireCurrent(attempt: Attempt) { if (!current(attempt) || attempt.closing.get()) throw CancellationException("Voice connection superseded") }

    private fun fail(attempt: Attempt, message: String) {
        if (!current(attempt) || attempt.closing.get() || !attempt.failed.compareAndSet(false, true)) return
        // This function is always on the independent audio control worker; cleanup may join native I/O.
        val failureGeneration = retire()
        callbackScope.launch { if (generation.get() == failureGeneration && active == null) onFailure?.invoke(message) }
    }

    private fun retire(): Long {
        val attempt = active
        active = null
        val next = generation.incrementAndGet()
        if (attempt != null && attempt.cleaned.compareAndSet(false, true)) {
            attempt.closing.set(true); attempt.ready = false; attempt.socketEpoch++
            attempt.scopeCompletion?.dispose(); attempt.goAway?.cancel(); attempt.reconnect?.cancel(); attempt.inputFinalizer?.cancel()
            attempt.toolTimeouts.values.forEach { it.cancel() }; attempt.toolTimeouts.clear(); attempt.pendingTools.clear()
            attempt.setupDone.cancel(); attempt.socket?.close(); attempt.socket?.cancel(); attempt.socket = null
            attempt.audio.close(); attempt.resumptionHandle = null; attempt.credentialFingerprint?.fill(0); attempt.credentialFingerprint = null
            attempt.history = JsonArray(emptyList())
            attempt.pendingContext = null; attempt.pendingReply = null
        }
        callbackScope.launch { if (generation.get() == next) onLevels?.invoke(0.0, 0.0) }
        return next
    }

    private class Attempt(val id: Long, val readCredential: () -> String?, val instructions: String, var history: JsonArray, val audio: GeminiAudio) {
        val cleaned = AtomicBoolean(); val closing = AtomicBoolean(); val failed = AtomicBoolean()
        @Volatile var ready = false
        @Volatile var muted = false
        @Volatile var socket: GeminiSocket? = null
        @Volatile var socketEpoch = 0L
        val inbound = Semaphore(256)
        val inboundRejected = AtomicBoolean()
        var setupDone = CompletableDeferred<Unit>()
        var scopeCompletion: DisposableHandle? = null
        var startedAudio = false
        var credentialFingerprint: ByteArray? = null
        var openFailure: String? = null
        var audioGeneration = 0L
        var ignoreUntilTurnComplete = false
        var modelActive = false
        var replyRequested = false
        var pendingDrains = 0
        var pendingContext: String? = null
        var pendingReply: LiveOperation? = null
        var turnReceivedAudio = false
        var inputFinalizer: Job? = null
        val assembler = GeminiTranscriptAssembler()
        var resumptionHandle: String? = null
        var resumable = false
        var handleAtNanos = 0L
        var goAway: Job? = null
        var reconnect: Job? = null
        val seenTools = mutableSetOf<String>()
        val pendingTools = mutableMapOf<String, String>()
        val toolTimeouts = mutableMapOf<String, Job>()
        val completedTools = linkedMapOf<String, Pair<Long, JsonObject>>()
        override fun toString() = "GeminiVoiceAttempt([redacted])"
    }

    class ConnectionException(message: String) : Exception(message)
    companion object {
        private val CONTROL = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "mural-gemini-control").apply { isDaemon = true } }.asCoroutineDispatcher()
        private const val MAX_SEND_QUEUE_BYTES = 65_536L
        private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    }
}
