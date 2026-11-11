package chat.mural.core

import org.junit.Assert.*
import org.junit.Test

class VendorFundingBoundaryTest {
    @Test fun fundingPersistedValuesRetainTheirOriginalMeaning() {
        assertEquals(ConversationProvider.PERSONAL_KEY, ConversationProvider.valueOf("PERSONAL_KEY"))
        assertEquals(ConversationProvider.HOSTED_MINUTES, ConversationProvider.valueOf("HOSTED_MINUTES"))
        assertEquals(setOf("PERSONAL_KEY", "HOSTED_MINUTES"), ConversationProvider.entries.map { it.name }.toSet())
    }

    @Test fun onlyPersonalFundingSupportsGeminiAndOpenAIHostedRemainsExplicit() {
        assertTrue(VendorFundingPolicy.supported(AIVendor.GEMINI, ConversationProvider.PERSONAL_KEY))
        assertFalse(VendorFundingPolicy.supported(AIVendor.GEMINI, ConversationProvider.HOSTED_MINUTES))
        assertTrue(VendorFundingPolicy.supported(AIVendor.OPENAI, ConversationProvider.PERSONAL_KEY))
        assertTrue(VendorFundingPolicy.supported(AIVendor.OPENAI, ConversationProvider.HOSTED_MINUTES))
    }

    @Test fun firstInstallCanChooseGeminiAndExistingOpenAIInstallsKeepTheirVendor() {
        assertEquals(AIVendor.GEMINI, VendorFundingPolicy.initialVendor(null, false))
        assertEquals(AIVendor.OPENAI, VendorFundingPolicy.initialVendor(null, true))
        assertEquals(AIVendor.GEMINI, VendorFundingPolicy.initialVendor("GEMINI", true))
        assertEquals(AIVendor.OPENAI, VendorFundingPolicy.initialVendor("OPENAI", false))
    }

    @Test fun corruptVendorSelectionCannotCreateAnUnknownProviderOrFundingValue() {
        assertEquals(AIVendor.GEMINI, VendorFundingPolicy.initialVendor("HOSTED_MINUTES", false))
        assertEquals(AIVendor.OPENAI, VendorFundingPolicy.initialVendor("PRIVATE_UNKNOWN_VENDOR", true))
        assertEquals(AIVendor.GEMINI, VendorFundingPolicy.initialVendor("", false))
    }
}
