package chat.mural.core

import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LearningBackupCodecTest {
    @Test
    fun storageRoundTripPreservesLearnerMemoriesReportsAndCardsWhileLoadingLegacyFilesGracefully() {
        val sessionId = UUID.randomUUID().toString()
        val session = SessionRecord(id = sessionId, languageID = "en", startedAt = 100.0, endedAt = 200.0).apply {
            append(Fragment(id = "f-1", speaker = Speaker.user, text = "I went to the gym.", startMS = 0, endMS = 1000))
        }
        val archive = Archive(
            sessions = mutableListOf(session),
            preferences = Preferences(learningLanguageID = "en", meaningLanguage = "Arabic"),
        )
        val card = SentenceCard(
            id = "card-1",
            topic = "Daily Routine",
            languageID = "en",
            sourceSessionID = sessionId,
            sourceSessionIDs = listOf(sessionId),
            frontText = "ذهبت إلى الجيم.",
            backText = "I went to the gym.",
            explanation = "Past simple.",
            userOriginalMistake = "I go to gym.",
            lastAutoCreditedSessionID = sessionId,
        )
        val report = SessionReport(
            id = "rep-1",
            sessionID = sessionId,
            languageID = "en",
            date = 1_700_000_000_000L,
            topic = "Daily Routine",
            durationMinutes = 5,
            wins = listOf("Spoke smoothly in past tense."),
            mistakeCount = 1,
            mistakes = listOf(SessionMistake("I go to gym.", "I went to the gym.", "ذهبت إلى الجيم.", "Past tense.")),
            naturalUpgrades = listOf(
                NaturalUpgrade(
                    userSaid = "I do exercise every day.",
                    naturalAlternative = "I work out every day.",
                    arabicMeaning = "أتمرن كل يوم.",
                    explanation = "'Work out' is more natural for gym exercise.",
                ),
            ),
            practicedPhrases = listOf(PracticedPhrase(cardID = "card-1", phrase = "I went to the gym.", userQuote = "I went to the gym")),
            creditedCardIDs = listOf("card-1"),
        )
        val memories = mapOf(
            "en" to LearnerMemory(
                profileFacts = listOf("CS student in Cairo"),
                interestsAndGoals = listOf("Backend engineering"),
                estimatedLevel = "B1",
                manualLevelOverride = "B2",
                speakingGuidance = "Keep turns concise.",
                recentTopics = listOf(RecentTopicMemory(sessionId, "Daily Routine", "Morning habits.", dateMillis = 1_700_000_000_000L)),
                suggestedTopics = listOf(
                    SuggestedTopic(
                        id = "backend-chat",
                        title = "Backend Architecture",
                        subtitle = "Scale and trade-offs",
                        situation = "Discuss database indexing.",
                        isRoleplay = false,
                    ),
                ),
            ),
        )
        val settings = LearningSettings(
            geminiVoice = "Fenrir",
            coachStyle = CoachStyle.STRICT_COACH,
            flashcardAutoPlayOnFlip = true,
            flashcardListeningMode = false,
            dailyGoal = DailyGoalConfig(targetSpeakingMinutes = 10, targetCardsReviewed = 15),
            reminder = StudyReminderConfig(enabled = true, hour = 19, minute = 0),
        )
        val activityDays = listOf(
            DailyActivityRecord(dateIso = "2026-11-18", speakingMinutes = 6, cardsReviewed = 5),
        )

        val encoded = LearningBackupCodec.encodeStorageJson(
            data = LearningBackupData(archive, listOf(report), listOf(card), memories, settings, activityDays),
            finalAssessments = emptyList(),
        )
        val (decoded, tickets) = LearningBackupCodec.decodeStorageJson(encoded)
        assertTrue(tickets.isEmpty())
        assertEquals(archive, decoded.archive)
        assertEquals(listOf(report), decoded.sessionReports)
        assertEquals(listOf(card), decoded.sentenceCards)
        assertEquals(memories["en"]!!.sanitized(), decoded.learnerMemories["en"])
        assertEquals(settings, decoded.learningSettings)
        assertEquals(activityDays, decoded.reviewActivityDays)

        // Legacy archive-only storage JSON loads cleanly with empty reports/cards/memories
        val legacyEncoded = ArchiveCodec.encode(archive)
        val (legacyDecoded, legacyTickets) = LearningBackupCodec.decodeStorageJson(legacyEncoded)
        assertTrue(legacyTickets.isEmpty())
        assertEquals(archive, legacyDecoded.archive)
        assertTrue(legacyDecoded.sessionReports.isEmpty())
        assertTrue(legacyDecoded.sentenceCards.isEmpty())
        assertTrue(legacyDecoded.learnerMemories.isEmpty())
    }

    @Test
    fun backupExportRemainsV2ArchiveCompatibleAndMergesBothLegacyAndExtendedBackups() {
        val sessLocalId = UUID.randomUUID().toString()
        val sessIncomingId = UUID.randomUUID().toString()

        val localArchive = Archive(
            sessions = mutableListOf(
                SessionRecord(id = sessLocalId, languageID = "en", startedAt = 100.0, endedAt = 200.0),
            ),
            preferences = Preferences(learningLanguageID = "en", meaningLanguage = "Arabic"),
        )
        val localCard = SentenceCard(
            id = "local-card-1",
            topic = "Tech Interviews",
            languageID = "en",
            sourceSessionID = sessLocalId,
            sourceSessionIDs = listOf(sessLocalId),
            frontText = "لدي خبرة.",
            backText = "I have experience in backend development.",
            explanation = "Use 'in'.",
            userOriginalMistake = "I have experience on backend.",
            repetitionCount = 3,
            intervalDays = 14,
        )
        val localData = LearningBackupData(
            archive = localArchive,
            sessionReports = listOf(
                SessionReport(
                    id = "rep-local",
                    sessionID = sessLocalId,
                    languageID = "en",
                    date = 1_000L,
                    topic = "Tech Interviews",
                    durationMinutes = 10,
                ),
            ),
            sentenceCards = listOf(localCard),
            learnerMemories = mapOf(
                "en" to LearnerMemory(
                    profileFacts = listOf("CS student"),
                    manualLevelOverride = "B2",
                ),
            ),
            learningSettings = LearningSettings(geminiVoice = "Aoede", coachStyle = CoachStyle.RELAXED_FRIEND),
            reviewActivityDays = listOf(DailyActivityRecord("2026-11-18", speakingMinutes = 8, cardsReviewed = 3)),
        )

        val exported = LearningBackupCodec.encodeBackup(localData)
        assertFalse(exported.contains("androidFinalAssessments"))
        // Standard v2 ArchiveCodec must decode the exported backup without error!
        val decodedV2Only = ArchiveCodec.decode(exported)
        assertEquals(localArchive, decodedV2Only)
        val rootKeys = Json.parseToJsonElement(exported).jsonObject.keys
        assertTrue(
            rootKeys.containsAll(
                setOf(
                    "schemaVersion",
                    "sessions",
                    "preferences",
                    "sessionReports",
                    "sentenceCards",
                    "learnerMemories",
                    "learningSettings",
                    "reviewActivityDays",
                ),
            ),
        )

        // Incoming extended backup with one shared card phrase + one new card + memory
        val incomingArchive = Archive(
            sessions = mutableListOf(
                SessionRecord(id = sessIncomingId, languageID = "en", startedAt = 300.0, endedAt = 400.0),
            ),
            preferences = Preferences(learningLanguageID = "en", meaningLanguage = "Arabic"),
        )
        val incomingSharedCard = SentenceCard(
            id = "incoming-shared",
            topic = "Tech Interviews",
            languageID = "en",
            sourceSessionID = sessIncomingId,
            sourceSessionIDs = listOf(sessIncomingId),
            frontText = "لدي خبرة.",
            backText = "I have experience in backend development.",
            explanation = "Use 'in'.",
            userOriginalMistake = "",
            repetitionCount = 0,
            intervalDays = 0,
        )
        val incomingNewCard = SentenceCard(
            id = "incoming-new",
            topic = "Travel",
            languageID = "en",
            sourceSessionID = sessIncomingId,
            sourceSessionIDs = listOf(sessIncomingId),
            frontText = "حجزت التذكرة.",
            backText = "I booked the ticket.",
            explanation = "Past simple.",
            userOriginalMistake = "I book the ticket.",
        )
        val incomingEncoded = LearningBackupCodec.encodeBackup(
            LearningBackupData(
                archive = incomingArchive,
                sessionReports = listOf(
                    SessionReport(
                        id = "rep-incoming",
                        sessionID = sessIncomingId,
                        languageID = "en",
                        date = 2_000L,
                        topic = "Travel",
                        durationMinutes = 7,
                    ),
                ),
                sentenceCards = listOf(incomingSharedCard, incomingNewCard),
                learnerMemories = mapOf(
                    "en" to LearnerMemory(
                        profileFacts = listOf("cs student", "Plays chess"),
                        interestsAndGoals = listOf("Backend systems"),
                    ),
                ),
                learningSettings = LearningSettings(geminiVoice = "Puck", coachStyle = CoachStyle.STRICT_COACH),
                reviewActivityDays = listOf(
                    DailyActivityRecord("2026-11-18", speakingMinutes = 12, cardsReviewed = 1),
                    DailyActivityRecord("2026-11-19", speakingMinutes = 5, cardsReviewed = 4),
                ),
            ),
        )

        val decodedIncoming = LearningBackupCodec.decodeBackup(incomingEncoded)
        val merged = LearningBackupCodec.mergeBackup(localData, decodedIncoming)
        assertEquals(2, merged.archive.sessions.size)
        assertEquals(2, merged.sessionReports.size)
        assertEquals(2, merged.sentenceCards.size)
        // Preserves local settings while merging daily activity idempotently
        assertEquals("Aoede", merged.learningSettings.geminiVoice)
        assertEquals(CoachStyle.RELAXED_FRIEND, merged.learningSettings.coachStyle)
        assertEquals(
            listOf(
                DailyActivityRecord("2026-11-18", speakingMinutes = 12, cardsReviewed = 3),
                DailyActivityRecord("2026-11-19", speakingMinutes = 5, cardsReviewed = 4),
            ),
            merged.reviewActivityDays,
        )
        val keptShared = merged.sentenceCards.first { it.id == "local-card-1" }
        // Must preserve existing SRS progress while merging provenance!
        assertEquals(3, keptShared.repetitionCount)
        assertEquals(14, keptShared.intervalDays)
        assertEquals(listOf(sessLocalId, sessIncomingId), keptShared.allSourceSessionIDs())

        val mergedMemory = merged.learnerMemories.getValue("en")
        assertEquals(listOf("CS student", "Plays chess"), mergedMemory.profileFacts)
        assertEquals(listOf("Backend systems"), mergedMemory.interestsAndGoals)
        assertEquals("B2", mergedMemory.manualLevelOverride)

        // Legacy v2 backup without extended fields imports cleanly
        val legacyOnlyId = UUID.randomUUID().toString()
        val legacyRaw = ArchiveCodec.encode(
            Archive(sessions = mutableListOf(SessionRecord(id = legacyOnlyId, languageID = "es", startedAt = 50.0, endedAt = 60.0))),
        )
        val mergedWithLegacy = LearningBackupCodec.mergeBackup(merged, LearningBackupCodec.decodeBackup(legacyRaw))
        assertEquals(3, mergedWithLegacy.archive.sessions.size)
        assertEquals(2, mergedWithLegacy.sentenceCards.size)
    }
}
