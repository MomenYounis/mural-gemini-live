package chat.mural.network

import chat.mural.core.Speaker
import java.util.UUID
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class OpenAILiveProtocolTest {
    private fun event(payload: String) = Json.parseToJsonElement(payload).jsonObject

    @Test fun retainedAppendOperationsKeepExistingOpenAIWireContract() {
        val cases = listOf(
            LiveOperation.UpdateContext("policy") to "session.instructions.append",
            LiveOperation.UpdateContext("source", referenceOnly = true) to "session.thinking.append",
            LiveOperation.RequestReply("greeting") to "session.instructions.append",
            LiveOperation.ToolResult("delegation-id", "legacy_delegation", "tool result") to "session.commentary.append")
        val ids = mutableSetOf<String>()
        for ((operation, type) in cases) {
            val value = OpenAILiveProtocol.encode(operation)
            assertEquals(setOf("type", "event_id", "delegation_id", "content"), value.keys)
            assertEquals(JsonPrimitive(type), value["type"])
            ids += UUID.fromString(value["event_id"]!!.jsonPrimitive.content).toString()
            assertEquals(if (operation is LiveOperation.ToolResult) JsonPrimitive("delegation-id") else JsonNull, value["delegation_id"])
        }
        assertEquals(cases.size, ids.size)
        assertEquals(1_000, OpenAILiveProtocol.encode(LiveOperation.UpdateContext("x".repeat(1_001)))["content"]!!.jsonPrimitive.content.length)
    }

    @Test fun retainedTypedHelperReplyKeepsNullDelegationRatherThanAnEmptyId() {
        val reply = OpenAILiveProtocol.encode(LiveOperation.ToolResult("", "legacy_delegation", "Hello"))
        assertEquals(JsonNull, reply["delegation_id"])
    }
    @Test fun typedGeminiIntentCannotAccidentallyEnterOpenAIsAppendPath() {
        assertThrows(IllegalStateException::class.java) { OpenAILiveProtocol.encode(LiveOperation.TypedTurn("hello")) }
    }

    @Test fun openAICreateKeepsItsOwnFifteenSecondPolicyAndProviderIdentifier() {
        val created = OpenAILiveProtocol.decode(event("""{"type":"mural.session.created","session":{"id":"provider-session"}}""")) as LiveEvent.Created
        assertEquals("provider-session", created.providerID); assertEquals(15.0, created.minimumUsageSeconds!!, 0.0)
        assertEquals(LiveEvent.Ready, OpenAILiveProtocol.decode(event("""{"type":"session.started"}""")))
        assertNull(LiveEvent.Created("google-session").minimumUsageSeconds)
    }

    @Test fun legacyTranscriptKeepsProviderIdentityUnicodeAndAuthoritativeTiming() {
        for ((type, speaker) in listOf("session.input_transcript.delta" to Speaker.user, "session.output_transcript.delta" to Speaker.assistant)) {
            val decoded = OpenAILiveProtocol.decode(event("""{"type":"$type","event_id":"stable-id","delta":"café مرحبًا 你好","start_ms":12,"end_ms":222}""")) as LiveEvent.Transcript
            assertEquals("stable-id", decoded.id); assertEquals("café مرحبًا 你好", decoded.text)
            assertEquals(speaker, decoded.speaker); assertEquals(12, decoded.startMS); assertEquals(222, decoded.endMS)
            assertFalse(decoded.estimatedTiming); assertFalse(decoded.interim)
        }
    }

    @Test fun invalidTimingAndOversizedTranscriptsAreRejected() {
        for (payload in listOf(
            """{"type":"session.input_transcript.delta","delta":"hello","start_ms":-1,"end_ms":2}""",
            """{"type":"session.input_transcript.delta","delta":"hello","start_ms":10,"end_ms":2}""",
            """{"type":"session.input_transcript.delta","delta":"hello","start_ms":1}""")) assertNull(OpenAILiveProtocol.decode(event(payload)))
        assertNull(OpenAILiveProtocol.decode(buildJsonObject {
            put("type", "session.input_transcript.delta"); put("delta", "x".repeat(50_001)); put("start_ms", 0); put("end_ms", 1)
        }))
    }

    @Test fun usageAndFinalityRetainHostedOpenAISemanticsWithoutTokenToSecondsConversion() {
        val usage = OpenAILiveProtocol.decode(event("""{"type":"session.usage.updated","usage":{"seconds":27.5}}""")) as LiveEvent.LegacyUsage
        assertEquals(27.5, usage.seconds!!, 0.0); assertFalse(usage.final)
        val closed = OpenAILiveProtocol.decode(event("""{"type":"session.closed","usage":{"seconds":29.0}}""")) as LiveEvent.LegacyUsage
        assertEquals(29.0, closed.seconds!!, 0.0); assertTrue(closed.final)
        val invalid = OpenAILiveProtocol.decode(event("""{"type":"session.usage.updated","usage":{"seconds":-2,"inputTokens":10000}}""")) as LiveEvent.LegacyUsage
        assertNull(invalid.seconds)
        assertNull(OpenAILiveProtocol.decode(event("""{"usageMetadata":{"promptTokenCount":10000}}""")))
    }

    @Test fun delegationRequiresClientTargetAndKeepsTheOriginalId() {
        assertNull(OpenAILiveProtocol.decode(event("""{"type":"session.delegation.created","delegation":{"id":"a","target":"server"}}""")))
        val value = OpenAILiveProtocol.decode(event("""{"type":"session.delegation.created","delegation":{"id":"original","target":"client"}}""")) as LiveEvent.ToolCall
        assertEquals("original", value.id); assertEquals("legacy_delegation", value.name); assertTrue(value.args.isEmpty())
    }

    @Test fun genericSessionModelsRepresentNativePcmWithoutForgingSdpOrLeakingPrompts() {
        val request = LiveSessionRequest(LiveOffer.NativePCM, "private-policy")
        val connection = LiveSessionConnection(LiveConnection.NativePCM, "opaque")
        assertEquals(LiveOffer.NativePCM, request.offer); assertEquals(LiveConnection.NativePCM, connection.connection)
        assertThrows(IllegalStateException::class.java) { request.sdp }
        assertThrows(IllegalStateException::class.java) { connection.sdp }
        assertFalse(request.toString().contains("private-policy"))
        assertFalse(LiveOffer.WebRTC("private-sdp").toString().contains("private-sdp"))
        assertFalse(LiveConnection.WebRTC("private-sdp").toString().contains("private-sdp"))
    }
}
