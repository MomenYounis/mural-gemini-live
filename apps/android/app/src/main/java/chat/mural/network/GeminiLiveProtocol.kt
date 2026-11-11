package chat.mural.network

import chat.mural.core.Speaker
import java.util.Base64
import java.util.UUID
import kotlinx.serialization.json.*

/** Raw v1beta wire contract; deliberately separate from Google's SDK configuration objects. */
internal object GeminiLiveProtocol {
    const val MAX_FRAME_BYTES = 524_288
    const val MAX_TEXT_CHARS = 32_000
    const val MAX_OUTPUT_TOKENS = 1_024
    const val TOOL_NAME = "mural_teaching_help"
    const val DEFAULT_MODEL = "gemini-3.8-live"
    const val DEFAULT_VOICE = "Kore"
    const val ENDPOINT = "https://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
    private val json = Json { ignoreUnknownKeys = true }

    data class Configuration(val model: String = DEFAULT_MODEL, val voice: String = DEFAULT_VOICE) {
        init {
            require(model.matches(Regex("gemini-[a-zA-Z0-9.-]{1,100}")))
            require(voice.matches(Regex("[A-Za-z]{1,40}")))
        }
    }

    fun setup(config: Configuration, instructions: String, handle: String? = null, hasHistory: Boolean = false): JsonObject = buildJsonObject {
        put("setup", buildJsonObject {
            put("model", "models/${config.model}")
            put("generationConfig", buildJsonObject {
                put("responseModalities", JsonArray(listOf(JsonPrimitive("AUDIO"))))
                // Bound a generated reply as well as pending PCM; this is not an audio clock.
                put("maxOutputTokens", MAX_OUTPUT_TOKENS)
                put("speechConfig", buildJsonObject {
                    put("voiceConfig", buildJsonObject {
                        put("prebuiltVoiceConfig", buildJsonObject { put("voiceName", config.voice) })
                    })
                })
            })
            put("systemInstruction", content("user", instructions.replace(
                "Delegate current facts and detailed explanations to the client.",
                "Use the declared mural_teaching_help tool for detailed explanations. Current web search is unavailable; say so when current facts or sources are required.")
                .take(MAX_TEXT_CHARS)))
            put("inputAudioTranscription", buildJsonObject { put("mode", "VERBATIM") })
            put("outputAudioTranscription", buildJsonObject { put("mode", "VERBATIM") })
            put("realtimeInputConfig", buildJsonObject {
                put("automaticActivityDetection", buildJsonObject {
                    put("disabled", false)
                    put("startOfSpeechSensitivity", "START_SENSITIVITY_LOW")
                    put("endOfSpeechSensitivity", "END_SENSITIVITY_LOW")
                    put("prefixPaddingMs", 200)
                    put("silenceDurationMs", 1_200)
                })
                put("interimTranscriptTimestampEnabled", true)
            })
            put("sessionResumption", buildJsonObject { if (handle != null) put("handle", handle) })
            put("contextWindowCompression", buildJsonObject { put("slidingWindow", buildJsonObject {}) })
            if (hasHistory && handle == null) put("historyConfig", buildJsonObject { put("initialHistoryInClientContent", true) })
            put("tools", JsonArray(listOf(buildJsonObject {
                put("functionDeclarations", JsonArray(listOf(buildJsonObject {
                    put("name", TOOL_NAME)
                    put("description", "Request bounded teaching help or a word explanation. Current-fact search is unavailable. Return the result to the learner in the normal tutoring flow; never speak tool metadata.")
                    put("behavior", "BLOCKING")
                    put("parameters", buildJsonObject {
                        put("type", "OBJECT")
                        put("properties", buildJsonObject { put("request", buildJsonObject { put("type", "STRING") }) })
                        put("required", JsonArray(listOf(JsonPrimitive("request"))))
                    })
                })))
            })))
        })
    }

    /** Initial history is explicitly acknowledged by the historyConfig contract, without a reply. */
    fun history(history: JsonArray): JsonObject = buildJsonObject {
        put("clientContent", buildJsonObject {
            put("turns", JsonArray(history.takeLast(40).mapNotNull { element ->
                val item = element as? JsonObject ?: return@mapNotNull null
                val role = when (item.geminiString("role")) { "assistant", "model" -> "model"; "user" -> "user"; else -> return@mapNotNull null }
                val text = item.geminiString("text") ?: item.geminiString("content") ?: (item["content"] as? JsonArray)?.mapNotNull {
                    (it as? JsonObject)?.geminiString("text")
                }?.joinToString("") ?: return@mapNotNull null
                content(role, text.take(4_000))
            }))
            put("turnComplete", true)
        })
    }

    fun text(text: String, respond: Boolean): JsonObject = buildJsonObject {
        put("clientContent", buildJsonObject {
            put("turns", JsonArray(listOf(content("user", text.take(MAX_TEXT_CHARS)))))
            put("turnComplete", respond)
        })
    }

    fun audio(pcm16: ByteArray): JsonObject {
        require(pcm16.size in 2..6_400 && pcm16.size % 2 == 0)
        return buildJsonObject { put("realtimeInput", buildJsonObject {
            put("audio", buildJsonObject { put("mimeType", "audio/pcm;rate=16000"); put("data", Base64.getEncoder().encodeToString(pcm16)) })
        }) }
    }

    fun audioStreamEnd(): JsonObject = buildJsonObject { put("realtimeInput", buildJsonObject { put("audioStreamEnd", true) }) }
    fun toolResult(id: String, name: String, text: String): JsonObject = buildJsonObject {
        put("toolResponse", buildJsonObject { put("functionResponses", JsonArray(listOf(buildJsonObject {
            put("id", id); put("name", name); put("response", buildJsonObject { put("result", text.take(MAX_TEXT_CHARS)) })
        }))) })
    }

    private fun content(role: String, text: String) = buildJsonObject {
        put("role", role); put("parts", JsonArray(listOf(buildJsonObject { put("text", text) })))
    }

    sealed interface Message {
        data object SetupComplete : Message
        data class Audio(val bytes: ByteArray) : Message
        data class Transcription(val speaker: Speaker, val text: String, val interim: Boolean,
            val finished: Boolean, val startMS: Long?, val endMS: Long?) : Message
        data object Interrupted : Message
        data object GenerationComplete : Message
        data object TurnComplete : Message
        data object WaitingForInput : Message
        data object Processing : Message
        data class VoiceActivity(val started: Boolean, val offsetMS: Long?) : Message
        data class Usage(val inputTokens: Long, val outputTokens: Long) : Message
        data class ToolCall(val id: String, val name: String, val args: JsonObject) : Message
        data class ToolCancelled(val ids: List<String>) : Message
        data class Resumption(val handle: String?, val resumable: Boolean) : Message {
            override fun toString() = "Resumption([redacted])"
        }
        data class GoAway(val timeLeftMS: Long) : Message
        data class Error(val message: String, val retryable: Boolean = false) : Message
    }

    fun decode(frame: String): List<Message> {
        if (frame.length > MAX_FRAME_BYTES || frame.toByteArray(Charsets.UTF_8).size > MAX_FRAME_BYTES || !boundedJsonDepth(frame)) {
            return listOf(Message.Error("Google sent an invalid or oversized voice message."))
        }
        val value = try { json.parseToJsonElement(frame) as? JsonObject } catch (_: Exception) { null }
            ?: return listOf(Message.Error("Google sent an invalid voice message."))
        return buildList {
            (value["error"] as? JsonObject)?.let {
                val reason = (it["details"] as? JsonArray).orEmpty().mapNotNull { detail ->
                    (detail as? JsonObject)?.geminiString("reason")?.takeIf { code -> code in setOf("API_KEY_INVALID", "API_KEY_EXPIRED") }
                }.firstOrNull()
                add(Message.Error(safeError(it.number("code")?.toInt(), reason ?: it.geminiString("status"))))
                return@buildList
            }
            if (value["setupComplete"] is JsonObject) add(Message.SetupComplete)
            (value["voiceActivity"] as? JsonObject)?.let {
                when (it.geminiString("type")) {
                    "ACTIVITY_START" -> add(Message.VoiceActivity(true, durationMS(it["audioOffset"])))
                    "ACTIVITY_END" -> add(Message.VoiceActivity(false, durationMS(it["audioOffset"])))
                }
            }
            (value["usageMetadata"] as? JsonObject)?.let {
                add(Message.Usage((it.number("promptTokenCount") ?: 0).coerceAtLeast(0), (it.number("responseTokenCount") ?: it.number("candidatesTokenCount") ?: 0).coerceAtLeast(0)))
            }
            (value["serverContent"] as? JsonObject)?.let { server ->
                // Process all fields; transcription and audio are allowed in the same message.
                val interrupted = server.flag("interrupted")
                if (interrupted) add(Message.Interrupted)
                for ((key, speaker, interim) in listOf(Triple("interimInputTranscription", Speaker.user, true),
                    Triple("inputTranscription", Speaker.user, false), Triple("outputTranscription", Speaker.assistant, false))) {
                    (server[key] as? JsonObject)?.let { transcript ->
                        val text = transcript.geminiString("text") ?: ""
                        if (text.length > MAX_TEXT_CHARS) {
                            add(Message.Error("Google sent a voice transcript that exceeded its safe limits.")); return@let
                        }
                        val words = (transcript["words"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                        if (text.isNotEmpty()) add(Message.Transcription(speaker, text, interim,
                            transcript.flag("finished"), durationMS(transcript["startOffset"]) ?: words.firstOrNull()?.let { durationMS(it["startOffset"]) },
                            durationMS(transcript["endOffset"]) ?: words.lastOrNull()?.let { durationMS(it["endOffset"]) }))
                    }
                }
                if (!interrupted) {
                    val parts = ((server["modelTurn"] as? JsonObject)?.get("parts") as? JsonArray).orEmpty()
                    for (part in parts) {
                        val inline = (part as? JsonObject)?.get("inlineData") as? JsonObject ?: continue
                        val mime = inline.geminiString("mimeType") ?: continue
                        if (mime != "audio/pcm;rate=24000" && mime != "audio/pcm;rate=24000;channels=1") {
                            add(Message.Error("Google returned an unsupported voice audio format.")); continue
                        }
                        val data = inline.geminiString("data") ?: continue
                        val audio = try { Base64.getDecoder().decode(data) } catch (_: IllegalArgumentException) { null }
                        if (audio == null || audio.isEmpty() || audio.size % 2 != 0 || audio.size > MAX_FRAME_BYTES / 2) {
                            add(Message.Error("Google sent invalid voice audio."))
                        } else add(Message.Audio(audio))
                    }
                }
                if (server.flag("generationComplete")) add(Message.GenerationComplete)
                if (server.flag("turnComplete")) add(Message.TurnComplete)
                if (server.geminiString("interactionStatus") == "IN_PROGRESS") add(Message.Processing)
                if (server.flag("waitingForInput") || server.geminiString("interactionStatus") in setOf("IDLE", "REQUIRES_ACTION")) add(Message.WaitingForInput)
            }
            (value["toolCall"] as? JsonObject)?.let { tool ->
                for (element in (tool["functionCalls"] as? JsonArray).orEmpty().take(8)) {
                    val call = element as? JsonObject ?: continue
                    val id = call.geminiString("id")?.takeIf { it.length in 1..200 } ?: continue
                    val name = call.geminiString("name")?.take(100) ?: "unknown"
                    add(Message.ToolCall(id, name, call["args"] as? JsonObject ?: buildJsonObject {}))
                }
            }
            (value["toolCallCancellation"] as? JsonObject)?.let { tool ->
                add(Message.ToolCancelled((tool["ids"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf { id -> id.length in 1..200 } }.take(32)))
            }
            (value["sessionResumptionUpdate"] as? JsonObject)?.let {
                val resumable = it.flag("resumable")
                add(Message.Resumption(it.geminiString("newHandle")?.takeIf { handle -> resumable && handle.length in 1..16_384 }, resumable))
            }
            (value["goAway"] as? JsonObject)?.let { add(Message.GoAway((durationMS(it["timeLeft"]) ?: 0).coerceIn(0, 600_000))) }
        }
    }

    fun safeError(code: Int?, status: String? = null): String = when {
        code == 401 || status in setOf("UNAUTHENTICATED", "API_KEY_INVALID", "API_KEY_EXPIRED") -> "Google could not authenticate your Gemini key. Check the key in Settings."
        code == 403 || status == "PERMISSION_DENIED" -> "Your Gemini project does not have access to this model or API. Check its permissions and availability."
        code == 429 || status == "RESOURCE_EXHAUSTED" -> "Google's Gemini quota or rate limit was reached. Check your project quota and try again later."
        code == 404 || status == "NOT_FOUND" -> "This Gemini model is unavailable for your API project. Check the configured model."
        code == 400 || status == "INVALID_ARGUMENT" -> "Google could not accept the Gemini session configuration or resumption handle. Start a new conversation."
        code in listOf(500, 502, 503, 504) || status in listOf("UNAVAILABLE", "INTERNAL", "DEADLINE_EXCEEDED") -> "Google's Gemini voice service is temporarily unavailable. Try again later."
        else -> "The Gemini voice connection failed. Check your network and API project."
    }

    internal fun durationMS(value: JsonElement?): Long? {
        val seconds = (value as? JsonPrimitive)?.contentOrNull?.removeSuffix("s")?.toDoubleOrNull()
            ?: (value as? JsonObject)?.let { (it.number("seconds") ?: 0).toDouble() + (it.number("nanos") ?: 0).toDouble() / 1e9 }
        return seconds?.takeIf { it.isFinite() && it >= 0 && it <= 86_400 }?.times(1000)?.toLong()
    }

    private fun boundedJsonDepth(text: String): Boolean {
        var depth = 0; var quoted = false; var escaped = false
        for (character in text) {
            if (escaped) { escaped = false; continue }
            if (quoted && character == '\\') { escaped = true; continue }
            if (character == '"') { quoted = !quoted; continue }
            if (!quoted) when (character) { '{', '[' -> if (++depth > 40) return false; '}', ']' -> if (--depth < 0) return false }
        }
        return depth == 0 && !quoted
    }
}

internal fun JsonObject.geminiString(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
private fun JsonObject.flag(key: String) = (this[key] as? JsonPrimitive)?.booleanOrNull == true
private fun JsonObject.number(key: String) = (this[key] as? JsonPrimitive)?.longOrNull

/** Stable IDs survive interim revisions. Missing provider alignment uses an explicitly estimated media clock. */
internal class GeminiTranscriptAssembler(private val id: () -> String = { UUID.randomUUID().toString() }) {
    data class Fragment(val id: String, val speaker: Speaker, val text: String, val startMS: Long,
        val endMS: Long, val interim: Boolean, val estimatedTiming: Boolean)
    private var input: Fragment? = null
    private var output: Fragment? = null
    private var interimInput = false
    private var committedInput = false
    private var streamOriginMS = 0L
    private var previousEndMS = 0L

    fun resumed(mediaMS: Long) { streamOriginMS = mediaMS; input = null; output = null; interimInput = false; committedInput = false }

    fun append(message: GeminiLiveProtocol.Message.Transcription, mediaMS: Long): Fragment {
        val previous = if (message.speaker == Speaker.user) input else output
        val providerAligned = message.startMS != null && message.endMS != null && message.endMS >= message.startMS
        val start = if (providerAligned) streamOriginMS + message.startMS!! else previous?.startMS ?: maxOf(previousEndMS, mediaMS - 1_200).coerceAtLeast(0)
        val end = if (providerAligned) streamOriginMS + message.endMS!! else maxOf(start, mediaMS)
        val replace = message.interim || (message.speaker == Speaker.user && interimInput)
        val text = when {
            previous == null || replace -> message.text
            else -> previous.text + message.text
        }
        require(text.length <= GeminiLiveProtocol.MAX_TEXT_CHARS) { "Voice transcript exceeded its safe limit" }
        val fragment = Fragment(previous?.id ?: id(), message.speaker, text, start, maxOf(start, end),
            message.interim || !message.finished, previous?.estimatedTiming == true || !providerAligned)
        if (message.speaker == Speaker.user) { input = fragment; interimInput = message.interim; if (!message.interim) committedInput = true } else output = fragment
        return fragment
    }

    fun finishInput(): Fragment? {
        val final = input?.takeIf { committedInput }?.copy(interim = false)
        input = null; interimInput = false; committedInput = false
        final?.let { previousEndMS = maxOf(previousEndMS, it.endMS) }
        return final
    }
    fun finishOutput(playedUntilMS: Long): Fragment? = output?.copy(interim = false,
        endMS = maxOf(output!!.startMS, playedUntilMS))?.also { output = null; previousEndMS = maxOf(previousEndMS, it.endMS) }
    fun takeOutput(): Fragment? = output?.also { output = null; previousEndMS = maxOf(previousEndMS, it.endMS) }
    fun interrupt() { output = null }
}
