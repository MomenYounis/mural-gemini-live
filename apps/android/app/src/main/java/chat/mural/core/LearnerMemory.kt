package chat.mural.core

import java.util.Locale
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

val CEFR_LEVELS: List<String> = listOf("A1", "A2", "B1", "B2", "C1", "C2")

@Serializable
data class SuggestedTopic(
    val id: String = "",
    val title: String,
    val subtitle: String = "",
    val situation: String,
    val isRoleplay: Boolean = false,
) {
    fun toConversationTheme(colorIndex: Int = 0): ConversationTheme {
        val cleanTitle = title.trim()
        val slug = id.trim().ifEmpty {
            cleanTitle.lowercase(Locale.ROOT)
                .replace(Regex("[^a-z0-9]+"), "-")
                .trim('-')
                .ifEmpty { "topic" }
        }
        return ConversationTheme(
            id = "suggested:$slug",
            title = cleanTitle,
            subtitle = subtitle.trim().ifEmpty {
                if (isRoleplay) "Personalized roleplay" else "Tailored to your interests"
            },
            symbol = if (isRoleplay) "sparkles" else "quote.bubble",
            category = if (isRoleplay) "Roleplay" else "For You",
            situation = situation.trim(),
            colorIndex = ((colorIndex % 4) + 4) % 4,
        )
    }
}

@Serializable
data class SuggestedTopicPayload(
    val title: String = "",
    val subtitle: String = "",
    val situation: String = "",
    @SerialName("is_roleplay") val isRoleplay: Boolean = false,
)

@Serializable
data class SuggestedTopicsRefreshPayload(
    @SerialName("suggested_topics") val suggestedTopics: List<SuggestedTopicPayload> = emptyList(),
)

@Serializable
data class RecentTopicMemory(
    val sessionID: String = "",
    val topic: String,
    val oneLineSummary: String = "",
    val followUpHint: String = "",
    val dateMillis: Long = System.currentTimeMillis(),
)

@Serializable
data class LearnerMemory(
    val profileFacts: List<String> = emptyList(),
    val interestsAndGoals: List<String> = emptyList(),
    val estimatedLevel: String = "",
    val manualLevelOverride: String? = null,
    val speakingGuidance: String = "",
    val recentTopics: List<RecentTopicMemory> = emptyList(),
    val suggestedTopics: List<SuggestedTopic> = emptyList(),
    val autoUpdateEnabled: Boolean = true,
    val updatedAtMillis: Long = 0L,
) {
    val effectiveLevel: String
        get() = manualLevelOverride?.trim()?.uppercase()?.takeIf { it in CEFR_LEVELS }
            ?: estimatedLevel.trim().uppercase().takeIf { it in CEFR_LEVELS }
            ?: ""

    fun hasContent(): Boolean =
        profileFacts.isNotEmpty() ||
            interestsAndGoals.isNotEmpty() ||
            effectiveLevel.isNotEmpty() ||
            speakingGuidance.isNotBlank() ||
            recentTopics.isNotEmpty() ||
            suggestedTopics.isNotEmpty()

    fun sanitized(): LearnerMemory {
        val cleanProfile = sanitizeFactList(profileFacts, MAX_PROFILE_FACTS)
        val cleanInterests = sanitizeFactList(interestsAndGoals, MAX_INTERESTS_AND_GOALS)
        val cleanEstimated = estimatedLevel.trim().uppercase().takeIf { it in CEFR_LEVELS } ?: ""
        val cleanOverride = manualLevelOverride?.trim()?.uppercase()?.takeIf { it in CEFR_LEVELS }
        val cleanGuidance = cleanText(speakingGuidance, MAX_SPEAKING_GUIDANCE_CHARS)
            .takeUnless(::looksLikeSensitiveSecret)
            .orEmpty()
        val seenSessions = mutableSetOf<String>()
        val cleanTopics = recentTopics
            .asSequence()
            .mapNotNull { item ->
                val topic = cleanText(item.topic, MAX_TOPIC_TITLE_CHARS)
                if (topic.isEmpty() || looksLikeSensitiveSecret(topic)) return@mapNotNull null
                val summary = cleanText(item.oneLineSummary, MAX_TOPIC_SUMMARY_CHARS)
                    .takeUnless(::looksLikeSensitiveSecret)
                    .orEmpty()
                val followUp = cleanText(item.followUpHint, MAX_FOLLOW_UP_HINT_CHARS)
                    .takeUnless(::looksLikeSensitiveSecret)
                    .orEmpty()
                RecentTopicMemory(
                    sessionID = item.sessionID.trim(),
                    topic = topic,
                    oneLineSummary = summary,
                    followUpHint = followUp,
                    dateMillis = item.dateMillis,
                )
            }
            .sortedByDescending { it.dateMillis }
            .filter { item -> item.sessionID.isEmpty() || seenSessions.add(item.sessionID) }
            .take(MAX_RECENT_TOPICS)
            .toList()
        val recentExclusions = cleanTopics.take(5).map { it.topic }.toSet()
        val cleanSuggested = sanitizeSuggestedList(suggestedTopics, excludeTitles = recentExclusions)

        return copy(
            profileFacts = cleanProfile,
            interestsAndGoals = cleanInterests,
            estimatedLevel = cleanEstimated,
            manualLevelOverride = cleanOverride,
            speakingGuidance = cleanGuidance,
            recentTopics = cleanTopics,
            suggestedTopics = cleanSuggested,
        )
    }

    fun formatForPrompt(maxChars: Int = DEFAULT_PROMPT_MAX_CHARS): String {
        val boundedMax = maxChars.coerceAtLeast(0)
        if (boundedMax == 0) return ""
        val clean = sanitized()
        if (!clean.hasContent()) return ""

        val lines = mutableListOf<String>()
        if (clean.effectiveLevel.isNotEmpty()) {
            val sourceTag = if (clean.manualLevelOverride != null) "set by learner" else "estimated"
            lines += "Effective proficiency level: ${clean.effectiveLevel} ($sourceTag)"
        }
        if (clean.speakingGuidance.isNotEmpty()) {
            lines += "Speaking adaptation: ${clean.speakingGuidance}"
        }
        if (clean.profileFacts.isNotEmpty()) {
            lines += "About the learner: ${clean.profileFacts.joinToString("; ")}"
        }
        if (clean.interestsAndGoals.isNotEmpty()) {
            lines += "Interests & goals: ${clean.interestsAndGoals.joinToString("; ")}"
        }

        if (lines.isEmpty() && clean.recentTopics.isEmpty()) return ""
        val headerOnly = lines.joinToString("\n")
        if (clean.recentTopics.isEmpty()) {
            return headerOnly.take(boundedMax)
        }

        val topicLines = clean.recentTopics.map { entry ->
            buildString {
                append("- ")
                append(entry.topic)
                if (entry.oneLineSummary.isNotEmpty()) {
                    append(": ")
                    append(entry.oneLineSummary)
                }
                if (entry.followUpHint.isNotEmpty()) {
                    append(" (Follow-up: ")
                    append(entry.followUpHint)
                    append(')')
                }
            }
        }

        val builder = StringBuilder()
        if (headerOnly.isNotEmpty()) {
            builder.append(headerOnly)
        }
        val sectionPrefix = if (builder.isEmpty()) "Recent topics:" else "\nRecent topics:"
        if (builder.length + sectionPrefix.length >= boundedMax) {
            return builder.toString().take(boundedMax)
        }
        var addedHeader = false
        for (line in topicLines) {
            val candidate = if (!addedHeader) "$sectionPrefix\n$line" else "\n$line"
            if (builder.length + candidate.length > boundedMax) break
            builder.append(candidate)
            addedHeader = true
        }
        return builder.toString().take(boundedMax)
    }

    companion object {
        const val MAX_PROFILE_FACTS = 15
        const val MAX_INTERESTS_AND_GOALS = 15
        const val MAX_FACT_CHARS = 140
        const val MAX_SPEAKING_GUIDANCE_CHARS = 350
        const val MAX_RECENT_TOPICS = 10
        const val MAX_TOPIC_TITLE_CHARS = 80
        const val MAX_TOPIC_SUMMARY_CHARS = 160
        const val MAX_FOLLOW_UP_HINT_CHARS = 140
        const val DEFAULT_PROMPT_MAX_CHARS = 1800
        const val MAX_SUGGESTED_TOPICS = 3
        const val MAX_SUGGESTED_TITLE_CHARS = 50
        const val MAX_SUGGESTED_SUBTITLE_CHARS = 75
        const val MAX_SUGGESTED_SITUATION_CHARS = 240

        private val SENSITIVE_KEYWORDS = Regex(
            "\\b(password|passcode|pin code|secret key|api key|credit card|debit card|cvv|cvc|ssn|social security|national id|passport number|bank account|routing number|iban)\\b",
            RegexOption.IGNORE_CASE,
        )
        private val LONG_DIGIT_SECRET = Regex("(?:\\d[ -]?){12,}")
        private val STREET_ADDRESS_PATTERN = Regex(
            "\\b\\d{1,5}\\s+[A-Za-z0-9.\\s]{2,30}\\b(street|st\\.|avenue|ave\\.|road|rd\\.|boulevard|blvd\\.|lane|ln\\.|apt\\.?|apartment)\\b",
            RegexOption.IGNORE_CASE,
        )

        internal fun looksLikeSensitiveSecret(text: String): Boolean {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return false
            return SENSITIVE_KEYWORDS.containsMatchIn(trimmed) ||
                LONG_DIGIT_SECRET.containsMatchIn(trimmed) ||
                STREET_ADDRESS_PATTERN.containsMatchIn(trimmed)
        }

        internal fun cleanText(text: String, maxChars: Int): String = text
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(maxChars)

        internal fun sanitizeFactList(items: List<String>, maxItems: Int): List<String> {
            val seen = mutableSetOf<String>()
            val out = mutableListOf<String>()
            for (raw in items) {
                val clean = cleanText(raw, MAX_FACT_CHARS)
                if (clean.isEmpty() || looksLikeSensitiveSecret(clean)) continue
                val key = clean.lowercase()
                if (seen.add(key)) {
                    out += clean
                    if (out.size >= maxItems) break
                }
            }
            return out
        }

        internal fun slugForTitle(title: String): String =
            title.lowercase(Locale.ROOT)
                .replace(Regex("[^a-z0-9]+"), "-")
                .trim('-')
                .ifEmpty { "topic" }

        internal fun sanitizeSuggestedList(
            items: List<SuggestedTopic>,
            excludeTitles: Set<String> = emptySet(),
            maxItems: Int = MAX_SUGGESTED_TOPICS,
        ): List<SuggestedTopic> {
            val bounded = maxItems.coerceIn(0, MAX_SUGGESTED_TOPICS)
            if (bounded == 0) return emptyList()
            val excluded = excludeTitles.map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotEmpty() }.toSet()
            val seen = mutableSetOf<String>()
            val out = mutableListOf<SuggestedTopic>()
            for (raw in items) {
                val title = cleanText(raw.title, MAX_SUGGESTED_TITLE_CHARS)
                val situation = cleanText(raw.situation, MAX_SUGGESTED_SITUATION_CHARS)
                val subtitle = cleanText(raw.subtitle, MAX_SUGGESTED_SUBTITLE_CHARS)
                if (title.isEmpty() || situation.isEmpty()) continue
                if (looksLikeSensitiveSecret(title) || looksLikeSensitiveSecret(subtitle) || looksLikeSensitiveSecret(situation)) continue
                val key = title.lowercase(Locale.ROOT)
                if (key in excluded || !seen.add(key)) continue
                val cleanId = cleanText(raw.id, 60).ifEmpty { slugForTitle(title) }
                out += SuggestedTopic(
                    id = cleanId,
                    title = title,
                    subtitle = subtitle,
                    situation = situation,
                    isRoleplay = raw.isRoleplay,
                )
                if (out.size >= bounded) break
            }
            return out
        }
    }
}

@Serializable
data class LearnerMemoryUpdatePayload(
    @SerialName("profile_facts") val profileFacts: List<String> = emptyList(),
    @SerialName("interests_and_goals") val interestsAndGoals: List<String> = emptyList(),
    @SerialName("estimated_level") val estimatedLevel: String = "",
    @SerialName("speaking_guidance") val speakingGuidance: String = "",
    @SerialName("session_topic") val sessionTopic: String = "",
    @SerialName("session_summary") val sessionSummary: String = "",
    @SerialName("follow_up_hint") val followUpHint: String = "",
    @SerialName("suggested_topics") val suggestedTopics: List<SuggestedTopicPayload> = emptyList(),
)

object LearnerMemoryReducer {
    private val ROLEPLAY_KEYWORDS = listOf(
        "interview", "work", "job", "career", "office", "meeting",
        "travel", "trip", "airport", "hotel", "restaurant", "cafe",
        "coffee", "doctor", "hospital", "shop", "market",
    )

    fun seedFromLegacyInterests(memory: LearnerMemory, legacyInterests: String): LearnerMemory {
        val cleanMemory = memory.sanitized()
        if (cleanMemory.interestsAndGoals.isNotEmpty() || legacyInterests.isBlank()) return cleanMemory
        val items = legacyInterests
            .split(',', ';', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (items.isEmpty()) return cleanMemory
        return cleanMemory.copy(interestsAndGoals = items).sanitized()
    }

    fun syncLegacyInterestsString(memory: LearnerMemory): String =
        memory.sanitized().interestsAndGoals.joinToString(", ").take(500)

    fun sanitizeSuggestedPayloads(
        payloads: List<SuggestedTopicPayload>,
        recentTopics: List<RecentTopicMemory> = emptyList(),
        excludeTitles: Set<String> = emptySet(),
    ): List<SuggestedTopic> {
        val recentExclusions = recentTopics.take(5).map { it.topic }.toSet()
        val combinedExclude = (recentExclusions + excludeTitles).map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val mapped = payloads.map { item ->
            SuggestedTopic(
                id = LearnerMemory.slugForTitle(item.title),
                title = item.title,
                subtitle = item.subtitle,
                situation = item.situation,
                isRoleplay = item.isRoleplay,
            )
        }
        return LearnerMemory.sanitizeSuggestedList(mapped, excludeTitles = combinedExclude)
    }

    fun fallbackSuggestedTopics(
        memory: LearnerMemory,
        excludeTitles: Set<String> = emptySet(),
        maxItems: Int = LearnerMemory.MAX_SUGGESTED_TOPICS,
    ): List<SuggestedTopic> {
        val clean = memory.sanitized()
        val seeds = (clean.interestsAndGoals + clean.profileFacts).distinct()
        if (seeds.isEmpty()) return emptyList()

        val recentTitles = clean.recentTopics.take(5).map { it.topic.trim().lowercase(Locale.ROOT) }.toSet()
        val extraExcluded = excludeTitles.map { it.trim().lowercase(Locale.ROOT) }.toSet()
        val blocked = recentTitles + extraExcluded

        val candidates = mutableListOf<SuggestedTopic>()
        for (seed in seeds) {
            val lower = seed.lowercase(Locale.ROOT)
            val isRoleplay = ROLEPLAY_KEYWORDS.any { lower.contains(it) }
            val primaryTitle = LearnerMemory.cleanText(
                if (isRoleplay) "$seed Practice" else seed,
                LearnerMemory.MAX_SUGGESTED_TITLE_CHARS,
            )
            candidates += SuggestedTopic(
                id = LearnerMemory.slugForTitle(primaryTitle),
                title = primaryTitle,
                subtitle = if (isRoleplay) "Interactive roleplay scenario" else "Personalized from your goals",
                situation = if (isRoleplay) {
                    "Roleplay a realistic scenario involving $seed. Stay natural and keep questions practical and engaging."
                } else {
                    "Discuss $seed naturally from the learner's own experience and opinions, asking curious follow-up questions."
                },
                isRoleplay = isRoleplay,
            )
        }
        // Secondary angles ensure offline refresh can rotate fresh ideas even with a small profile.
        for (seed in seeds) {
            val lower = seed.lowercase(Locale.ROOT)
            val isRoleplay = ROLEPLAY_KEYWORDS.any { lower.contains(it) }
            val altTitle = LearnerMemory.cleanText(
                if (isRoleplay) "Real-Life $seed Scenario" else "Deep Dive: $seed",
                LearnerMemory.MAX_SUGGESTED_TITLE_CHARS,
            )
            candidates += SuggestedTopic(
                id = LearnerMemory.slugForTitle(altTitle),
                title = altTitle,
                subtitle = if (isRoleplay) "Practice a realistic conversation" else "Explore opinions & stories",
                situation = if (isRoleplay) {
                    "Act out an everyday real-life situation around $seed with gentle, natural turn-taking."
                } else {
                    "Explore interesting stories, lessons learned, or future plans related to $seed."
                },
                isRoleplay = isRoleplay,
            )
        }

        return LearnerMemory.sanitizeSuggestedList(
            items = candidates,
            excludeTitles = blocked,
            maxItems = maxItems,
        )
    }

    fun effectiveSuggestedTopics(memory: LearnerMemory): List<SuggestedTopic> {
        val clean = memory.sanitized()
        val recentTitles = clean.recentTopics.take(5).map { it.topic.trim().lowercase(Locale.ROOT) }.toSet()
        val stored = LearnerMemory.sanitizeSuggestedList(clean.suggestedTopics, excludeTitles = recentTitles)
        if (stored.isNotEmpty()) return stored
        return fallbackSuggestedTopics(clean)
    }

    fun merge(
        current: LearnerMemory,
        payload: LearnerMemoryUpdatePayload,
        sessionID: String,
        fallbackTopic: String,
        nowMillis: Long = System.currentTimeMillis(),
    ): LearnerMemory {
        val base = current.sanitized()
        val updatedProfile = LearnerMemory.sanitizeFactList(
            payload.profileFacts,
            LearnerMemory.MAX_PROFILE_FACTS,
        ).ifEmpty { base.profileFacts }
        val updatedInterests = LearnerMemory.sanitizeFactList(
            payload.interestsAndGoals,
            LearnerMemory.MAX_INTERESTS_AND_GOALS,
        ).ifEmpty { base.interestsAndGoals }

        val incomingLevel = payload.estimatedLevel.trim().uppercase().takeIf { it in CEFR_LEVELS }
        val updatedEstimated = if (base.manualLevelOverride != null) {
            base.estimatedLevel
        } else {
            incomingLevel ?: base.estimatedLevel
        }

        val incomingGuidance = LearnerMemory.cleanText(
            payload.speakingGuidance,
            LearnerMemory.MAX_SPEAKING_GUIDANCE_CHARS,
        ).takeUnless(LearnerMemory::looksLikeSensitiveSecret).orEmpty()
        val updatedGuidance = incomingGuidance.ifEmpty { base.speakingGuidance }

        val cleanSessionID = sessionID.trim()
        val topicTitle = LearnerMemory.cleanText(
            payload.sessionTopic.ifBlank { fallbackTopic }.ifBlank { Sm2Calculator.GENERAL_TOPIC },
            LearnerMemory.MAX_TOPIC_TITLE_CHARS,
        )
        val summary = LearnerMemory.cleanText(
            payload.sessionSummary,
            LearnerMemory.MAX_TOPIC_SUMMARY_CHARS,
        ).takeUnless(LearnerMemory::looksLikeSensitiveSecret).orEmpty()
        val followUp = LearnerMemory.cleanText(
            payload.followUpHint,
            LearnerMemory.MAX_FOLLOW_UP_HINT_CHARS,
        ).takeUnless(LearnerMemory::looksLikeSensitiveSecret).orEmpty()

        val updatedTopics = if (topicTitle.isNotEmpty() && !LearnerMemory.looksLikeSensitiveSecret(topicTitle)) {
            val newEntry = RecentTopicMemory(
                sessionID = cleanSessionID,
                topic = topicTitle,
                oneLineSummary = summary,
                followUpHint = followUp,
                dateMillis = nowMillis,
            )
            val filteredExisting = if (cleanSessionID.isNotEmpty()) {
                base.recentTopics.filterNot { it.sessionID == cleanSessionID }
            } else {
                base.recentTopics
            }
            listOf(newEntry) + filteredExisting
        } else {
            base.recentTopics
        }

        val recentExclusions = updatedTopics.take(5).map { it.topic }.toSet()
        val incomingSuggested = sanitizeSuggestedPayloads(payload.suggestedTopics, updatedTopics)
        val updatedSuggested = if (incomingSuggested.isNotEmpty()) {
            incomingSuggested
        } else {
            LearnerMemory.sanitizeSuggestedList(base.suggestedTopics, excludeTitles = recentExclusions)
        }

        return base.copy(
            profileFacts = updatedProfile,
            interestsAndGoals = updatedInterests,
            estimatedLevel = updatedEstimated,
            manualLevelOverride = base.manualLevelOverride,
            speakingGuidance = updatedGuidance,
            recentTopics = updatedTopics,
            suggestedTopics = updatedSuggested,
            autoUpdateEnabled = base.autoUpdateEnabled,
            updatedAtMillis = nowMillis,
        ).sanitized()
    }

    fun mergeImported(
        current: Map<String, LearnerMemory>,
        incoming: Map<String, LearnerMemory>,
    ): Map<String, LearnerMemory> {
        if (incoming.isEmpty()) return current
        val out = current.toMutableMap()
        for ((langId, rawIncoming) in incoming) {
            if (LanguageRegistry.get(langId) == null) continue
            val cleanIncoming = rawIncoming.sanitized()
            val existing = out[langId]?.sanitized()
            if (existing == null) {
                out[langId] = cleanIncoming
                continue
            }
            val mergedProfile = LearnerMemory.sanitizeFactList(
                existing.profileFacts + cleanIncoming.profileFacts,
                LearnerMemory.MAX_PROFILE_FACTS,
            )
            val mergedInterests = LearnerMemory.sanitizeFactList(
                existing.interestsAndGoals + cleanIncoming.interestsAndGoals,
                LearnerMemory.MAX_INTERESTS_AND_GOALS,
            )
            val mergedTopics = (existing.recentTopics + cleanIncoming.recentTopics)
                .sortedByDescending { it.dateMillis }
            val mergedEstimated = existing.estimatedLevel.ifEmpty { cleanIncoming.estimatedLevel }
            val mergedOverride = existing.manualLevelOverride ?: cleanIncoming.manualLevelOverride
            val mergedGuidance = existing.speakingGuidance.ifEmpty { cleanIncoming.speakingGuidance }
            val mergedSuggested = existing.suggestedTopics.ifEmpty { cleanIncoming.suggestedTopics }
            out[langId] = existing.copy(
                profileFacts = mergedProfile,
                interestsAndGoals = mergedInterests,
                estimatedLevel = mergedEstimated,
                manualLevelOverride = mergedOverride,
                speakingGuidance = mergedGuidance,
                recentTopics = mergedTopics,
                suggestedTopics = mergedSuggested,
                updatedAtMillis = maxOf(existing.updatedAtMillis, cleanIncoming.updatedAtMillis),
            ).sanitized()
        }
        return out
    }
}
