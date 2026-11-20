package chat.mural.network

import kotlin.math.sin
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GroqTranscriptionClientTest {
    private lateinit var server: MockWebServer

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun teardown() {
        server.shutdown()
    }

    private fun speechPcm(durationMs: Int = 1_000, amplitude: Int = 4_000): ByteArray {
        val sampleCount = durationMs * 16
        val bytes = ByteArray(sampleCount * 2)
        for (i in 0 until sampleCount) {
            val sample = (amplitude * sin(2.0 * Math.PI * 300.0 * i / 16_000.0)).toInt()
            bytes[i * 2] = (sample and 0xFF).toByte()
            bytes[i * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
        }
        return bytes
    }

    private data class MultipartPart(val headers: String, val bodyBytes: ByteArray) {
        val name: String?
            get() = Regex("""name="([^"]+)"""").find(headers)?.groupValues?.get(1)
        val filename: String?
            get() = Regex("""filename="([^"]+)"""").find(headers)?.groupValues?.get(1)
        val bodyText: String
            get() = String(bodyBytes, Charsets.UTF_8)
    }

    private fun parseMultipart(contentType: String, body: ByteArray): List<MultipartPart> {
        val boundary = Regex("""boundary=([^\s;]+)""").find(contentType)?.groupValues?.get(1)
            ?.trim('"') ?: error("Missing multipart boundary in $contentType")
        val delimiter = "--$boundary".toByteArray(Charsets.US_ASCII)
        val parts = mutableListOf<MultipartPart>()
        var cursor = indexOf(body, delimiter, 0)
        while (cursor >= 0) {
            val afterDelimiter = cursor + delimiter.size
            if (afterDelimiter + 2 <= body.size && body[afterDelimiter] == '-'.code.toByte() && body[afterDelimiter + 1] == '-'.code.toByte()) {
                break
            }
            val headerStart = if (afterDelimiter + 2 <= body.size && body[afterDelimiter] == '\r'.code.toByte()) afterDelimiter + 2 else afterDelimiter
            val headerEndMarker = "\r\n\r\n".toByteArray(Charsets.US_ASCII)
            val headerEnd = indexOf(body, headerEndMarker, headerStart)
            if (headerEnd < 0) break
            val headersText = String(body, headerStart, headerEnd - headerStart, Charsets.UTF_8)
            val contentStart = headerEnd + headerEndMarker.size
            val nextBoundary = indexOf(body, delimiter, contentStart)
            if (nextBoundary < 0) break
            val contentEnd = if (nextBoundary >= 2 && body[nextBoundary - 2] == '\r'.code.toByte() && body[nextBoundary - 1] == '\n'.code.toByte()) {
                nextBoundary - 2
            } else {
                nextBoundary
            }
            parts += MultipartPart(headersText, body.copyOfRange(contentStart, contentEnd))
            cursor = nextBoundary
        }
        return parts
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray, start: Int): Int {
        outer@ for (i in start..(haystack.size - needle.size)) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }

    @Test
    fun sendsMultipartWavWithBearerAuthNoForcedLanguageAndParsesMultilingualText() = runBlocking {
        val client = GroqTranscriptionClient(
            readCredential = { "gsk_test_groq_credential_1234567890" },
            client = OkHttpClient(),
            endpointUrl = server.url("/openai/v1/audio/transcriptions"),
        )
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "text": " Yesterday I went إلى المكتب early. ",
                  "segments": [
                    {"no_speech_prob": 0.04, "avg_logprob": -0.22}
                  ]
                }
                """.trimIndent(),
            ),
        )

        val pcm = speechPcm(1_000)
        val longHint = GroqTranscriptionClient.buildPromptHint(
            previousAssistantReply = "Tell me more about your commute to work this morning and how the traffic was around Nasr City when you left your apartment today? ".repeat(4),
            topic = "Daily Commute",
        )
        assertTrue(longHint.length <= GroqTranscriptionClient.MAX_PROMPT_HINT_CHARS)
        assertTrue(longHint.contains("Mural"))

        val result = client.transcribe(pcm, GroqWhisperModel.LARGE_V3, longHint)
        assertEquals(GroqTranscriptionResult.Success("Yesterday I went إلى المكتب early."), result)

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/openai/v1/audio/transcriptions", recorded.path)
        assertEquals("Bearer gsk_test_groq_credential_1234567890", recorded.getHeader("Authorization"))

        val parts = parseMultipart(recorded.getHeader("Content-Type")!!, recorded.body.readByteArray())
        val byName = parts.associateBy { it.name }

        assertEquals(setOf("file", "model", "response_format", "temperature", "prompt"), byName.keys)
        assertFalse(byName.containsKey("language"))
        assertEquals("whisper-large-v3", byName.getValue("model").bodyText)
        assertEquals("verbose_json", byName.getValue("response_format").bodyText)
        assertEquals("0", byName.getValue("temperature").bodyText)
        assertEquals(longHint, byName.getValue("prompt").bodyText)

        val filePart = byName.getValue("file")
        assertEquals("turn.wav", filePart.filename)
        assertTrue(filePart.headers.contains("audio/wav", ignoreCase = true))
        assertEquals(44 + pcm.size, filePart.bodyBytes.size)
        assertEquals("RIFF", String(filePart.bodyBytes, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(filePart.bodyBytes, 8, 4, Charsets.US_ASCII))
    }

    @Test
    fun supportsTurboModelSelection() = runBlocking {
        val client = GroqTranscriptionClient(
            readCredential = { "gsk_test_groq_credential_1234567890" },
            client = OkHttpClient(),
            endpointUrl = server.url("/openai/v1/audio/transcriptions"),
        )
        server.enqueue(
            MockResponse().setBody("""{"text":"I went home.","segments":[{"no_speech_prob":0.1,"avg_logprob":-0.3}]}"""),
        )

        val result = client.transcribe(speechPcm(500), GroqWhisperModel.LARGE_V3_TURBO, "")
        assertEquals(GroqTranscriptionResult.Success("I went home."), result)

        val recorded = server.takeRequest()
        val parts = parseMultipart(recorded.getHeader("Content-Type")!!, recorded.body.readByteArray())
        val byName = parts.associateBy { it.name }
        assertEquals("whisper-large-v3-turbo", byName.getValue("model").bodyText)
        assertFalse(byName.containsKey("prompt"))
        assertFalse(byName.containsKey("language"))
    }

    @Test
    fun hallucinationFilterDiscardsBlankHighNoSpeechAndSilenceHallucinations() = runBlocking {
        val client = GroqTranscriptionClient(
            readCredential = { "gsk_test_groq_credential_1234567890" },
            client = OkHttpClient(),
            endpointUrl = server.url("/openai/v1/audio/transcriptions"),
        )

        server.enqueue(MockResponse().setBody("""{"text":"   ","segments":[]}"""))
        assertEquals(GroqTranscriptionResult.Discarded, client.transcribe(speechPcm(500), GroqWhisperModel.LARGE_V3, "Mural"))

        server.enqueue(
            MockResponse().setBody(
                """{"text":"Hello there.","segments":[{"no_speech_prob":0.82,"avg_logprob":-1.35}]}""",
            ),
        )
        assertEquals(GroqTranscriptionResult.Discarded, client.transcribe(speechPcm(600), GroqWhisperModel.LARGE_V3, "Mural"))

        server.enqueue(
            MockResponse().setBody(
                """{"text":"Thank you for watching.","segments":[{"no_speech_prob":0.3,"avg_logprob":-0.4}]}""",
            ),
        )
        // Short low-energy clip (<2.5s, RMS < 0.025 -> amplitude 150 gives RMS ~0.0032)
        assertEquals(
            GroqTranscriptionResult.Discarded,
            client.transcribe(speechPcm(durationMs = 800, amplitude = 150), GroqWhisperModel.LARGE_V3, "Mural"),
        )

        server.enqueue(
            MockResponse().setBody(
                """{"text":"Subtitles by Amara.org","segments":[{"no_speech_prob":0.2,"avg_logprob":-0.4}]}""",
            ),
        )
        assertEquals(
            GroqTranscriptionResult.Discarded,
            client.transcribe(speechPcm(durationMs = 800, amplitude = 4_000), GroqWhisperModel.LARGE_V3, "Mural"),
        )

        // Genuine louder speech saying "Thank you for watching." in a full turn is preserved when confidence is healthy.
        server.enqueue(
            MockResponse().setBody(
                """{"text":"Thank you for watching.","segments":[{"no_speech_prob":0.05,"avg_logprob":-0.2}]}""",
            ),
        )
        assertEquals(
            GroqTranscriptionResult.Success("Thank you for watching."),
            client.transcribe(speechPcm(durationMs = 3_000, amplitude = 6_000), GroqWhisperModel.LARGE_V3, "Mural"),
        )
    }

    @Test
    fun mapsAuthRateLimitServerAndMissingKeyGracefullyWithoutThrowing() = runBlocking {
        val missingKeyClient = GroqTranscriptionClient(
            readCredential = { null },
            client = OkHttpClient(),
            endpointUrl = server.url("/openai/v1/audio/transcriptions"),
        )
        assertEquals(
            GroqTranscriptionResult.Failure(GroqTranscriptionResult.FailureKind.MISSING_KEY),
            missingKeyClient.transcribe(speechPcm(500), GroqWhisperModel.LARGE_V3, "Mural"),
        )
        assertEquals(0, server.requestCount)

        val client = GroqTranscriptionClient(
            readCredential = { "gsk_test_groq_credential_1234567890" },
            client = OkHttpClient(),
            endpointUrl = server.url("/openai/v1/audio/transcriptions"),
        )

        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"Invalid API key"}}"""))
        assertEquals(
            GroqTranscriptionResult.Failure(GroqTranscriptionResult.FailureKind.UNAUTHORIZED, 401),
            client.transcribe(speechPcm(500), GroqWhisperModel.LARGE_V3, "Mural"),
        )

        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":{"message":"Rate limit"}}"""))
        assertEquals(
            GroqTranscriptionResult.Failure(GroqTranscriptionResult.FailureKind.RATE_LIMITED, 429),
            client.transcribe(speechPcm(500), GroqWhisperModel.LARGE_V3, "Mural"),
        )

        server.enqueue(MockResponse().setResponseCode(503).setBody("unavailable"))
        assertEquals(
            GroqTranscriptionResult.Failure(GroqTranscriptionResult.FailureKind.SERVER, 503),
            client.transcribe(speechPcm(500), GroqWhisperModel.LARGE_V3, "Mural"),
        )
    }
}
