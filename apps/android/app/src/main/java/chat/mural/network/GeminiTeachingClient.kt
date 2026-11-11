package chat.mural.network

import chat.mural.core.ProviderFailureKind
import chat.mural.core.SourceLink
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer

/** Gemini's stateless teaching helpers. A Live token cannot authorize these REST requests. */
class GeminiTeachingClient private constructor(
    private val readCredential: () -> String?,
    private val model: String,
    client: OkHttpClient,
    private val baseUrl: HttpUrl,
) : TeachingClient {
    constructor(credentials: CredentialStore, model: String = DEFAULT_MODEL) :
        this(credentials::read, model, defaultClient(), API_BASE_URL)

    /** Fixed Google origin for explicitly authorized native/JVM diagnostics; no alternate endpoint. */
    internal constructor(readCredential: () -> String?, model: String = DEFAULT_MODEL) :
        this(readCredential, model, defaultClient(), API_BASE_URL)

    /** Only loopback servers may replace Google's production origin in deterministic offline tests. */
    internal constructor(readCredential: () -> String?, client: OkHttpClient, baseUrl: HttpUrl,
        model: String = DEFAULT_MODEL) :
        this(readCredential, model, client, checkedTestOrigin(baseUrl))

    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .cookieJar(CookieJar.NO_COOKIES).cache(null).retryOnConnectionFailure(false).build()
    private val assessmentClient = this.client.newBuilder().readTimeout(90, TimeUnit.SECONDS)
        .callTimeout(95, TimeUnit.SECONDS).build()

    init {
        require(MODEL_NAME.matches(model)) { "Use a Gemini model name, without an endpoint or path." }
    }

    override suspend fun respond(instructions: String, input: String, schema: JsonObject?, search: Boolean,
        purpose: HelperPurpose?): APIResult {
        // The current native topic view cannot render Google's required Search Suggestions. Never
        // substitute an ungrounded answer, or spend on a request the product cannot present correctly.
        if (search) throw GeminiException.SearchUnavailable
        if (purpose == HelperPurpose.ASSESSMENT) {
            val request = request("streamGenerateContent", requestBody(instructions, input, schema), stream = true)
            val decoder = GeminiTeachingResponse()
            safeResponse(request, assessmentClient) { response ->
                if (response.header("Content-Type")?.substringBefore(';')?.trim()?.equals("text/event-stream", true) != true) {
                    throw GeminiException.InvalidResponse
                }
                readGeminiEvents(response) { decoder.accept(it) }
            }
            // Partial JSON is never learning evidence. Require completion and the full schema.
            return decoder.result(schema)
        }
        val request = request("generateContent", requestBody(instructions, input, schema))
        val response = safeResponse(request) { decodeObject(it.readBoundedBody()) }
        return GeminiTeachingResponse().apply { accept(response) }.result(schema)
    }

    override suspend fun streamMeaning(instructions: String, input: String, onText: (String) -> Unit): APIResult {
        val request = request("streamGenerateContent", requestBody(instructions, input, null), stream = true)
        val callbacks = currentCoroutineContext().minusKey(Job)
        val decoder = GeminiTeachingResponse()
        safeResponse(request) { response ->
            if (response.header("Content-Type")?.substringBefore(';')?.trim()?.equals("text/event-stream", true) != true) {
                throw GeminiException.InvalidResponse
            }
            readGeminiEvents(response) { event ->
                val changed = decoder.accept(event)
                if (changed) withContext(callbacks) { onText(decoder.text) }
            }
        }
        return decoder.result(null)
    }

    private fun requestBody(instructions: String, input: String, schema: JsonObject?): JsonObject = buildJsonObject {
        put("systemInstruction", buildJsonObject { put("parts", buildJsonArray { add(buildJsonObject { put("text", instructions) }) }) })
        put("contents", buildJsonArray { add(buildJsonObject {
            put("role", "user"); put("parts", buildJsonArray { add(buildJsonObject { put("text", input) }) })
        }) })
        put("generationConfig", buildJsonObject {
            put("candidateCount", 1)
            put("maxOutputTokens", if (schema == null) 4_096 else 6_144)
            put("thinkingConfig", buildJsonObject { put("thinkingLevel", "LOW") })
            if (schema != null) put("responseFormat", buildJsonObject { put("text", buildJsonObject {
                // The raw reference uses the protobuf enum; SDK MIME-string tutorials differ.
                put("mimeType", "APPLICATION_JSON"); put("schema", schema)
            }) })
        })
    }

    private fun request(operation: String, body: JsonObject, stream: Boolean = false): Request {
        val payload = body.toString()
        if (payload.toByteArray(Charsets.UTF_8).size > MAX_REQUEST_BYTES) throw GeminiException.InvalidResponse
        val key = readCredential()?.takeIf { it.isNotBlank() && it.length <= 4_096 && it.all { character -> character.code in 33..126 } }
            ?: throw GeminiException.MissingKey
        val targetModel = if (baseUrl == API_BASE_URL && model == DEFAULT_MODEL) FALLBACK_MODEL else model
        val url = baseUrl.newBuilder().addPathSegment("models").addPathSegment("$targetModel:$operation")
            .apply { if (stream) addQueryParameter("alt", "sse") }.build()
        return Request.Builder().url(url).header("x-goog-api-key", key)
            .apply { if (stream) header("Accept", "text/event-stream") }
            .post(payload.toRequestBody(JSON_MEDIA_TYPE)).build()
    }

    private suspend fun <T> safeResponse(request: Request, responseClient: OkHttpClient = client,
        receive: suspend (Response) -> T): T = try {
        streamingResponse(responseClient, request) { response ->
            if (!response.isSuccessful) {
                val code = runCatching {
                    val raw = response.peekBody(MAX_ERROR_BYTES + 1).bytes()
                    if (raw.size > MAX_ERROR_BYTES) null else safeGeminiErrorCode(decodeObject(raw.toString(Charsets.UTF_8)))
                }.getOrNull()
                throw GeminiException.Http(response.code, code)
            }
            receive(response)
        }
    } catch (error: GeminiException) {
        throw error
    } catch (_: IOException) {
        currentCoroutineContext().ensureActive()
        // Transport errors can contain URLs and provider payloads; expose only a fixed safe message.
        throw GeminiException.Unavailable
    } catch (_: IllegalArgumentException) {
        throw GeminiException.InvalidResponse
    }

    private fun Response.readBoundedBody(): String {
        val body = body ?: throw GeminiException.InvalidResponse
        if (body.contentLength() > MAX_RESPONSE_BYTES) throw GeminiException.InvalidResponse
        val source = body.source(); val buffer = Buffer(); var total = 0L
        while (true) {
            val count = source.read(buffer, minOf(8_192L, MAX_RESPONSE_BYTES + 1 - total))
            if (count == -1L) break
            total += count
            if (total > MAX_RESPONSE_BYTES) throw GeminiException.InvalidResponse
        }
        return buffer.readString(Charsets.UTF_8)
    }

    companion object {
        const val DEFAULT_MODEL = "gemini-3.8-flash"
        private const val FALLBACK_MODEL = "gemini-3.5-flash"
        private const val MAX_REQUEST_BYTES = 262_144
        private const val MAX_RESPONSE_BYTES = 1_048_576L
        private const val MAX_ERROR_BYTES = 16_384L
        private val MODEL_NAME = Regex("gemini-[a-z0-9][a-z0-9._-]{0,100}")
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val API_BASE_URL = HttpUrl.Builder().scheme("https").host("generativelanguage.googleapis.com")
            .addPathSegment("v1beta").addPathSegment("").build()
        private fun defaultClient() = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS).writeTimeout(30, TimeUnit.SECONDS).callTimeout(60, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false).cookieJar(CookieJar.NO_COOKIES).cache(null).retryOnConnectionFailure(false).build()
        private fun checkedTestOrigin(url: HttpUrl): HttpUrl {
            require(url.host in setOf("localhost", "127.0.0.1", "::1") && url.username.isEmpty() && url.password.isEmpty() &&
                url.query == null && url.fragment == null) { "Teaching test origins must be loopback URLs." }
            return url
        }
    }
}

/** No raw provider messages, credentials, response bodies, or request URLs reach product errors. */
sealed class GeminiException(message: String) : IOException(message) {
    data object MissingKey : GeminiException("Add your Gemini key in Settings to begin.")
    data object InvalidResponse : GeminiException("Gemini returned an invalid response. Please try again.")
    data object Incomplete : GeminiException("Gemini didn't complete that response. Please try again.")
    data object Refused : GeminiException("Mural couldn't complete that request. Try a different topic.")
    data object Unavailable : GeminiException("Gemini couldn't connect. Check your connection and try again.")
    data object SearchUnavailable : GeminiException("Sourced current topics are unavailable with Gemini in this Android version.")
    class Http(val status: Int, rawCode: String? = null) : GeminiException(messageFor(status, rawCode)) {
        val code = rawCode?.takeIf { it in SAFE_GEMINI_CODES }
        val kind: ProviderFailureKind get() = when {
            code in setOf("API_KEY_INVALID", "API_KEY_EXPIRED", "UNAUTHENTICATED") || status == 401 -> ProviderFailureKind.authentication
            status == 403 || status == 404 -> ProviderFailureKind.modelAccess
            code == "RATE_LIMIT_EXCEEDED" -> ProviderFailureKind.rateLimit
            status == 429 || code in setOf("RESOURCE_EXHAUSTED", "QUOTA_EXCEEDED") -> ProviderFailureKind.quota
            status == 408 || status >= 500 -> ProviderFailureKind.unavailable
            status == 400 -> ProviderFailureKind.invalidRequest
            else -> ProviderFailureKind.unknown
        }
    }

    companion object {
        private fun messageFor(status: Int, code: String?): String = when {
            code in setOf("API_KEY_INVALID", "API_KEY_EXPIRED", "UNAUTHENTICATED") || status == 401 -> "Your Gemini key wasn't accepted. Check it in Settings."
            status == 403 || status == 404 -> "Your Gemini project may not have access to this model. Check your Google project."
            status == 429 -> "Gemini's quota or rate limit was reached. Check your Google project billing and limits."
            status == 400 -> "Gemini couldn't accept this request. Check the selected model and try again."
            else -> "Gemini couldn't complete the request. Please try again."
        }
    }
}

private val SAFE_GEMINI_CODES = setOf("API_KEY_INVALID", "API_KEY_EXPIRED", "UNAUTHENTICATED", "PERMISSION_DENIED",
    "NOT_FOUND", "RESOURCE_EXHAUSTED", "RATE_LIMIT_EXCEEDED", "QUOTA_EXCEEDED", "INVALID_ARGUMENT", "FAILED_PRECONDITION", "INTERNAL", "UNAVAILABLE", "DEADLINE_EXCEEDED")

private fun safeGeminiErrorCode(response: JsonObject): String? {
    val error = response["error"] as? JsonObject ?: return null
    for (detail in error.array("details")) {
        val reason = (detail as? JsonObject)?.string("reason")
        if (reason in SAFE_GEMINI_CODES) return reason
    }
    return error.string("status")?.takeIf { it in SAFE_GEMINI_CODES }
}

private fun decodeObject(payload: String): JsonObject = try {
    Json.parseToJsonElement(payload) as? JsonObject ?: throw GeminiException.InvalidResponse
} catch (_: IllegalArgumentException) { throw GeminiException.InvalidResponse }

/** Aggregates visible text, final token counts, and actual grounding; never exposes thought parts. */
internal class GeminiTeachingResponse {
    private val content = StringBuilder()
    private val sources = linkedMapOf<String, SourceLink>()
    private val searchQueries = linkedSetOf<String>()
    private var usage = APIUsage()
    private var promptTokens = 0
    private var candidateTokens = 0
    private var thoughtTokens = 0
    private var finished = false
    val text: String get() = content.toString()

    fun accept(response: JsonObject): Boolean {
        if (response["error"] != null) throw GeminiException.Incomplete
        val blocked = (response["promptFeedback"] as? JsonObject)?.string("blockReason")
        if (blocked != null && blocked != "BLOCK_REASON_UNSPECIFIED") throw GeminiException.Refused
        (response["usageMetadata"] as? JsonObject)?.let { value ->
            fun token(name: String, previous: Int) = ((value[name] as? JsonPrimitive)?.longOrNull ?: previous.toLong())
                .coerceIn(0, 1_000_000_000).toInt()
            promptTokens = token("promptTokenCount", promptTokens)
            candidateTokens = token("candidatesTokenCount", candidateTokens)
            thoughtTokens = token("thoughtsTokenCount", thoughtTokens)
            // Output includes separately reported thinking tokens so local accounting does not hide them.
            usage = APIUsage(promptTokens, (candidateTokens.toLong() + thoughtTokens)
                .coerceAtMost(1_000_000_000).toInt(), 0)
        }
        val candidates = response.array("candidates")
        if (candidates.size > 1) throw GeminiException.InvalidResponse
        val candidate = candidates.firstOrNull() as? JsonObject ?: return false
        var changed = false
        for (element in (candidate["content"] as? JsonObject)?.array("parts").orEmpty()) {
            val part = element as? JsonObject ?: throw GeminiException.InvalidResponse
            if ((part["thought"] as? JsonPrimitive)?.booleanOrNull == true) continue
            val fragment = part.string("text") ?: continue
            if (finished && fragment.isNotEmpty()) throw GeminiException.InvalidResponse
            content.append(fragment); changed = changed || fragment.isNotEmpty()
            if (content.toString().toByteArray(Charsets.UTF_8).size > 65_536) throw GeminiException.InvalidResponse
        }
        (candidate["groundingMetadata"] as? JsonObject)?.let { grounding ->
            for (element in grounding.array("groundingChunks")) {
                val web = (element as? JsonObject)?.get("web") as? JsonObject ?: continue
                val url = web.string("uri") ?: continue
                val source = SourceLink(web.string("title")?.take(512) ?: "Source", url)
                if (url.length <= 8_192 && source.safeUrl() != null && sources.size < 32) sources.putIfAbsent(url, source)
            }
            grounding.array("webSearchQueries").forEach { value ->
                (value as? JsonPrimitive)?.contentOrNull?.takeIf { it.length <= 1_024 && searchQueries.size < 32 }?.let(searchQueries::add)
            }
        }
        candidate.string("finishReason")?.let { reason ->
            when (reason) {
                "STOP" -> finished = true
                "SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII" -> throw GeminiException.Refused
                "FINISH_REASON_UNSPECIFIED" -> Unit
                else -> throw GeminiException.Incomplete
            }
        }
        return changed
    }

    fun result(schema: JsonObject?): APIResult {
        if (!finished || content.isBlank()) throw GeminiException.Incomplete
        if (schema != null) {
            val value = try { Json.parseToJsonElement(text) } catch (_: IllegalArgumentException) { throw GeminiException.InvalidResponse }
            if (!matchesGeminiSchema(value, schema)) throw GeminiException.InvalidResponse
        }
        return APIResult(text, sources.values.toList(), usage.copy(searches = searchQueries.size))
    }
}

/** Validates the bounded schema subset used by Android's existing learning-assessment contract. */
private fun matchesGeminiSchema(value: JsonElement, schema: JsonObject, depth: Int = 0): Boolean {
    if (depth > 20) return false
    if (schema["enum"] is JsonArray && value !in schema.array("enum")) return false
    return when (schema.string("type")) {
        "object" -> {
            val obj = value as? JsonObject ?: return false
            val fields = schema["properties"] as? JsonObject ?: JsonObject(emptyMap())
            if (schema.array("required").any { (it as? JsonPrimitive)?.contentOrNull !in obj }) return false
            if ((schema["additionalProperties"] as? JsonPrimitive)?.booleanOrNull == false && obj.keys.any { it !in fields }) return false
            fields.all { (key, child) -> key !in obj || child is JsonObject && matchesGeminiSchema(obj.getValue(key), child, depth + 1) }
        }
        "array" -> {
            val values = value as? JsonArray ?: return false
            val max = (schema["maxItems"] as? JsonPrimitive)?.intOrNull
            val min = (schema["minItems"] as? JsonPrimitive)?.intOrNull
            val item = schema["items"] as? JsonObject
            (max == null || values.size <= max) && (min == null || values.size >= min) &&
                (item == null || values.all { matchesGeminiSchema(it, item, depth + 1) })
        }
        "string" -> value is JsonPrimitive && value.isString
        "integer", "number" -> {
            val number = (value as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull ?: return false
            val minimum = (schema["minimum"] as? JsonPrimitive)?.doubleOrNull
            val maximum = (schema["maximum"] as? JsonPrimitive)?.doubleOrNull
            number.isFinite() && (schema.string("type") != "integer" || number % 1.0 == 0.0) &&
                (minimum == null || number >= minimum) && (maximum == null || number <= maximum)
        }
        "boolean" -> value is JsonPrimitive && !value.isString && value.booleanOrNull != null
        "null" -> value == JsonNull
        else -> false
    }
}

private suspend fun readGeminiEvents(response: Response, receive: suspend (JsonObject) -> Unit) {
    val source = response.body?.source() ?: throw GeminiException.InvalidResponse
    val data = StringBuilder(); var total = 0L
    suspend fun dispatch() {
        if (data.isEmpty()) return
        val payload = data.toString(); data.clear()
        if (payload == "[DONE]") return
        receive(decodeObject(payload))
    }
    while (!source.exhausted()) {
        currentCoroutineContext().ensureActive()
        val line = try { source.readUtf8LineStrict(65_536) } catch (_: IOException) { throw GeminiException.InvalidResponse }
        total += line.toByteArray(Charsets.UTF_8).size + 1
        if (total > 1_048_576) throw GeminiException.InvalidResponse
        if (line.isEmpty()) dispatch()
        else if (line.startsWith("data:")) {
            if (data.isNotEmpty()) data.append('\n')
            data.append(line.removePrefix("data:").removePrefix(" "))
            if (data.toString().toByteArray(Charsets.UTF_8).size > 65_536) throw GeminiException.InvalidResponse
        }
    }
    dispatch()
}

private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
private fun JsonObject.array(key: String) = this[key] as? JsonArray ?: JsonArray(emptyList())
