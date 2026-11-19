package chat.mural.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LearnerMemoryTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun mergeDeduplicatesRespectsBoundsFiltersSecretsAndPreservesManualOverride() {
        val current = LearnerMemory(
            profileFacts = listOf("Computer science student", "Lives in Cairo"),
            interestsAndGoals = listOf("Backend engineering", "Chess"),
            estimatedLevel = "B1",
            manualLevelOverride = "B2",
            speakingGuidance = "Keep turns concise.",
            recentTopics = (1..10).map { idx ->
                RecentTopicMemory(
                    sessionID = "sess-$idx",
                    topic = "Topic $idx",
                    oneLineSummary = "Summary $idx",
                    dateMillis = idx * 1_000L,
                )
            },
            autoUpdateEnabled = true,
            updatedAtMillis = 10_000L,
        )

        val incomingFacts = (1..20).map { "Fact $it" } + listOf(
            "fact 1", // case-insensitive dup
            "My password is hunter2",
            "Card number 4111 2222 3333 4444",
            "Lives at 123 Main Street",
        )
        val payload = LearnerMemoryUpdatePayload(
            profileFacts = incomingFacts,
            interestsAndGoals = listOf("Backend engineering", "backend engineering", "Data analysis"),
            estimatedLevel = "A2", // Must NOT override manualLevelOverride or overwrite estimatedLevel while override is active
            speakingGuidance = "G".repeat(500),
            sessionTopic = "Distributed Databases",
            sessionSummary = "Discussed indexing and query plans.",
            followUpHint = "Ask about query profiling results.",
        )

        val merged = LearnerMemoryReducer.merge(
            current = current,
            payload = payload,
            sessionID = "sess-11",
            fallbackTopic = "Fallback",
            nowMillis = 20_000L,
        )

        assertEquals(LearnerMemory.MAX_PROFILE_FACTS, merged.profileFacts.size)
        assertFalse(merged.profileFacts.any { it.contains("password", ignoreCase = true) })
        assertFalse(merged.profileFacts.any { it.contains("4111") })
        assertFalse(merged.profileFacts.any { it.contains("Main Street") })

        assertEquals(listOf("Backend engineering", "Data analysis"), merged.interestsAndGoals)
        assertEquals("B2", merged.manualLevelOverride)
        assertEquals("B1", merged.estimatedLevel)
        assertEquals("B2", merged.effectiveLevel)
        assertEquals(LearnerMemory.MAX_SPEAKING_GUIDANCE_CHARS, merged.speakingGuidance.length)

        // Rolling recent topics capped at 10, newest first
        assertEquals(LearnerMemory.MAX_RECENT_TOPICS, merged.recentTopics.size)
        assertEquals("sess-11", merged.recentTopics.first().sessionID)
        assertEquals("Distributed Databases", merged.recentTopics.first().topic)
        assertFalse(merged.recentTopics.any { it.sessionID == "sess-1" })
    }

    @Test
    fun mergeUpdatesEstimatedLevelWhenNoManualOverrideAndPreservesExistingOnEmptyPayload() {
        val initial = LearnerMemory(
            profileFacts = listOf("Lives in Nasr City"),
            interestsAndGoals = listOf("Chess"),
            estimatedLevel = "B1",
            manualLevelOverride = null,
        )
        val merged = LearnerMemoryReducer.merge(
            current = initial,
            payload = LearnerMemoryUpdatePayload(
                profileFacts = listOf("My password is secret"), // Filtered out -> falls back to existing facts
                interestsAndGoals = emptyList(), // Empty -> falls back to existing interests
                estimatedLevel = "b2",
                speakingGuidance = "Practice conditional clauses.",
                sessionTopic = "Future Plans",
                sessionSummary = "Talked about graduation goals.",
            ),
            sessionID = "sess-new",
            fallbackTopic = "Default",
            nowMillis = 50_000L,
        )

        assertEquals(listOf("Lives in Nasr City"), merged.profileFacts)
        assertEquals(listOf("Chess"), merged.interestsAndGoals)
        assertEquals("B2", merged.estimatedLevel)
        assertEquals("B2", merged.effectiveLevel)
        assertNull(merged.manualLevelOverride)
    }

    @Test
    fun formatForPromptEnforcesMaxCharsAndLegacyInterestsSeedCleanly() {
        val seeded = LearnerMemoryReducer.seedFromLegacyInterests(
            LearnerMemory(),
            "Backend engineering, Chess; Data science\nChess",
        )
        assertEquals(listOf("Backend engineering", "Chess", "Data science"), seeded.interestsAndGoals)
        assertEquals("Backend engineering, Chess, Data science", LearnerMemoryReducer.syncLegacyInterestsString(seeded))

        val rich = seeded.copy(
            profileFacts = (1..15).map { "Profile detail number $it about daily studies and projects" },
            estimatedLevel = "B1",
            speakingGuidance = "Keep turns concise and clarify phrasal verbs gently.",
            recentTopics = (1..10).map {
                RecentTopicMemory(
                    sessionID = "s-$it",
                    topic = "Conversation Topic Number $it",
                    oneLineSummary = "Discussed topic $it in detail with helpful examples.",
                    followUpHint = "Follow up on part $it",
                    dateMillis = it * 1000L,
                )
            },
        )
        val formattedFull = rich.formatForPrompt(1800)
        assertTrue(formattedFull.length <= 1800)
        assertTrue(formattedFull.contains("Effective proficiency level: B1"))
        assertTrue(formattedFull.contains("Conversation Topic Number 10"))

        val formattedTiny = rich.formatForPrompt(160)
        assertTrue(formattedTiny.length <= 160)
        assertTrue(formattedTiny.contains("Effective proficiency level: B1"))

        val encoded = json.encodeToString(rich)
        val decoded = json.decodeFromString<LearnerMemory>(encoded)
        assertEquals(rich.sanitized(), decoded.sanitized())
    }

    @Test
    fun mergeImportedPreservesLocalOverridesAndDeduplicatesFacts() {
        val local = mapOf(
            "en" to LearnerMemory(
                profileFacts = listOf("Student in Cairo"),
                interestsAndGoals = listOf("Chess"),
                estimatedLevel = "B1",
                manualLevelOverride = "B2",
                autoUpdateEnabled = false,
            ),
        )
        val incoming = mapOf(
            "en" to LearnerMemory(
                profileFacts = listOf("student in cairo", "Works on Arduino robots"),
                interestsAndGoals = listOf("Data analysis"),
                estimatedLevel = "C1",
                manualLevelOverride = "A2",
                autoUpdateEnabled = true,
            ),
            "es" to LearnerMemory(
                profileFacts = listOf("Learning Spanish for travel"),
                estimatedLevel = "A1",
            ),
            "invalid-lang" to LearnerMemory(profileFacts = listOf("Should be ignored")),
        )

        val merged = LearnerMemoryReducer.mergeImported(local, incoming)
        assertEquals(setOf("en", "es"), merged.keys)
        val en = merged.getValue("en")
        assertEquals(listOf("Student in Cairo", "Works on Arduino robots"), en.profileFacts)
        assertEquals(listOf("Chess", "Data analysis"), en.interestsAndGoals)
        assertEquals("B2", en.manualLevelOverride)
        assertFalse(en.autoUpdateEnabled)
    }

    @Test
    fun suggestedTopicsEnforceBoundsFilterSecretsExcludeRecentAndProvideDeterministicFallback() {
        // Empty memory -> empty fallback suggestions
        assertTrue(LearnerMemoryReducer.fallbackSuggestedTopics(LearnerMemory()).isEmpty())
        assertTrue(LearnerMemoryReducer.effectiveSuggestedTopics(LearnerMemory()).isEmpty())

        // Sanitization caps at 3, deduplicates, and filters secrets
        val dirty = LearnerMemory(
            interestsAndGoals = listOf("Backend Interviews", "Chess"),
            recentTopics = listOf(RecentTopicMemory(sessionID = "s-recent", topic = "Recent Topic")),
            suggestedTopics = listOf(
                SuggestedTopic(title = "Recent Topic", subtitle = "Should be excluded", situation = "Avoid repeating."),
                SuggestedTopic(title = "My password is 123", subtitle = "Secret", situation = "Should drop."),
                SuggestedTopic(title = "API Design Debate", subtitle = "REST vs gRPC", situation = "Debate API trade-offs."),
                SuggestedTopic(title = "api design debate", subtitle = "Duplicate", situation = "Dup."),
                SuggestedTopic(title = "Mock Tech Interview", subtitle = "System design", situation = "Roleplay an interviewer.", isRoleplay = true),
                SuggestedTopic(title = "Chess Opening Ideas", subtitle = "Sicilian defense", situation = "Discuss chess strategy."),
                SuggestedTopic(title = "Fourth Extra Topic", subtitle = "Overflow", situation = "Should be dropped."),
            ),
        )
        val effective = LearnerMemoryReducer.effectiveSuggestedTopics(dirty)
        assertEquals(3, effective.size)
        assertEquals(listOf("API Design Debate", "Mock Tech Interview", "Chess Opening Ideas"), effective.map { it.title })
        assertTrue(effective[1].toConversationTheme(1).id.startsWith("suggested:"))

        // Fallback generator detects roleplay keywords and excludes recent topics
        val fallbackMem = LearnerMemory(
            profileFacts = listOf("CS student in Cairo"),
            interestsAndGoals = listOf("Backend Interview", "Chess", "Travel"),
            recentTopics = listOf(RecentTopicMemory(sessionID = "s-1", topic = "Chess")),
        )
        val fallbacks = LearnerMemoryReducer.fallbackSuggestedTopics(fallbackMem)
        assertEquals(3, fallbacks.size)
        assertFalse(fallbacks.any { it.title.equals("Chess", ignoreCase = true) })
        assertTrue(fallbacks.any { it.isRoleplay })
    }
}
