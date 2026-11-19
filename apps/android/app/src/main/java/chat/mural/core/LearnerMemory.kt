package chat.mural.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

val CEFR_LEVELS: List<String> = listOf("A1", "A2", "B1", "B2", "C1", "C2")

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
            recentTopics.isNotEmpty()

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

        return copy(
            profileFacts = cleanProfile,
            interestsAndGoals = cleanInterests,
            estimatedLevel = cleanEstimated,
            manualLevelOverride = cleanOverride,
            speakingGuidance = cleanGuidance,
            recentTopics = cleanTopics,
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
)

object LearnerMemoryReducer {
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

        return base.copy(
            profileFacts = updatedProfile,
            interestsAndGoals = updatedInterests,
            estimatedLevel = updatedEstimated,
            manualLevelOverride = base.manualLevelOverride,
            speakingGuidance = updatedGuidance,
            recentTopics = updatedTopics,
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
            out[langId] = existing.copy(
                profileFacts = mergedProfile,
                interestsAndGoals = mergedInterests,
                estimatedLevel = mergedEstimated,
                manualLevelOverride = mergedOverride,
                speakingGuidance = mergedGuidance,
                recentTopics = mergedTopics,
                updatedAtMillis = maxOf(existing.updatedAtMillis, cleanIncoming.updatedAtMillis),
            ).sanitized()
        }
        return out
    }
}
