package chat.mural.core

import kotlinx.serialization.Serializable

@Serializable
enum class CoachStyle(
    val displayName: String,
    val shortDescription: String,
) {
    RELAXED_FRIEND(
        displayName = "Relaxed Friend",
        shortDescription = "Warm & low-pressure; fixes only clarity blockers",
    ),
    BALANCED(
        displayName = "Balanced Coach",
        shortDescription = "Standard Mural coaching (max 1 clear fix per turn)",
    ),
    STRICT_COACH(
        displayName = "Strict Coach",
        shortDescription = "Thorough grammar & phrasing fixes with spoken repair",
    ),
    ROLEPLAY(
        displayName = "Roleplay Immersion",
        shortDescription = "Stay in character with brief, natural corrections",
    );

    val label: String
        get() = "$displayName · $shortDescription"

    fun promptGuidance(): String = when (this) {
        RELAXED_FRIEND ->
            "Adopt a warm, relaxed, friend-like tone. Prioritize conversational flow and confidence over error-hunting: gently correct only mistakes that noticeably confuse meaning or sound clearly broken, and skip minor polish."
        BALANCED -> ""
        STRICT_COACH ->
            "Adopt an attentive, thorough coaching style. Catch and briefly correct clear grammar, tense, preposition, article, or unnatural phrasing mistakes cleanly, and invite a quick spoken repair whenever repeating the right form helps."
        ROLEPLAY ->
            "Stay deeply immersed in the selected or personalized scenario role and respond naturally in character. Keep any language correction ultra-brief and tucked gracefully into character dialogue without breaking the scene."
    }

    companion object {
        fun fromNameOrDefault(name: String?): CoachStyle =
            entries.firstOrNull { it.name.equals(name?.trim(), ignoreCase = true) } ?: BALANCED
    }
}
