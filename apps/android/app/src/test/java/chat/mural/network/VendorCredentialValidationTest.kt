package chat.mural.network

import chat.mural.core.AIVendor
import org.junit.Assert.*
import org.junit.Test

class VendorCredentialValidationTest {
    @Test fun legacyOpenAIValidationStillRequiresItsOwnPrefix() {
        assertTrue(CredentialStore.validCredential(AIVendor.OPENAI, "sk-owner-fake-test-only"))
        assertFalse(CredentialStore.validCredential(AIVendor.OPENAI, "owner-google-fake-test-only"))
    }

    @Test fun legacyReadAndSaveValidationIsPreservedWhileGeminiBoundsPrintableCharacters() {
        assertTrue(CredentialStore.validCredential(AIVendor.OPENAI, "sk-" + "x".repeat(501)))
        for (value in listOf("x".repeat(501), "owner\u0000fake-test-only", "owner\u007Ffake-test-only", "owner-é-fake-test-only"))
            assertFalse(CredentialStore.validCredential(AIVendor.GEMINI, value))
    }
    @Test fun geminiCredentialsDoNotAssumeAPermanentVendorPrefix() {
        for (value in listOf("owner-google-fake-test-only", "AIza-fake-owner-test-only", "new-prefix-fake-owner-test-only")) {
            assertTrue(value, CredentialStore.validCredential(AIVendor.GEMINI, value))
        }
    }

    @Test fun bothVendorsRejectEmptyShortAndWhitespaceValues() {
        for (vendor in AIVendor.entries) for (value in listOf("", "short",
            "sk-owner fake-test-only", "sk-owner\nfake-test-only", "sk-owner\rfake-test-only")) {
            assertFalse("$vendor must reject malformed credentials", CredentialStore.validCredential(vendor, value))
        }
    }
}
