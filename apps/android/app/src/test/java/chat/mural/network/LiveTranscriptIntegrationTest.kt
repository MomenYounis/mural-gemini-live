package chat.mural.network

import chat.mural.core.*
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LiveTranscriptIntegrationTest {
    private fun transcript(id: String, text: String, speaker: Speaker = Speaker.user, start: Int = 0,
        end: Int = 1_000, interim: Boolean = false) = LiveEvent.Transcript(id, speaker, text, start, end, interim)

    @Test fun interimHypothesesCannotBecomePersistentLearningEvidenceOrArchiveCaptions() {
        val session = SessionRecord(languageID = "en")
        LiveTranscript.apply(session, transcript("learner-1", "I has café", interim = true), true)
        assertTrue(session.fragments.isEmpty()); assertTrue(session.passages.isEmpty())
        assertEquals(0, LearningEngine.project(listOf(session), "en").observationCount)
        assertTrue(ArchiveCodec.decode(ArchiveCodec.encode(Archive(sessions = mutableListOf(session)))).sessions.single().fragments.isEmpty())
        LiveTranscript.apply(session, transcript("learner-1", "I has café."), true)
        assertEquals("I has café.", session.passages.single().text)
        assertEquals(0, session.fragments.single().revision)
    }

    @Test fun sameFragmentRevisionsPreserveLearnerErrorsUnicodeAndInvalidateExactOldEvidence() {
        val session = SessionRecord(languageID = "en")
        LiveTranscript.apply(session, transcript("learner-1", "I like the café."), true)
        val passage = session.passages.single()
        val oldAssessment = Assessment(passage.id, passage.revisionKey, Outcome.success, 1, "goal", "preference",
            listOf(WordProposal("like", "enjoy", "like", EvidenceKind.independent, .95, listOf("learner-1"), "I like the café.", "en")), textAssemblyVersion = 2)
        session.assessments += oldAssessment
        val oldMeaning = MeaningRequest(session.id, passage, "en", "Arabic")
        session.translations[oldMeaning.cacheKey] = "أنا أحب المقهى."
        session.translations["caption2/Arabic::unrelated:0"] = "محفوظ"
        LiveTranscript.apply(session, transcript("learner-1", "I likes the café. مرحبًا 你好"), false)
        assertEquals(1, session.fragments.size); assertEquals(1, session.fragments.single().revision)
        assertEquals(listOf("I like the café."), session.fragments.single().previousTexts)
        assertEquals("I likes the café. مرحبًا 你好", session.passages.single().text)
        assertTrue(session.assessments.isEmpty()); assertNull(LearningEngine.validate(oldAssessment, session))
        assertNull(session.translations[oldMeaning.cacheKey]); assertEquals("محفوظ", session.translations["caption2/Arabic::unrelated:0"])
        assertNotEquals(oldMeaning.cacheKey, MeaningRequest(session.id, session.passages.single(), "en", "Arabic").cacheKey)
        // Repeated finals are idempotent and preserve the original aid visibility on the evidence.
        LiveTranscript.apply(session, transcript("learner-1", "I likes the café. مرحبًا 你好"), false)
        assertEquals(1, session.fragments.single().revision); assertTrue(session.fragments.single().meaningVisible)
    }

    @Test fun adjacentFragmentsKeepWordBoundariesAndStableIdsForExactQuotations() {
        val session = SessionRecord(languageID = "en")
        LiveTranscript.apply(session, transcript("turn-a", "I like", end = 500), false)
        LiveTranscript.apply(session, transcript("turn-b", " the café.", start = 550, end = 1_000), false)
        val passage = session.passages.single()
        assertEquals("I like the café.", passage.text); assertEquals("turn-a:0,turn-b:0", passage.revisionKey)
        val assessment = Assessment(passage.id, passage.revisionKey, Outcome.success, 1, "goal", "preference",
            listOf(WordProposal("café", "coffee shop", "café", EvidenceKind.independent, .95, listOf("turn-a", "turn-b"), "I like the café.", "en")), textAssemblyVersion = 2)
        assertEquals(1, LearningEngine.validate(assessment, session)!!.words.size)
        LiveTranscript.apply(session, transcript("turn-b", " tea.", start = 550, end = 1_000), false)
        assertNull(LearningEngine.validate(assessment, session)); assertEquals("I like tea.", session.passages.single().text)
    }

    @Test fun oldMeaningCompletionCannotBeDisplayedOrCachedAfterLiveTranscriptRevision() = runTest {
        val session = SessionRecord(languageID = "en")
        val responses = ArrayDeque<CompletableDeferred<MeaningResult>>()
        val dispatched = mutableListOf<MeaningRequest>()
        val saved = mutableListOf<Pair<String, String>>()
        val controller = MeaningController(backgroundScope, delayMillis = 0, incompleteDelayMillis = 0,
            minimumSpacingMillis = 0, now = { testScheduler.currentTime }) { request ->
            dispatched += request
            withContext(NonCancellable) { CompletableDeferred<MeaningResult>().also(responses::addLast).await() }
        }
        controller.onResult = { request, result -> if (LiveTranscript.acceptsMeaning(session, request)) saved += request.cacheKey to result.text }
        LiveTranscript.apply(session, transcript("tutor", "I like coffee.", Speaker.assistant), false)
        controller.update(MeaningRequest(session.id, session.passages.single(), "en", "Arabic")); runCurrent()
        LiveTranscript.apply(session, transcript("tutor", "I like tea.", Speaker.assistant), false)
        controller.update(MeaningRequest(session.id, session.passages.single(), "en", "Arabic"))
        responses.removeFirst().complete(MeaningResult("أنا أحب القهوة.")); runCurrent()
        assertEquals("", controller.text); assertTrue(saved.isEmpty()); assertEquals(2, dispatched.size)
        responses.removeFirst().complete(MeaningResult("أنا أحب الشاي.")); runCurrent()
        assertEquals("أنا أحب الشاي.", controller.text); assertEquals(1, saved.size)
        assertEquals("caption2/Arabic::tutor:1", saved.single().first)
        controller.reset()
    }

    @Test fun speakerMutationAndInvalidTimelineCannotCorruptAnExistingFragment() {
        val session = SessionRecord(languageID = "en")
        LiveTranscript.apply(session, transcript("stable", "hello"), false)
        assertThrows(IllegalArgumentException::class.java) { LiveTranscript.apply(session, transcript("stable", "tutor", Speaker.assistant), false) }
        assertThrows(IllegalArgumentException::class.java) { LiveTranscript.apply(session, transcript("bad", "hello", start = -1), false) }
        assertThrows(IllegalArgumentException::class.java) { LiveTranscript.apply(session, transcript("bad", "hello", start = 9, end = 2), false) }
        assertEquals("hello", session.fragments.single().text)
    }

    @Test fun geminiTranscriptKeepsLegacyExportShapeAndDoesNotInventProviderUsageFinality() {
        val session = SessionRecord(languageID = "en", endedAt = nowSeconds())
        LiveTranscript.apply(session, transcript("gemini-turn", "English café العربية 中文"), false)
        session.inputTokens = 43; session.outputTokens = 17
        val encoded = ArchiveCodec.encode(Archive(sessions = mutableListOf(session)))
        val json = Json.parseToJsonElement(encoded).jsonObject
        val record = json["sessions"]!!.jsonArray.single().jsonObject
        assertFalse(record["usageFinal"]!!.jsonPrimitive.boolean)
        assertEquals(0.0, record["voiceSeconds"]!!.jsonPrimitive.double, 0.0)
        for (forbidden in listOf("vendor", "funding", "resumptionHandle", "apiKey", "estimatedTiming", "geminiUsage", "elapsedMilliseconds")) {
            assertFalse(record.containsKey(forbidden)); assertFalse(record["fragments"]!!.jsonArray.single().jsonObject.containsKey(forbidden))
        }
        val legacyFixture = Json.parseToJsonElement(File("../../../shared/fixtures/cross-platform/archive.json").readText()).jsonObject
            .getValue("sessions").jsonArray.first().jsonObject
        assertEquals(legacyFixture.keys - setOf("providerID", "endedAt", "themeID", "endReason"), record.keys - setOf("providerID", "endedAt", "themeID", "endReason"))
        val restored = ArchiveCodec.decode(encoded).sessions.single()
        assertEquals("English café العربية 中文", restored.passages.single().text)
        assertEquals(43, restored.inputTokens); assertEquals(17, restored.outputTokens); assertFalse(restored.usageFinal)
    }
}
