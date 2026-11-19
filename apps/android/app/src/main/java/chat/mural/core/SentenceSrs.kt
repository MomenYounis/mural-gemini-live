package chat.mural.core

import java.util.UUID
import kotlin.math.roundToInt
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
data class SessionMistake(
    @SerialName("user_said") val userSaid: String,
    val correction: String,
    @SerialName("arabic_meaning") val arabicMeaning: String = "",
    val explanation: String,
) {
    val user_said: String get() = userSaid
    val arabic_meaning: String get() = arabicMeaning
}

@Serializable
data class PracticedPhrase(
    @SerialName("card_id") val cardID: String,
    val phrase: String = "",
    @SerialName("user_quote") val userQuote: String,
)

@Serializable
data class PostSessionAnalysisPayload(
    val topic: String,
    val mistakes: List<SessionMistake> = emptyList(),
    @SerialName("practiced_phrases") val practicedPhrases: List<PracticedPhrase> = emptyList(),
)

@Serializable
data class SessionReport(
    val id: String = UUID.randomUUID().toString(),
    val sessionID: String = "",
    val languageID: String = "en",
    val date: Long = System.currentTimeMillis(),
    val topic: String,
    val durationMinutes: Int,
    val mistakes: List<SessionMistake> = emptyList(),
    val mistakeCount: Int = mistakes.size,
    val practicedPhrases: List<PracticedPhrase> = emptyList(),
    val creditedCardIDs: List<String> = emptyList(),
)

@Serializable
data class SentenceCard(
    val id: String = UUID.randomUUID().toString(),
    val topic: String,
    val languageID: String = "en",
    val sourceSessionID: String = "",
    val sourceSessionIDs: List<String> = emptyList(),
    val frontText: String,
    val backText: String,
    val explanation: String,
    val userOriginalMistake: String,
    val repetitionCount: Int = 0,
    val easeFactor: Float = Sm2Calculator.DEFAULT_EASE_FACTOR,
    val intervalDays: Int = 0,
    val nextReviewDate: Long = System.currentTimeMillis(),
    val lastAutoCreditedSessionID: String = "",
) {
    fun isDue(nowMillis: Long = System.currentTimeMillis()): Boolean = nextReviewDate <= nowMillis

    fun allSourceSessionIDs(): List<String> =
        (sourceSessionIDs + listOf(sourceSessionID))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

    fun belongsToSession(sessionID: String): Boolean {
        val clean = sessionID.trim()
        return clean.isNotEmpty() && clean in allSourceSessionIDs()
    }
}

enum class ReviewRating {
    Again,
    Hard,
    Good,
    Easy;

    val label: String get() = name
}

data class TopicDeck(
    val topic: String,
    val cards: List<SentenceCard>,
    val dueCards: List<SentenceCard>,
) {
    val dueCount: Int get() = dueCards.size
    val totalCount: Int get() = cards.size
}

data class SessionDeletionImpact(
    val deletedCardCount: Int,
    val unlinkedSharedCardCount: Int,
) {
    val totalAffectedCount: Int get() = deletedCardCount + unlinkedSharedCardCount
}

data class LearningPhrasesLookupResult(
    val jsonText: String,
    val matchedCardIDs: List<String>,
)

data class PracticedPhrasesResult(
    val updatedCards: List<SentenceCard>,
    val verifiedPhrases: List<PracticedPhrase>,
    val creditedCardIDs: List<String>,
)

/**
 * Clean Anki SM-2 Spaced Repetition calculator and local phrase utilities for [SentenceCard].
 */
object Sm2Calculator {
    const val DEFAULT_EASE_FACTOR: Float = 2.5f
    const val MIN_EASE_FACTOR: Float = 1.3f
    const val DAY_MILLIS: Long = 86_400_000L
    const val GENERAL_TOPIC: String = "General Conversation"
    private const val STILL_LEARNING_MAX_REPS = 3
    private const val STILL_LEARNING_MIN_INTERVAL_DAYS = 14
    private const val STILL_LEARNING_MIN_EASE = 2.2f

    private val SEARCH_STOP_WORDS = setOf(
        "the", "and", "for", "with", "that", "this", "from", "into", "about",
        "what", "when", "where", "your", "have", "has", "had", "was", "were",
        "are", "can", "will", "would", "could", "should", "they", "them",
    )

    fun normalizedTopic(topic: String): String = topic.trim().ifEmpty { GENERAL_TOPIC }

    fun cardsForLanguage(cards: List<SentenceCard>, languageID: String): List<SentenceCard> =
        cards.filter { it.languageID == languageID }

    fun reportsForLanguage(reports: List<SessionReport>, languageID: String): List<SessionReport> =
        reports.filter { it.languageID == languageID }

    fun review(
        card: SentenceCard,
        rating: ReviewRating,
        nowMillis: Long = System.currentTimeMillis(),
    ): SentenceCard {
        val currentEase = card.easeFactor.coerceAtLeast(MIN_EASE_FACTOR)
        val currentReps = card.repetitionCount.coerceAtLeast(0)
        val currentInterval = card.intervalDays.coerceAtLeast(0)

        val newRepetitionCount: Int
        val newIntervalDays: Int
        val newEaseFactor: Float

        when (rating) {
            ReviewRating.Again -> {
                newRepetitionCount = 0
                newIntervalDays = 1
                newEaseFactor = (currentEase - 0.20f).coerceAtLeast(MIN_EASE_FACTOR)
            }
            ReviewRating.Hard -> {
                newRepetitionCount = currentReps + 1
                newIntervalDays = when (currentReps) {
                    0 -> 1
                    1 -> 3
                    else -> maxOf(currentInterval + 1, (currentInterval * 1.2f).roundToInt())
                }
                newEaseFactor = (currentEase - 0.15f).coerceAtLeast(MIN_EASE_FACTOR)
            }
            ReviewRating.Good -> {
                newRepetitionCount = currentReps + 1
                newIntervalDays = when (currentReps) {
                    0 -> 1
                    1 -> 6
                    else -> maxOf(currentInterval + 1, (currentInterval * currentEase).roundToInt())
                }
                newEaseFactor = currentEase
            }
            ReviewRating.Easy -> {
                newRepetitionCount = currentReps + 1
                newEaseFactor = (currentEase + 0.15f).coerceAtLeast(MIN_EASE_FACTOR)
                newIntervalDays = when (currentReps) {
                    0 -> 4
                    1 -> 10
                    else -> maxOf(currentInterval + 1, (currentInterval * currentEase * 1.3f).roundToInt())
                }
            }
        }

        val nextReview = nowMillis + newIntervalDays * DAY_MILLIS
        return card.copy(
            repetitionCount = newRepetitionCount,
            easeFactor = newEaseFactor,
            intervalDays = newIntervalDays,
            nextReviewDate = nextReview,
        )
    }

    fun previewIntervalDays(card: SentenceCard, rating: ReviewRating): Int =
        review(card, rating, nowMillis = 0L).intervalDays

    fun dueCards(
        cards: List<SentenceCard>,
        nowMillis: Long = System.currentTimeMillis(),
        limit: Int = Int.MAX_VALUE,
        languageID: String? = null,
    ): List<SentenceCard> = cards
        .asSequence()
        .filter { languageID == null || it.languageID == languageID }
        .filter { it.isDue(nowMillis) }
        .sortedBy { it.nextReviewDate }
        .take(limit.coerceAtLeast(0))
        .toList()

    fun groupByTopicDecks(
        cards: List<SentenceCard>,
        nowMillis: Long = System.currentTimeMillis(),
        languageID: String? = null,
    ): List<TopicDeck> = cards
        .filter { languageID == null || it.languageID == languageID }
        .groupBy { normalizedTopic(it.topic) }
        .map { (topic, topicCards) ->
            val sortedCards = topicCards.sortedBy { it.nextReviewDate }
            val due = sortedCards.filter { it.isDue(nowMillis) }
            TopicDeck(topic = topic, cards = sortedCards, dueCards = due)
        }
        .sortedWith(compareByDescending<TopicDeck> { it.dueCount }.thenBy { it.topic.lowercase() })

    fun mergeFromReport(
        existingCards: List<SentenceCard>,
        report: SessionReport,
        nowMillis: Long = System.currentTimeMillis(),
    ): List<SentenceCard> {
        if (report.mistakes.isEmpty()) return existingCards
        val topic = normalizedTopic(report.topic)
        val cleanSessionID = report.sessionID.trim()
        val result = existingCards.toMutableList()
        for (mistake in report.mistakes) {
            val correction = mistake.correction.trim()
            val front = mistake.arabicMeaning.trim()
            val userSaid = mistake.userSaid.trim()
            val explanation = mistake.explanation.trim()
            if (correction.isEmpty() || front.isEmpty()) continue
            val existingIndex = result.indexOfFirst {
                it.languageID == report.languageID &&
                    it.backText.trim().equals(correction, ignoreCase = true)
            }
            if (existingIndex >= 0) {
                val existing = result[existingIndex]
                val mergedSessions = (
                    existing.allSourceSessionIDs() +
                        listOfNotNull(cleanSessionID.takeIf { it.isNotEmpty() })
                    ).distinct()
                result[existingIndex] = existing.copy(
                    topic = topic,
                    sourceSessionID = cleanSessionID.ifEmpty {
                        existing.sourceSessionID.ifBlank { mergedSessions.lastOrNull().orEmpty() }
                    },
                    sourceSessionIDs = mergedSessions,
                    frontText = front,
                    backText = correction,
                    explanation = explanation.ifEmpty { existing.explanation },
                    userOriginalMistake = userSaid.ifEmpty { existing.userOriginalMistake },
                )
            } else {
                result += SentenceCard(
                    topic = topic,
                    languageID = report.languageID,
                    sourceSessionID = cleanSessionID,
                    sourceSessionIDs = if (cleanSessionID.isNotEmpty()) listOf(cleanSessionID) else emptyList(),
                    frontText = front,
                    backText = correction,
                    explanation = explanation,
                    userOriginalMistake = userSaid,
                    repetitionCount = 0,
                    easeFactor = DEFAULT_EASE_FACTOR,
                    intervalDays = 0,
                    nextReviewDate = nowMillis,
                )
            }
        }
        return result
    }

    fun mergeMistakesIntoCards(
        existing: List<SentenceCard>,
        topic: String,
        languageID: String,
        sessionID: String,
        mistakes: List<SessionMistake>,
        nowMillis: Long = System.currentTimeMillis(),
    ): List<SentenceCard> = mergeFromReport(
        existingCards = existing,
        report = SessionReport(
            sessionID = sessionID,
            languageID = languageID,
            date = nowMillis,
            topic = topic,
            durationMinutes = 1,
            mistakeCount = mistakes.size,
            mistakes = mistakes,
        ),
        nowMillis = nowMillis,
    )

    fun sessionDeletionImpact(cards: List<SentenceCard>, sessionID: String): SessionDeletionImpact {
        val cleanID = sessionID.trim()
        if (cleanID.isEmpty()) return SessionDeletionImpact(0, 0)
        var deleted = 0
        var unlinked = 0
        for (card in cards) {
            if (!card.belongsToSession(cleanID)) continue
            val remaining = card.allSourceSessionIDs().filterNot { it == cleanID }
            if (remaining.isEmpty()) deleted++ else unlinked++
        }
        return SessionDeletionImpact(deletedCardCount = deleted, unlinkedSharedCardCount = unlinked)
    }

    fun removeSessionFromCards(cards: List<SentenceCard>, sessionID: String): List<SentenceCard> {
        val cleanID = sessionID.trim()
        if (cleanID.isEmpty()) return cards
        return cards.mapNotNull { card -> unlinkSession(card, cleanID) }
    }

    fun deleteTopicDeck(
        cards: List<SentenceCard>,
        topic: String,
        languageID: String,
    ): List<SentenceCard> {
        val target = normalizedTopic(topic)
        return cards.filterNot {
            it.languageID == languageID && normalizedTopic(it.topic).equals(target, ignoreCase = true)
        }
    }

    fun deleteCard(cards: List<SentenceCard>, cardID: String): List<SentenceCard> =
        cards.filterNot { it.id == cardID }

    fun removeMistakeFromReport(
        reports: List<SessionReport>,
        cards: List<SentenceCard>,
        reportID: String,
        mistakeIndex: Int,
    ): Pair<List<SessionReport>, List<SentenceCard>> {
        val report = reports.firstOrNull { it.id == reportID } ?: return reports to cards
        if (mistakeIndex !in report.mistakes.indices) return reports to cards
        val removed = report.mistakes[mistakeIndex]
        val updatedMistakes = report.mistakes.filterIndexed { index, _ -> index != mistakeIndex }
        val updatedReport = report.copy(mistakes = updatedMistakes, mistakeCount = updatedMistakes.size)
        val updatedReports = reports.map { if (it.id == reportID) updatedReport else it }

        val targetCorrection = removed.correction.trim()
        val stillPresentInReport = updatedMistakes.any {
            it.correction.trim().equals(targetCorrection, ignoreCase = true)
        }
        if (targetCorrection.isEmpty() || stillPresentInReport) {
            return updatedReports to cards
        }
        val cleanSessionID = report.sessionID.trim()
        val updatedCards = cards.mapNotNull { card ->
            val matchesCard = card.languageID == report.languageID &&
                card.backText.trim().equals(targetCorrection, ignoreCase = true)
            if (!matchesCard) {
                card
            } else if (cleanSessionID.isNotEmpty()) {
                if (card.belongsToSession(cleanSessionID)) unlinkSession(card, cleanSessionID) else card
            } else {
                null
            }
        }
        return updatedReports to updatedCards
    }

    private fun unlinkSession(card: SentenceCard, cleanSessionID: String): SentenceCard? {
        if (!card.belongsToSession(cleanSessionID)) return card
        val remaining = card.allSourceSessionIDs().filterNot { it == cleanSessionID }
        return if (remaining.isEmpty()) {
            null
        } else {
            card.copy(
                sourceSessionID = remaining.last(),
                sourceSessionIDs = remaining,
            )
        }
    }

    fun isStillLearning(
        card: SentenceCard,
        nowMillis: Long = System.currentTimeMillis(),
    ): Boolean = card.isDue(nowMillis) ||
        card.repetitionCount < STILL_LEARNING_MAX_REPS ||
        card.intervalDays < STILL_LEARNING_MIN_INTERVAL_DAYS ||
        card.easeFactor < STILL_LEARNING_MIN_EASE

    fun stillLearningCards(
        cards: List<SentenceCard>,
        languageID: String,
        nowMillis: Long = System.currentTimeMillis(),
        limit: Int = Int.MAX_VALUE,
    ): List<SentenceCard> = cards
        .asSequence()
        .filter { it.languageID == languageID && isStillLearning(it, nowMillis) }
        .sortedWith(
            compareByDescending<SentenceCard> { it.isDue(nowMillis) }
                .thenBy { it.repetitionCount }
                .thenBy { it.easeFactor }
                .thenBy { it.nextReviewDate },
        )
        .take(limit.coerceAtLeast(0))
        .toList()

    fun stillLearningTopics(
        cards: List<SentenceCard>,
        languageID: String,
        nowMillis: Long = System.currentTimeMillis(),
        maxTopics: Int = 12,
    ): List<String> = stillLearningCards(cards, languageID, nowMillis)
        .groupBy { normalizedTopic(it.topic) }
        .map { (topic, items) ->
            Triple(topic, items.count { it.isDue(nowMillis) }, items.size)
        }
        .sortedWith(
            compareByDescending<Triple<String, Int, Int>> { it.second }
                .thenByDescending { it.third }
                .thenBy { it.first.lowercase() },
        )
        .take(maxTopics.coerceAtLeast(0))
        .map { it.first }

    fun searchLearningPhrases(
        cards: List<SentenceCard>,
        languageID: String,
        query: String? = null,
        topic: String? = null,
        limit: Int = 5,
        nowMillis: Long = System.currentTimeMillis(),
    ): List<SentenceCard> {
        val boundedLimit = limit.coerceIn(1, 8)
        val langCards = cardsForLanguage(cards, languageID)
        if (langCards.isEmpty()) return emptyList()

        val cleanQuery = query?.trim().orEmpty()
        val cleanTopic = topic?.trim().orEmpty()
        if (cleanQuery.isEmpty() && cleanTopic.isEmpty()) {
            return stillLearningCards(langCards, languageID, nowMillis, boundedLimit)
        }

        val queryTokens = tokenizeForSearch(cleanQuery)
        val topicTokens = tokenizeForSearch(cleanTopic)
        val allowMasteredByDirectQuery = cleanQuery.isNotEmpty()

        data class RankedCard(val card: SentenceCard, val score: Double)

        return langCards.mapNotNull { card ->
            val stillLearning = isStillLearning(card, nowMillis)
            if (!allowMasteredByDirectQuery && !stillLearning) return@mapNotNull null

            val cardTopic = normalizedTopic(card.topic)
            val cardTopicTokens = tokenizeForSearch(cardTopic)
            val backTokens = tokenizeForSearch(card.backText)
            val frontTokens = tokenizeForSearch(card.frontText)
            val explanationTokens = tokenizeForSearch(card.explanation)
            val mistakeTokens = tokenizeForSearch(card.userOriginalMistake)

            var relevance = 0.0
            if (cleanTopic.isNotEmpty()) {
                when {
                    cardTopic.equals(cleanTopic, ignoreCase = true) -> relevance += 50.0
                    cardTopic.contains(cleanTopic, ignoreCase = true) ||
                        cleanTopic.contains(cardTopic, ignoreCase = true) -> relevance += 30.0
                }
                for (tt in topicTokens) {
                    if (tt in cardTopicTokens) relevance += 15.0
                    else if (cardTopicTokens.any { it.contains(tt) || tt.contains(it) }) relevance += 8.0
                    if (tt in backTokens) relevance += 6.0
                    if (tt in explanationTokens) relevance += 4.0
                }
            }
            if (cleanQuery.isNotEmpty()) {
                if (card.backText.contains(cleanQuery, ignoreCase = true)) relevance += 28.0
                if (card.frontText.contains(cleanQuery, ignoreCase = true)) relevance += 24.0
                if (cardTopic.contains(cleanQuery, ignoreCase = true)) relevance += 22.0
                if (card.explanation.contains(cleanQuery, ignoreCase = true)) relevance += 14.0
                if (card.userOriginalMistake.contains(cleanQuery, ignoreCase = true)) relevance += 10.0
                for (qt in queryTokens) {
                    if (qt in backTokens) relevance += 12.0
                    else if (backTokens.any { it.contains(qt) || qt.contains(it) }) relevance += 6.0
                    if (qt in frontTokens) relevance += 10.0
                    else if (frontTokens.any { it.contains(qt) || qt.contains(it) }) relevance += 5.0
                    if (qt in cardTopicTokens) relevance += 10.0
                    else if (cardTopicTokens.any { it.contains(qt) || qt.contains(it) }) relevance += 5.0
                    if (qt in explanationTokens) relevance += 6.0
                    if (qt in mistakeTokens) relevance += 5.0
                }
            }

            if (relevance <= 0.0) return@mapNotNull null

            val dueBoost = if (card.isDue(nowMillis)) 12.0 else 0.0
            val stillLearningBoost = if (stillLearning) 8.0 else 0.0
            val repBoost = (4 - card.repetitionCount.coerceIn(0, 4)) * 1.5
            val easeBoost = (3.0 - card.easeFactor.toDouble().coerceIn(1.3, 3.0)) * 2.0
            RankedCard(card, relevance + dueBoost + stillLearningBoost + repBoost + easeBoost)
        }
            .sortedWith(
                compareByDescending<RankedCard> { it.score }
                    .thenBy { it.card.nextReviewDate },
            )
            .take(boundedLimit)
            .map { it.card }
    }

    fun formatLearningPhrasesToolResult(
        cards: List<SentenceCard>,
        languageID: String,
        topic: String? = null,
        query: String? = null,
        limit: Int = 5,
        nowMillis: Long = System.currentTimeMillis(),
    ): LearningPhrasesLookupResult {
        val matches = searchLearningPhrases(cards, languageID, query, topic, limit, nowMillis)
        val json = buildJsonObject {
            put("language", languageID)
            put("count", matches.size)
            put(
                "phrases",
                buildJsonArray {
                    for (card in matches) {
                        add(
                            buildJsonObject {
                                put("target_phrase", card.backText.take(240))
                                put("meaning", card.frontText.take(240))
                                put("topic", normalizedTopic(card.topic).take(80))
                                if (card.userOriginalMistake.isNotBlank()) {
                                    put("past_mistake", card.userOriginalMistake.take(240))
                                }
                            },
                        )
                    }
                },
            )
            if (matches.isEmpty()) {
                put("note", "No matching still-learning phrases found for this topic or query.")
            }
        }
        return LearningPhrasesLookupResult(
            jsonText = json.toString(),
            matchedCardIDs = matches.map { it.id },
        )
    }

    fun selectPostSessionCandidates(
        cards: List<SentenceCard>,
        languageID: String,
        prioritizedCardIDs: Collection<String>,
        nowMillis: Long = System.currentTimeMillis(),
        maxCandidates: Int = 25,
    ): List<SentenceCard> {
        val boundedMax = maxCandidates.coerceAtLeast(0)
        if (boundedMax == 0) return emptyList()
        val langCards = cardsForLanguage(cards, languageID)
        val byId = langCards.associateBy { it.id }
        val selected = linkedMapOf<String, SentenceCard>()
        for (id in prioritizedCardIDs) {
            if (selected.size >= boundedMax) break
            byId[id]?.let { selected.putIfAbsent(it.id, it) }
        }
        if (selected.size < boundedMax) {
            for (card in stillLearningCards(langCards, languageID, nowMillis)) {
                if (selected.size >= boundedMax) break
                selected.putIfAbsent(card.id, card)
            }
        }
        return selected.values.toList()
    }

    fun applyPracticedPhrases(
        existingCards: List<SentenceCard>,
        reportedPhrases: List<PracticedPhrase>,
        languageID: String,
        sessionID: String,
        userTranscriptTexts: List<String>,
        excludedCorrections: Set<String> = emptySet(),
        alreadyCreditedCardIDs: Set<String> = emptySet(),
        nowMillis: Long = System.currentTimeMillis(),
    ): PracticedPhrasesResult {
        val cleanSessionID = sessionID.trim()
        val excluded = excludedCorrections.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
        val normalizedTranscript = normalizeForMatch(userTranscriptTexts.joinToString(" "))
        if (normalizedTranscript.isEmpty() || reportedPhrases.isEmpty()) {
            return PracticedPhrasesResult(existingCards, emptyList(), alreadyCreditedCardIDs.toList())
        }
        val transcriptWords = normalizedTranscript.split(' ').filter { it.isNotEmpty() }.toSet()

        val cardsById = existingCards
            .filter { it.languageID == languageID }
            .associateBy { it.id }
        val verified = mutableListOf<PracticedPhrase>()
        val seenIds = mutableSetOf<String>()
        val toCreditIds = mutableSetOf<String>()
        val creditedIds = alreadyCreditedCardIDs.toMutableSet()

        for (item in reportedPhrases) {
            val cardID = item.cardID.trim()
            val quote = item.userQuote.trim()
            if (cardID.isEmpty() || quote.isEmpty() || !seenIds.add(cardID)) continue
            val card = cardsById[cardID] ?: continue
            if (card.backText.trim().lowercase() in excluded) continue
            if (!quoteSupportedByTranscript(quote, normalizedTranscript, transcriptWords)) continue

            verified += PracticedPhrase(
                cardID = card.id,
                phrase = card.backText,
                userQuote = quote,
            )
            val alreadyCreditedHere = card.id in creditedIds ||
                (cleanSessionID.isNotEmpty() && card.lastAutoCreditedSessionID == cleanSessionID)
            if (!alreadyCreditedHere) {
                toCreditIds += card.id
                creditedIds += card.id
            }
        }

        if (toCreditIds.isEmpty()) {
            return PracticedPhrasesResult(existingCards, verified, creditedIds.toList())
        }

        val updatedCards = existingCards.map { card ->
            if (card.id in toCreditIds) {
                review(card, ReviewRating.Good, nowMillis).copy(lastAutoCreditedSessionID = cleanSessionID)
            } else {
                card
            }
        }
        return PracticedPhrasesResult(updatedCards, verified, creditedIds.toList())
    }

    private fun quoteSupportedByTranscript(
        quote: String,
        normalizedTranscript: String,
        transcriptWords: Set<String>,
    ): Boolean {
        val normalizedQuote = normalizeForMatch(quote)
        if (normalizedQuote.isEmpty()) return false
        if (normalizedTranscript.contains(normalizedQuote)) return true
        val quoteWords = normalizedQuote.split(' ').filter { it.isNotEmpty() }
        if (quoteWords.isEmpty()) return false
        val matched = quoteWords.count { it in transcriptWords }
        return if (quoteWords.size <= 2) {
            matched == quoteWords.size
        } else {
            matched.toDouble() / quoteWords.size.toDouble() >= 0.6
        }
    }

    private fun normalizeForMatch(text: String): String = text
        .lowercase()
        .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun tokenizeForSearch(text: String): Set<String> = text
        .lowercase()
        .split(Regex("[^\\p{L}\\p{N}]+"))
        .asSequence()
        .map { it.trim() }
        .filter { it.length >= 2 && it !in SEARCH_STOP_WORDS }
        .toSet()
}
