package chat.mural.network

import android.content.Context
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

enum class GroqWhisperModel(
    val id: String,
    val displayName: String,
    val shortName: String,
) {
    LARGE_V3(
        id = "whisper-large-v3",
        displayName = "Whisper Large v3 (Best Accuracy)",
        shortName = "Whisper Large v3",
    ),
    LARGE_V3_TURBO(
        id = "whisper-large-v3-turbo",
        displayName = "Whisper Large v3 Turbo (Fastest)",
        shortName = "Whisper Large v3 Turbo",
    );

    companion object {
        val DEFAULT = LARGE_V3

        fun fromId(id: String?): GroqWhisperModel =
            entries.firstOrNull { it.id == id || it.name.equals(id, ignoreCase = true) } ?: DEFAULT
    }
}

/** Local non-secret Groq STT preferences; deliberately excluded from learning backups. */
class GroqSttSettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun readEnabled(hasKey: Boolean): Boolean {
        if (!hasKey) return false
        return if (prefs.contains(KEY_ENABLED)) prefs.getBoolean(KEY_ENABLED, true) else true
    }

    fun saveEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun readModel(): GroqWhisperModel =
        GroqWhisperModel.fromId(prefs.getString(KEY_MODEL, GroqWhisperModel.DEFAULT.id))

    fun saveModel(model: GroqWhisperModel) {
        prefs.edit().putString(KEY_MODEL, model.id).apply()
    }

    companion object {
        private const val PREFS_NAME = "mural_groq_stt_prefs"
        private const val KEY_ENABLED = "groq_stt_enabled"
        private const val KEY_MODEL = "groq_stt_model"
    }
}

sealed class GroqTranscriptionResult {
    data class Success(val text: String) : GroqTranscriptionResult()
    data object Discarded : GroqTranscriptionResult()
    data class Failure(val kind: FailureKind, val httpStatus: Int? = null) : GroqTranscriptionResult()

    enum class FailureKind {
        MISSING_KEY,
        UNAUTHORIZED,
        RATE_LIMITED,
        NETWORK,
        SERVER,
        INVALID_RESPONSE,
    }
}

internal interface TurnTranscriber {
    suspend fun transcribe(
        pcm16: ByteArray,
        model: GroqWhisperModel,
        promptHint: String,
    ): GroqTranscriptionResult
}

/**
 * Sends a single 16 kHz mono WAV turn strictly to Groq's transcription endpoint for
 * high-accuracy multilingual Whisper refinement without touching disk or logging secrets.
 */
class GroqTranscriptionClient internal constructor(
    private val readCredential: () -> String?,
    client: OkHttpClient = defaultHttpClient(),
    private val endpointUrl: HttpUrl = ENDPOINT.toHttpUrl(),
) : TurnTranscriber {

    constructor(readCredential: () -> String?) : this(
        readCredential = readCredential,
        client = defaultHttpClient(),
        endpointUrl = ENDPOINT.toHttpUrl(),
    )

    private val httpClient = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .cookieJar(CookieJar.NO_COOKIES)
        .cache(null)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build()

    override suspend fun transcribe(
        pcm16: ByteArray,
        model: GroqWhisperModel,
        promptHint: String,
    ): GroqTranscriptionResult = withContext(Dispatchers.IO) {
        val key = readCredential()?.trim()?.takeIf { CredentialStore.validGroqCredential(it) }
            ?: return@withContext GroqTranscriptionResult.Failure(GroqTranscriptionResult.FailureKind.MISSING_KEY)

        val clipDurationMs = (pcm16.size / TurnAudioBuffer.BYTES_PER_MS).toLong()
        val clipRms = pcm16Rms(pcm16)
        val wavBytes = encodePcm16ToWav(pcm16)
        val cleanHint = sanitizeHint(promptHint)

        val bodyBuilder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                "turn.wav",
                wavBytes.toRequestBody(WAV_MEDIA_TYPE),
            )
            .addFormDataPart("model", model.id)
            .addFormDataPart("response_format", "verbose_json")
            .addFormDataPart("temperature", "0")

        if (cleanHint.isNotEmpty()) {
            bodyBuilder.addFormDataPart("prompt", cleanHint)
        }

        val request = Request.Builder()
            .url(endpointUrl)
            .header("Authorization", "Bearer $key")
            .post(bodyBuilder.build())
            .build()

        try {
            streamingResponse(httpClient, request) { response ->
                if (!response.isSuccessful) {
                    val kind = when (response.code) {
                        401 -> GroqTranscriptionResult.FailureKind.UNAUTHORIZED
                        429 -> GroqTranscriptionResult.FailureKind.RATE_LIMITED
                        in 500..599 -> GroqTranscriptionResult.FailureKind.SERVER
                        else -> GroqTranscriptionResult.FailureKind.INVALID_RESPONSE
                    }
                    return@streamingResponse GroqTranscriptionResult.Failure(kind, response.code)
                }

                val rawBody = response.peekBody(MAX_RESPONSE_BYTES).string()
                val root = jsonParser.parseToJsonElement(rawBody) as? JsonObject
                    ?: return@streamingResponse GroqTranscriptionResult.Failure(
                        GroqTranscriptionResult.FailureKind.INVALID_RESPONSE,
                        response.code,
                    )

                val rawText = (root["text"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull.orEmpty()
                val segments = (root["segments"] as? JsonArray).orEmpty().mapNotNull { el ->
                    val obj = el as? JsonObject ?: return@mapNotNull null
                    SegmentMetrics(
                        noSpeechProb = (obj["no_speech_prob"] as? JsonPrimitive)?.doubleOrNull,
                        avgLogprob = (obj["avg_logprob"] as? JsonPrimitive)?.doubleOrNull,
                    )
                }

                val filtered = filterHallucination(
                    rawText = rawText,
                    segments = segments,
                    clipDurationMs = clipDurationMs,
                    clipRms = clipRms,
                )
                if (filtered != null) {
                    GroqTranscriptionResult.Success(filtered)
                } else {
                    GroqTranscriptionResult.Discarded
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            GroqTranscriptionResult.Failure(GroqTranscriptionResult.FailureKind.NETWORK)
        } catch (_: Exception) {
            GroqTranscriptionResult.Failure(GroqTranscriptionResult.FailureKind.INVALID_RESPONSE)
        }
    }

    internal data class SegmentMetrics(val noSpeechProb: Double?, val avgLogprob: Double?)

    companion object {
        const val ENDPOINT = "https://api.groq.com/openai/v1/audio/transcriptions"
        const val MAX_PROMPT_HINT_CHARS = 220
        private const val MAX_RESPONSE_BYTES = 262_144L
        private val WAV_MEDIA_TYPE = "audio/wav".toMediaType()
        private val jsonParser = Json { ignoreUnknownKeys = true }

        private val HALLUCINATION_EXACT = setOf(
            "thank you for watching",
            "thanks for watching",
            "thank you very much for watching",
            "thank you for listening",
            "thanks for listening",
            "please subscribe",
            "like and subscribe",
            "subscribe to my channel",
            "amara.org",
        )

        private val HALLUCINATION_PREFIXES = listOf(
            "subtitles by",
            "captioned by",
            "transcribed by",
        )

        private fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS)
            .build()

        internal fun sanitizeHint(hint: String): String =
            hint.replace(Regex("[\\u0000-\\u001F\\u007F]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
                .take(MAX_PROMPT_HINT_CHARS)

        fun buildPromptHint(previousAssistantReply: String?, topic: String?): String {
            val parts = mutableListOf("Mural")
            val cleanTopic = topic?.let(::sanitizeHint)?.take(60).orEmpty()
            if (cleanTopic.isNotEmpty()) {
                parts += cleanTopic
            }
            val cleanReply = previousAssistantReply?.let(::sanitizeHint).orEmpty()
            if (cleanReply.isNotEmpty()) {
                parts += cleanReply
            }
            return sanitizeHint(parts.joinToString(" · "))
        }

        internal fun filterHallucination(
            rawText: String,
            segments: List<SegmentMetrics>,
            clipDurationMs: Long,
            clipRms: Double,
        ): String? {
            val cleaned = rawText.replace(Regex("\\s+"), " ").trim()
            if (cleaned.isEmpty() || cleaned.length > 10_000) return null

            if (segments.isNotEmpty()) {
                val allLowConfidenceNoSpeech = segments.all { seg ->
                    (seg.noSpeechProb ?: 0.0) >= 0.75 && (seg.avgLogprob ?: 0.0) < -1.0
                }
                if (allLowConfidenceNoSpeech) return null

                val allCertainSilence = segments.all { seg ->
                    (seg.noSpeechProb ?: 0.0) >= 0.90
                }
                if (allCertainSilence) return null
            }

            val normalized = cleaned.lowercase()
                .trim { ch -> ch.isWhitespace() || ch in ".!?,;:\"'`~()[]{}…-–—" }
            val exactMatch = normalized in HALLUCINATION_EXACT
            val prefixMatch = HALLUCINATION_PREFIXES.any { normalized.startsWith(it) }
            if (exactMatch || prefixMatch) {
                val shortLowEnergy = clipDurationMs < 2_500L && clipRms < 0.025
                val elevatedNoSpeech = segments.any { (it.noSpeechProb ?: 0.0) >= 0.5 }
                val shortAttributionClip = (prefixMatch || normalized == "amara.org") && clipDurationMs < 4_000L
                if (shortLowEnergy || elevatedNoSpeech || shortAttributionClip) {
                    return null
                }
            }

            return cleaned
        }
    }
}
