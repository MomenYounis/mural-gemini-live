package chat.mural.core

/** Vendor and funding are independent persisted choices. Existing funding enum values retain their meaning. */
enum class AIVendor { OPENAI, GEMINI }

object VendorFundingPolicy {
    fun supported(vendor: AIVendor, funding: ConversationProvider) =
        funding == ConversationProvider.PERSONAL_KEY || vendor == AIVendor.OPENAI
    fun initialVendor(saved: String?, hasLegacyOpenAIKey: Boolean): AIVendor =
        saved?.let { runCatching { AIVendor.valueOf(it) }.getOrNull() }
            ?: if (hasLegacyOpenAIKey) AIVendor.OPENAI else AIVendor.GEMINI
}
