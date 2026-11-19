package chat.mural.core

import chat.mural.reminder.StudyReminderScheduler
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LearningProgressTest {

    private fun appleSecondsForInstant(instant: Instant): Double =
        (instant.toEpochMilli() / 1000.0) - APPLE_EPOCH_UNIX_SECONDS

    private fun sessionAt(
        instant: Instant,
        durationSeconds: Double,
        languageID: String = "en",
        withUserSpeech: Boolean = true,
    ): SessionRecord {
        val start = appleSecondsForInstant(instant)
        return SessionRecord(
            languageID = languageID,
            startedAt = start,
            endedAt = start + durationSeconds,
            voiceSeconds = durationSeconds,
        ).apply {
            if (withUserSpeech) {
                append(Fragment(speaker = Speaker.user, text = "I practiced speaking today.", startMS = 0, endMS = 1000))
            } else {
                append(Fragment(speaker = Speaker.assistant, text = "Hi!", startMS = 0, endMS = 500))
            }
        }
    }

    @Test
    fun streakCalculationHandlesConsecutiveDaysYesterdayAliveAndMissedDayReset() {
        val zone = ZoneId.of("UTC")
        val todayClock = Clock.fixed(Instant.parse("2026-11-19T15:00:00Z"), zone)

        // 1. Practiced on Nov 17 (session), Nov 18 (cards), Nov 19 (both) -> 3-day streak
        val records = listOf(
            DailyActivityRecord("2026-11-18", speakingMinutes = 0, cardsReviewed = 5),
            DailyActivityRecord("2026-11-19", speakingMinutes = 4, cardsReviewed = 2),
        )
        val sessions = listOf(
            sessionAt(Instant.parse("2026-11-17T10:00:00Z"), durationSeconds = 360.0),
            sessionAt(Instant.parse("2026-11-19T12:00:00Z"), durationSeconds = 240.0),
        )
        val summary3 = LearningProgressCalculator.compute(
            sessions = sessions,
            sentenceCards = emptyList(),
            activityRecords = records,
            languageID = "en",
            memory = null,
            dailyGoal = DailyGoalConfig(targetSpeakingMinutes = 5, targetCardsReviewed = 5),
            clock = todayClock,
        )
        assertEquals(3, summary3.currentStreakDays)
        assertTrue(summary3.practicedToday)
        // maxOf(session=4, record=4) == 4 (no double-counting!)
        assertEquals(4, summary3.todaySpeakingMinutes)
        assertEquals(2, summary3.todayCardsReviewed)
        assertFalse(summary3.dailyGoalMet) // 4 < 5 min and 2 < 5 cards
        assertEquals(10, summary3.weeklySpeakingMinutes) // 6 + 4 = 10

        // 2. Alive streak from yesterday when user hasn't practiced today yet
        val aliveFromYesterday = LearningProgressCalculator.compute(
            sessions = listOf(sessionAt(Instant.parse("2026-11-17T10:00:00Z"), 300.0)),
            sentenceCards = emptyList(),
            activityRecords = listOf(DailyActivityRecord("2026-11-18", speakingMinutes = 5, cardsReviewed = 0)),
            languageID = "en",
            memory = null,
            clock = todayClock,
        )
        assertEquals(2, aliveFromYesterday.currentStreakDays)
        assertFalse(aliveFromYesterday.practicedToday)

        // 3. Missed yesterday -> streak resets to 0
        val broken = LearningProgressCalculator.compute(
            sessions = listOf(sessionAt(Instant.parse("2026-11-17T10:00:00Z"), 300.0)),
            sentenceCards = emptyList(),
            activityRecords = emptyList(),
            languageID = "en",
            memory = null,
            clock = todayClock,
        )
        assertEquals(0, broken.currentStreakDays)
        assertFalse(broken.practicedToday)
    }

    @Test
    fun timezoneBoundariesAndMasteredAndDailyGoalComputationStayExact() {
        // 2026-11-19T22:30:00Z is already Nov 20 in Africa/Cairo (UTC+2), still Nov 19 in America/New_York (UTC-5)
        val instant = Instant.parse("2026-11-19T22:30:00Z")
        val cairoClock = Clock.fixed(instant, ZoneId.of("Africa/Cairo"))
        val nyClock = Clock.fixed(instant, ZoneId.of("America/New_York"))

        val session = sessionAt(instant, durationSeconds = 420.0) // 7 min
        val assistantOnlySession = sessionAt(instant, durationSeconds = 300.0, withUserSpeech = false)

        val cards = listOf(
            SentenceCard(
                id = "m-1",
                topic = "Tech",
                languageID = "en",
                frontText = "مثال ١",
                backText = "Mastered sentence one.",
                explanation = "Ok",
                userOriginalMistake = "",
                repetitionCount = 3,
                intervalDays = 14,
                nextReviewDate = instant.toEpochMilli() + 14 * Sm2Calculator.DAY_MILLIS,
            ),
            SentenceCard(
                id = "l-1",
                topic = "Tech",
                languageID = "en",
                frontText = "مثال ٢",
                backText = "Still learning sentence.",
                explanation = "Ok",
                userOriginalMistake = "",
                repetitionCount = 3,
                intervalDays = 13, // Not >= 14 days yet -> learning
                nextReviewDate = instant.toEpochMilli() - 1000L, // Due
            ),
            SentenceCard(
                id = "es-1",
                topic = "Viajes",
                languageID = "es",
                frontText = "مثال ٣",
                backText = "Quiero un café.",
                explanation = "Ok",
                userOriginalMistake = "",
                repetitionCount = 4,
                intervalDays = 20,
                nextReviewDate = instant.toEpochMilli() - 1000L,
            ),
        )
        val memory = LearnerMemory(estimatedLevel = "B1", manualLevelOverride = "B2")

        // Cairo: session happened on 2026-11-20 local date
        val cairoRecords = LearningProgressCalculator.recordCardReviewActivity(emptyList(), cardsReviewed = 5, clock = cairoClock)
        val cairoSummary = LearningProgressCalculator.compute(
            sessions = listOf(session, assistantOnlySession),
            sentenceCards = cards,
            activityRecords = cairoRecords,
            languageID = "en",
            memory = memory,
            dailyGoal = DailyGoalConfig(targetSpeakingMinutes = 10, targetCardsReviewed = 5),
            clock = cairoClock,
        )
        assertEquals(LocalDate.of(2026, 11, 20), LocalDate.now(cairoClock))
        assertEquals(7, cairoSummary.todaySpeakingMinutes) // assistant-only session excluded
        assertEquals(5, cairoSummary.todayCardsReviewed)
        assertTrue(cairoSummary.dailyGoalMet) // Met via 5 cards reviewed!
        assertEquals(1, cairoSummary.masteredSentenceCount)
        assertEquals(1, cairoSummary.learningSentenceCount)
        assertEquals(1, cairoSummary.dueSentenceCount)
        assertEquals("B2", cairoSummary.effectiveCefrLevel)

        // New York: same instant is 2026-11-19 local date; Cairo's 2026-11-20 card activity is tomorrow from NY's perspective
        val nySummary = LearningProgressCalculator.compute(
            sessions = listOf(session),
            sentenceCards = cards,
            activityRecords = cairoRecords,
            languageID = "en",
            memory = memory,
            dailyGoal = DailyGoalConfig(targetSpeakingMinutes = 10, targetCardsReviewed = 5),
            clock = nyClock,
        )
        assertEquals(7, nySummary.todaySpeakingMinutes)
        assertEquals(0, nySummary.todayCardsReviewed)
        assertFalse(nySummary.dailyGoalMet)
    }

    @Test
    fun weeklySpeakingMinutesSumsRollingSevenDaysOnly() {
        val zone = ZoneId.of("UTC")
        val clock = Clock.fixed(Instant.parse("2026-11-19T12:00:00Z"), zone)
        val records = listOf(
            DailyActivityRecord("2026-11-12", speakingMinutes = 30), // 7 days ago -> outside [Nov 13..Nov 19]
            DailyActivityRecord("2026-11-13", speakingMinutes = 6),  // inside
            DailyActivityRecord("2026-11-16", speakingMinutes = 8),  // inside
            DailyActivityRecord("2026-11-19", speakingMinutes = 5),  // inside
        )
        val summary = LearningProgressCalculator.compute(
            sessions = emptyList(),
            sentenceCards = emptyList(),
            activityRecords = records,
            languageID = "en",
            memory = null,
            clock = clock,
        )
        assertEquals(19, summary.weeklySpeakingMinutes)
    }

    @Test
    fun smartReminderSuppressesWhenDisabledOrPracticedTodayAndFiresOnlyWhenHelpful() {
        val baseSummary = LearningProgressSummary(
            currentStreakDays = 4,
            practicedToday = false,
            todaySpeakingMinutes = 0,
            todayCardsReviewed = 0,
            dailyGoal = DailyGoalConfig(5, 5),
            dailyGoalMet = false,
            weeklySpeakingMinutes = 25,
            masteredSentenceCount = 3,
            learningSentenceCount = 5,
            dueSentenceCount = 3,
            effectiveCefrLevel = "B1",
        )

        // Disabled -> suppressed
        assertFalse(
            LearningProgressCalculator.evaluateReminder(
                StudyReminderConfig(enabled = false, hour = 20),
                baseSummary,
                totalDueCardsAllLanguages = 3,
            ).shouldNotify,
        )

        // Already practiced today -> suppressed even with due cards
        assertFalse(
            LearningProgressCalculator.evaluateReminder(
                StudyReminderConfig(enabled = true, hour = 20),
                baseSummary.copy(practicedToday = true),
                totalDueCardsAllLanguages = 3,
            ).shouldNotify,
        )

        // Enabled + not practiced today + due cards -> fires with due-card & streak context
        val fireWithDue = LearningProgressCalculator.evaluateReminder(
            StudyReminderConfig(enabled = true, hour = 20),
            baseSummary,
            totalDueCardsAllLanguages = 3,
        )
        assertTrue(fireWithDue.shouldNotify)
        assertTrue(fireWithDue.title.contains("4-day streak"))
        assertTrue(fireWithDue.body.contains("3 due flashcards"))

        // Enabled + not practiced today + 0 due cards + daily goal unmet -> fires gentle goal nudge
        val fireGoalOnly = LearningProgressCalculator.evaluateReminder(
            StudyReminderConfig(enabled = true, hour = 20),
            baseSummary.copy(dueSentenceCount = 0),
            totalDueCardsAllLanguages = 0,
        )
        assertTrue(fireGoalOnly.shouldNotify)
        assertTrue(fireGoalOnly.body.contains("4-day streak"))
    }

    @Test
    fun reminderSchedulerComputesNextTriggerBeforeAfterAndForcedTomorrow() {
        val zone = ZoneId.of("UTC")
        // 18:00 UTC, target 20:00 -> same day 20:00
        val beforeHour = Clock.fixed(Instant.parse("2026-11-19T18:00:00Z"), zone)
        assertEquals(
            Instant.parse("2026-11-19T20:00:00Z").toEpochMilli(),
            StudyReminderScheduler.nextTriggerAtMillis(hour = 20, minute = 0, clock = beforeHour),
        )

        // 20:00 UTC exact -> advances to next day 20:00
        val exactHour = Clock.fixed(Instant.parse("2026-11-19T20:00:00Z"), zone)
        assertEquals(
            Instant.parse("2026-11-20T20:00:00Z").toEpochMilli(),
            StudyReminderScheduler.nextTriggerAtMillis(hour = 20, minute = 0, clock = exactHour),
        )

        // 19:57 UTC (slightly early inexact alarm wakeup) with forceTomorrow=true -> next day 20:00
        val slightlyEarly = Clock.fixed(Instant.parse("2026-11-19T19:57:00Z"), zone)
        assertEquals(
            Instant.parse("2026-11-20T20:00:00Z").toEpochMilli(),
            StudyReminderScheduler.nextTriggerAtMillis(hour = 20, minute = 0, clock = slightlyEarly, forceTomorrow = true),
        )
    }
}
