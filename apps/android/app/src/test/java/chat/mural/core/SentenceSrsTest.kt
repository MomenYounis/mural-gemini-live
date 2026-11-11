package chat.mural.core

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SentenceSrsTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun sm2CalculatorHandlesAgainHardGoodEasyTransitions() {
        val now = 1_700_000_000_000L
        val initial = SentenceCard(
            id = "card-1",
            topic = "Tech Interviews",
            languageID = "en",
            frontText = "لدي خبرة ثلاث سنوات في تطوير البرمجيات.",
            backText = "I have three years of experience in software development.",
            explanation = "Use 'experience in' instead of 'experience on'.",
            userOriginalMistake = "I have three years experience on software development.",
            nextReviewDate = now
        )
        assertEquals(0, initial.repetitionCount)
        assertEquals(2.5f, initial.easeFactor, 0.001f)
        assertEquals(0, initial.intervalDays)
        assertTrue(initial.isDue(now))

        // 1st review: Good -> rep 1, interval 1 day, EF stays 2.5
        val afterFirstGood = Sm2Calculator.review(initial, ReviewRating.Good, now)
        assertEquals(1, afterFirstGood.repetitionCount)
        assertEquals(1, afterFirstGood.intervalDays)
        assertEquals(2.5f, afterFirstGood.easeFactor, 0.001f)
        assertEquals(now + Sm2Calculator.DAY_MILLIS, afterFirstGood.nextReviewDate)
        assertFalse(afterFirstGood.isDue(now))
        assertTrue(afterFirstGood.isDue(now + Sm2Calculator.DAY_MILLIS))

        // 2nd review: Good -> rep 2, interval 6 days
        val day1 = now + Sm2Calculator.DAY_MILLIS
        val afterSecondGood = Sm2Calculator.review(afterFirstGood, ReviewRating.Good, day1)
        assertEquals(2, afterSecondGood.repetitionCount)
        assertEquals(6, afterSecondGood.intervalDays)
        assertEquals(2.5f, afterSecondGood.easeFactor, 0.001f)

        // 3rd review: Easy -> rep 3, interval > 6 days, EF increases by 0.15
        val day7 = day1 + 6 * Sm2Calculator.DAY_MILLIS
        val afterEasy = Sm2Calculator.review(afterSecondGood, ReviewRating.Easy, day7)
        assertEquals(3, afterEasy.repetitionCount)
        assertTrue(afterEasy.intervalDays >= 18)
        assertEquals(2.65f, afterEasy.easeFactor, 0.001f)

        // Hard rating -> EF decreases by 0.15, never below 1.3
        val afterHard = Sm2Calculator.review(afterSecondGood, ReviewRating.Hard, day7)
        assertEquals(3, afterHard.repetitionCount)
        assertEquals(7, afterHard.intervalDays)
        assertEquals(2.35f, afterHard.easeFactor, 0.001f)

        // Again rating -> resets repetitionCount to 0, intervalDays to 1, lowers EF by 0.20
        val afterAgain = Sm2Calculator.review(afterEasy, ReviewRating.Again, day7)
        assertEquals(0, afterAgain.repetitionCount)
        assertEquals(1, afterAgain.intervalDays)
        assertEquals(2.45f, afterAgain.easeFactor, 0.001f)
    }

    @Test
    fun mergeMistakesAndGroupDecksComputeDueCountsAndContextInjection() {
        val now = 2_000_000_000_000L
        val mistakes = listOf(
            SessionMistake(
                userSaid = "Yesterday I go to the gym.",
                correction = "Yesterday I went to the gym.",
                arabicMeaning = "ذهبت إلى الجيم بالأمس.",
                explanation = "Use past tense 'went' with 'yesterday'."
            ),
            SessionMistake(
                userSaid = "I am agree with you.",
                correction = "I agree with you.",
                arabicMeaning = "أنا أتفق معك.",
                explanation = "'Agree' is a verb, so do not use 'am' before it."
            )
        )
        val cards = Sm2Calculator.mergeMistakesIntoCards(
            existing = emptyList(),
            topic = "Daily Routine",
            languageID = "en",
            sessionID = "sess-1",
            mistakes = mistakes,
            nowMillis = now
        )
        assertEquals(2, cards.size)
        assertEquals("ذهبت إلى الجيم بالأمس.", cards[0].frontText)
        assertEquals("Yesterday I went to the gym.", cards[0].backText)
        assertEquals("Yesterday I go to the gym.", cards[0].userOriginalMistake)

        val decks = Sm2Calculator.groupByTopicDecks(cards, now)
        assertEquals(1, decks.size)
        assertEquals("Daily Routine", decks.single().topic)
        assertEquals(2, decks.single().dueCount)
        assertEquals(2, decks.single().totalCount)

        // Context injection returns up to 5 due cards
        val dueForPrompt = Sm2Calculator.dueCards(cards, now, limit = 5)
        assertEquals(2, dueForPrompt.size)

        val voicePrompt = TeachingPolicy.voice(
            language = LanguageRegistry.get("es") ?: LanguageRegistry.all.first(),
            learner = LearnerState(challenge = 1, observationCount = 0, nextGoal = "", capabilities = emptyList(), words = emptyList()),
            theme = null,
            interests = "",
            meaningLanguage = "ar",
            dueSentenceCards = dueForPrompt
        )
        assertTrue(voicePrompt.contains("Spaced repetition target sentences"))
        assertTrue(voicePrompt.contains("Yesterday I went to the gym."))
        assertTrue(voicePrompt.contains("I agree with you."))
    }

    @Test
    fun postSessionPayloadDeserializesSnakeCaseFields() {
        val sampleJson = """
            {
              "topic": "Backend Engineering",
              "mistakes": [
                {
                  "user_said": "The query is very slow because no index.",
                  "correction": "The query is very slow because there is no index.",
                  "arabic_meaning": "الاستعلام بطيء جدًا لأنه لا يوجد فهرس.",
                  "explanation": "After 'because', use a full clause with a subject and verb ('there is')."
                }
              ]
            }
        """.trimIndent()
        val parsed = json.decodeFromString<PostSessionAnalysisPayload>(sampleJson)
        assertEquals("Backend Engineering", parsed.topic)
        assertEquals(1, parsed.mistakes.size)
        assertEquals("The query is very slow because no index.", parsed.mistakes[0].userSaid)
        assertEquals("The query is very slow because there is no index.", parsed.mistakes[0].correction)
        assertEquals("الاستعلام بطيء جدًا لأنه لا يوجد فهرس.", parsed.mistakes[0].arabicMeaning)
    }
}
