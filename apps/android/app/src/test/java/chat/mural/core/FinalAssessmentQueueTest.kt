package chat.mural.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FinalAssessmentQueueTest {
    @Test fun immediateAssessmentIsDeliveredAndRemovedFromPending() {
        val session = ended()
        val passage = session.passages.last()
        val queue = FinalAssessmentQueue(CoroutineScope(Dispatchers.Unconfined)) { snapshot, _ ->
            FinalAssessmentResult(snapshot.id, snapshot.languageID,
                Assessment(passage.id, passage.revisionKey, Outcome.success, 1, "Next", "A word", emptyList()))
        }
        var received = 0
        queue.onResult = { received++ }
        assertTrue(queue.submit(session))
        assertEquals(1, received)
        assertFalse(queue.isPending(session.id))
        queue.cancelAll()
    }

    @Test fun providerWaitsForTheDurableAttemptCheckpoint() = runTest {
        val session = ended()
        val checkpoint = CompletableDeferred<Boolean>()
        var started = 0
        val queue = FinalAssessmentQueue(backgroundScope) { snapshot, passage ->
            started++
            FinalAssessmentResult(snapshot.id, snapshot.languageID,
                Assessment(passage.id, passage.revisionKey, Outcome.success, 1, "Next", "A word", emptyList()))
        }
        queue.beforeAssessment = { _, _ -> checkpoint.await() }
        assertTrue(queue.submit(session)); runCurrent()
        assertEquals(0, started)
        checkpoint.complete(true); runCurrent()
        assertEquals(1, started)
        assertFalse(queue.isPending(session.id))
    }

    @Test fun failedOrCancelledCheckpointsNeverReachTheProvider() = runTest {
        for (allow in listOf(false, true)) {
            val session = ended()
            val checkpoint = CompletableDeferred<Boolean>()
            var started = 0
            val queue = FinalAssessmentQueue(backgroundScope) { _, _ ->
                started++
                error("A cancelled or rejected checkpoint must not make a paid request")
            }
            queue.beforeAssessment = { _, _ -> checkpoint.await() }
            queue.submit(session); runCurrent()
            if (allow) queue.cancel(session.id)
            checkpoint.complete(allow); runCurrent()
            assertEquals(0, started)
            assertFalse(queue.isPending(session.id))
        }
    }

    @Test fun restartRecoversOnlyQueuedCurrentRevisionsAndKeepsAttemptCounts() {
        val session = ended()
        val now = session.endedAt!!
        var tickets = FinalAssessmentRecovery.enqueue(session, emptyList())
        assertEquals(listOf(session.id), FinalAssessmentRecovery.recover(listOf(session), tickets, now).map { it.id })
        assertTrue(FinalAssessmentRecovery.recover(listOf(session), emptyList(), now).isEmpty())
        tickets = tickets.map { it.copy(attempts = 1, lastAttemptAt = now) }
        val restored = Json.decodeFromString<List<FinalAssessmentTicket>>(Json.encodeToString(tickets))
        assertTrue(FinalAssessmentRecovery.recover(listOf(session), restored, now + 59).isEmpty())
        assertEquals(1, FinalAssessmentRecovery.recover(listOf(session), restored, now + 60).size)
        assertEquals(restored, FinalAssessmentRecovery.enqueue(session, restored))
        session.correctFragment(session.fragments.single().id, "televisión")
        assertTrue(FinalAssessmentRecovery.recover(listOf(session), restored, now + 61).isEmpty())
        val corrected = FinalAssessmentRecovery.enqueue(session, restored)
        assertEquals(1, corrected.size)
        assertEquals(0, corrected.single().attempts)
        assertNotEquals(restored.single().revisionKey, corrected.single().revisionKey)
    }

    @Test fun retriesAreBoundedByAttemptsAgeAndLaunchBatch() {
        val records = (1..8).map { ended() }
        val now = records.maxOf { it.endedAt!! }
        val tickets = records.fold(emptyList<FinalAssessmentTicket>()) { list, record -> FinalAssessmentRecovery.enqueue(record, list) }
        assertEquals(5, FinalAssessmentRecovery.recover(records, tickets, now).size)
        assertTrue(FinalAssessmentRecovery.recover(records, tickets.map { it.copy(attempts = 3, lastAttemptAt = now - 500) }, now).isEmpty())
        assertTrue(FinalAssessmentRecovery.recover(records, tickets, now + 8 * 24 * 3600).isEmpty())
        assertTrue(FinalAssessmentRecovery.recover(records, tickets, now - 1).isEmpty())
        assertTrue(FinalAssessmentRecovery.recover(emptyList(), tickets, now).isEmpty())
    }

    @Test fun completedAssessmentRemovesItsRetryTicket() {
        val session = ended()
        val tickets = FinalAssessmentRecovery.enqueue(session, emptyList())
        val passage = session.passages.last()
        session.assessments += Assessment(passage.id, passage.revisionKey, Outcome.success, 1, "Next", "A word", emptyList())
        assertTrue(FinalAssessmentRecovery.enqueue(session, tickets).isEmpty())
        assertTrue(FinalAssessmentRecovery.recover(listOf(session), tickets, session.endedAt!!).isEmpty())
    }

    @Test fun laterSuccessDoesNotDiscardEarlierLearnerEvidenceOrItsRetryBudget() {
        val session = ended("en").apply {
            append(Fragment(id = "second", speaker = Speaker.user, text = "I enjoy tea.", startMS = 5_000, endMS = 6_000, typed = true))
        }
        val first = session.passages.first()
        val latest = session.passages.last()
        val now = session.endedAt!!
        val tickets = FinalAssessmentRecovery.enqueue(session, emptyList()).map {
            if (it.passageID == first.id) it.copy(attempts = 1, lastAttemptAt = now - 60) else it
        }
        assertEquals(2, tickets.size)
        session.assessments += Assessment(latest.id, latest.revisionKey, Outcome.success, 1, "Next", "A preference", emptyList())
        val retained = FinalAssessmentRecovery.enqueue(session, tickets)
        assertEquals(1, retained.size)
        assertEquals(first.id, retained.single().passageID)
        assertEquals(1, retained.single().attempts)
        assertEquals(first.id, FinalAssessmentRecovery.passage(session)?.id)
        assertTrue(FinalAssessmentRecovery.canAttempt(retained.single(), session, now))
    }

    @Test fun correctionReplacesOnlyTheAffectedPassageTicketAndRecoveryDeduplicatesSessions() {
        val session = ended().apply {
            append(Fragment(id = "second", speaker = Speaker.user, text = "la televisión", startMS = 5_000, endMS = 6_000, typed = true))
        }
        val tickets = FinalAssessmentRecovery.enqueue(session, emptyList()).map { it.copy(attempts = 1, lastAttemptAt = session.endedAt!! - 60) }
        assertEquals(1, FinalAssessmentRecovery.recover(listOf(session), tickets, session.endedAt!!).size)
        session.correctFragment(session.fragments.first().id, "el periódico")
        val retained = FinalAssessmentRecovery.enqueue(session, tickets)
        assertEquals(2, retained.size)
        assertEquals(0, retained.first().attempts)
        assertEquals(tickets.last(), retained.last())
        assertFalse(FinalAssessmentRecovery.canAttempt(tickets.first(), session, session.endedAt!!))
    }

    @Test fun recoveryCanTargetAnEligibleEarlierPassageWithoutUsingTheLatestBackoffTicket() = runTest {
        val session = ended("en").apply {
            append(Fragment(id = "second", speaker = Speaker.user, text = "I enjoy tea.", startMS = 5_000, endMS = 6_000, typed = true))
        }
        val provider = Provider()
        val queue = queue(provider)
        val earlier = session.passages.first()
        assertTrue(queue.submit(session, passageID = earlier.id)); runCurrent()
        assertEquals(earlier.id, provider.pending.single().second.id)
        provider.finish(); runCurrent()
        assertFalse(queue.submit(session, passageID = "missing"))
    }

    @Test fun recoveredTypedAndMicrophoneWordsStillRequireExactCurrentEvidence() {
        var session = ended("en").apply {
            fragments.clear()
            append(Fragment(id = "mic", speaker = Speaker.user, text = "I like the café.", startMS = 0, endMS = 1_000))
            append(Fragment(id = "typed", speaker = Speaker.user, text = "I drink tea.", startMS = 5_000, endMS = 6_000, typed = true))
        }
        for (passage in session.passages) {
            val form = if (passage.id == "mic") "café" else "tea"
            val result = FinalAssessmentResult(session.id, session.languageID,
                Assessment(passage.id, passage.revisionKey, Outcome.success, 1, "Next", "A preference",
                    listOf(WordProposal(form, if (form == "café") "coffee shop" else "tea", form, EvidenceKind.independent, .95,
                        passage.fragments.map { it.id }, passage.text, "en")), textAssemblyVersion = 2))
            session = result.applying(session)!!
        }
        val learner = LearningEngine.project(listOf(session), "en")
        assertEquals(2, learner.words.size)
        assertEquals(1, learner.words.single { it.lemma == "café" }.independentCount)
        assertEquals(0, learner.words.single { it.lemma == "tea" }.independentCount)
        val savedMic = session.assessments.first { it.passageID == "mic" }
        session.correctFragment("mic", "I like the library.")
        assertNull(LearningEngine.validate(savedMic, session))
        assertEquals(listOf("tea"), LearningEngine.project(listOf(session), "en").words.map { it.lemma })
        assertEquals(listOf("I like the café."), session.fragments.first().previousTexts)
        assertEquals(0, session.fragments.first().startMS)
        assertEquals(1_000, session.fragments.first().endMS)
    }

    @Test fun helperFailureAndDeadlineAreReportedWithoutCompletingOrErasingEvidence() = runTest {
        val session = ended()
        val failure = IllegalStateException("offline failure")
        var failures = 0
        val queue = FinalAssessmentQueue(backgroundScope) { _, _ -> throw failure }
        queue.onFailure = { snapshot, passage, error ->
            assertEquals(session.id, snapshot.id)
            assertEquals(session.passages.single().revisionKey, passage.revisionKey)
            assertSame(failure, error)
            failures++
        }
        assertTrue(queue.submit(session)); runCurrent()
        assertEquals(1, failures)
        assertFalse(queue.isPending(session.id))
        assertEquals(1, FinalAssessmentRecovery.enqueue(session, emptyList()).size)
        assertTrue(session.assessments.isEmpty())

        val provider = Provider()
        val timed = queue(provider, timeoutMillis = 30)
        timed.onFailure = { _, _, error -> assertTrue(error is FinalAssessmentTimeoutException); failures++ }
        assertTrue(timed.submit(session)); runCurrent()
        advanceTimeBy(31); runCurrent()
        assertEquals(2, failures)
        provider.finish(); runCurrent()
        assertEquals(2, failures)
    }

    @Test fun mismatchedSessionLanguagePassageAndRevisionEachReportAFixedSafeFailure() = runTest {
        val session = ended()
        val passage = session.passages.single()
        val valid = FinalAssessmentResult(session.id, session.languageID,
            Assessment(passage.id, passage.revisionKey, Outcome.success, 1, "Next", "A word", emptyList()))
        val invalid = listOf(
            valid.copy(sessionID = "private-unrelated-session"),
            valid.copy(languageID = "private-unrelated-language"),
            valid.copy(assessment = valid.assessment.copy(passageID = "private-unrelated-passage")),
            valid.copy(assessment = valid.assessment.copy(revisionKey = "private-unrelated-revision")),
        )
        for (result in invalid) {
            var received = 0
            var failures = 0
            val queue = FinalAssessmentQueue(backgroundScope) { _, _ -> result }
            queue.onResult = { received++ }
            queue.onFailure = { snapshot, target, error ->
                assertEquals(session.id, snapshot.id)
                assertEquals(passage.revisionKey, target.revisionKey)
                assertTrue(error is FinalAssessmentInvalidResultException)
                assertEquals("The word assessment could not be verified. Please try again.", error.message)
                assertFalse(error.toString().contains("private-unrelated"))
                failures++
            }
            assertTrue(queue.submit(session)); runCurrent()
            assertEquals(0, received)
            assertEquals(1, failures)
            assertFalse(queue.isPending(session.id))
            assertTrue(session.assessments.isEmpty())
            assertEquals(1, FinalAssessmentRecovery.enqueue(session, emptyList()).size)
        }
    }

    @Test fun explicitCancellationDoesNotShowAHelperFailure() = runTest {
        val session = ended()
        val provider = Provider()
        val queue = queue(provider)
        var failures = 0
        queue.onFailure = { _, _, _ -> failures++ }
        assertTrue(queue.submit(session)); runCurrent()
        queue.cancel(session.id)
        provider.finish(); runCurrent()
        assertEquals(0, failures)
    }

    private class Provider {
        val pending = ArrayDeque<Triple<SessionRecord, Passage, CompletableDeferred<FinalAssessmentResult>>>()
        suspend fun assess(session: SessionRecord, passage: Passage): FinalAssessmentResult {
            val response = CompletableDeferred<FinalAssessmentResult>()
            pending.addLast(Triple(session, passage, response))
            // Ignores cancellation, like a response already in flight.
            return withContext(NonCancellable) { response.await() }
        }
        fun finish() {
            val (session, passage, response) = pending.removeFirst()
            val assessment = Assessment(passage.id, passage.revisionKey, Outcome.success, 1, "Una pregunta más.", "Names an object",
                listOf(WordProposal("la radio", "radio", "radio", EvidenceKind.independent, 0.95, passage.fragments.map { it.id }, passage.text, session.languageID)))
            response.complete(FinalAssessmentResult(session.id, session.languageID, assessment, inputTokens = 100, outputTokens = 20))
        }
    }

    private fun ended(languageID: String = "es") = SessionRecord(languageID = languageID).apply {
        append(Fragment(speaker = Speaker.user, text = "radio", startMS = 0, endMS = 1000))
        endedAt = nowSeconds()
    }

    private fun TestScope.queue(provider: Provider, timeoutMillis: Long = 15_000) =
        FinalAssessmentQueue(backgroundScope, timeoutMillis, assess = provider::assess)

    @Test fun resetAndNewLanguageDoNotRedirectResultsToTheNewSession() = runTest {
        val old = ended(); val new = ended("fr"); val provider = Provider()
        val records = mutableMapOf(old.id to old, new.id to new)
        var visible: SessionRecord? = old
        val queue = queue(provider)
        queue.onResult = { result ->
            result.applying(records[result.sessionID])?.let { records[it.id] = it }
            if (visible?.id == result.sessionID) visible = records[result.sessionID]
        }
        assertTrue(queue.submit(old)); runCurrent()
        assertEquals(1, provider.pending.size)
        visible = null
        visible = new
        provider.finish(); runCurrent()
        assertFalse(queue.isPending(old.id))
        assertEquals(1, records.getValue(old.id).assessments.size)
        assertEquals(100, records.getValue(old.id).inputTokens)
        assertTrue(records.getValue(new.id).assessments.isEmpty())
        assertEquals(new.id, visible?.id)
        assertTrue(visible!!.assessments.isEmpty())
    }

    @Test fun deletedSessionIsNeverRecreatedByALateResult() = runTest {
        val session = ended(); val provider = Provider()
        val records = mutableMapOf(session.id to session)
        val queue = queue(provider)
        queue.onResult = { result -> result.applying(records[result.sessionID])?.let { records[it.id] = it } }
        queue.submit(session); runCurrent()
        records.remove(session.id)
        provider.finish(); runCurrent()
        assertFalse(queue.isPending(session.id))
        assertTrue(records.isEmpty())
    }

    @Test fun cancellationAndDeadlineRejectResponsesThatIgnoreCancellation() = runTest {
        for (explicitlyCancel in listOf(true, false)) {
            val session = ended(); val provider = Provider()
            var received = 0
            val queue = queue(provider, timeoutMillis = 30)
            queue.onResult = { received++ }
            queue.submit(session); runCurrent()
            assertEquals(1, provider.pending.size)
            if (explicitlyCancel) queue.cancel(session.id) else { advanceTimeBy(31); runCurrent() }
            assertFalse(queue.isPending(session.id))
            provider.finish(); runCurrent()
            assertEquals(0, received)
        }
    }

    @Test fun customFortyFiveSecondWindowDoesNotCancelAtTheLegacyFifteenSecondLimit() = runTest {
        val session = ended()
        val provider = Provider()
        val queue = queue(provider, timeoutMillis = 45_000)
        var received = 0
        var failures = 0
        queue.onResult = { received++ }
        queue.onFailure = { _, _, _ -> failures++ }
        assertTrue(queue.submit(session)); runCurrent()
        advanceTimeBy(15_001); runCurrent()
        assertTrue(queue.isPending(session.id))
        assertEquals(0, failures)
        advanceTimeBy(29_998); runCurrent()
        assertTrue(queue.isPending(session.id))
        provider.finish(); runCurrent()
        assertEquals(1, received)
        assertFalse(queue.isPending(session.id))
        assertEquals(0, failures)
    }

    @Test fun submitOverrideCanCompleteAfterFortyFiveAndSixtySecondsBeforeNinety() = runTest {
        val session = ended()
        val provider = Provider()
        val queue = FinalAssessmentQueue(backgroundScope, 45_000,
            clock = { testScheduler.currentTime }, assess = provider::assess)
        var received = 0
        var failures = 0
        queue.onResult = { received++ }
        queue.onFailure = { _, _, _ -> failures++ }
        assertTrue(queue.submit(session, timeoutMillis = 90_000)); runCurrent()
        advanceTimeBy(45_001); runCurrent()
        assertTrue(queue.isPending(session.id))
        advanceTimeBy(15_000); runCurrent()
        assertTrue(queue.isPending(session.id))
        advanceTimeBy(29_998); runCurrent()
        assertTrue(queue.isPending(session.id))
        provider.finish(); runCurrent()
        assertEquals(1, received)
        assertEquals(0, failures)
        assertFalse(queue.isPending(session.id))
    }

    @Test fun ninetySecondOverrideExpiresOnceAndRejectsALateResponse() = runTest {
        val session = ended()
        val provider = Provider()
        val queue = FinalAssessmentQueue(backgroundScope, 45_000,
            clock = { testScheduler.currentTime }, assess = provider::assess)
        var received = 0
        var failures = 0
        queue.onResult = { received++ }
        queue.onFailure = { _, _, error -> assertTrue(error is FinalAssessmentTimeoutException); failures++ }
        assertTrue(queue.submit(session, timeoutMillis = 90_000)); runCurrent()
        advanceTimeBy(89_999); runCurrent()
        assertTrue(queue.isPending(session.id))
        assertEquals(0, failures)
        advanceTimeBy(1); runCurrent()
        assertFalse(queue.isPending(session.id))
        assertEquals(1, failures)
        provider.finish(); runCurrent()
        assertEquals(0, received)
        assertEquals(1, failures)
    }

    @Test fun anOverrideDoesNotChangeTheConstructorDefaultForOtherRequests() = runTest {
        for (defaultWindow in listOf(15_000L, 45_000L)) {
            val extended = ended()
            val ordinary = ended()
            val provider = Provider()
            val queue = FinalAssessmentQueue(backgroundScope, defaultWindow,
                clock = { testScheduler.currentTime }, assess = provider::assess)
            val received = mutableListOf<String>()
            val failed = mutableListOf<String>()
            queue.onResult = { received += it.sessionID }
            queue.onFailure = { snapshot, _, error ->
                assertTrue(error is FinalAssessmentTimeoutException); failed += snapshot.id
            }
            assertTrue(queue.submit(extended, timeoutMillis = 90_000))
            assertTrue(queue.submit(ordinary)); runCurrent()
            advanceTimeBy(defaultWindow - 1); runCurrent()
            assertTrue(queue.isPending(extended.id))
            assertTrue(queue.isPending(ordinary.id))
            advanceTimeBy(1); runCurrent()
            assertTrue(queue.isPending(extended.id))
            assertFalse(queue.isPending(ordinary.id))
            assertEquals(listOf(ordinary.id), failed)
            provider.finish(); provider.finish(); runCurrent()
            assertEquals(listOf(extended.id), received)
            assertEquals(listOf(ordinary.id), failed)
            assertFalse(queue.isPending(extended.id))
        }
    }

    @Test fun submitOverrideClampsItsTimerToSafeBounds() = runTest {
        for ((requested, effective) in listOf(Long.MIN_VALUE to 1L, 0L to 1L,
            120_001L to 120_000L, Long.MAX_VALUE to 120_000L)) {
            val session = ended()
            val provider = Provider()
            val queue = FinalAssessmentQueue(backgroundScope, 45_000,
                clock = { testScheduler.currentTime }, assess = provider::assess)
            var received = 0
            var failures = 0
            queue.onResult = { received++ }
            queue.onFailure = { _, _, error -> assertTrue(error is FinalAssessmentTimeoutException); failures++ }
            assertTrue(queue.submit(session, timeoutMillis = requested)); runCurrent()
            advanceTimeBy(effective - 1); runCurrent()
            assertTrue(queue.isPending(session.id))
            assertEquals(0, failures)
            advanceTimeBy(1); runCurrent()
            assertFalse(queue.isPending(session.id))
            assertEquals(1, failures)
            provider.finish(); runCurrent()
            assertEquals(0, received)
            assertEquals(1, failures)
        }
    }

    @Test fun wallClockChecksUseTheEffectiveSubmitOverrideAfterSleep() = runTest {
        for ((requested, effective) in listOf(90_000L to 90_000L,
            Long.MIN_VALUE to 1L, Long.MAX_VALUE to 120_000L)) {
            for (late in listOf(false, true)) {
                val session = ended()
                val provider = Provider()
                var now = 1_000_000L
                val queue = FinalAssessmentQueue(backgroundScope, 45_000,
                    clock = { now }, assess = provider::assess)
                var received = 0
                var failures = 0
                queue.onResult = { received++ }
                queue.onFailure = { _, _, error -> assertTrue(error is FinalAssessmentTimeoutException); failures++ }
                assertTrue(queue.submit(session, timeoutMillis = requested)); runCurrent()
                // Advance only the wall clock, as when device sleep delays the coroutine timer.
                now += if (late) effective + 1 else effective - 1
                provider.finish(); runCurrent()
                assertEquals(if (late) 0 else 1, received)
                assertEquals(if (late) 1 else 0, failures)
                assertFalse(queue.isPending(session.id))
            }
        }
    }

    @Test fun cancelledOverrideCannotDeliverToOrExpireItsReplacementRequest() = runTest {
        val session = ended()
        val provider = Provider()
        val queue = FinalAssessmentQueue(backgroundScope, 45_000,
            clock = { testScheduler.currentTime }, assess = provider::assess)
        var received = 0
        var failures = 0
        queue.onResult = { received++ }
        queue.onFailure = { _, _, _ -> failures++ }
        assertTrue(queue.submit(session, timeoutMillis = 90_000)); runCurrent()
        advanceTimeBy(60_001); runCurrent()
        queue.cancel(session.id)
        assertTrue(queue.submit(session)); runCurrent()
        advanceTimeBy(30_000); runCurrent()
        assertTrue(queue.isPending(session.id))
        assertEquals(0, failures)
        provider.finish(); runCurrent()
        assertTrue(queue.isPending(session.id))
        assertEquals(0, received)
        assertEquals(0, failures)
        provider.finish(); runCurrent()
        assertFalse(queue.isPending(session.id))
        assertEquals(1, received)
        assertEquals(0, failures)
    }

    @Test fun wallClockDeadlineRejectsAResponseAfterTheDeviceSlept() = runTest {
        val session = ended(); val provider = Provider()
        var now = 1_000_000L
        var received = 0
        val queue = FinalAssessmentQueue(backgroundScope, 15_000, clock = { now }, assess = provider::assess)
        queue.onResult = { received++ }
        queue.submit(session); runCurrent()
        now += 20_000
        provider.finish(); runCurrent()
        assertEquals(0, received)
        assertFalse(queue.isPending(session.id))
    }

    @Test fun cancelAllRejectsEveryPendingResult() = runTest {
        val first = ended(); val second = ended("fr"); val provider = Provider()
        var received = 0
        val queue = queue(provider)
        queue.onResult = { received++ }
        queue.submit(first); queue.submit(second); runCurrent()
        queue.cancelAll()
        assertFalse(queue.isPending(first.id) || queue.isPending(second.id))
        provider.finish(); provider.finish(); runCurrent()
        assertEquals(0, received)
    }

    @Test fun correctedTranscriptRejectsTheOriginalAssessment() = runTest {
        val session = ended(); val provider = Provider(); val queue = queue(provider)
        var applied = false
        queue.onResult = { result -> applied = result.applying(session) != null }
        queue.submit(session); runCurrent()
        session.correctFragment(session.fragments[0].id, "televisión")
        provider.finish(); runCurrent()
        assertFalse(queue.isPending(session.id))
        assertFalse(applied)
    }

    @Test fun alreadyAssessedAndPendingPassagesDoNotStartDuplicateRequests() = runTest {
        var session = ended(); val provider = Provider(); val queue = queue(provider)
        queue.onResult = { result -> session = result.applying(session)!! }
        assertTrue(queue.submit(session))
        assertFalse(queue.submit(session))
        runCurrent()
        assertEquals(1, provider.pending.size)
        provider.finish(); runCurrent()
        assertFalse(queue.isPending(session.id))
        assertFalse(queue.submit(session))
        assertEquals(1, session.assessments.size)
        assertEquals(20, session.outputTokens)
    }
}
