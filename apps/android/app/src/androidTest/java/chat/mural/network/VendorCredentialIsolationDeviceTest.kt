package chat.mural.network

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import chat.mural.core.AIVendor
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Offline Keystore checks use unique stores/aliases in the separate .uitest installation. */
@RunWith(AndroidJUnit4::class)
class VendorCredentialIsolationDeviceTest {
    @Test fun savingReplacingDeletingAndRecreatingGeminiCannotAlterExistingOpenAICredential() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("chat.mural.android.uitest", context.packageName)
        val namespace = UUID.randomUUID().toString()
        val openaiPreferences = "mural_vendor_test_openai_$namespace"
        val geminiPreferences = "mural_vendor_test_gemini_$namespace"
        val openaiAlias = "chat.mural.vendor.test.openai.$namespace"
        val geminiAlias = "chat.mural.vendor.test.gemini.$namespace"
        val openai = CredentialStore(context, openaiPreferences, openaiAlias, AIVendor.OPENAI)
        val gemini = CredentialStore(context, geminiPreferences, geminiAlias, AIVendor.GEMINI)
        val fakeOpenAI = "sk-offline-vendor-isolation-never-sent"
        val fakeGemini = "owner-offline-gemini-isolation-never-sent"
        val fakeReplacement = "replacement-offline-gemini-never-sent"
        try {
            openai.save(fakeOpenAI)
            val originalOpenAICiphertext = context.getSharedPreferences(openaiPreferences, Context.MODE_PRIVATE).all.toMap()
            gemini.save(fakeGemini)
            assertEquals(fakeOpenAI, openai.read()); assertEquals(fakeGemini, gemini.read())
            assertEquals(fakeGemini, CredentialStore(context, geminiPreferences, geminiAlias, AIVendor.GEMINI).read())
            val geminiCiphertext = context.getSharedPreferences(geminiPreferences, Context.MODE_PRIVATE).all.values.joinToString()
            assertFalse(geminiCiphertext.contains(fakeGemini)); assertFalse(geminiCiphertext.contains(fakeOpenAI))
            gemini.save(fakeReplacement)
            assertEquals(fakeReplacement, gemini.read()); assertEquals(fakeOpenAI, openai.read())
            assertEquals(originalOpenAICiphertext, context.getSharedPreferences(openaiPreferences, Context.MODE_PRIVATE).all)
            assertThrows(CredentialStore.CredentialException.Invalid::class.java) { gemini.save("bad") }
            assertEquals(fakeReplacement, gemini.read())
            gemini.delete()
            assertFalse(gemini.hasKey); assertNull(CredentialStore(context, geminiPreferences, geminiAlias, AIVendor.GEMINI).read())
            assertEquals(fakeOpenAI, CredentialStore(context, openaiPreferences, openaiAlias, AIVendor.OPENAI).read())
            assertEquals(originalOpenAICiphertext, context.getSharedPreferences(openaiPreferences, Context.MODE_PRIVATE).all)
        } finally {
            gemini.delete(); openai.delete()
            context.deleteSharedPreferences(geminiPreferences); context.deleteSharedPreferences(openaiPreferences)
        }
    }

    @Test fun unreadableGeminiCiphertextClearsOnlyItsOwnStoreAndAlias() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("chat.mural.android.uitest", context.packageName)
        val namespace = UUID.randomUUID().toString()
        val openaiPreferences = "mural_vendor_test_openai_$namespace"
        val geminiPreferences = "mural_vendor_test_gemini_$namespace"
        val openai = CredentialStore(context, openaiPreferences, "chat.mural.vendor.test.openai.$namespace", AIVendor.OPENAI)
        val gemini = CredentialStore(context, geminiPreferences, "chat.mural.vendor.test.gemini.$namespace", AIVendor.GEMINI)
        val fakeOpenAI = "sk-offline-unreadable-isolation-never-sent"
        try {
            openai.save(fakeOpenAI); gemini.save("owner-offline-unreadable-gemini-never-sent")
            assertTrue(context.getSharedPreferences(geminiPreferences, Context.MODE_PRIVATE).edit().putString("ciphertext", "invalid").commit())
            assertNull(gemini.read())
            assertTrue(context.getSharedPreferences(geminiPreferences, Context.MODE_PRIVATE).all.isEmpty())
            assertEquals(fakeOpenAI, openai.read())
            gemini.save("replacement-offline-unreadable-never-sent")
            assertTrue(gemini.hasKey); assertEquals(fakeOpenAI, openai.read())
        } finally {
            gemini.delete(); openai.delete()
            context.deleteSharedPreferences(geminiPreferences); context.deleteSharedPreferences(openaiPreferences)
        }
    }
}
