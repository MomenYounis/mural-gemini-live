package chat.mural.core

import java.util.UUID
import kotlin.math.roundToInt
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
data class PostSessionAnalysisPayload(
    val topic: String,
    val mistakes: List<SessionMistake> = emptyList(),
)

@Serializable
data class SessionReport(
    val id: String = UUID.randomUUID().toString(),
    val sessionID: String = "",
    val languageID: String = "en",
    val date: Long = System.currentTimeMillis(),
    val topic: String,
    val durationMinutes: Int,
    val mistakeCount: Int = mistakes.size,
    val mistakes: List<SessionMistake> = emptyList(),
)

@Serializable
data class SentenceCard(
    val id: String = UUID.randomUUID().toString(),
    val topic: String,
    val languageID: String = "en",
    val sourceSessionID: String = "",
    val frontText: String,
    val backText: String,
    val explanation: String,
    val userOriginalMistake: String,
    val repetitionCount: Int = 0,
    val easeFactor: Float = Sm2Calculator.DEFAULT_EASE_FACTOR,
    val intervalDays: Int = 0,
    val nextReviewDate: Long = System.currentTimeMillis(),
) {
    fun isDue(nowMillis: Long = System.currentTimeMillis()): Boolean = nextReviewDate <= nowMillis
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

/**
 * Clean Anki SM-2 Spaced Repetition calculator for [SentenceCard] scheduling.
 */
object Sm2Calculator {
    const val DEFAULT_EASE_FACTOR: Float = 2.5f
    const val MIN_EASE_FACTOR: Float = 1.3f
    const val DAY_MILLIS: Long = 86_400_000L

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
    ): List<SentenceCard> = cards
        .filter { it.isDue(nowMillis) }
        .sortedBy { it.nextReviewDate }
        .take(limit.coerceAtLeast(0))

    fun groupByTopicDecks(
        cards: List<SentenceCard>,
        nowMillis: Long = System.currentTimeMillis(),
    ): List<TopicDeck> = cards
        .groupBy { it.topic.trim().ifEmpty { "General Conversation" } }
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
        val topic = report.topic.trim().ifEmpty { "General Conversation" }
        val result = existingCards.toMutableList()
        for (mistake in report.mistakes) {
            val correction = mistake.correction.trim()
            val front = mistake.arabicMeaning.trim()
            val userSaid = mistake.userSaid.trim()
            val explanation = mistake.explanation.trim()
            if (correction.isEmpty() || front.isEmpty()) continue
            val existingIndex = result.indexOfFirst {
                it.backText.trim().equals(correction, ignoreCase = true)
            }
            if (existingIndex >= 0) {
                val existing = result[existingIndex]
                result[existingIndex] = existing.copy(
                    topic = topic,
                    frontText = front,
                    backText = correction,
                    explanation = explanation.ifEmpty { existing.explanation },
                    userOriginalMistake = userSaid.ifEmpty { existing.userOriginalMistake },
                )
            } else {
                result += SentenceCard(
                    topic = topic,
                    languageID = report.languageID,
                    sourceSessionID = report.sessionID,
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
}
