package chat.mural.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** Local provenance is separate from exported learning content and contains no credentials.
 * A hosted session cannot be recovered later using a personal API key. */
internal class ConversationProviderStore(context: Context) {
    private val preferences = context.getSharedPreferences("mural_conversation_providers", Context.MODE_PRIVATE)
    data class Snapshot(val hostedIDs: Set<String>, val pendingOwnerID: String?, val selection: ConversationProvider,
        val vendorSelection: String?, val personalVendors: Map<String, AIVendor>)
    suspend fun read(defaultSelection: ConversationProvider = ConversationProvider.PERSONAL_KEY) = withContext(Dispatchers.IO) {
        Snapshot(preferences.getStringSet("hosted", emptySet())?.toSet().orEmpty(),
            preferences.getString("pending_owner", null),
            runCatching { ConversationProvider.valueOf(preferences.getString("selection", null).orEmpty()) }
                .getOrDefault(defaultSelection), preferences.getString("vendor_selection", null), readVendors())
    }
    private fun readVendors(): Map<String, AIVendor> = runCatching {
        Json.parseToJsonElement(preferences.getString("personal_vendors", "{}")!!).jsonObject.mapNotNull { (id, v) ->
            runCatching { id to AIVendor.valueOf(v.jsonPrimitive.content) }.getOrNull()
        }.toMap()
    }.getOrDefault(emptyMap())
    suspend fun selectVendor(vendor: AIVendor) = withContext(Dispatchers.IO) {
        check(preferences.edit().putString("vendor_selection", vendor.name).commit())
    }
    suspend fun markPersonal(localID: String, vendor: AIVendor) = withContext(Dispatchers.IO) {
        val values = readVendors() + (localID to vendor)
        check(preferences.edit().putString("personal_vendors", buildJsonObject {
            values.forEach { (id, v) -> put(id, v.name) }
        }.toString()).commit())
    }
    /** Android-only measurement/provenance; never part of the iPhone-compatible learning archive. */
    suspend fun recordGeminiUsage(localID: String, elapsedMS: Long, input: Long, output: Long) = withContext(Dispatchers.IO) {
        check(preferences.edit().putString("gemini_usage.$localID", buildJsonObject {
            put("elapsedMilliseconds", elapsedMS.coerceAtLeast(0)); put("inputTokens", input.coerceAtLeast(0))
            put("outputTokens", output.coerceAtLeast(0)); put("usageFinal", false)
            put("timing", "local-estimate"); put("model", chat.mural.BuildConfig.GEMINI_LIVE_MODEL)
        }.toString()).commit())
    }
    suspend fun geminiElapsedMilliseconds(): Long = withContext(Dispatchers.IO) {
        preferences.all.filterKeys { it.startsWith("gemini_usage.") }.values.sumOf { value ->
            runCatching { Json.parseToJsonElement(value as String).jsonObject["elapsedMilliseconds"]?.jsonPrimitive?.longOrNull?.coerceAtLeast(0) ?: 0L }.getOrDefault(0L)
        }
    }
    suspend fun forgetPersonal(ids: Set<String>) = withContext(Dispatchers.IO) {
        val values = readVendors() - ids
        val editor = preferences.edit().putString("personal_vendors", buildJsonObject {
            values.forEach { (id, vendor) -> put(id, vendor.name) }
        }.toString())
        ids.forEach { editor.remove("gemini_usage.$it") }
        check(editor.commit())
    }
    suspend fun markHosted(localID: String, ownerID: String) = withContext(Dispatchers.IO) {
        val ids = preferences.getStringSet("hosted", emptySet()).orEmpty() + localID
        check(preferences.edit().putStringSet("hosted", ids).putString("pending_owner", ownerID).commit())
    }
    suspend fun clearPending() = withContext(Dispatchers.IO) { check(preferences.edit().remove("pending_owner").commit()) }
    suspend fun select(provider: ConversationProvider) = withContext(Dispatchers.IO) {
        check(preferences.edit().putString("selection", provider.name).commit())
    }
}
