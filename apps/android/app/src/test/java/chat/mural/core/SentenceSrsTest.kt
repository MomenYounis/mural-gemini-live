package chat.mural.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
        assertTrue(parsed.practicedPhrases.isEmpty())
    }

    @Test
    fun multiSessionProvenanceAndLanguageScopingStayAccurateAcrossMerges() {
        val now = 3_000_000_000_000L
        // Legacy card serialized with sourceSessionID only (sourceSessionIDs empty)
        val legacyCard = SentenceCard(
            id = "legacy-1",
            topic = "Daily Routine",
            languageID = "en",
            sourceSessionID = "sess-1",
            frontText = "ذهبت إلى الجيم بالأمس.",
            backText = "Yesterday I went to the gym.",
            explanation = "Use past tense.",
            userOriginalMistake = "Yesterday I go to the gym.",
            nextReviewDate = now - 1000L,
        )
        val spanishCard = SentenceCard(
            id = "es-1",
            topic = "Travel",
            languageID = "es",
            sourceSessionID = "sess-es-1",
            frontText = "أريد قهوة",
            backText = "Quiero un café.",
            explanation = "Polite order.",
            userOriginalMistake = "Yo querer café.",
            nextReviewDate = now - 1000L,
        )

        val report2 = SessionReport(
            sessionID = "sess-2",
            languageID = "en",
            date = now,
            topic = "Morning Habits",
            durationMinutes = 10,
            mistakes = listOf(
                SessionMistake(
                    userSaid = "Yesterday I go to the gym again.",
                    correction = "Yesterday I went to the gym.",
                    arabicMeaning = "ذهبت إلى الجيم بالأمس.",
                    explanation = "Past simple 'went'.",
                ),
            ),
        )
        val merged = Sm2Calculator.mergeFromReport(listOf(legacyCard, spanishCard), report2, now)
        assertEquals(2, merged.size)
        val updatedEn = merged.first { it.id == "legacy-1" }
        assertEquals("sess-2", updatedEn.sourceSessionID)
        assertEquals(listOf("sess-1", "sess-2"), updatedEn.allSourceSessionIDs())
        assertTrue(updatedEn.belongsToSession("sess-1"))
        assertTrue(updatedEn.belongsToSession("sess-2"))

        // Language scoping
        assertEquals(1, Sm2Calculator.cardsForLanguage(merged, "en").size)
        assertEquals(1, Sm2Calculator.cardsForLanguage(merged, "es").size)
        assertEquals(1, Sm2Calculator.dueCards(merged, now, languageID = "es").size)
        assertEquals(listOf("Travel"), Sm2Calculator.groupByTopicDecks(merged, now, languageID = "es").map { it.topic })
        assertEquals(listOf("Morning Habits"), Sm2Calculator.groupByTopicDecks(merged, now, languageID = "en").map { it.topic })
    }

    @Test
    fun multiLevelDeletionHandlesSessionsSharedCardsTopicsSingleCardsAndMistakes() {
        val now = 3_000_000_000_000L
        val sharedCard = SentenceCard(
            id = "c-shared",
            topic = "Tech Interviews",
            languageID = "en",
            sourceSessionID = "sess-2",
            sourceSessionIDs = listOf("sess-1", "sess-2"),
            frontText = "لدي خبرة ثلاث سنوات.",
            backText = "I have three years of experience.",
            explanation = "Use 'years of experience'.",
            userOriginalMistake = "I have three years experience.",
            nextReviewDate = now,
        )
        val sess1OnlyCard = SentenceCard(
            id = "c-sess1",
            topic = "Tech Interviews",
            languageID = "en",
            sourceSessionID = "sess-1",
            sourceSessionIDs = listOf("sess-1"),
            frontText = "قمت بنشر الكود.",
            backText = "I deployed the code.",
            explanation = "Past tense.",
            userOriginalMistake = "I deploy the code.",
            nextReviewDate = now,
        )
        val spanishSameTopicCard = SentenceCard(
            id = "c-es",
            topic = "Tech Interviews",
            languageID = "es",
            sourceSessionID = "sess-es",
            sourceSessionIDs = listOf("sess-es"),
            frontText = "مقابلة عمل",
            backText = "Tengo experiencia en desarrollo.",
            explanation = "Spanish tech phrase.",
            userOriginalMistake = "",
            nextReviewDate = now,
        )

        val cards = listOf(sharedCard, sess1OnlyCard, spanishSameTopicCard)
        val impact = Sm2Calculator.sessionDeletionImpact(cards, "sess-1")
        assertEquals(1, impact.deletedCardCount)
        assertEquals(1, impact.unlinkedSharedCardCount)
        assertEquals(2, impact.totalAffectedCount)

        // Level 1: Delete session flashcards for sess-1
        val afterSess1Delete = Sm2Calculator.removeSessionFromCards(cards, "sess-1")
        assertEquals(2, afterSess1Delete.size)
        val remainingShared = afterSess1Delete.first { it.id == "c-shared" }
        assertEquals("sess-2", remainingShared.sourceSessionID)
        assertEquals(listOf("sess-2"), remainingShared.sourceSessionIDs)

        // Level 2: Delete entire Topic Deck for active language only
        val afterDeckDelete = Sm2Calculator.deleteTopicDeck(afterSess1Delete, "Tech Interviews", "en")
        assertEquals(listOf("c-es"), afterDeckDelete.map { it.id })

        // Level 3A: Delete single card
        assertTrue(Sm2Calculator.deleteCard(afterDeckDelete, "c-es").isEmpty())

        // Level 3B: Delete single mistake from report preserves shared card's other session
        val report = SessionReport(
            id = "rep-1",
            sessionID = "sess-1",
            languageID = "en",
            date = now,
            topic = "Tech Interviews",
            durationMinutes = 8,
            mistakeCount = 2,
            mistakes = listOf(
                SessionMistake("I have three years experience.", "I have three years of experience.", "لدي خبرة.", "exp"),
                SessionMistake("I deploy the code.", "I deployed the code.", "قمت بنشر الكود.", "past"),
            ),
        )
        val (updatedReports, updatedCards) = Sm2Calculator.removeMistakeFromReport(
            reports = listOf(report),
            cards = cards,
            reportID = "rep-1",
            mistakeIndex = 0,
        )
        assertEquals(1, updatedReports.single().mistakeCount)
        assertEquals(1, updatedReports.single().mistakes.size)
        assertEquals(listOf("sess-2"), updatedCards.first { it.id == "c-shared" }.allSourceSessionIDs())
        assertTrue(updatedCards.any { it.id == "c-sess1" })
    }

    @Test
    fun stillLearningFilteringAndLocalPhraseSearchRankCorrectly() {
        val now = 4_000_000_000_000L
        val dueHighPriority = SentenceCard(
            id = "due-1",
            topic = "Backend Engineering",
            languageID = "en",
            frontText = "الفهرس يحسن سرعة قاعدة البيانات.",
            backText = "Adding a database index improves query performance.",
            explanation = "Technical phrasing about SQL optimization.",
            userOriginalMistake = "Add index make query fast.",
            repetitionCount = 0,
            easeFactor = 2.5f,
            intervalDays = 0,
            nextReviewDate = now - 5_000L,
        )
        val weakNotDue = SentenceCard(
            id = "weak-1",
            topic = "Backend Engineering",
            languageID = "en",
            frontText = "قمت بنشر التحديث.",
            backText = "I deployed the microservice yesterday.",
            explanation = "Use past tense 'deployed'.",
            userOriginalMistake = "I deploy the microservice yesterday.",
            repetitionCount = 2,
            easeFactor = 2.1f,
            intervalDays = 5,
            nextReviewDate = now + 5 * Sm2Calculator.DAY_MILLIS,
        )
        val masteredCard = SentenceCard(
            id = "mastered-1",
            topic = "Backend Engineering",
            languageID = "en",
            frontText = "أحب البرمجة.",
            backText = "I enjoy writing clean database queries.",
            explanation = "Already mastered expression.",
            userOriginalMistake = "I am enjoy writing code.",
            repetitionCount = 5,
            easeFactor = 2.6f,
            intervalDays = 30,
            nextReviewDate = now + 30 * Sm2Calculator.DAY_MILLIS,
        )
        val travelCard = SentenceCard(
            id = "travel-1",
            topic = "Travel Plans",
            languageID = "en",
            frontText = "حجزت تذكرة القطار.",
            backText = "I booked a train ticket in advance.",
            explanation = "Use 'in advance'.",
            userOriginalMistake = "I book train ticket before.",
            repetitionCount = 1,
            easeFactor = 2.5f,
            intervalDays = 1,
            nextReviewDate = now + Sm2Calculator.DAY_MILLIS,
        )
        val spanishCard = travelCard.copy(id = "es-1", languageID = "es", backText = "Reservé un billete de tren.")

        val all = listOf(masteredCard, travelCard, weakNotDue, dueHighPriority, spanishCard)

        val stillLearning = Sm2Calculator.stillLearningCards(all, "en", now)
        assertEquals(listOf("due-1", "travel-1", "weak-1"), stillLearning.map { it.id })
        assertFalse(stillLearning.any { it.id == "mastered-1" })

        val topics = Sm2Calculator.stillLearningTopics(all, "en", now)
        assertEquals(listOf("Backend Engineering", "Travel Plans"), topics)

        // Topic search excludes mastered unless directly queried
        val topicMatches = Sm2Calculator.searchLearningPhrases(all, "en", topic = "Backend Engineering", nowMillis = now)
        assertEquals(listOf("due-1", "weak-1"), topicMatches.map { it.id })

        // Direct keyword query surfaces matching cards including mastered if relevant, ranked below still-learning
        val queryMatches = Sm2Calculator.searchLearningPhrases(all, "en", query = "database", nowMillis = now)
        assertEquals(listOf("due-1", "mastered-1"), queryMatches.map { it.id })

        // Empty query/topic returns still-learning phrases bounded to 1..8
        val emptyQuery = Sm2Calculator.searchLearningPhrases(all, "en", limit = 2, nowMillis = now)
        assertEquals(listOf("due-1", "travel-1"), emptyQuery.map { it.id })

        // Tool payload formatting
        val toolResult = Sm2Calculator.formatLearningPhrasesToolResult(all, "en", topic = "Travel", limit = 5, nowMillis = now)
        assertEquals(listOf("travel-1"), toolResult.matchedCardIDs)
        val parsed = Json.parseToJsonElement(toolResult.jsonText).jsonObject
        assertEquals(1, parsed["count"]!!.jsonPrimitive.content.toInt())
        assertEquals(
            "I booked a train ticket in advance.",
            parsed["phrases"]!!.jsonArray.single().jsonObject["target_phrase"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun closedLoopAutoCreditAdvancesVerifiedPracticedPhrasesOncePerSession() {
        val now = 5_000_000_000_000L
        val c1 = SentenceCard(
            id = "c-1",
            topic = "Daily Routine",
            languageID = "en",
            frontText = "أشرب القهوة قبل المذاكرة.",
            backText = "I drink coffee before studying.",
            explanation = "Gerund after before.",
            userOriginalMistake = "I drink coffee before study.",
            repetitionCount = 0,
            intervalDays = 0,
            nextReviewDate = now,
        )
        val c2 = SentenceCard(
            id = "c-2",
            topic = "Tech",
            languageID = "en",
            frontText = "لدي خبرة في البرمجة.",
            backText = "I have experience in programming.",
            explanation = "Experience in.",
            userOriginalMistake = "I have experience on programming.",
            repetitionCount = 1,
            intervalDays = 1,
            nextReviewDate = now + Sm2Calculator.DAY_MILLIS,
        )
        val c3 = SentenceCard(
            id = "c-3",
            topic = "Tech",
            languageID = "en",
            frontText = "أصلحت الخطأ.",
            backText = "I fixed the bug yesterday.",
            explanation = "Past tense.",
            userOriginalMistake = "I fix bug yesterday.",
            repetitionCount = 0,
            intervalDays = 0,
            nextReviewDate = now,
        )

        val candidates = Sm2Calculator.selectPostSessionCandidates(
            cards = listOf(c1, c2, c3),
            languageID = "en",
            prioritizedCardIDs = listOf("c-2"),
            nowMillis = now,
        )
        assertEquals("c-2", candidates.first().id)

        val transcriptTexts = listOf(
            "Every morning I drink coffee before studying and then I work.",
            "Also I fix bug yesterday in my project.",
        )
        val reported = listOf(
            PracticedPhrase(cardID = "c-1", userQuote = "I drink coffee before studying"),
            PracticedPhrase(cardID = "c-2", userQuote = "I have experience in programming"), // Hallucinated quote not in transcript!
            PracticedPhrase(cardID = "c-3", userQuote = "I fix bug yesterday"), // Excluded because flagged in mistakes
            PracticedPhrase(cardID = "invented-id", userQuote = "Every morning I drink coffee"), // Invalid card ID
        )

        val result = Sm2Calculator.applyPracticedPhrases(
            existingCards = listOf(c1, c2, c3),
            reportedPhrases = reported,
            languageID = "en",
            sessionID = "sess-99",
            userTranscriptTexts = transcriptTexts,
            excludedCorrections = setOf("I fixed the bug yesterday."),
            nowMillis = now,
        )
        assertEquals(listOf("c-1"), result.creditedCardIDs)
        assertEquals(1, result.verifiedPhrases.size)
        assertEquals("I drink coffee before studying.", result.verifiedPhrases.single().phrase)
        val advanced = result.updatedCards.first { it.id == "c-1" }
        assertEquals(1, advanced.repetitionCount)
        assertEquals(1, advanced.intervalDays)
        assertEquals("sess-99", advanced.lastAutoCreditedSessionID)

        // Re-running for the same session must not double-credit c-1
        val rerun = Sm2Calculator.applyPracticedPhrases(
            existingCards = result.updatedCards,
            reportedPhrases = reported,
            languageID = "en",
            sessionID = "sess-99",
            userTranscriptTexts = transcriptTexts,
            excludedCorrections = setOf("I fixed the bug yesterday."),
            alreadyCreditedCardIDs = result.creditedCardIDs.toSet(),
            nowMillis = now + 10_000L,
        )
        assertEquals(1, rerun.updatedCards.first { it.id == "c-1" }.repetitionCount)
        assertEquals(1, rerun.updatedCards.first { it.id == "c-1" }.intervalDays)
    }

    @Test fun sessionReportDefaultArgumentsDoNotThrow() {
        val report = SessionReport(topic = "Defaults", durationMinutes = 3)
        assertTrue(report.mistakes.isEmpty())
        assertTrue(report.wins.isEmpty())
        assertTrue(report.naturalUpgrades.isEmpty())
        assertEquals(0, report.mistakeCount)
    }

    @Test
    fun punctuationAndFormattingOnlyDiffGuardFiltersNoiseWhilePreservingRealFixes() {
        assertTrue(isPunctuationOrFormattingOnlyDiff("I said moral, not more.", "I said \"moral,\" not \"more.\""))
        assertTrue(isPunctuationOrFormattingOnlyDiff("i said moral not more", "I said “moral,” not “more”!"))
        assertTrue(isPunctuationOrFormattingOnlyDiff("This is well known.", "This is well-known."))
        assertTrue(isPunctuationOrFormattingOnlyDiff("Send me an email", "Send me an e-mail."))
        assertTrue(isPunctuationOrFormattingOnlyDiff("I dont know", "I don't know."))
        assertTrue(isPunctuationOrFormattingOnlyDiff("Hello   world", "Hello world"))

        // Genuine grammar/vocabulary/preposition/article/word-order fixes must NOT be filtered
        assertFalse(isPunctuationOrFormattingOnlyDiff("Yesterday I go to the gym.", "Yesterday I went to the gym."))
        assertFalse(isPunctuationOrFormattingOnlyDiff("I am agree with you", "I agree with you."))
        assertFalse(isPunctuationOrFormattingOnlyDiff("I have three years experience on backend.", "I have three years of experience in backend."))
        assertFalse(isPunctuationOrFormattingOnlyDiff("I want go home", "I want to go home."))
        assertFalse(isPunctuationOrFormattingOnlyDiff("She like reading", "She likes reading."))

        // Sm2Calculator.mergeFromReport also drops punctuation-only items
        val now = 6_000_000_000_000L
        val report = SessionReport(
            sessionID = "sess-filter",
            languageID = "en",
            date = now,
            topic = "Programming Interview Concepts (Loops, Classes, Objects, Constructors)",
            durationMinutes = 6,
            mistakes = listOf(
                SessionMistake(
                    userSaid = "I said moral, not more.",
                    correction = "I said \"moral,\" not \"more.\"",
                    arabicMeaning = "قلت مورال، وليس مور.",
                    explanation = "Add quotation marks.",
                ),
                SessionMistake(
                    userSaid = "Yesterday I go to the office.",
                    correction = "Yesterday I went to the office.",
                    arabicMeaning = "ذهبت إلى المكتب بالأمس.",
                    explanation = "Use past simple 'went'.",
                ),
            ),
        )
        val merged = Sm2Calculator.mergeFromReport(emptyList(), report, nowMillis = now)
        assertEquals(1, merged.size)
        assertEquals("Yesterday I went to the office.", merged.single().backText)
        assertEquals("Programming Interview Concepts", merged.single().topic)
    }

    @Test
    fun cleanPostSessionTopicKeepsShortTitlesAndTrimsParentheticalLists() {
        assertEquals("Daily Routine", Sm2Calculator.cleanPostSessionTopic("Daily Routine"))
        assertEquals("Tech Interviews", Sm2Calculator.cleanPostSessionTopic("  Tech   Interviews  "))
        assertEquals(
            "Programming Interview Concepts",
            Sm2Calculator.cleanPostSessionTopic("Programming Interview Concepts (Loops, Classes, Objects, Constructors)"),
        )
        assertEquals(Sm2Calculator.GENERAL_TOPIC, Sm2Calculator.cleanPostSessionTopic("   "))
    }

    @Test
    fun postSessionPayloadAndSessionReportSupportWinsAndNaturalUpgradesWithBackwardCompatibility() {
        val richJson = """
            {
              "topic": "System Design",
              "wins": [
                "You explained database sharding clearly without translating.",
                "You used past tense consistently when describing your internship."
              ],
              "mistakes": [],
              "natural_upgrades": [
                {
                  "user_said": "It makes the database more fast when users grow.",
                  "natural_alternative": "It keeps queries fast as traffic scales.",
                  "arabic_meaning": "يحافظ على سرعة الاستعلامات مع زيادة الزيارات.",
                  "explanation": "'Keeps queries fast as traffic scales' sounds more idiomatic in tech discussions."
                }
              ],
              "practiced_phrases": []
            }
        """.trimIndent()
        val parsed = json.decodeFromString<PostSessionAnalysisPayload>(richJson)
        assertEquals(2, parsed.wins.size)
        assertEquals(1, parsed.naturalUpgrades.size)
        assertEquals("It keeps queries fast as traffic scales.", parsed.naturalUpgrades.single().naturalAlternative)

        // Legacy payload without wins / natural_upgrades still decodes cleanly
        val legacyReportJson = """
            {
              "id": "rep-legacy",
              "sessionID": "sess-legacy",
              "languageID": "en",
              "date": 1700000000000,
              "topic": "Daily Routine",
              "durationMinutes": 4,
              "mistakes": [],
              "mistakeCount": 0
            }
        """.trimIndent()
        val decodedReport = json.decodeFromString<SessionReport>(legacyReportJson)
        assertTrue(decodedReport.wins.isEmpty())
        assertTrue(decodedReport.naturalUpgrades.isEmpty())

        // Adding a natural upgrade creates a SentenceCard in the session's topic deck
        val report = SessionReport(
            id = "rep-up",
            sessionID = "sess-up",
            languageID = "en",
            topic = "System Design",
            durationMinutes = 8,
            naturalUpgrades = parsed.naturalUpgrades,
        )
        assertFalse(Sm2Calculator.hasCardForBackText(emptyList(), "en", "It keeps queries fast as traffic scales."))
        val updatedCards = Sm2Calculator.addNaturalUpgradeToCards(
            existingCards = emptyList(),
            report = report,
            upgrade = parsed.naturalUpgrades.single(),
            nowMillis = 7_000_000_000_000L,
        )
        assertEquals(1, updatedCards.size)
        assertTrue(Sm2Calculator.hasCardForBackText(updatedCards, "en", "it keeps queries fast as traffic scales."))
        assertEquals("System Design", updatedCards.single().topic)
    }

    @Test
    fun deckPracticeThemeAndPrioritizationFocusActiveDeckCards() {
        val now = 8_000_000_000_000L
        val masteredDeckCard = SentenceCard(
            id = "d-mastered",
            topic = "Tech Interviews",
            languageID = "en",
            frontText = "أحب البرمجة.",
            backText = "I enjoy building scalable APIs.",
            explanation = "Mastered.",
            userOriginalMistake = "",
            repetitionCount = 5,
            intervalDays = 30,
            nextReviewDate = now + 30 * Sm2Calculator.DAY_MILLIS,
        )
        val learningDeckCard = SentenceCard(
            id = "d-learning",
            topic = "Tech Interviews",
            languageID = "en",
            frontText = "أضفت فهرساً للجدول.",
            backText = "I added an index to speed up the query.",
            explanation = "Still learning.",
            userOriginalMistake = "I add index make query fast.",
            repetitionCount = 0,
            intervalDays = 0,
            nextReviewDate = now - 1_000L,
        )
        val otherDeckCard = SentenceCard(
            id = "other-1",
            topic = "Daily Routine",
            languageID = "en",
            frontText = "أشرب القهوة.",
            backText = "I drink coffee before studying.",
            explanation = "Other deck.",
            userOriginalMistake = "",
            nextReviewDate = now - 5_000L,
        )

        val prioritized = Sm2Calculator.prioritizeDeckCards(
            cards = listOf(masteredDeckCard, otherDeckCard, learningDeckCard),
            languageID = "en",
            deckTopic = "Tech Interviews",
            nowMillis = now,
            limit = 5,
        )
        assertEquals(listOf("d-learning", "d-mastered"), prioritized.map { it.id })

        val theme = Sm2Calculator.buildDeckPracticeTheme("Tech Interviews")
        assertEquals("deck:tech interviews", theme.id)
        assertEquals("Tech Interviews", theme.title)
        assertTrue(theme.situation.contains("Tech Interviews"))
    }
}
