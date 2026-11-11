package chat.mural.network

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Exercises OkHttp's real opcode callbacks, which the transport's fake socket cannot cover. */
class GeminiWebSocketTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private val sockets = CopyOnWriteArrayList<GeminiSocket>()
    private val serverSockets = CopyOnWriteArrayList<WebSocket>()

    @Before fun setup() {
        server = MockWebServer()
        server.start()
        client = OkHttpClient.Builder().retryOnConnectionFailure(false)
            .connectTimeout(5, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS).build()
    }

    @After fun teardown() {
        // MockWebServer's server-side socket has no HTTP Call to cancel; close its stream instead.
        serverSockets.forEach { it.close(1000, null) }
        sockets.forEach { it.cancel() }
        client.dispatcher.executorService.shutdownNow()
        client.connectionPool.evictAll()
        server.shutdown()
    }

    @Test fun binarySetupCompletePassesThroughActualWebSocketUpgrade() {
        upgrade { webSocket -> webSocket.send(SETUP_COMPLETE.encodeUtf8()) }
        val listener = RecordingListener()
        val socket = open(listener)
        assertEquals(Event.Opened, listener.next())
        assertTrue(socket.send(GeminiLiveProtocol.setup(GeminiLiveProtocol.Configuration(), "Policy").toString()))
        val message = listener.next() as Event.Message
        assertEquals(listOf(GeminiLiveProtocol.Message.SetupComplete), GeminiLiveProtocol.decode(message.text))
        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("websocket", request.getHeader("Upgrade"))
        assertEquals("GET", request.method)
    }

    @Test fun interleavedBinaryAndTextJsonPreserveTranscriptsAndAudioInOrder() {
        val transcript = """{"serverContent":{"inputTranscription":{"text":"مرحبا"}}}"""
        val audio = """{"serverContent":{"modelTurn":{"parts":[{"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"AAA="}}]},"outputTranscription":{"text":"Hello"}}}"""
        val complete = """{"serverContent":{"turnComplete":true}}"""
        upgrade { webSocket ->
            webSocket.send(SETUP_COMPLETE.encodeUtf8())
            webSocket.send(transcript)
            webSocket.send(audio.encodeUtf8())
            webSocket.send(complete.encodeUtf8())
        }
        val listener = RecordingListener()
        val socket = open(listener)
        assertEquals(Event.Opened, listener.next())
        assertTrue(socket.send("""{"setup":{"model":"models/gemini-3.8-live"}}"""))
        val received = List(4) { (listener.next() as Event.Message).text }
        assertEquals(listOf(SETUP_COMPLETE, transcript, audio, complete), received)
        val decoded = received.flatMap(GeminiLiveProtocol::decode)
        assertTrue(decoded.first() is GeminiLiveProtocol.Message.SetupComplete)
        assertEquals(listOf("مرحبا", "Hello"), decoded.filterIsInstance<GeminiLiveProtocol.Message.Transcription>().map { it.text })
        assertArrayEquals(byteArrayOf(0, 0), decoded.filterIsInstance<GeminiLiveProtocol.Message.Audio>().single().bytes)
        assertTrue(decoded.last() is GeminiLiveProtocol.Message.TurnComplete)
    }

    @Test fun malformedBinaryUtf8IsAProtocolFailureWithoutSyntheticHttpStatus() {
        upgrade { webSocket -> webSocket.send(byteArrayOf(0xc3.toByte(), 0x28).toByteString()) }
        val listener = RecordingListener()
        val socket = open(listener)
        assertEquals(Event.Opened, listener.next())
        assertTrue(socket.send("{}"))
        assertEquals(Event.ProtocolFailure, listener.next())
        assertNull(listener.events.poll(100, TimeUnit.MILLISECONDS))
    }

    @Test fun binaryByteLimitAcceptsBoundaryAndRejectsOversizeBeforeDecoding() {
        val boundary = SETUP_COMPLETE + " ".repeat(GeminiLiveProtocol.MAX_FRAME_BYTES - SETUP_COMPLETE.length)
        upgrade { webSocket ->
            webSocket.send(boundary.encodeUtf8())
            webSocket.send(ByteArray(GeminiLiveProtocol.MAX_FRAME_BYTES + 1) { ' '.code.toByte() }.toByteString())
        }
        val listener = RecordingListener()
        val socket = open(listener)
        assertEquals(Event.Opened, listener.next())
        assertTrue(socket.send("{}"))
        val accepted = listener.next() as Event.Message
        assertEquals(GeminiLiveProtocol.MAX_FRAME_BYTES, accepted.text.length)
        assertEquals(listOf(GeminiLiveProtocol.Message.SetupComplete), GeminiLiveProtocol.decode(accepted.text))
        assertEquals(Event.ProtocolFailure, listener.next())
    }

    @Test fun failedUpgradeExposesOnlyHttpStatusAndNeverBodyOrAuthenticatedUrl() {
        server.enqueue(MockResponse().setResponseCode(403).setBody("private-provider-response-$SECRET"))
        val listener = RecordingListener()
        open(listener)
        val event = listener.next()
        assertEquals(Event.Failed(403), event)
        assertFalse(event.toString().contains(SECRET))
        assertFalse(event.toString().contains("private-provider-response"))
        assertFalse(event.toString().contains(server.hostName))
        assertTrue(GeminiLiveProtocol.safeError((event as Event.Failed).httpCode).contains("access"))
    }

    @Test fun connectionFailureDoesNotExposeThrowableOrAuthenticatedUrl() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        val listener = RecordingListener()
        open(listener)
        val event = listener.next()
        assertEquals(Event.Failed(null), event)
        assertFalse(event.toString().contains(SECRET))
        assertFalse(event.toString().contains(server.hostName))
    }

    private fun upgrade(reply: (WebSocket) -> Unit) {
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { serverSockets.add(webSocket) }
            override fun onMessage(webSocket: WebSocket, text: String) { reply(webSocket) }
        }))
    }

    private fun open(listener: RecordingListener): GeminiSocket {
        val request = Request.Builder().url(server.url("/live").newBuilder().addQueryParameter("key", SECRET).build()).build()
        return OkHttpGeminiSocketFactory(client).open(request, listener).also { sockets.add(it) }
    }

    private sealed interface Event {
        data object Opened : Event
        data class Message(val text: String) : Event
        data class Failed(val httpCode: Int?) : Event
        data object ProtocolFailure : Event
        data class Closed(val code: Int) : Event
    }

    private class RecordingListener : GeminiSocketListener {
        val events = LinkedBlockingQueue<Event>()
        override fun opened() { events.add(Event.Opened) }
        override fun message(text: String) { events.add(Event.Message(text)) }
        override fun failed(httpCode: Int?) { events.add(Event.Failed(httpCode)) }
        override fun protocolFailed() { events.add(Event.ProtocolFailure) }
        override fun closed(code: Int) { events.add(Event.Closed(code)) }
        fun next(): Event = events.poll(5, TimeUnit.SECONDS) ?: throw AssertionError("Timed out waiting for WebSocket callback")
    }

    private companion object {
        const val SECRET = "wire-test-placeholder-key"
        const val SETUP_COMPLETE = """{"setupComplete":{}}"""
    }
}
