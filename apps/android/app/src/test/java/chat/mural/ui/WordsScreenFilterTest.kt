package chat.mural.ui

import chat.mural.core.NaturalUpgrade
import chat.mural.core.PracticedPhrase
import chat.mural.core.SentenceCard
import chat.mural.core.SessionMistake
import chat.mural.core.SessionReport
import chat.mural.core.TopicDeck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WordsScreenFilterTest {
    private val reportInterviews = SessionReport(
        id = "rep-interviews",
        sessionID = "sess-1",
        languageID = "en",
        date = 1_700_000_000_000L,
        topic = "Tech Interviews",
        durationMinutes = 14,
        wins = listOf("Clear explanation of microservices architecture"),
        mistakes = listOf(
            SessionMistake(
                userSaid = "I have three years experience on backend.",
                correction = "I have three years of experience in backend development.",
                arabicMeaning = "لدي ثلاث سنوات من الخبرة في تطوير الواجهات الخلفية.",
                explanation = "Use 'experience in' rather than 'experience on'.",
            ),
        ),
        naturalUpgrades = listOf(
            NaturalUpgrade(
                userSaid = "I want to talk about my project.",
                naturalAlternative = "I'd love to walk you through a recent project.",
                arabicMeaning = "يسعدني أن أشرح لك مشروعاً حديثاً.",
                explanation = "Commonly used by senior engineers in interviews.",
            ),
        ),
        practicedPhrases = listOf(
            PracticedPhrase(
                cardID = "card-1",
                phrase = "scalable distributed systems",
                userQuote = "We built scalable distributed systems last year.",
            ),
        ),
    )

    private val reportCoffee = SessionReport(
        id = "rep-coffee",
        sessionID = "sess-2",
        languageID = "en",
        date = 1_700_050_000_000L,
        topic = "Morning Coffee",
        durationMinutes = 7,
        wins = listOf("Smooth polite ordering"),
        mistakes = listOf(
            SessionMistake(
                userSaid = "Give me one latte.",
                correction = "Could I get an oat milk latte, please?",
                arabicMeaning = "هل يمكنني الحصول على لاتيه بحليب الشوفان من فضلك؟",
                explanation = "Use 'Could I get' for polite cafe requests.",
            ),
        ),
    )

    private val allReports = listOf(reportInterviews, reportCoffee)

    @Test
    fun filterSessionReportsReturnsAllForBlankOrWhitespaceQueries() {
        assertEquals(allReports, filterSessionReports(allReports, ""))
        assertEquals(allReports, filterSessionReports(allReports, "   \t  "))
    }

    @Test
    fun filterSessionReportsMatchesTopicMistakesWinsUpgradesAndPracticedPhrases() {
        // Topic match (case-insensitive)
        assertEquals(listOf(reportCoffee), filterSessionReports(allReports, "morning"))
        // Mistake userSaid
        assertEquals(listOf(reportInterviews), filterSessionReports(allReports, "years experience on"))
        // Mistake correction
        assertEquals(listOf(reportCoffee), filterSessionReports(allReports, "oat milk latte"))
        // Mistake arabicMeaning
        assertEquals(listOf(reportInterviews), filterSessionReports(allReports, "الواجهات الخلفية"))
        // Mistake explanation
        assertEquals(listOf(reportCoffee), filterSessionReports(allReports, "polite cafe requests"))
        // Wins
        assertEquals(listOf(reportInterviews), filterSessionReports(allReports, "MICROSERVICES"))
        // Natural upgrades (naturalAlternative & explanation)
        assertEquals(listOf(reportInterviews), filterSessionReports(allReports, "walk you through"))
        assertEquals(listOf(reportInterviews), filterSessionReports(allReports, "senior engineers"))
        // Practiced phrases (phrase & userQuote)
        assertEquals(listOf(reportInterviews), filterSessionReports(allReports, "distributed systems"))
        assertEquals(listOf(reportInterviews), filterSessionReports(allReports, "built scalable"))
    }

    @Test
    fun filterSessionReportsReturnsEmptyListWhenNothingMatches() {
        assertTrue(filterSessionReports(allReports, "quantum gravity").isEmpty())
    }

    @Test
    fun filterTopicDecksMatchesTopicAndCardFieldsAndRespectsBlankQueries() {
        val cafeCard = SentenceCard(
            id = "c-1",
            topic = "Cafe",
            languageID = "en",
            frontText = "أريد كوب قهوة.",
            backText = "I'd like a cup of coffee.",
            explanation = "Polite ordering phrase.",
            userOriginalMistake = "I want coffee.",
        )
        val travelCard = SentenceCard(
            id = "c-2",
            topic = "Travel",
            languageID = "en",
            frontText = "أين محطة القطار؟",
            backText = "Where is the train station?",
            explanation = "Asking for directions.",
            userOriginalMistake = "Where train station?",
        )
        val decks = listOf(
            TopicDeck(topic = "Cafe", cards = listOf(cafeCard), dueCards = listOf(cafeCard)),
            TopicDeck(topic = "Travel", cards = listOf(travelCard), dueCards = listOf(travelCard)),
        )

        assertEquals(decks, filterTopicDecks(decks, ""))
        assertEquals(decks, filterTopicDecks(decks, "   "))
        assertEquals(listOf(decks[0]), filterTopicDecks(decks, "cafe"))
        assertEquals(listOf(decks[1]), filterTopicDecks(decks, "محطة القطار"))
        assertEquals(listOf(decks[0]), filterTopicDecks(decks, "cup of coffee"))
        assertEquals(listOf(decks[1]), filterTopicDecks(decks, "directions"))
        assertTrue(filterTopicDecks(decks, "nonexistent query").isEmpty())
    }
}
