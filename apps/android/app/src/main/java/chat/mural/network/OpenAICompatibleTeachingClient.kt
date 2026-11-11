package chat.mural.network

import android.content.Context
import chat.mural.core.SourceLink
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

enum class TeachingProvider(
    val id: String,
    val displayName: String,
    val shortName: String,
    val baseUrl: String,
    val defaultModel: String,
    val keysUrl: String,
) {
    DEFAULT_GEMINI(
        id = "default_gemini",
        displayName = "Default (Gemini 3.5 Flash)",
        shortName = "Gemini 3.5 Flash",
        baseUrl = "https://generativelanguage.googleapis.com/v1beta/",
        defaultModel = "gemini-3.5-flash",
        keysUrl = "https://aistudio.google.com/api-keys",
    ),
    OPENROUTER(
        id = "openrouter",
        displayName = "OpenRouter (openrouter.ai)",
        shortName = "OpenRouter",
        baseUrl = "https://openrouter.ai/api/v1/",
        defaultModel = "google/gemini-2.5-flash",
        keysUrl = "https://openrouter.ai/keys",
    ),
    SOVEREIGN_EG(
        id = "sovereign_eg",
        displayName = "SovereignEG (sovereigneg.com)",
        shortName = "SovereignEG",
        baseUrl = "https://backend.sovereigneg.com/v1/",
        defaultModel = "gemini-3.5-flash",
        keysUrl = "https://sovereigneg.com/dashboard",
    );

    companion object {
        fun fromId(id: String?): TeachingProvider =
            entries.firstOrNull { it.id == id || it.name.equals(id, ignoreCase = true) } ?: DEFAULT_GEMINI
    }
}

data class TeachingModelOption(
    val id: String,
    val name: String,
    val description: String = "",
)

class TeachingProviderStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun readProvider(): TeachingProvider =
        TeachingProvider.fromId(prefs.getString(KEY_PROVIDER, TeachingProvider.DEFAULT_GEMINI.id))

    fun saveProvider(provider: TeachingProvider) {
        prefs.edit().putString(KEY_PROVIDER, provider.id).apply()
    }

    fun readModel(provider: TeachingProvider): String {
        val raw = when (provider) {
            TeachingProvider.DEFAULT_GEMINI -> TeachingProvider.DEFAULT_GEMINI.defaultModel
            TeachingProvider.OPENROUTER -> prefs.getString(KEY_OPENROUTER_MODEL, null)
            TeachingProvider.SOVEREIGN_EG -> prefs.getString(KEY_SOVEREIGN_MODEL, null)
        }
        return raw?.trim()?.takeIf { it.isNotEmpty() } ?: provider.defaultModel
    }

    fun saveModel(provider: TeachingProvider, modelId: String) {
        val clean = modelId.trim().ifEmpty { provider.defaultModel }
        when (provider) {
            TeachingProvider.DEFAULT_GEMINI -> Unit
            TeachingProvider.OPENROUTER -> prefs.edit().putString(KEY_OPENROUTER_MODEL, clean).apply()
            TeachingProvider.SOVEREIGN_EG -> prefs.edit().putString(KEY_SOVEREIGN_MODEL, clean).apply()
        }
    }

    companion object {
        private const val PREFS_NAME = "mural_teaching_provider_prefs"
        private const val KEY_PROVIDER = "teaching_provider"
        private const val KEY_OPENROUTER_MODEL = "openrouter_model"
        private const val KEY_SOVEREIGN_MODEL = "sovereign_eg_model"
    }
}

/**
 * OpenAI-compatible client for OpenRouter (https://openrouter.ai/api/v1) and
 * SovereignEG (https://backend.sovereigneg.com/v1) handling Post-Session Mistake Analysis,
 * Flashcard generation, live Meaning translation, A little help, and Learning Assessments.
 */
class OpenAICompatibleTeachingClient(
    private val provider: TeachingProvider,
    private val readCredential: () -> String?,
    private val readModel: () -> String,
    client: OkHttpClient = defaultHttpClient(),
    private val baseUrl: HttpUrl = provider.baseUrl.toHttpUrl(),
) : TeachingClient {

    private val httpClient = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .cookieJar(CookieJar.NO_COOKIES)
        .cache(null)
        .retryOnConnectionFailure(true)
        .build()

    override suspend fun respond(
        instructions: String,
        input: String,
        schema: JsonObject?,
        search: Boolean,
        purpose: HelperPurpose?,
    ): APIResult = withContext(Dispatchers.IO) {
        val key = requireValidKey()
        val modelId = readModel().trim().ifEmpty { provider.defaultModel }
        val systemText = if (schema != null) {
            buildSchemaInstruction(instructions, schema)
        } else {
            instructions
        }

        val firstAttempt = executeChatCompletion(
            key = key,
            modelId = modelId,
            systemText = systemText,
            userText = input,
            useJsonObjectFormat = schema != null,
        )

        val responseJson = if (firstAttempt == null && schema != null) {
            // Some models on OpenRouter/SovereignEG reject response_format={"type":"json_object"} with HTTP 400;
            // retry once without response_format and parse the JSON object from the model output.
            executeChatCompletion(
                key = key,
                modelId = modelId,
                systemText = systemText,
                userText = input,
                useJsonObjectFormat = false,
            ) ?: throw GeminiException.InvalidResponse
        } else {
            firstAttempt ?: throw GeminiException.InvalidResponse
        }

        val rawText = extractAssistantText(responseJson)
        val finalText = if (schema != null) extractCleanJson(rawText) else rawText.trim()
        if (finalText.isBlank()) throw GeminiException.Incomplete

        if (schema != null) {
            val parsed = try {
                jsonParser.parseToJsonElement(finalText)
            } catch (_: Exception) {
                throw GeminiException.InvalidResponse
            }
            if (parsed !is JsonObject) throw GeminiException.InvalidResponse
        }

        val usage = parseUsage(responseJson)
        APIResult(finalText, emptyList<SourceLink>(), usage)
    }

    override suspend fun streamMeaning(
        instructions: String,
        input: String,
        onText: (String) -> Unit,
    ): APIResult = withContext(Dispatchers.IO) {
        val key = requireValidKey()
        val modelId = readModel().trim().ifEmpty { provider.defaultModel }
        val callbacks = currentCoroutineContext().minusKey(Job)

        val payload = buildJsonObject {
            put("model", modelId)
            put("stream", true)
            put("max_tokens", 2048)
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "system")
                    put("content", instructions)
                })
                add(buildJsonObject {
                    put("role", "user")
                    put("content", input)
                })
            })
        }

        val request = buildRequest("chat/completions", key, payload, stream = true)
        val aggregated = StringBuilder()
        var usage = APIUsage()

        try {
            streamingResponse(httpClient, request) { response ->
                if (!response.isSuccessful) {
                    throw mapHttpException(response)
                }
                val contentType = response.header("Content-Type").orEmpty()
                if (!contentType.contains("text/event-stream", ignoreCase = true)) {
                    val bodyStr = response.body?.string().orEmpty()
                    val obj = jsonParser.parseToJsonElement(bodyStr).jsonObject
                    val text = extractAssistantText(obj).trim()
                    usage = parseUsage(obj)
                    if (text.isNotEmpty()) {
                        aggregated.append(text)
                        withContext(callbacks) { onText(aggregated.toString()) }
                    }
                    return@streamingResponse
                }

                val source = response.body?.source() ?: throw GeminiException.InvalidResponse
                while (!source.exhausted()) {
                    currentCoroutineContext().ensureActive()
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val dataStr = line.removePrefix("data:").trim()
                    if (dataStr.isEmpty() || dataStr == "[DONE]") continue
                    val chunkObj = runCatching { jsonParser.parseToJsonElement(dataStr).jsonObject }.getOrNull() ?: continue
                    chunkObj["usage"]?.let { usage = parseUsage(chunkObj) }
                    val choices = chunkObj["choices"] as? JsonArray ?: continue
                    val firstChoice = choices.firstOrNull() as? JsonObject ?: continue
                    val delta = (firstChoice["delta"] as? JsonObject)
                        ?: (firstChoice["message"] as? JsonObject)
                    val deltaText = (delta?.get("content") as? JsonPrimitive)?.contentOrNull.orEmpty()
                    if (deltaText.isNotEmpty()) {
                        aggregated.append(deltaText)
                        withContext(callbacks) { onText(aggregated.toString()) }
                    }
                }
            }
        } catch (e: GeminiException) {
            throw e
        } catch (_: IOException) {
            currentCoroutineContext().ensureActive()
            throw GeminiException.Unavailable
        }

        val finalText = aggregated.toString().trim()
        if (finalText.isBlank()) {
            // Fallback to non-streaming respond if SSE stream yielded no text
            val fallback = respond(instructions, input, schema = null, search = false, purpose = HelperPurpose.MEANING)
            withContext(callbacks) { onText(fallback.text) }
            return@withContext fallback
        }
        APIResult(finalText, emptyList(), usage)
    }

    suspend fun fetchAvailableModels(): List<TeachingModelOption> = withContext(Dispatchers.IO) {
        val presets = presetModelsFor(provider)
        val key = readCredential()?.trim()?.takeIf { it.isNotEmpty() }
        val url = baseUrl.newBuilder().addPathSegment("models").build()
        val reqBuilder = Request.Builder().url(url).get()
        if (key != null) {
            reqBuilder.header("Authorization", "Bearer $key")
        }
        if (provider == TeachingProvider.OPENROUTER) {
            reqBuilder.header("HTTP-Referer", "https://mural.chat")
            reqBuilder.header("X-Title", "Mural")
        }

        val fetched = runCatching {
            httpClient.newCall(reqBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) return@use emptyList<TeachingModelOption>()
                val body = response.body?.string().orEmpty()
                val root = jsonParser.parseToJsonElement(body).jsonObject
                val data = root["data"] as? JsonArray ?: return@use emptyList<TeachingModelOption>()
                data.mapNotNull { el ->
                    val item = el as? JsonObject ?: return@mapNotNull null
                    val id = (item["id"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                    if (id.isEmpty() || isNonChatModel(id)) return@mapNotNull null
                    val name = (item["name"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: id
                    val desc = (item["description"] as? JsonPrimitive)?.contentOrNull?.trim()?.take(120).orEmpty()
                    TeachingModelOption(id = id, name = name, description = desc)
                }
            }
        }.getOrElse { emptyList() }

        val merged = linkedMapOf<String, TeachingModelOption>()
        for (preset in presets) {
            merged[preset.id] = preset
        }
        for (model in fetched) {
            if (model.id !in merged) {
                merged[model.id] = model
            }
        }
        merged.values.toList()
    }

    private suspend fun executeChatCompletion(
        key: String,
        modelId: String,
        systemText: String,
        userText: String,
        useJsonObjectFormat: Boolean,
    ): JsonObject? {
        val payload = buildJsonObject {
            put("model", modelId)
            put("stream", false)
            put("max_tokens", 4096)
            if (useJsonObjectFormat) {
                put("response_format", buildJsonObject { put("type", "json_object") })
            }
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "system")
                    put("content", systemText)
                })
                add(buildJsonObject {
                    put("role", "user")
                    put("content", userText)
                })
            })
        }
        val request = buildRequest("chat/completions", key, payload, stream = false)
        return try {
            streamingResponse(httpClient, request) { response ->
                if (!response.isSuccessful) {
                    if (useJsonObjectFormat && response.code == 400) {
                        return@streamingResponse null
                    }
                    throw mapHttpException(response)
                }
                val body = response.body?.string().orEmpty()
                jsonParser.parseToJsonElement(body).jsonObject
            }
        } catch (e: GeminiException) {
            throw e
        } catch (_: IOException) {
            currentCoroutineContext().ensureActive()
            throw GeminiException.Unavailable
        } catch (_: Exception) {
            throw GeminiException.InvalidResponse
        }
    }

    private fun buildRequest(path: String, key: String, payload: JsonObject, stream: Boolean): Request {
        val url = baseUrl.newBuilder().addPathSegments(path).build()
        return Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $key")
            .header("Content-Type", "application/json; charset=utf-8")
            .apply {
                if (stream) header("Accept", "text/event-stream")
                if (provider == TeachingProvider.OPENROUTER) {
                    header("HTTP-Referer", "https://mural.chat")
                    header("X-Title", "Mural")
                }
            }
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
    }

    private fun requireValidKey(): String =
        readCredential()?.trim()?.takeIf { it.isNotEmpty() && it.length <= 4096 }
            ?: throw GeminiException.MissingKey

    private fun mapHttpException(response: Response): GeminiException {
        val code = when (response.code) {
            401 -> "UNAUTHENTICATED"
            403 -> "PERMISSION_DENIED"
            404 -> "NOT_FOUND"
            429 -> "RESOURCE_EXHAUSTED"
            400 -> "INVALID_ARGUMENT"
            else -> "UNAVAILABLE"
        }
        return GeminiException.Http(response.code, code)
    }

    private fun extractAssistantText(response: JsonObject): String {
        val choices = response["choices"] as? JsonArray ?: return ""
        val first = choices.firstOrNull() as? JsonObject ?: return ""
        val message = first["message"] as? JsonObject ?: return ""
        val contentElement = message["content"] ?: return ""
        return when (contentElement) {
            is JsonPrimitive -> contentElement.contentOrNull.orEmpty()
            is JsonArray -> contentElement.mapNotNull { part ->
                val obj = part as? JsonObject ?: return@mapNotNull null
                (obj["text"] as? JsonPrimitive)?.contentOrNull
            }.joinToString("")
            else -> ""
        }
    }

    private fun parseUsage(response: JsonObject): APIUsage {
        val usage = response["usage"] as? JsonObject ?: return APIUsage()
        val prompt = (usage["prompt_tokens"] as? JsonPrimitive)?.intOrNull ?: 0
        val completion = (usage["completion_tokens"] as? JsonPrimitive)?.intOrNull ?: 0
        return APIUsage(input = prompt.coerceAtLeast(0), output = completion.coerceAtLeast(0), searches = 0)
    }

    private fun buildSchemaInstruction(instructions: String, schema: JsonObject): String =
        buildString {
            append(instructions.trim())
            append("\n\nCRITICAL OUTPUT FORMAT REQUIREMENT:\n")
            append("Respond ONLY with a single raw valid JSON object (no markdown fences, no commentary) matching this exact JSON Schema:\n")
            append(schema.toString())
        }

    private fun extractCleanJson(raw: String): String {
        val trimmed = raw.trim()
            .removePrefix("```json")
            .removePrefix("```JSON")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val firstBrace = trimmed.indexOf('{')
        val lastBrace = trimmed.lastIndexOf('}')
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            return trimmed.substring(firstBrace, lastBrace + 1)
        }
        return trimmed
    }

    companion object {
        private val jsonParser = Json { ignoreUnknownKeys = true }
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private fun defaultHttpClient() = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(75, TimeUnit.SECONDS)
            .build()

        private fun isNonChatModel(id: String): Boolean {
            val lower = id.lowercase()
            return listOf("embed", "minilm", "mpnet", "bge-", "bria-", "flux-", "whisper", "tts", "clip-vit", "e5-").any {
                lower.contains(it)
            }
        }

        fun presetModelsFor(provider: TeachingProvider): List<TeachingModelOption> = when (provider) {
            TeachingProvider.DEFAULT_GEMINI -> listOf(
                TeachingModelOption("gemini-3.5-flash", "Google: Gemini 3.5 Flash (Default)", "Fast, accurate structured JSON & Arabic explanations")
            )
            TeachingProvider.OPENROUTER -> listOf(
                TeachingModelOption("google/gemini-2.5-flash", "Google: Gemini 2.5 Flash", "Fast multimodal & structured output"),
                TeachingModelOption("openai/gpt-4o-mini", "OpenAI: GPT-4o Mini", "Fast, low-cost English & Arabic analysis"),
                TeachingModelOption("openai/gpt-4.1-mini", "OpenAI: GPT-4.1 Mini", "Strong grammar correction & JSON adherence"),
                TeachingModelOption("anthropic/claude-3.5-haiku", "Anthropic: Claude 3.5 Haiku", "Natural explanations & nuanced corrections"),
                TeachingModelOption("anthropic/claude-3.7-sonnet", "Anthropic: Claude 3.7 Sonnet", "High-precision grammar & bilingual explanations"),
                TeachingModelOption("deepseek/deepseek-chat-v3-0324", "DeepSeek: DeepSeek V3", "High quality & ultra-low cost"),
                TeachingModelOption("qwen/qwen-2.5-72b-instruct", "Qwen: Qwen 2.5 72B Instruct", "Strong Arabic & English support"),
                TeachingModelOption("meta-llama/llama-3.3-70b-instruct", "Meta: Llama 3.3 70B Instruct", "Fast open-weights instruct model"),
            )
            TeachingProvider.SOVEREIGN_EG -> listOf(
                TeachingModelOption("gemini-3.5-flash", "Google: Gemini 3.5 Flash", "Fast structured analysis & Arabic translations"),
                TeachingModelOption("gemini-3.8-flash", "Google: Gemini 3.8 Flash", "Latest Gemini Flash on SovereignEG"),
                TeachingModelOption("gemini-2.5-flash", "Google: Gemini 2.5 Flash", "Balanced speed and accuracy"),
                TeachingModelOption("deepseek-v3.2", "DeepSeek: DeepSeek V3.2", "Strong reasoning & bilingual explanations"),
                TeachingModelOption("deepseek-v4-flash", "DeepSeek: DeepSeek V4 Flash", "Fast low-latency DeepSeek Flash"),
                TeachingModelOption("claude-sonnet-4.5", "Anthropic: Claude Sonnet 4.5", "Premium grammar & style feedback"),
                TeachingModelOption("claude-haiku-4.5", "Anthropic: Claude Haiku 4.5", "Fast Claude model on SovereignEG"),
                TeachingModelOption("gpt-oss-20b", "OpenAI OSS 20B (gpt-oss-20b)", "SovereignEG Quickstart model"),
                TeachingModelOption("gemma-3-27b-it", "Google: Gemma 3 27B Instruct", "Open-weights instruction model"),
                TeachingModelOption("glm-4.7-flash", "GLM 4.7 Flash", "Fast multilingual Flash model"),
            )
        }
    }
}
