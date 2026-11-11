package chat.mural.network

import chat.mural.core.Speaker
import java.util.Base64
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GeminiLiveTransportTest {
    @Test fun setupIsFirstAndOnlyMessageUntilSetupComplete() = runTest {
        val h = Harness(this)
        val connecting = async { h.transport.connect({ h.key }, "English policy") }
        runCurrent()
        h.socket.open()
        runCurrent()
        assertEquals(1, h.socket.sent.size)
        assertTrue(h.socket.sent.single().contains("\"setup\""))
        assertFalse(h.transport.send(LiveOperation.RequestReply("Greet once.")))
        h.socket.receive(AUDIO)
        runCurrent()
        assertEquals(0, h.audio.enqueued)
        assertEquals(0, h.audio.starts)
        h.socket.receive(SETUP)
        runCurrent(); connecting.await()
        assertEquals(1, h.audio.starts)
        assertEquals(listOf(LiveEvent.Ready), h.events)
        h.stop()
    }

    @Test fun nativeAuthHasFixedGoogleEndpointAndEncodedQuery() = runTest {
        val h = Harness(this)
        h.key = "placeholder/+?&="
        h.ready()
        val request = h.sockets.requests.single()
        assertEquals("generativelanguage.googleapis.com", request.url.host)
        assertEquals("https", request.url.scheme)
        assertEquals(h.key, request.url.queryParameter("key"))
        assertEquals(1, request.url.querySize)
        assertFalse(request.url.encodedQuery!!.contains("placeholder/+?&="))
        h.stop()
    }

    @Test fun greetingAndTypedInputEachSendOneUserTurn() = runTest {
        val h = Harness(this); h.ready()
        assertTrue(h.transport.send(LiveOperation.RequestReply("Give the greeting now.")))
        runCurrent()
        assertTrue(h.transport.send(LiveOperation.TypedTurn("I goed yesterday.")))
        runCurrent()
        val turns = h.socket.sent.map { Json.parseToJsonElement(it).jsonObject }.filter { it.containsKey("clientContent") }
        assertEquals(2, turns.size)
        assertTrue(turns.all { it["clientContent"]!!.jsonObject["turnComplete"]!!.jsonPrimitive.boolean })
        assertEquals(1, h.audio.interruptions)
        h.stop()
    }

    @Test fun historyIsSentOnceAfterSetupAndNeverOnResumption() = runTest {
        val h = Harness(this)
        val history = Json.parseToJsonElement("""[{"role":"assistant","content":"Hello"}]""").jsonArray
        h.ready(history)
        assertEquals(2, h.socket.sent.size)
        h.socket.receive(RESUMABLE)
        runCurrent()
        val old = h.socket
        old.listener.failed()
        runCurrent(); advanceTimeBy(250); runCurrent()
        assertEquals(2, h.sockets.values.size)
        h.socket.open(); runCurrent(); h.socket.receive(SETUP); runCurrent()
        assertEquals(1, h.socket.sent.size)
        assertTrue(h.socket.sent.single().contains("handle-placeholder"))
        assertEquals(1, h.events.count { it == LiveEvent.Ready })
        assertEquals(1, h.events.count { it == LiveEvent.Resumed })
        assertFalse(h.socket.sent.any { it.contains("Hello") })
        old.receive("{"); runCurrent() // Late old-connection callbacks cannot kill the resumed owner.
        assertTrue(h.failures.isEmpty())
        h.stop()
    }

    @Test fun outputBecomesFinalEvidenceOnlyAfterNativePlaybackDrains() = runTest {
        val h = Harness(this); h.ready()
        h.audio.inputMilliseconds = 2_000
        h.socket.receive("""{"serverContent":{"outputTranscription":{"text":"Hello learner."},"modelTurn":{"parts":[{"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"AAA="}}]},"generationComplete":true,"turnComplete":true}}""")
        runCurrent()
        assertTrue(h.events.filterIsInstance<LiveEvent.Transcript>().all { it.interim })
        assertTrue(h.events.contains(LiveEvent.GenerationComplete))
        assertFalse(h.events.contains(LiveEvent.PlaybackComplete))
        h.audio.drain(); runCurrent()
        assertEquals("Hello learner.", h.events.filterIsInstance<LiveEvent.Transcript>().single { !it.interim }.text)
        assertTrue(h.events.contains(LiveEvent.PlaybackComplete))
        h.stop()
    }

    @Test fun interruptionFlushesPendingEvidenceAndLateChunksUntilTurnBoundary() = runTest {
        val h = Harness(this); h.ready()
        h.socket.receive("""{"serverContent":{"outputTranscription":{"text":"Unheard queued speech"},"modelTurn":{"parts":[{"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"AAA="}}]},"turnComplete":true}}""")
        runCurrent()
        h.socket.receive("""{"serverContent":{"interrupted":true}}"""); runCurrent()
        h.socket.receive(AUDIO); runCurrent()
        assertEquals(1, h.audio.enqueued)
        h.audio.drain(); runCurrent()
        assertFalse(h.events.filterIsInstance<LiveEvent.Transcript>().any { !it.interim && it.speaker == Speaker.assistant })
        h.socket.receive("""{"serverContent":{"turnComplete":true}}"""); runCurrent()
        h.socket.receive(AUDIO); runCurrent()
        assertEquals(2, h.audio.enqueued)
        h.stop()
    }

    @Test fun muteFlushesAutomaticVadAndLaterPcmReopensStream() = runTest {
        val h = Harness(this); h.ready()
        h.transport.mute(true); runCurrent()
        assertTrue(h.audio.muted)
        assertTrue(h.socket.sent.last().contains("audioStreamEnd"))
        val count = h.socket.sent.size
        h.audio.input(ByteArray(640)); runCurrent()
        assertEquals(count, h.socket.sent.size)
        h.transport.mute(false); runCurrent()
        h.audio.input(ByteArray(640)); runCurrent()
        assertTrue(h.socket.sent.last().contains("audio/pcm;rate=16000"))
        assertFalse(h.socket.sent.any { it.contains("activityStart") || it.contains("activityEnd") })
        h.stop()
    }

    @Test fun malformedOrOversizedFrameClosesOnceAndRedactsErrors() = runTest {
        val h = Harness(this); h.ready()
        h.socket.receive(" ".repeat(GeminiLiveProtocol.MAX_FRAME_BYTES + 1)); runCurrent()
        assertEquals(1, h.failures.size)
        assertEquals(1, h.audio.closes)
        h.socket.receive("{"); h.transport.disconnect(); h.transport.disconnect(); runCurrent()
        assertEquals(1, h.audio.closes)
        assertFalse(h.failures.single().contains(h.key!!))
    }

    @Test fun setupFailureCancellationAndLateCallbackReleaseSingleOwner() = runTest {
        val h = Harness(this)
        val connecting = launch { h.transport.connect({ h.key }, "Policy") }
        runCurrent()
        connecting.cancelAndJoin(); runCurrent()
        h.socket.open(); h.socket.receive(SETUP); runCurrent()
        assertEquals(0, h.audio.starts)
        assertEquals(1, h.audio.closes)
        assertFalse(h.transport.started)
        assertTrue(h.events.isEmpty())
        assertEquals(1, h.socket.cancels)
    }

    @Test fun setupAuthenticationFailureIsSanitizedAndDoesNotRetry() = runTest {
        val h = Harness(this)
        val connecting = async { runCatching { h.transport.connect({ h.key }, "Policy") } }
        runCurrent(); h.socket.open(); runCurrent()
        h.socket.receive("""{"error":{"code":401,"message":"private provider body","status":"UNAUTHENTICATED"}}""")
        runCurrent()
        val error = connecting.await().exceptionOrNull()!!
        assertTrue(error.message!!.contains("authenticate"))
        assertFalse(error.message!!.contains("private provider body"))
        assertEquals(1, h.audio.closes)
        assertEquals(1, h.sockets.values.size)
    }

    @Test fun credentialDeletionAndReplacementPreventAuthorizedOperations() = runTest {
        val h = Harness(this); h.ready()
        h.key = null
        assertFalse(h.transport.send(LiveOperation.TypedTurn("hello")))
        runCurrent()
        assertEquals(1, h.audio.closes)
        assertEquals(1, h.failures.size)
        val other = Harness(this); other.ready()
        other.key = "replacement-placeholder"
        assertFalse(other.transport.send(LiveOperation.RequestReply("hello")))
        runCurrent(); assertEquals(1, other.audio.closes)
    }

    @Test fun nonresumableConnectionDoesNotCreateNewSessionOrReplayAudio() = runTest {
        val h = Harness(this); h.ready()
        h.socket.receive("""{"sessionResumptionUpdate":{"resumable":false}}"""); runCurrent()
        h.socket.listener.failed(); runCurrent()
        assertEquals(1, h.sockets.values.size)
        assertTrue(h.failures.single().contains("without a usable resumption"))
        assertEquals(1, h.audio.closes)
    }

    @Test fun goAwayResumesWithoutReplayingUnacknowledgedPcm() = runTest {
        val h = Harness(this); h.ready()
        h.audio.input(ByteArray(640))
        h.socket.receive(RESUMABLE)
        h.socket.receive("""{"goAway":{"timeLeft":"3s"}}""")
        runCurrent(); advanceTimeBy(1_000); runCurrent()
        assertTrue(h.events.contains(LiveEvent.Reconnecting))
        h.audio.input(ByteArray(640))
        advanceTimeBy(250); runCurrent()
        h.socket.open(); runCurrent(); h.socket.receive(SETUP); runCurrent()
        assertFalse(h.socket.sent.any { it.contains("\"audio\"") })
        h.stop()
    }

    @Test fun duplicateToolRunsOnceAndReplaysOnlyCachedResponse() = runTest {
        val h = Harness(this); h.ready()
        val tool = """{"toolCall":{"functionCalls":[{"id":"tool-a","name":"mural_teaching_help","args":{"request":"Explain went"}}]}}"""
        h.socket.receive(tool); h.socket.receive(tool); runCurrent()
        assertEquals(1, h.events.filterIsInstance<LiveEvent.ToolCall>().size)
        assertTrue(h.transport.send(LiveOperation.ToolResult("tool-a", "mural_teaching_help", "Past tense of go.")))
        runCurrent()
        h.socket.receive(tool); runCurrent()
        assertEquals(1, h.events.filterIsInstance<LiveEvent.ToolCall>().size)
        assertEquals(2, h.socket.sent.count { it.contains("functionResponses") })
        h.stop()
    }

    @Test fun toolCancellationAndTimeoutRejectLateResults() = runTest {
        val h = Harness(this); h.ready()
        h.socket.receive("""{"toolCall":{"functionCalls":[{"id":"a","name":"mural_teaching_help","args":{"request":"Explain"}},{"id":"b","name":"mural_teaching_help","args":{"request":"Explain again"}}]}}""")
        runCurrent()
        h.socket.receive("""{"toolCallCancellation":{"ids":["a"]}}"""); runCurrent()
        h.transport.send(LiveOperation.ToolResult("a", "mural_teaching_help", "late")); runCurrent()
        assertFalse(h.socket.sent.any { it.contains("\"late\"") })
        advanceTimeBy(25_000); runCurrent()
        assertTrue(h.socket.sent.any { it.contains("timed out") })
        h.stop()
    }

    @Test fun committedInputCanFinalizeAfterIdleWithoutTutorAudio() = runTest {
        val h = Harness(this); h.ready()
        h.socket.receive("""{"serverContent":{"turnComplete":true,"interactionStatus":"IDLE"}}""")
        h.socket.receive("""{"serverContent":{"inputTranscription":{"text":"أنا goed yesterday"}}}""")
        runCurrent(); advanceTimeBy(750); runCurrent()
        val input = h.events.filterIsInstance<LiveEvent.Transcript>().last { it.speaker == Speaker.user }
        assertEquals("أنا goed yesterday", input.text)
        assertFalse(input.interim)
        assertTrue(input.estimatedTiming)
        h.stop()
    }

    @Test fun sendBackpressureAndDisconnectBarrierReleaseResources() = runTest {
        val h = Harness(this); h.ready()
        h.socket.queuedBytes = 65_537
        h.audio.input(ByteArray(640)); runCurrent()
        assertEquals(1, h.audio.closes)
        assertEquals(1, h.failures.size)
        h.transport.disconnect(); h.transport.awaitDisconnected(); runCurrent()
        assertFalse(h.transport.started)
        assertEquals(1, h.audio.closes)
    }

    @Test fun setupTimeoutFailsWithUsefulErrorAndReleasesInsteadOfRemainingConnecting() = runTest {
        val h = Harness(this)
        val connecting = async { runCatching { h.transport.connect({ h.key }, "Policy") } }
        runCurrent(); h.socket.open(); runCurrent()
        advanceTimeBy(20_001); runCurrent()
        assertTrue(connecting.await().exceptionOrNull() is GeminiLiveTransport.ConnectionException)
        assertEquals(1, h.audio.closes); assertFalse(h.transport.started)
        h.socket.receive(SETUP); runCurrent(); assertEquals(0, h.audio.starts)
        h.stop()
    }

    @Test fun incomingQueueIsBoundedBeforeProtocolWorkAndClosesOnlyOnce() = runTest {
        val h = Harness(this); h.ready()
        repeat(300) { h.socket.receive(AUDIO) }
        runCurrent()
        assertEquals(1, h.audio.closes); assertEquals(1, h.failures.size)
        assertFalse(h.transport.started); h.stop()
    }

    @Test fun typedBargeInDropsOldGenerationUntilItsTurnBoundaryThenAcceptsReply() = runTest {
        val h = Harness(this); h.ready()
        h.socket.receive(AUDIO); runCurrent()
        h.transport.send(LiveOperation.TypedTurn("I goed.")); runCurrent()
        h.socket.receive(AUDIO); runCurrent()
        assertEquals(1, h.audio.enqueued)
        h.socket.receive("""{"serverContent":{"interrupted":true,"turnComplete":true}}"""); runCurrent()
        h.socket.receive(AUDIO); runCurrent(); assertEquals(2, h.audio.enqueued)
        h.stop()
    }

    @Test fun passiveTeachingUpdatesWaitForGenerationAndPlaybackInsteadOfInterruptingSpeech() = runTest {
        val h = Harness(this); h.ready()
        h.socket.receive(AUDIO); runCurrent()
        h.transport.send(LiveOperation.UpdateContext("Keep the next turn shorter.")); runCurrent()
        assertFalse(h.socket.sent.any { it.contains("Keep the next turn") })
        h.socket.receive("""{"serverContent":{"turnComplete":true,"interactionStatus":"IDLE"}}"""); runCurrent()
        assertFalse(h.socket.sent.any { it.contains("Keep the next turn") })
        h.audio.drain(); runCurrent()
        val update = h.socket.sent.single { it.contains("Keep the next turn") }
        assertFalse(Json.parseToJsonElement(update).jsonObject["clientContent"]!!.jsonObject["turnComplete"]!!.jsonPrimitive.boolean)
        h.stop()
    }

    @Test fun thirtySecondBurstFinishesGenerationBeforePlaybackAndFlushesOneCoalescedReplyAfterDrain() = runTest {
        val h = Harness(this); h.ready()
        h.socket.receive("""{"serverContent":{"outputTranscription":{"text":"A complete spoken reply."}}}"""); runCurrent()
        val second = audioFrame(ByteArray(48_000))
        repeat(30) { h.socket.receive(second); runCurrent() }
        assertEquals(30_000L, h.audio.queuedMilliseconds)
        assertEquals(0L, h.audio.playedMilliseconds)
        assertTrue(h.audio.playbackBacklogged)
        assertTrue(h.failures.isEmpty())
        h.transport.send(LiveOperation.RequestReply("Outdated automatic check-in.")); runCurrent()
        h.transport.send(LiveOperation.RequestReply("Newest automatic check-in.")); runCurrent()
        h.socket.receive("""{"serverContent":{"generationComplete":true,"turnComplete":true,"interactionStatus":"IDLE"}}"""); runCurrent()
        assertTrue(h.events.contains(LiveEvent.TurnComplete))
        assertFalse(h.events.contains(LiveEvent.PlaybackComplete))
        assertTrue(h.events.filterIsInstance<LiveEvent.Transcript>().all { it.interim })
        assertFalse(h.socket.sent.any { it.contains("check-in") })
        h.audio.drain(); runCurrent()
        assertEquals(30_000L, h.audio.playedMilliseconds)
        assertEquals(1, h.events.count { it == LiveEvent.PlaybackComplete })
        assertEquals(1, h.events.filterIsInstance<LiveEvent.Transcript>().count { !it.interim })
        assertEquals(1, h.socket.sent.count { it.contains("Newest automatic check-in") })
        assertFalse(h.socket.sent.any { it.contains("Outdated automatic check-in") })
        h.socket.receive("""{"serverContent":{"interactionStatus":"IDLE"}}"""); runCurrent()
        h.audio.drain(); runCurrent()
        assertEquals(1, h.socket.sent.count { it.contains("Newest automatic check-in") })
        assertTrue(h.failures.isEmpty()); h.stop()
    }

    @Test fun optionalReplyReservesGenerationBeforeAnyServerAudioCanArrive() = runTest {
        val h = Harness(this); h.ready()
        h.transport.send(LiveOperation.RequestReply("First greeting."))
        h.transport.send(LiveOperation.RequestReply("Old reminder."))
        h.transport.send(LiveOperation.RequestReply("Latest reminder."))
        runCurrent()
        assertEquals(1, h.socket.sent.count { it.contains("clientContent") })
        assertTrue(h.socket.sent.last().contains("First greeting"))
        h.socket.receive("""{"serverContent":{"turnComplete":true}}"""); runCurrent()
        assertEquals(2, h.socket.sent.count { it.contains("clientContent") })
        assertTrue(h.socket.sent.last().contains("Latest reminder"))
        assertFalse(h.socket.sent.any { it.contains("Old reminder") })
        h.stop()
    }

    @Test fun respondingTeachingUpdatesWaitForPlaybackAndPreserveReplacedContextWithoutExtraGeneration() = runTest {
        val h = Harness(this); h.ready()
        h.socket.receive(AUDIO); runCurrent()
        h.transport.send(LiveOperation.UpdateContext("Earlier correction.", respond = true)); runCurrent()
        h.transport.send(LiveOperation.UpdateContext("Current correction.", respond = true)); runCurrent()
        assertFalse(h.socket.sent.any { it.contains("correction") })
        h.socket.receive("""{"serverContent":{"turnComplete":true,"interactionStatus":"IDLE"}}"""); runCurrent()
        assertFalse(h.socket.sent.any { it.contains("correction") })
        h.audio.drain(); runCurrent()
        val updates = h.socket.sent.filter { it.contains("correction") }.map { Json.parseToJsonElement(it).jsonObject["clientContent"]!!.jsonObject }
        assertEquals(2, updates.size)
        assertEquals(1, updates.count { it["turnComplete"]!!.jsonPrimitive.boolean })
        assertTrue(updates.first().toString().contains("Earlier correction"))
        assertTrue(updates.last().toString().contains("Current correction"))
        assertEquals(0, h.audio.interruptions)
        h.stop()
    }

    @Test fun typedBargeInClearsQueuedAutomaticReplyAndDoesNotWaitForOldPlayback() = runTest {
        val h = Harness(this); h.ready()
        h.socket.receive(AUDIO); runCurrent()
        h.socket.receive("""{"serverContent":{"turnComplete":true}}"""); runCurrent()
        h.transport.send(LiveOperation.RequestReply("Stale automatic check-in.")); runCurrent()
        assertFalse(h.socket.sent.any { it.contains("Stale automatic") })
        h.transport.send(LiveOperation.TypedTurn("My new spoken-answer request.")); runCurrent()
        assertTrue(h.socket.sent.last().contains("My new spoken-answer request"))
        assertEquals(1, h.audio.interruptions)
        assertEquals(0L, h.audio.queuedMilliseconds)
        h.audio.drain(); runCurrent()
        h.socket.receive("""{"serverContent":{"turnComplete":true,"interactionStatus":"IDLE"}}"""); runCurrent()
        assertFalse(h.socket.sent.any { it.contains("Stale automatic") })
        h.stop()
    }

    @Test fun failedNativeAudioIsReportedAsAnAudioRouteFailureInsteadOfBufferOverflow() = runTest {
        val h = Harness(this); h.ready()
        h.audio.nativeFailed = true
        h.socket.receive(AUDIO); runCurrent()
        assertEquals(1, h.audio.closes)
        assertTrue(h.failures.single().contains("communication audio stopped"))
        assertFalse(h.failures.single().contains("safe buffer"))
        h.stop()
    }

    @Test fun oldInterruptedTurnBoundaryCannotReleaseAnotherReplyAheadOfTheTypedAnswer() = runTest {
        val h = Harness(this); h.ready()
        h.socket.receive(AUDIO); runCurrent()
        h.transport.send(LiveOperation.TypedTurn("A new typed question.")); runCurrent()
        h.transport.send(LiveOperation.RequestReply("A later optional check-in.")); runCurrent()
        h.socket.receive("""{"serverContent":{"interrupted":true,"turnComplete":true}}"""); runCurrent()
        h.audio.drain(); runCurrent()
        assertFalse(h.socket.sent.any { it.contains("later optional") })
        h.socket.receive(AUDIO); runCurrent()
        h.socket.receive("""{"serverContent":{"turnComplete":true}}"""); runCurrent()
        assertFalse(h.socket.sent.any { it.contains("later optional") })
        h.audio.drain(); runCurrent()
        assertEquals(1, h.socket.sent.count { it.contains("later optional") })
        h.stop()
    }

    @Test fun onlyAnActualCapacityRejectionIsReportedAsBufferOverflow() = runTest {
        val h = Harness(this); h.ready()
        h.audio.accepted = false
        h.socket.receive(AUDIO); runCurrent()
        assertFalse(h.failures.single().contains("safe buffer"))
        h.stop()
        val full = Harness(this); full.ready()
        val second = audioFrame(ByteArray(48_000))
        repeat(60) { full.socket.receive(second); runCurrent() }
        assertTrue(full.failures.isEmpty())
        full.socket.receive(AUDIO); runCurrent()
        assertTrue(full.failures.single().contains("safe buffer"))
        assertEquals(1, full.audio.closes)
        full.stop()
    }

    @Test fun rejectedPcmSendResumesAsNetworkLossWithoutFailingTheMicrophone() = runTest {
        val h = Harness(this); h.ready()
        h.socket.receive(RESUMABLE); runCurrent()
        h.socket.accepted = false
        assertTrue(h.audio.input(ByteArray(640)))
        runCurrent(); advanceTimeBy(250); runCurrent()
        assertTrue(h.events.contains(LiveEvent.Reconnecting)); assertTrue(h.failures.isEmpty())
        h.socket.open(); runCurrent(); h.socket.receive(SETUP); runCurrent()
        assertTrue(h.transport.started); assertEquals(1, h.audio.starts); assertEquals(0, h.audio.closes)
        h.stop()
    }

    @Test fun expiredCredentialErrorDuringActiveCallStopsWithoutRetryOrFallback() = runTest {
        val h = Harness(this); h.ready()
        h.socket.receive(RESUMABLE); runCurrent()
        h.socket.receive("""{"error":{"code":400,"status":"INVALID_ARGUMENT","details":[{"reason":"API_KEY_EXPIRED"}]}}""")
        runCurrent(); advanceTimeBy(5_000); runCurrent()
        assertEquals(1, h.sockets.values.size); assertEquals(1, h.audio.closes)
        assertTrue(h.failures.single().contains("authenticate")); h.stop()
    }

    @Test fun protocolFailureCannotMasqueradeAsConfigurationOrAttemptResumption() = runTest {
        val h = Harness(this); h.ready()
        h.socket.receive(RESUMABLE); runCurrent()
        h.socket.listener.protocolFailed(); runCurrent(); advanceTimeBy(5_000); runCurrent()
        assertEquals(1, h.audio.closes); assertEquals(1, h.sockets.values.size)
        assertTrue(h.failures.single().contains("invalid voice message"))
        assertFalse(h.failures.single().contains("configuration")); h.stop()
    }

    private class Harness(val scope: TestScope) {
        val sockets = FakeSockets()
        val audio = FakeAudio()
        var key: String? = "owner-key-placeholder"
        val events = mutableListOf<LiveEvent>()
        val failures = mutableListOf<String>()
        val transport = GeminiLiveTransport(scope, sockets, { audio }, StandardTestDispatcher(scope.testScheduler)).also {
            it.onEvent = events::add; it.onFailure = failures::add
        }
        val socket get() = sockets.values.last()
        suspend fun ready(history: JsonArray = JsonArray(emptyList())) {
            val connecting = scope.async { transport.connect({ key }, "Policy", history) }
            scope.runCurrent(); socket.open(); scope.runCurrent(); socket.receive(SETUP); scope.runCurrent(); connecting.await()
        }
        suspend fun stop() { transport.disconnect(); transport.awaitDisconnected(); scope.runCurrent() }
    }
    private class FakeSockets : GeminiSocketFactory {
        val values = mutableListOf<FakeSocket>()
        val requests = mutableListOf<Request>()
        override fun open(request: Request, listener: GeminiSocketListener): GeminiSocket {
            requests.add(request)
            return FakeSocket(listener).also { values.add(it) }
        }
    }
    private class FakeSocket(val listener: GeminiSocketListener) : GeminiSocket {
        val sent = mutableListOf<String>()
        var closes = 0; var cancels = 0; var queuedBytes = 0L; var accepted = true
        override fun send(text: String): Boolean { if (!accepted) return false; sent.add(text); return true }
        override fun queueSize() = queuedBytes
        override fun close() { closes++ }
        override fun cancel() { cancels++ }
        fun open() = listener.opened()
        fun receive(text: String) = listener.message(text)
    }
    private class FakeAudio : GeminiAudio {
        override var inputMilliseconds = 0L
        override var playedMilliseconds = 0L
        private val queue = GeminiPlaybackQueue()
        override val queuedMilliseconds get() = queue.queuedMilliseconds()
        override val playbackBacklogged get() = queue.backlogged
        override val playbackFailed get() = nativeFailed || closes > 0
        override val remainingPlaybackBytes get() = queue.remainingBytes()
        var starts = 0; var closes = 0; var enqueued = 0; var interruptions = 0
        var muted = false
        var nativeFailed = false
        var accepted = true
        lateinit var input: (ByteArray) -> Boolean
        val drains = mutableListOf<() -> Unit>()
        override fun start(onInput: (ByteArray) -> Boolean, onFailure: () -> Unit, onLevels: (Double, Double) -> Unit) { starts++; input = onInput }
        override fun mute(value: Boolean) { muted = value }
        override fun enqueue(pcm16: ByteArray, generation: Long): Boolean {
            enqueued++; return accepted && !playbackFailed && queue.offer(pcm16, generation)
        }
        override fun interrupt(generation: Long) { interruptions++; queue.interrupt(generation) }
        override fun finishGeneration(generation: Long, onDrained: () -> Unit) { drains.add(onDrained) }
        override fun close() { closes++; queue.interrupt(queue.generation + 1) }
        fun drain() {
            playedMilliseconds += queue.queuedSamples() / 24
            while (!queue.empty()) queue.poll()
            drains.toList().also { drains.clear() }.forEach { it() }
        }
    }
    companion object {
        private fun audioFrame(pcm: ByteArray) = """{"serverContent":{"modelTurn":{"parts":[{"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"${Base64.getEncoder().encodeToString(pcm)}"}}]}}}"""
        const val SETUP = "{\"setupComplete\":{}}"
        const val AUDIO = "{\"serverContent\":{\"modelTurn\":{\"parts\":[{\"inlineData\":{\"mimeType\":\"audio/pcm;rate=24000\",\"data\":\"AAA=\"}}]}}}"
        const val RESUMABLE = "{\"sessionResumptionUpdate\":{\"resumable\":true,\"newHandle\":\"handle-placeholder\"}}"
    }
}
