package chat.mural.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TeachingPolicyUpgradeTest {
    @Test
    fun voicePromptOmitsLegacySingleWordsAndInjectsMemoryLevelTopicsAndNudging() {
        val english = LanguageRegistry.get("en")!!
        val dueLegacyWord = WordState(
            id = "en|book|book",
            lemma = "legacywordonly",
            meaning = "a book",
            form = "book",
            example = "I read a book.",
            bars = 1,
            understandingCount = 0,
            independentCount = 1,
            lastSeen = 0.0,
            dueAt = 0.0,
        )
        val learner = LearnerState(
            challenge = 2,
            observationCount = 3,
            nextGoal = "Use past tense consistently.",
            capabilities = listOf("Talks about daily life"),
            words = listOf(dueLegacyWord),
        )
        val memory = LearnerMemory(
            profileFacts = listOf("Computer science student in Cairo"),
            interestsAndGoals = listOf("Backend engineering", "Chess"),
            estimatedLevel = "B1",
            manualLevelOverride = "B2",
            speakingGuidance = "Keep turns concise and challenge on connector phrasing.",
            recentTopics = listOf(
                RecentTopicMemory(
                    sessionID = "s-1",
                    topic = "SQL Indexes",
                    oneLineSummary = "Discussed B-tree indexes.",
                    followUpHint = "Ask if benchmarks improved.",
                ),
            ),
        )
        val card = SentenceCard(
            id = "card-1",
            topic = "Backend Engineering",
            languageID = "en",
            frontText = "أضفت فهرساً للجدول.",
            backText = "I added an index to the table.",
            explanation = "Use 'to the table'.",
            userOriginalMistake = "I added index on table.",
        )

        val prompt = TeachingPolicy.voice(
            language = english,
            learner = learner,
            theme = null,
            interests = "Ignored raw interests when memory is rich",
            meaningLanguage = "Arabic",
            dueSentenceCards = listOf(card),
            learningTopics = listOf("Backend Engineering", "Daily Routine"),
            memory = memory,
        )

        assertFalse(prompt.contains("Words to revisit when relevant"))
        assertFalse(prompt.contains("legacywordonly"))

        assertTrue(prompt.contains("Effective proficiency level: B2 (set by learner)"))
        assertTrue(prompt.contains("Computer science student in Cairo"))
        assertTrue(prompt.contains("Backend engineering"))
        assertTrue(prompt.contains("Keep turns concise and challenge on connector phrasing."))
        assertTrue(prompt.contains("Still-learning phrase topics available via get_learning_phrases: Backend Engineering, Daily Routine"))
        assertTrue(prompt.contains("I added an index to the table."))
        assertTrue(prompt.contains("past mistake: \"I added index on table.\""))
        assertTrue(prompt.contains("Natural phrase-nudging policy:"))
        assertTrue(prompt.contains("silently call get_learning_phrases"))
        assertTrue(prompt.contains("1-3 phrases per conversation"))
    }

    @Test
    fun greetingPersonalizesFreeConversationsWhileHonoringThemesAndContinuations() {
        val english = LanguageRegistry.get("en")!!
        val memory = LearnerMemory(
            profileFacts = listOf("Software student"),
            interestsAndGoals = listOf("Chess", "AI engineering"),
            estimatedLevel = "B1",
            recentTopics = listOf(
                RecentTopicMemory(
                    sessionID = "s-1",
                    topic = "Database Indexing",
                    oneLineSummary = "Talked about query latency.",
                    followUpHint = "whether the benchmark got faster",
                ),
                RecentTopicMemory(
                    sessionID = "s-2",
                    topic = "Daily Coffee Routine",
                    oneLineSummary = "Morning schedule.",
                ),
            ),
        )

        val freeOpening = TeachingPolicy.greeting(english, theme = null, continuing = false, memory = memory)
        assertTrue(freeOpening.length <= 1000)
        assertTrue(freeOpening.contains(english.greeting))
        assertTrue(freeOpening.contains("fresh, engaging topic"))
        assertTrue(freeOpening.contains("avoiding repeating recent topics (Database Indexing, Daily Coffee Routine)"))
        assertTrue(freeOpening.contains("level B1"))
        assertTrue(freeOpening.contains("All speech must be in English."))

        val coffeeTheme = english.themes.first { it.id == "coffee" }
        val themedOpening = TeachingPolicy.greeting(english, theme = coffeeTheme, continuing = false, memory = memory)
        assertTrue(themedOpening.contains(coffeeTheme.situation))
        assertFalse(themedOpening.contains("Database Indexing"))

        val resumeOpening = TeachingPolicy.greeting(english, theme = null, continuing = true, memory = memory)
        assertTrue(resumeOpening.contains("Resume the conversation from the supplied history"))
        assertFalse(resumeOpening.contains("Database Indexing"))
    }

    @Test
    fun typedReplyIncludesCompactMemoryWhenPresent() {
        val english = LanguageRegistry.get("en")!!
        val memory = LearnerMemory(
            profileFacts = listOf("Studies math and CS"),
            estimatedLevel = "B1",
        )
        val replyPrompt = TeachingPolicy.typedReply(english, memory)
        assertTrue(replyPrompt.contains("Studies math and CS"))
        assertTrue(replyPrompt.contains("B1"))
    }

    @Test
    fun postSessionAndMemorySchemasStayStrictlyCompatibleWithGeminiValidator() {
        val postSchema = TeachingPolicy.postSessionSchema()
        assertStrictSchema(postSchema)
        val postProps = postSchema["properties"]!!.jsonObject
        assertEquals(setOf("topic", "mistakes", "practiced_phrases"), postProps.keys)

        val memorySchema = TeachingPolicy.learnerMemoryUpdateSchema()
        assertStrictSchema(memorySchema)
        val memProps = memorySchema["properties"]!!.jsonObject
        assertEquals(
            setOf(
                "profile_facts",
                "interests_and_goals",
                "estimated_level",
                "speaking_guidance",
                "session_topic",
                "session_summary",
                "follow_up_hint",
            ),
            memProps.keys,
        )
        val levelEnum = memProps["estimated_level"]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("", "A1", "A2", "B1", "B2", "C1", "C2"), levelEnum)
    }

    private fun assertStrictSchema(schema: JsonObject) {
        val type = schema["type"]?.jsonPrimitive?.content
        assertTrue(type in setOf("object", "array", "string", "integer", "number", "boolean"))
        when (type) {
            "object" -> {
                assertEquals(false, (schema["additionalProperties"] as? JsonPrimitive)?.booleanOrNull)
                val props = schema["properties"]!!.jsonObject
                val required = (schema["required"] as JsonArray).map { it.jsonPrimitive.content }.toSet()
                assertEquals(props.keys, required)
                props.values.forEach { assertStrictSchema(it.jsonObject) }
            }
            "array" -> {
                assertStrictSchema(schema["items"]!!.jsonObject)
            }
        }
    }
}
