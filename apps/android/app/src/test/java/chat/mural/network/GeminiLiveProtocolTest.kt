package chat.mural.network

import chat.mural.core.Speaker
import java.util.Base64
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class GeminiLiveProtocolTest {
    @Test fun rawSetupUsesGenerationConfigAndVerbatimTranscription() {
        val setup = GeminiLiveProtocol.setup(GeminiLiveProtocol.Configuration(), "Practise English.")["setup"]!!.jsonObject
        assertEquals("models/gemini-3.8-live", setup["model"]!!.jsonPrimitive.content)
        assertEquals("AUDIO", setup["generationConfig"]!!.jsonObject["responseModalities"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals(1_024, setup["generationConfig"]!!.jsonObject["maxOutputTokens"]!!.jsonPrimitive.int)
        assertFalse(setup["generationConfig"]!!.jsonObject.containsKey("thinkingConfig"))
        assertEquals("Kore", setup["generationConfig"]!!.jsonObject["speechConfig"]!!.jsonObject["voiceConfig"]!!.jsonObject["prebuiltVoiceConfig"]!!.jsonObject["voiceName"]!!.jsonPrimitive.content)
        assertEquals("VERBATIM", setup["inputAudioTranscription"]!!.jsonObject["mode"]!!.jsonPrimitive.content)
        assertEquals("VERBATIM", setup["outputAudioTranscription"]!!.jsonObject["mode"]!!.jsonPrimitive.content)
        assertFalse(setup["inputAudioTranscription"]!!.jsonObject.containsKey("wordTimestamp"))
        assertFalse(setup["outputAudioTranscription"]!!.jsonObject.containsKey("wordTimestamp"))
        assertFalse(setup.containsKey("responseModalities"))
        assertFalse(setup.containsKey("thinkingConfig"))
        assertFalse(setup.containsKey("proactivity"))
        assertFalse(setup.toString().contains("store"))
        assertEquals("BLOCKING", setup["tools"]!!.jsonArray.single().jsonObject["functionDeclarations"]!!.jsonArray.single().jsonObject["behavior"]!!.jsonPrimitive.content)
    }

    @Test fun historyMapsAssistantToModelAndDoesNotGenerate() {
        val history = Json.parseToJsonElement("""[{"role":"assistant","content":"Hello"},{"role":"user","content":"مرحبا"}]""").jsonArray
        val setup = GeminiLiveProtocol.setup(GeminiLiveProtocol.Configuration(), "Policy", hasHistory = true)["setup"]!!.jsonObject
        assertTrue(setup["historyConfig"]!!.jsonObject["initialHistoryInClientContent"]!!.jsonPrimitive.boolean)
        val content = GeminiLiveProtocol.history(history)["clientContent"]!!.jsonObject
        assertEquals("model", content["turns"]!!.jsonArray[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertTrue(content["turnComplete"]!!.jsonPrimitive.boolean)
        val resumed = GeminiLiveProtocol.setup(GeminiLiveProtocol.Configuration(), "Policy", "handle-placeholder", true)["setup"]!!.jsonObject
        assertFalse(resumed.containsKey("historyConfig"))
    }

    @Test fun textAndMuteUseSupportedInputOperations() {
        val turn = GeminiLiveProtocol.text("I go yesterday.", true)["clientContent"]!!.jsonObject
        assertTrue(turn["turnComplete"]!!.jsonPrimitive.boolean)
        assertEquals("user", turn["turns"]!!.jsonArray.single().jsonObject["role"]!!.jsonPrimitive.content)
        val paused = GeminiLiveProtocol.audioStreamEnd()["realtimeInput"]!!.jsonObject
        assertTrue(paused["audioStreamEnd"]!!.jsonPrimitive.boolean)
        assertFalse(paused.containsKey("activityEnd"))
        assertFalse(GeminiLiveProtocol.text("context", false)["clientContent"]!!.jsonObject["turnComplete"]!!.jsonPrimitive.boolean)
    }

    @Test fun pcmIsBase64LittleEndianWithoutHeader() {
        val pcm = byteArrayOf(0x34, 0x12, 0, -128)
        val input = GeminiLiveProtocol.audio(pcm)["realtimeInput"]!!.jsonObject["audio"]!!.jsonObject
        assertEquals("audio/pcm;rate=16000", input["mimeType"]!!.jsonPrimitive.content)
        assertArrayEquals(pcm, Base64.getDecoder().decode(input["data"]!!.jsonPrimitive.content))
    }

    @Test fun mixedFrameProcessesAllAudioPartsAndBothTranscriptions() {
        val decoded = GeminiLiveProtocol.decode("""{"usageMetadata":{"promptTokenCount":12,"responseTokenCount":7},"serverContent":{"inputTranscription":{"text":"I goed","finished":true},"outputTranscription":{"text":"You went."},"modelTurn":{"parts":[{"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"AAA="}},{"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"AQI="}}]},"generationComplete":true,"turnComplete":true}}""")
        assertEquals(2, decoded.filterIsInstance<GeminiLiveProtocol.Message.Audio>().size)
        assertEquals(2, decoded.filterIsInstance<GeminiLiveProtocol.Message.Transcription>().size)
        assertTrue(decoded.contains(GeminiLiveProtocol.Message.GenerationComplete))
        assertTrue(decoded.contains(GeminiLiveProtocol.Message.TurnComplete))
        assertEquals(12L, decoded.filterIsInstance<GeminiLiveProtocol.Message.Usage>().single().inputTokens)
    }

    @Test fun interruptionDropsInlineAudioAndNeedsNoGenerationComplete() {
        val decoded = GeminiLiveProtocol.decode("""{"serverContent":{"interrupted":true,"modelTurn":{"parts":[{"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"AAA="}}]},"turnComplete":true}}""")
        assertEquals(listOf(GeminiLiveProtocol.Message.Interrupted, GeminiLiveProtocol.Message.TurnComplete), decoded)
    }

    @Test fun malformedDeepOversizedAndInvalidPcmFramesFailSafely() {
        for (frame in listOf("{", "[".repeat(42) + "]".repeat(42), " ".repeat(GeminiLiveProtocol.MAX_FRAME_BYTES + 1),
            """{"serverContent":{"modelTurn":{"parts":[{"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"AQ=="}}]}}}""",
            """{"serverContent":{"modelTurn":{"parts":[{"inlineData":{"mimeType":"audio/aac","data":"AAA="}}]}}}""")) {
            assertTrue(GeminiLiveProtocol.decode(frame).any { it is GeminiLiveProtocol.Message.Error })
        }
    }

    @Test fun errorsNeverContainProviderBodyCredentialsOrUrl() {
        val result = GeminiLiveProtocol.decode("""{"error":{"code":401,"message":"secret-placeholder https://example.invalid/?key=secret-placeholder","status":"UNAUTHENTICATED"}}""").single() as GeminiLiveProtocol.Message.Error
        assertTrue(result.message.contains("authenticate"))
        assertFalse(result.message.contains("secret-placeholder"))
        assertFalse(result.message.contains("https://"))
        assertTrue(GeminiLiveProtocol.safeError(429).contains("quota"))
        assertTrue(GeminiLiveProtocol.safeError(403).contains("access"))
    }

    @Test fun everyToolCallCancellationResumptionAndGoAwayIsDecoded() {
        val calls = GeminiLiveProtocol.decode("""{"toolCall":{"functionCalls":[{"id":"a","name":"mural_teaching_help","args":{"request":"explain"}},{"id":"b","name":"bad","args":{}}]}}""")
        assertEquals(2, calls.size)
        assertEquals(listOf("a", "b"), (GeminiLiveProtocol.decode("""{"toolCallCancellation":{"ids":["a","b"]}}""").single() as GeminiLiveProtocol.Message.ToolCancelled).ids)
        val resume = GeminiLiveProtocol.decode("""{"sessionResumptionUpdate":{"resumable":true,"newHandle":"secret-placeholder"}}""").single()
        assertFalse(resume.toString().contains("secret-placeholder"))
        assertEquals(1_250L, (GeminiLiveProtocol.decode("""{"goAway":{"timeLeft":"1.25s"}}""").single() as GeminiLiveProtocol.Message.GoAway).timeLeftMS)
    }

    @Test fun unicodeInterimRevisionRetainsIdentityAndDoesNotCleanGrammar() {
        val assembler = GeminiTranscriptAssembler { "stable-id" }
        val a = assembler.append(transcript("أنا go", interim = true), 1_000)
        val b = assembler.append(transcript("أنا goed أمس", interim = true), 1_100)
        val c = assembler.append(transcript("أنا goed أمس", finished = true), 1_200)
        assertEquals(a.id, b.id); assertEquals(a.id, c.id)
        assertEquals("أنا goed أمس", c.text)
        assertFalse(c.interim); assertTrue(c.estimatedTiming)
    }

    @Test fun committedDeltasPreserveWhitespaceAndProviderOffsets() {
        val assembler = GeminiTranscriptAssembler { "id" }
        assembler.append(transcript("I ", start = 100, end = 200), 500)
        val fragment = assembler.append(transcript("goed yesterday.", finished = true, start = 100, end = 900), 1_000)
        assertEquals("I goed yesterday.", fragment.text)
        assertEquals(100L, fragment.startMS); assertEquals(900L, fragment.endMS)
        assertFalse(fragment.estimatedTiming)
    }

    @Test fun playbackFinalizationAndInterruptDoNotDemonstrateUnheardText() {
        val assembler = GeminiTranscriptAssembler { "output" }
        assembler.append(transcript("Hello there.", speaker = Speaker.assistant), 300)
        assembler.interrupt()
        assertNull(assembler.finishOutput(500))
        assembler.append(transcript("Try again.", speaker = Speaker.assistant), 600)
        val result = assembler.finishOutput(1_000)!!
        assertFalse(result.interim); assertEquals("Try again.", result.text)
    }

    @Test fun committedRepeatedWordsAreNotDroppedAsDuplicatePayloads() {
        val assembler = GeminiTranscriptAssembler { "id" }
        assembler.append(transcript("very "), 100)
        val result = assembler.append(transcript("very "), 200)
        assertEquals("very very ", result.text)
    }

    @Test fun invalidAndExpiredCredentialsUseSafeAuthenticationReasons() {
        for (reason in listOf("API_KEY_INVALID", "API_KEY_EXPIRED")) {
            val event = GeminiLiveProtocol.decode("""{"error":{"code":400,"status":"INVALID_ARGUMENT","details":[{"reason":"$reason"}],"message":"private"}}""").single() as GeminiLiveProtocol.Message.Error
            assertTrue(event.message.contains("authenticate")); assertFalse(event.message.contains("private"))
        }
    }

    @Test fun onlyInterimHypothesesCannotBecomeEvidenceAtAnOutputBoundary() {
        val assembler = GeminiTranscriptAssembler { "hypothesis" }
        assembler.append(transcript("I might", interim = true), 100)
        assertNull(assembler.finishInput())
    }

    @Test fun voiceActivityProcessingAndWordOffsetsUseTheirDocumentedFields() {
        val events = GeminiLiveProtocol.decode("""{"voiceActivity":{"type":"ACTIVITY_START","audioOffset":"0.1s"},"serverContent":{"inputTranscription":{"text":"I goed","words":[{"startOffset":"0.1s","endOffset":"0.5s","word":"I"},{"startOffset":"0.6s","endOffset":"1.1s","word":"goed"}]},"turnComplete":true,"interactionStatus":"IN_PROGRESS"}}""")
        assertEquals(100L, events.filterIsInstance<GeminiLiveProtocol.Message.VoiceActivity>().single().offsetMS)
        assertEquals(100L, events.filterIsInstance<GeminiLiveProtocol.Message.Transcription>().single().startMS)
        assertEquals(1100L, events.filterIsInstance<GeminiLiveProtocol.Message.Transcription>().single().endMS)
        assertTrue(events.contains(GeminiLiveProtocol.Message.Processing))
        assertFalse(events.contains(GeminiLiveProtocol.Message.WaitingForInput))
    }

    private fun transcript(text: String, interim: Boolean = false, finished: Boolean = false,
        start: Long? = null, end: Long? = null, speaker: Speaker = Speaker.user) =
        GeminiLiveProtocol.Message.Transcription(speaker, text, interim, finished, start, end)
}
