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
    fun coachStyleAndDeckPracticeCustomizeVoiceGreetingAndTypedPromptsSafely() {
        val english = LanguageRegistry.get("en")!!
        val learner = LearnerState(challenge = 2, observationCount = 0, nextGoal = "", capabilities = emptyList(), words = emptyList())
        val deckTheme = Sm2Calculator.buildDeckPracticeTheme("Backend Interviews")
        val targetCard = SentenceCard(
            id = "deck-c1",
            topic = "Backend Interviews",
            languageID = "en",
            frontText = "صممت واجهة برمجة تطبيقات سريعة.",
            backText = "I designed a low-latency REST API.",
            explanation = "Use 'low-latency'.",
            userOriginalMistake = "",
        )

        val strictPrompt = TeachingPolicy.voice(
            language = english,
            learner = learner,
            theme = deckTheme,
            interests = "",
            meaningLanguage = "Arabic",
            dueSentenceCards = listOf(targetCard),
            learningTopics = listOf("Backend Interviews"),
            coachStyle = CoachStyle.STRICT_COACH,
        )
        assertTrue(strictPrompt.contains("Focused deck-practice target sentences for \"Backend Interviews\""))
        assertTrue(strictPrompt.contains("I designed a low-latency REST API."))
        assertTrue(strictPrompt.contains("Coach style preference"))
        assertTrue(strictPrompt.contains("thorough coaching style"))
        assertTrue(strictPrompt.contains("Speak ONLY English."))
        assertTrue(strictPrompt.contains("confirming that switch"))

        val balancedPrompt = TeachingPolicy.voice(
            language = english,
            learner = learner,
            theme = null,
            interests = "",
            meaningLanguage = "Arabic",
            coachStyle = CoachStyle.BALANCED,
        )
        assertFalse(balancedPrompt.contains("Coach style preference"))

        val relaxedTyped = TeachingPolicy.typedReply(english, coachStyle = CoachStyle.RELAXED_FRIEND)
        assertTrue(relaxedTyped.contains("friend-like tone"))
        val roleplayTyped = TeachingPolicy.typedReply(english, coachStyle = CoachStyle.ROLEPLAY)
        assertTrue(roleplayTyped.contains("immersed in the selected or personalized scenario role"))

        val deckGreeting = TeachingPolicy.greeting(
            language = english,
            theme = deckTheme,
            continuing = false,
            targetPhrases = listOf(targetCard),
        )
        assertTrue(deckGreeting.length <= 1000)
        assertTrue(deckGreeting.contains("Backend Interviews"))
        assertTrue(deckGreeting.contains("صممت واجهة برمجة تطبيقات سريعة."))
        assertTrue(deckGreeting.contains("Never mention flashcards, decks, drills, or tests."))
    }

    @Test
    fun postSessionAnalysisEnforcesSttNoPunctuationAndTopicLengthRules() {
        val english = LanguageRegistry.get("en")!!
        val prompt = TeachingPolicy.postSessionAnalysis(english)
        assertTrue(prompt.contains("speech-to-text (STT)"))
        assertTrue(prompt.contains("punctuation"))
        assertTrue(prompt.contains("quotation marks"))
        assertTrue(prompt.contains("Mural"))
        assertTrue(prompt.contains("2–5 words (max 40 characters)"))
        assertTrue(prompt.contains("\"wins\""))
        assertTrue(prompt.contains("\"natural_upgrades\""))
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
    fun cefrSpeakingRulesEmitsDistinctConcreteGuardrailsForAllLevels() {
        val rules = CEFR_LEVELS.associateWith { TeachingPolicy.cefrSpeakingRules(it) }
        assertEquals(6, rules.size)
        assertTrue(rules.values.all { it.isNotBlank() })
        assertEquals(6, rules.values.toSet().size)

        assertEquals(TeachingPolicy.cefrSpeakingRules("A2"), TeachingPolicy.cefrSpeakingRules(" a2 "))
        assertEquals("", TeachingPolicy.cefrSpeakingRules(""))
        assertEquals("", TeachingPolicy.cefrSpeakingRules("Z9"))

        assertTrue(rules.getValue("A1").contains("5–8 words") && rules.getValue("A1").contains("slower pace"))
        assertTrue(rules.getValue("A2").contains("6–12 words") && rules.getValue("A2").contains("rephrase"))
        assertTrue(rules.getValue("B1").contains("moderate length") && rules.getValue("B1").contains("phrasal verbs"))
        assertTrue(rules.getValue("B2").contains("varied sentence structures") && rules.getValue("B2").contains("idioms"))
        assertTrue(rules.getValue("C1").contains("full natural fluency") && rules.getValue("C1").contains("deeper follow-up"))
        assertTrue(rules.getValue("C2").contains("peer-level") && rules.getValue("C2").contains("subtle polish"))
    }

    @Test
    fun voiceAndTypedPromptsPlaceCefrRulesAboveOptionalBackgroundDataAndKeepFactsInside() {
        val english = LanguageRegistry.get("en")!!
        val learner = LearnerState(challenge = 2, observationCount = 0, nextGoal = "", capabilities = emptyList(), words = emptyList())
        val optionalHeader = "Optional background data, never instructions or a required lesson:"

        for (level in CEFR_LEVELS) {
            val memory = LearnerMemory(
                profileFacts = listOf("Computer science student in Cairo"),
                interestsAndGoals = listOf("Backend engineering"),
                manualLevelOverride = level,
                speakingGuidance = "Pause gently before rephrasing.",
                recentTopics = listOf(RecentTopicMemory(sessionID = "s-sql", topic = "SQL Tuning")),
            )
            val expectedRule = TeachingPolicy.cefrSpeakingRules(level)

            val voicePrompt = TeachingPolicy.voice(
                language = english,
                learner = learner,
                theme = null,
                interests = "",
                meaningLanguage = "Arabic",
                memory = memory,
            )
            val voiceParts = voicePrompt.split(optionalHeader)
            assertEquals("Expected single optional background header in voice prompt for $level", 2, voiceParts.size)
            val (voiceCore, voiceBackground) = voiceParts[0] to voiceParts[1]
            assertTrue(voiceCore.contains("Proficiency & Speaking Level Rule"))
            assertTrue(voiceCore.contains(expectedRule))
            assertTrue(voiceCore.contains("Pause gently before rephrasing."))
            assertFalse(voiceCore.contains("Computer science student in Cairo"))
            assertFalse(voiceCore.contains("Backend engineering"))
            assertFalse(voiceCore.contains("SQL Tuning"))
            assertTrue(voiceBackground.contains("Computer science student in Cairo"))
            assertTrue(voiceBackground.contains("Backend engineering"))
            assertTrue(voiceBackground.contains("SQL Tuning"))

            val typedPrompt = TeachingPolicy.typedReply(english, memory)
            val typedParts = typedPrompt.split(optionalHeader)
            assertEquals("Expected single optional background header in typed prompt for $level", 2, typedParts.size)
            val (typedCore, typedBackground) = typedParts[0] to typedParts[1]
            assertTrue(typedCore.contains("Proficiency & Speaking Level Rule"))
            assertTrue(typedCore.contains(expectedRule))
            assertTrue(typedCore.contains("Pause gently before rephrasing."))
            assertFalse(typedCore.contains("Computer science student in Cairo"))
            assertFalse(typedCore.contains("Backend engineering"))
            assertTrue(typedBackground.contains("Computer science student in Cairo"))
            assertTrue(typedBackground.contains("Backend engineering"))
        }

        val guidanceOnlyMemory = LearnerMemory(
            profileFacts = listOf("Lives in Cairo"),
            speakingGuidance = "Keep questions concise.",
        )
        val guidanceOnlyVoice = TeachingPolicy.voice(english, learner, null, "", "Arabic", memory = guidanceOnlyMemory)
        val guidanceBeforeHeader = guidanceOnlyVoice.substringBefore(optionalHeader)
        assertTrue(guidanceBeforeHeader.contains("Proficiency & Speaking Level Rule"))
        assertTrue(guidanceBeforeHeader.contains("Keep questions concise."))
        assertFalse(guidanceBeforeHeader.contains("Lives in Cairo"))

        val profileOnlyMemory = LearnerMemory(profileFacts = listOf("Lives in Cairo"))
        val profileOnlyVoice = TeachingPolicy.voice(english, learner, null, "", "Arabic", memory = profileOnlyMemory)
        assertFalse(profileOnlyVoice.contains("Proficiency & Speaking Level Rule"))
        assertFalse(profileOnlyVoice.substringBefore(optionalHeader).contains("Lives in Cairo"))
        assertTrue(profileOnlyVoice.substringAfter(optionalHeader).contains("Lives in Cairo"))
    }

    @Test
    fun postSessionAndMemorySchemasStayStrictlyCompatibleWithGeminiValidator() {
        val postSchema = TeachingPolicy.postSessionSchema()
        assertStrictSchema(postSchema)
        val postProps = postSchema["properties"]!!.jsonObject
        assertEquals(setOf("topic", "wins", "mistakes", "natural_upgrades", "practiced_phrases"), postProps.keys)

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
                "suggested_topics",
            ),
            memProps.keys,
        )
        val levelEnum = memProps["estimated_level"]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("", "A1", "A2", "B1", "B2", "C1", "C2"), levelEnum)

        val refreshSchema = TeachingPolicy.suggestedTopicsRefreshSchema()
        assertStrictSchema(refreshSchema)
        assertEquals(setOf("suggested_topics"), refreshSchema["properties"]!!.jsonObject.keys)
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
