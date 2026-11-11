package chat.mural.network

import android.content.Context
import chat.mural.core.AIVendor
import chat.mural.core.Speaker
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.*
import java.util.UUID

/** App operations carry intent. Only provider adapters know their wire protocol. */
sealed interface LiveOperation {
    data class UpdateContext(val content: String, val respond: Boolean = false, val referenceOnly: Boolean = false) : LiveOperation
    data class RequestReply(val content: String) : LiveOperation
    data class TypedTurn(val text: String) : LiveOperation
    data class ToolResult(val id: String, val name: String, val text: String) : LiveOperation
}

sealed interface LiveEvent {
    data class Created(val providerID: String?, val minimumUsageSeconds: Double? = null) : LiveEvent
    data object Ready : LiveEvent
    data class Transcript(val id: String, val speaker: Speaker, val text: String, val startMS: Int,
        val endMS: Int, val interim: Boolean = false, val estimatedTiming: Boolean = true) : LiveEvent
    data object Interrupted : LiveEvent
    data object GenerationComplete : LiveEvent
    data object PlaybackComplete : LiveEvent
    data object TurnComplete : LiveEvent
    data object WaitingForInput : LiveEvent
    data object Processing : LiveEvent
    data class Usage(val inputTokens: Int, val outputTokens: Int) : LiveEvent
    data class LegacyUsage(val seconds: Double?, val final: Boolean) : LiveEvent
    data class ToolCall(val id: String, val name: String, val args: JsonObject) : LiveEvent
    data class ToolCancelled(val ids: List<String>) : LiveEvent
    data object Reconnecting : LiveEvent
    data object Resumed : LiveEvent
    data object Closed : LiveEvent
    data class Failure(val message: String) : LiveEvent
    data class LegacyError(val code: String?) : LiveEvent
}

/** Retains the existing OpenAI/hosted WebRTC lifecycle unchanged behind a narrow adapter. */
internal object OpenAILiveProtocol {
    fun encode(operation: LiveOperation): JsonObject = buildJsonObject {
        val kind = when (operation) {
            is LiveOperation.ToolResult -> "commentary"
            is LiveOperation.UpdateContext -> if (operation.referenceOnly) "thinking" else "instructions"
            is LiveOperation.RequestReply -> "instructions"
            is LiveOperation.TypedTurn -> error("OpenAI typed replies use the existing teaching path")
        }
        put("type", "session.$kind.append"); put("event_id", UUID.randomUUID().toString())
        put("delegation_id", (operation as? LiveOperation.ToolResult)?.id?.takeIf { it.isNotBlank() }?.let(::JsonPrimitive) ?: JsonNull)
        put("content", when (operation) {
            is LiveOperation.ToolResult -> operation.text
            is LiveOperation.UpdateContext -> operation.content
            is LiveOperation.RequestReply -> operation.content
            is LiveOperation.TypedTurn -> operation.text
        }.take(1000))
    }
    fun decode(event: JsonObject): LiveEvent? = when (event["type"]?.jsonPrimitive?.contentOrNull) {
        "mural.session.created" -> LiveEvent.Created((event["session"] as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull, 15.0)
        "session.started" -> LiveEvent.Ready
        "session.input_transcript.delta", "session.output_transcript.delta" -> {
            val text = event["delta"]?.jsonPrimitive?.contentOrNull
            val start = event["start_ms"]?.jsonPrimitive?.intOrNull
            val end = event["end_ms"]?.jsonPrimitive?.intOrNull
            if (text == null || start == null || end == null || start < 0 || end < start || text.length > 50_000) null
            else LiveEvent.Transcript(event["event_id"]?.jsonPrimitive?.contentOrNull ?: UUID.randomUUID().toString(),
                if (event["type"]?.jsonPrimitive?.content == "session.input_transcript.delta") Speaker.user else Speaker.assistant,
                text, start, end, estimatedTiming = false)
        }
        "session.delegation.created" -> (event["delegation"] as? JsonObject)?.let { d ->
            if (d["target"]?.jsonPrimitive?.contentOrNull != "client") null else
                d["id"]?.jsonPrimitive?.contentOrNull?.let { LiveEvent.ToolCall(it, "legacy_delegation", buildJsonObject {}) }
        }
        "session.usage.updated", "session.closed" -> LiveEvent.LegacyUsage(
            (event["usage"] as? JsonObject)?.get("seconds")?.jsonPrimitive?.doubleOrNull?.takeIf { it.isFinite() && it in 0.0..31536000.0 },
            event["type"]?.jsonPrimitive?.content == "session.closed")
        "error" -> LiveEvent.LegacyError((event["error"] as? JsonObject)?.get("code")?.jsonPrimitive?.contentOrNull)
        else -> null
    }
}

internal class NativeLiveConversation(context: Context, scope: CoroutineScope, private val geminiCredential: () -> String?) {
    var onEvent: ((LiveEvent) -> Unit)? = null
    var onFailure: ((String) -> Unit)? = null
    var onLevels: ((Double, Double) -> Unit)? = null
    private val openai = LiveTransport(context, scope)
    private val gemini = GeminiLiveTransport(context, scope)
    private var vendor = AIVendor.OPENAI
    init {
        openai.onEvent = { OpenAILiveProtocol.decode(it)?.let { event -> onEvent?.invoke(event) } }
        openai.onFailure = { onFailure?.invoke(it) }; openai.onLevels = { i, o -> onLevels?.invoke(i, o) }
        gemini.onEvent = { onEvent?.invoke(it) }; gemini.onFailure = { onFailure?.invoke(it) }
        gemini.onLevels = { i, o -> onLevels?.invoke(i, o) }
    }
    suspend fun connect(api: LiveSessionProvider?, vendor: AIVendor, instructions: String,
        history: JsonArray, language: String?) {
        disconnect(); openai.awaitDisconnected(); gemini.awaitDisconnected(); this.vendor = vendor
        if (vendor == AIVendor.GEMINI) gemini.connect(geminiCredential, LiveSessionRequest(LiveOffer.NativePCM, instructions, history, language))
        else openai.connect(requireNotNull(api), instructions, history, language)
    }
    fun send(operation: LiveOperation): Boolean = if (vendor == AIVendor.GEMINI) gemini.send(operation)
        else openai.send(OpenAILiveProtocol.encode(operation))
    fun mute(value: Boolean) { if (vendor == AIVendor.GEMINI) gemini.mute(value) else openai.mute(value) }
    fun close() { if (vendor == AIVendor.GEMINI) gemini.close() else openai.close() }
    fun disconnect() { openai.disconnect(); gemini.disconnect() }
}
