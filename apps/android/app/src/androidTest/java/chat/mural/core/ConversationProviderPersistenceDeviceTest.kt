package chat.mural.core

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import chat.mural.BuildConfig
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Provider provenance is private Android metadata, separate from the shared learning archive. */
@RunWith(AndroidJUnit4::class)
class ConversationProviderPersistenceDeviceTest {
    private fun isolated(test: suspend (Context, SharedPreferences) -> Unit) = runBlocking {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("chat.mural.android.uitest", target.packageName)
        val preferencesName = "mural_provider_test_${UUID.randomUUID()}"
        val context = object : ContextWrapper(target) {
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
                check(name == "mural_conversation_providers")
                return target.getSharedPreferences(preferencesName, mode)
            }
        }
        try { test(context, target.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)) }
        finally { target.deleteSharedPreferences(preferencesName) }
    }

    @Test fun legacyFundingAndOriginalHostedOwnerSurviveIndependentVendorSelectionAndRestart() = isolated { context, preferences ->
        assertTrue(preferences.edit().putString("selection", "HOSTED_MINUTES").commit())
        val store = ConversationProviderStore(context)
        val legacy = store.read()
        assertEquals(ConversationProvider.HOSTED_MINUTES, legacy.selection); assertNull(legacy.vendorSelection)
        val geminiID = UUID.randomUUID().toString(); val openaiID = UUID.randomUUID().toString()
        val hostedID = UUID.randomUUID().toString(); val originalOwner = UUID.randomUUID().toString()
        store.markPersonal(geminiID, AIVendor.GEMINI)
        store.markPersonal(openaiID, AIVendor.OPENAI)
        store.markHosted(hostedID, originalOwner)
        store.select(ConversationProvider.PERSONAL_KEY); store.selectVendor(AIVendor.GEMINI)
        val recreated = ConversationProviderStore(context).read()
        assertEquals(ConversationProvider.PERSONAL_KEY, recreated.selection)
        assertEquals("GEMINI", recreated.vendorSelection)
        assertEquals(mapOf(geminiID to AIVendor.GEMINI, openaiID to AIVendor.OPENAI), recreated.personalVendors)
        assertEquals(setOf(hostedID), recreated.hostedIDs); assertEquals(originalOwner, recreated.pendingOwnerID)
        store.selectVendor(AIVendor.OPENAI); store.select(ConversationProvider.HOSTED_MINUTES)
        val hosted = ConversationProviderStore(context).read()
        assertEquals(ConversationProvider.HOSTED_MINUTES, hosted.selection); assertEquals("OPENAI", hosted.vendorSelection)
        assertEquals(originalOwner, hosted.pendingOwnerID); assertEquals(recreated.personalVendors, hosted.personalVendors)
        store.clearPending()
        assertNull(ConversationProviderStore(context).read().pendingOwnerID)
        assertEquals(setOf(hostedID), ConversationProviderStore(context).read().hostedIDs)
    }

    @Test fun geminiElapsedAndTokenMetadataAreEstimatedLocalProvenanceAndNeverSharedExportFields() = isolated { context, preferences ->
        val id = UUID.randomUUID().toString(); val store = ConversationProviderStore(context)
        store.markPersonal(id, AIVendor.GEMINI); store.recordGeminiUsage(id, 12_345, 400, 800)
        val sidecar = Json.parseToJsonElement(preferences.getString("gemini_usage.$id", null)!!).jsonObject
        assertEquals(12_345L, sidecar["elapsedMilliseconds"]!!.jsonPrimitive.long)
        assertEquals(400L, sidecar["inputTokens"]!!.jsonPrimitive.long); assertEquals(800L, sidecar["outputTokens"]!!.jsonPrimitive.long)
        assertFalse(sidecar["usageFinal"]!!.jsonPrimitive.boolean)
        assertEquals("local-estimate", sidecar["timing"]!!.jsonPrimitive.content)
        assertEquals(BuildConfig.GEMINI_LIVE_MODEL, sidecar["model"]!!.jsonPrimitive.content)
        assertEquals(12_345L, ConversationProviderStore(context).geminiElapsedMilliseconds())
        val session = SessionRecord(id = id, languageID = "en", endedAt = nowSeconds(), inputTokens = 400, outputTokens = 800)
        session.append(Fragment(id = "turn-1", speaker = Speaker.user, text = "I like tea.", startMS = 0, endMS = 1_000))
        val export = ArchiveCodec.encode(Archive(sessions = mutableListOf(session)))
        for (privateField in listOf("vendor_selection", "personal_vendors", "gemini_usage", "elapsedMilliseconds", "local-estimate", BuildConfig.GEMINI_LIVE_MODEL)) {
            assertFalse(export.contains(privateField))
        }
        val restored = ArchiveCodec.decode(export).sessions.single()
        assertEquals(session, restored); assertEquals(0.0, restored.voiceSeconds, 0.0); assertFalse(restored.usageFinal)
    }

    @Test fun corruptVendorMapCannotChangeLegacyFundingOrCreateUnknownVendor() = isolated { context, preferences ->
        val knownID = UUID.randomUUID().toString(); val unknownID = UUID.randomUUID().toString()
        assertTrue(preferences.edit().putString("selection", "PERSONAL_KEY")
            .putString("personal_vendors", """{"$knownID":"OPENAI","$unknownID":"UNKNOWN_VENDOR"}""").commit())
        val loaded = ConversationProviderStore(context).read()
        assertEquals(ConversationProvider.PERSONAL_KEY, loaded.selection)
        assertEquals(mapOf(knownID to AIVendor.OPENAI), loaded.personalVendors)
        assertNull(loaded.vendorSelection)
    }
}
