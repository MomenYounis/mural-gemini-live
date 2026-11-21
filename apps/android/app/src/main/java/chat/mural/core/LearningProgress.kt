package chat.mural.core

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import kotlin.math.roundToInt
import kotlinx.serialization.Serializable

@Serializable
data class DailyActivityRecord(
    val dateIso: String,
    val speakingMinutes: Int = 0,
    val cardsReviewed: Int = 0,
)

@Serializable
data class DailyGoalConfig(
    val targetSpeakingMinutes: Int = 5,
    val targetCardsReviewed: Int = 5,
)

@Serializable
data class StudyReminderConfig(
    val enabled: Boolean = false,
    val hour: Int = 20,
    val minute: Int = 0,
)

@Serializable
data class LearningSettings(
    val geminiVoice: String = "",
    val coachStyle: CoachStyle = CoachStyle.BALANCED,
    val audioEnvironment: AudioEnvironment = AudioEnvironment.QUIET,
    val pushToTalkEnabled: Boolean = false,
    val flashcardAutoPlayOnFlip: Boolean = false,
    val flashcardListeningMode: Boolean = false,
    val dailyGoal: DailyGoalConfig = DailyGoalConfig(),
    val reminder: StudyReminderConfig = StudyReminderConfig(),
) {
    fun sanitized(): LearningSettings = copy(
        geminiVoice = geminiVoice.trim().take(40),
        dailyGoal = DailyGoalConfig(
            targetSpeakingMinutes = dailyGoal.targetSpeakingMinutes.coerceIn(1, 120),
            targetCardsReviewed = dailyGoal.targetCardsReviewed.coerceIn(1, 200),
        ),
        reminder = StudyReminderConfig(
            enabled = reminder.enabled,
            hour = reminder.hour.coerceIn(0, 23),
            minute = reminder.minute.coerceIn(0, 59),
        ),
    )
}

data class LearningProgressSummary(
    val currentStreakDays: Int,
    val practicedToday: Boolean,
    val todaySpeakingMinutes: Int,
    val todayCardsReviewed: Int,
    val dailyGoal: DailyGoalConfig,
    val dailyGoalMet: Boolean,
    val weeklySpeakingMinutes: Int,
    val masteredSentenceCount: Int,
    val learningSentenceCount: Int,
    val dueSentenceCount: Int,
    val effectiveCefrLevel: String,
)

data class ReminderDecision(
    val shouldNotify: Boolean,
    val dueCardCount: Int,
    val currentStreakDays: Int,
    val title: String,
    val body: String,
)

object LearningProgressCalculator {
    private const val MAX_ACTIVITY_DAYS = 365
    private const val MASTERED_MIN_REPS = 3
    private const val MASTERED_MIN_INTERVAL_DAYS = 14

    fun sanitizeActivityRecords(records: List<DailyActivityRecord>): List<DailyActivityRecord> {
        val byDate = linkedMapOf<LocalDate, DailyActivityRecord>()
        for (raw in records) {
            val parsedDate = runCatching { LocalDate.parse(raw.dateIso.trim()) }.getOrNull() ?: continue
            val cleanSpeaking = raw.speakingMinutes.coerceAtLeast(0)
            val cleanCards = raw.cardsReviewed.coerceAtLeast(0)
            if (cleanSpeaking == 0 && cleanCards == 0) continue
            val existing = byDate[parsedDate]
            byDate[parsedDate] = if (existing == null) {
                DailyActivityRecord(
                    dateIso = parsedDate.toString(),
                    speakingMinutes = cleanSpeaking,
                    cardsReviewed = cleanCards,
                )
            } else {
                DailyActivityRecord(
                    dateIso = parsedDate.toString(),
                    speakingMinutes = maxOf(existing.speakingMinutes, cleanSpeaking),
                    cardsReviewed = maxOf(existing.cardsReviewed, cleanCards),
                )
            }
        }
        return byDate.entries
            .sortedBy { it.key }
            .map { it.value }
            .takeLast(MAX_ACTIVITY_DAYS)
    }

    fun recordConversationActivity(
        records: List<DailyActivityRecord>,
        durationMinutes: Int,
        clock: Clock = Clock.systemDefaultZone(),
    ): List<DailyActivityRecord> {
        val addedMinutes = durationMinutes.coerceAtLeast(1)
        val today = LocalDate.now(clock).toString()
        val sanitized = sanitizeActivityRecords(records).associateBy { it.dateIso }.toMutableMap()
        val current = sanitized[today]
        sanitized[today] = DailyActivityRecord(
            dateIso = today,
            speakingMinutes = (current?.speakingMinutes ?: 0) + addedMinutes,
            cardsReviewed = current?.cardsReviewed ?: 0,
        )
        return sanitizeActivityRecords(sanitized.values.toList())
    }

    fun recordCardReviewActivity(
        records: List<DailyActivityRecord>,
        cardsReviewed: Int = 1,
        clock: Clock = Clock.systemDefaultZone(),
    ): List<DailyActivityRecord> {
        val addedCards = cardsReviewed.coerceAtLeast(1)
        val today = LocalDate.now(clock).toString()
        val sanitized = sanitizeActivityRecords(records).associateBy { it.dateIso }.toMutableMap()
        val current = sanitized[today]
        sanitized[today] = DailyActivityRecord(
            dateIso = today,
            speakingMinutes = current?.speakingMinutes ?: 0,
            cardsReviewed = (current?.cardsReviewed ?: 0) + addedCards,
        )
        return sanitizeActivityRecords(sanitized.values.toList())
    }

    fun mergeActivityRecords(
        current: List<DailyActivityRecord>,
        incoming: List<DailyActivityRecord>,
    ): List<DailyActivityRecord> = sanitizeActivityRecords(current + incoming)

    fun totalDueCards(
        sentenceCards: List<SentenceCard>,
        nowMillis: Long = System.currentTimeMillis(),
    ): Int = sentenceCards.count { it.isDue(nowMillis) }

    fun compute(
        sessions: List<SessionRecord>,
        sentenceCards: List<SentenceCard>,
        activityRecords: List<DailyActivityRecord>,
        languageID: String,
        memory: LearnerMemory?,
        dailyGoal: DailyGoalConfig = DailyGoalConfig(),
        clock: Clock = Clock.systemDefaultZone(),
    ): LearningProgressSummary {
        val zone = clock.zone
        val nowMillis = clock.millis()
        val nowAppleSeconds = nowMillis / 1000.0 - APPLE_EPOCH_UNIX_SECONDS

        val sessionMinutesByDate = mutableMapOf<LocalDate, Int>()
        for (session in sessions) {
            val hasUserSpeech = session.passages.any { it.speaker == Speaker.user && it.text.isNotBlank() }
            if (!hasUserSpeech) continue
            val rawSeconds = if (session.voiceSeconds > 0.0) {
                session.voiceSeconds
            } else {
                ((session.endedAt ?: nowAppleSeconds) - session.startedAt)
            }
            val minutes = maxOf(1, (rawSeconds.coerceAtLeast(1.0) / 60.0).roundToInt())
            val unixMillis = ((session.startedAt + APPLE_EPOCH_UNIX_SECONDS) * 1000.0).toLong()
            val localDate = Instant.ofEpochMilli(unixMillis).atZone(zone).toLocalDate()
            sessionMinutesByDate[localDate] = (sessionMinutesByDate[localDate] ?: 0) + minutes
        }

        val recordByDate = mutableMapOf<LocalDate, DailyActivityRecord>()
        for (record in sanitizeActivityRecords(activityRecords)) {
            runCatching { LocalDate.parse(record.dateIso) }.getOrNull()?.let { date ->
                recordByDate[date] = record
            }
        }

        val allDates = (sessionMinutesByDate.keys + recordByDate.keys).toSet()
        val combinedSpeakingByDate = mutableMapOf<LocalDate, Int>()
        val combinedCardsByDate = mutableMapOf<LocalDate, Int>()
        val activeDates = mutableSetOf<LocalDate>()

        for (date in allDates) {
            val speaking = maxOf(sessionMinutesByDate[date] ?: 0, recordByDate[date]?.speakingMinutes ?: 0)
            val cards = recordByDate[date]?.cardsReviewed ?: 0
            combinedSpeakingByDate[date] = speaking
            combinedCardsByDate[date] = cards
            if (speaking > 0 || cards > 0) {
                activeDates += date
            }
        }

        val today = LocalDate.now(clock)
        val practicedToday = today in activeDates
        val streakStart = when {
            practicedToday -> today
            today.minusDays(1) in activeDates -> today.minusDays(1)
            else -> null
        }
        var currentStreakDays = 0
        if (streakStart != null) {
            var cursor: LocalDate = streakStart
            while (cursor in activeDates) {
                currentStreakDays++
                cursor = cursor.minusDays(1)
            }
        }

        val todaySpeakingMinutes = combinedSpeakingByDate[today] ?: 0
        val todayCardsReviewed = combinedCardsByDate[today] ?: 0
        val cleanGoal = DailyGoalConfig(
            targetSpeakingMinutes = dailyGoal.targetSpeakingMinutes.coerceIn(1, 120),
            targetCardsReviewed = dailyGoal.targetCardsReviewed.coerceIn(1, 200),
        )
        val dailyGoalMet = todaySpeakingMinutes >= cleanGoal.targetSpeakingMinutes ||
            todayCardsReviewed >= cleanGoal.targetCardsReviewed

        val weekStart = today.minusDays(6)
        var weeklySpeakingMinutes = 0
        var cursor = weekStart
        while (!cursor.isAfter(today)) {
            weeklySpeakingMinutes += combinedSpeakingByDate[cursor] ?: 0
            cursor = cursor.plusDays(1)
        }

        val langCards = sentenceCards.filter { it.languageID == languageID }
        val masteredSentenceCount = langCards.count {
            it.repetitionCount >= MASTERED_MIN_REPS && it.intervalDays >= MASTERED_MIN_INTERVAL_DAYS
        }
        val learningSentenceCount = langCards.size - masteredSentenceCount
        val dueSentenceCount = langCards.count { it.isDue(nowMillis) }
        val effectiveLevel = memory?.sanitized()?.effectiveLevel.orEmpty()

        return LearningProgressSummary(
            currentStreakDays = currentStreakDays,
            practicedToday = practicedToday,
            todaySpeakingMinutes = todaySpeakingMinutes,
            todayCardsReviewed = todayCardsReviewed,
            dailyGoal = cleanGoal,
            dailyGoalMet = dailyGoalMet,
            weeklySpeakingMinutes = weeklySpeakingMinutes,
            masteredSentenceCount = masteredSentenceCount,
            learningSentenceCount = learningSentenceCount,
            dueSentenceCount = dueSentenceCount,
            effectiveCefrLevel = effectiveLevel,
        )
    }

    fun evaluateReminder(
        reminderConfig: StudyReminderConfig,
        progress: LearningProgressSummary,
        totalDueCardsAllLanguages: Int,
    ): ReminderDecision {
        val due = totalDueCardsAllLanguages.coerceAtLeast(0)
        val shouldNotify = reminderConfig.enabled &&
            !progress.practicedToday &&
            (due > 0 || !progress.dailyGoalMet)

        val title = when {
            due > 0 && progress.currentStreakDays > 0 ->
                "Keep your ${progress.currentStreakDays}-day streak going"
            due > 0 ->
                "A few sentences are ready to review"
            else ->
                "Ready for a quick practice?"
        }
        val body = when {
            due > 0 ->
                "You have $due due ${if (due == 1) "flashcard" else "flashcards"} waiting — or jump into a calm ${progress.dailyGoal.targetSpeakingMinutes}-minute chat."
            progress.currentStreakDays > 0 ->
                "A quick ${progress.dailyGoal.targetSpeakingMinutes}-minute conversation keeps your ${progress.currentStreakDays}-day streak alive."
            else ->
                "Take ${progress.dailyGoal.targetSpeakingMinutes} calm minutes to practice speaking today."
        }
        return ReminderDecision(
            shouldNotify = shouldNotify,
            dueCardCount = due,
            currentStreakDays = progress.currentStreakDays,
            title = title,
            body = body,
        )
    }
}
