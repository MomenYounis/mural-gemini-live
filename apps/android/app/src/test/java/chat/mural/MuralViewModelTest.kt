package chat.mural

import chat.mural.core.AccountFailure
import chat.mural.network.HostedFailure
import chat.mural.core.Archive
import chat.mural.core.ArchiveCodec
import chat.mural.core.Assessment
import chat.mural.core.EvidenceKind
import chat.mural.core.Fragment
import chat.mural.core.Outcome
import chat.mural.core.SessionRecord
import chat.mural.core.Speaker
import chat.mural.core.WordProposal
import chat.mural.network.APIClient
import chat.mural.network.CredentialStore
import chat.mural.network.GroqTranscriptionResult
import chat.mural.network.GroqWhisperModel
import chat.mural.network.TurnAudioBuffer
import chat.mural.network.TurnTranscriber
import kotlin.math.sin
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MuralViewModelTest {
    @Test fun extendedReviewDeadlineIsScopedToPersonalGemini() {
        assertEquals(90_000L, learningReviewTimeoutMillis(chat.mural.core.AIVendor.GEMINI, chat.mural.core.ConversationProvider.PERSONAL_KEY))
        assertEquals(45_000L, learningReviewTimeoutMillis(chat.mural.core.AIVendor.OPENAI, chat.mural.core.ConversationProvider.PERSONAL_KEY))
        assertEquals(45_000L, learningReviewTimeoutMillis(chat.mural.core.AIVendor.OPENAI, chat.mural.core.ConversationProvider.HOSTED_MINUTES))
        assertEquals(45_000L, learningReviewTimeoutMillis(chat.mural.core.AIVendor.GEMINI, chat.mural.core.ConversationProvider.HOSTED_MINUTES))
    }
    @Test fun geminiInvalidExpiredAndQuotaErrorsStayVendorScopedAndLocalized() {
        for (reason in listOf("API_KEY_INVALID", "API_KEY_EXPIRED")) {
            val error = chat.mural.network.GeminiException.Http(400, reason)
            assertTrue(errorNeedsKeySetup(error))
            assertEquals(R.string.gemini_authentication, errorMessageRes(error))
        }
        assertEquals(R.string.gemini_quota, errorMessageRes(chat.mural.network.GeminiException.Http(429)))
        assertEquals(R.string.gemini_search_unavailable, errorMessageRes(chat.mural.network.GeminiException.SearchUnavailable))
        assertFalse(errorNeedsKeySetup(chat.mural.network.GeminiException.SearchUnavailable))
    }
    @Test fun importedUnfinishedHistoryCannotLookLikeALocalInterruptedSession() {
        val partial = SessionRecord(languageID = "es", startedAt = 800_000_000.0).apply {
            append(Fragment(speaker = Speaker.user, text = "la radio", startMS = 0, endMS = 1_000))
        }
        val finished = SessionRecord(languageID = "fr", startedAt = 800_000_000.0, endedAt = 800_000_010.0,
            usageFinal = true, endReason = "Ended by you")
        val imported = prepareImportedArchive(ArchiveCodec.encode(Archive(sessions = mutableListOf(partial, finished))),
            importedAt = 800_000_020.0)
        assertEquals(800_000_020.0, imported.sessions[0].endedAt!!, 0.0)
        assertEquals(partial.fragments, imported.sessions[0].fragments)
        assertFalse(imported.sessions[0].usageFinal)
        assertEquals(finished, imported.sessions[1])
        assertEquals(imported, ArchiveCodec.decode(ArchiveCodec.encode(imported)))
    }

    @Test fun everyApiAndCredentialReasonMapsToANonZeroResource() {
        val reasons = listOf(
            APIClient.APIException.MissingKey,
            APIClient.APIException.Refused,
            APIClient.APIException.InvalidResponse,
            APIClient.APIException.Http(401),
            APIClient.APIException.Http(403),
            APIClient.APIException.Http(429),
            APIClient.APIException.Http(500),
            CredentialStore.CredentialException.Invalid,
            CredentialStore.CredentialException.Save,
            CredentialStore.CredentialException.Remove,
        )
        reasons.forEach { assertTrue("expected a resource for $it", errorMessageRes(it) != 0) }
    }

    @Test fun incompleteResponseSharesTheSameResourceAsInvalidResponse() {
        assertEquals(errorMessageRes(APIClient.APIException.InvalidResponse), errorMessageRes(APIClient.APIException.Incomplete))
    }

    @Test fun httpReasonsWithTheSameMeaningShareAResourceButOthersDiffer() {
        assertEquals(errorMessageRes(APIClient.APIException.Http(403)), errorMessageRes(APIClient.APIException.Http(404)))
        val distinctReasons = listOf(
            APIClient.APIException.MissingKey,
            APIClient.APIException.Refused,
            APIClient.APIException.InvalidResponse,
            APIClient.APIException.Http(401),
            APIClient.APIException.Http(403),
            APIClient.APIException.Http(429),
            APIClient.APIException.Http(500),
            CredentialStore.CredentialException.Invalid,
            CredentialStore.CredentialException.Save,
            CredentialStore.CredentialException.Remove,
        )
        val ids = distinctReasons.map { errorMessageRes(it) }
        assertEquals("distinct reasons must map to distinct resources", ids.size, ids.toSet().size)
    }

    @Test fun unmappedThrowableHasNoResource() {
        assertEquals(0, errorMessageRes(IllegalStateException("unexpected")))
    }

    @Test fun onlyMissingKeyAndUnauthorizedNeedKeySetup() {
        assertTrue(errorNeedsKeySetup(APIClient.APIException.MissingKey))
        assertTrue(errorNeedsKeySetup(APIClient.APIException.Http(401)))
        assertFalse(errorNeedsKeySetup(APIClient.APIException.Http(403)))
        assertFalse(errorNeedsKeySetup(APIClient.APIException.Refused))
        assertFalse(errorNeedsKeySetup(CredentialStore.CredentialException.Invalid))
    }
    @Test fun hostedSignInFailuresRecoverThroughAccountAndNeverPersonalKeySetup() {
        for (error in listOf(HostedFailure.SignInRequired, HostedFailure.Http(401, "sign_in_required"),
            HostedFailure.Http(403, "sign_in_to_continue"), AccountFailure.Http(401, "sign_in_required"))) {
            assertEquals(R.string.hosted_sign_in_again, errorMessageRes(error))
            assertTrue(needsAccountRecovery(error))
            assertFalse(errorNeedsKeySetup(error))
        }
        assertFalse(needsAccountRecovery(HostedFailure.Http(503, "internal")))
        assertEquals(R.string.hosted_rate_limit, errorMessageRes(HostedFailure.Http(429, "rate_limit")))
        assertEquals(R.string.hosted_start_rejected, errorMessageRes(HostedFailure.Http(502, "provider_create_rejected")))
    }

    @Test fun onlySafeOpaqueReferencesReachTheErrorMessage() {
        assertEquals("0123abcdef45", requestErrorReference(HostedFailure.Http(500, "internal", reference = "0123abcdef45")))
        assertEquals("0123abcdef45", requestErrorReference(AccountFailure.Http(500, "internal", "0123abcdef45")))
        for (value in listOf("person@example.com", "0123ABCDEF45", "0123abcdef45\n", "", "123")) {
            assertEquals(null, requestErrorReference(HostedFailure.Http(500, "internal", reference = value)))
            assertEquals(null, requestErrorReference(AccountFailure.Http(500, "internal", value)))
        }
    }

    @Test fun recoveryAdviceDistinguishesQuotaBusyLimitsAndUnconfirmedBilling() {
        assertEquals(R.string.error_provider_quota, errorMessageRes(APIClient.APIException.Http(429, "insufficient_quota")))
        assertEquals(R.string.error_http_429, errorMessageRes(APIClient.APIException.Http(429, "rate_limit_exceeded")))
        assertEquals(R.string.error_request_timeout, errorMessageRes(java.net.SocketTimeoutException()))
        assertEquals(R.string.error_request_connection, errorMessageRes(java.net.UnknownHostException()))
        assertEquals(R.string.hosted_help_busy, errorMessageRes(HostedFailure.Http(429, "helper_session_limit", retryable = true)))
        assertEquals(R.string.hosted_extra_help_limit, errorMessageRes(HostedFailure.Http(429, "helper_session_limit", retryable = false)))
        assertEquals(R.string.hosted_balance_checking, errorMessageRes(HostedFailure.Http(503, "provider_reconciliation_required")))
        assertEquals(R.string.hosted_help_funding, errorMessageRes(HostedFailure.Http(503, "helper_session_funding_unavailable")))
        assertEquals(R.string.error_request_refused, errorMessageRes(HostedFailure.Http(502, "helper_output_refused")))
    }

    @Test fun callDurationTrackerStaysMonotonicAcrossReadsAndAdvancesAccurately() {
        var nowMillis = 10_000L
        val tracker = CallDurationTracker { nowMillis }

        assertEquals(null, tracker.activeCallStartedAtMillis)
        assertEquals(0L, tracker.elapsedSeconds())

        assertEquals(0L, tracker.startIfNeeded())
        assertEquals(10_000L, tracker.activeCallStartedAtMillis)

        nowMillis = 10_999L
        assertEquals(0L, tracker.elapsedSeconds())

        nowMillis = 11_000L
        assertEquals(1L, tracker.elapsedSeconds())

        nowMillis = 75_400L
        assertEquals(65L, tracker.elapsedSeconds())

        // Even if the clock returned an earlier value, elapsed seconds stays monotonic.
        nowMillis = 70_000L
        assertEquals(65L, tracker.elapsedSeconds())

        nowMillis = 10_000L + 3_661_000L
        assertEquals(3_661L, tracker.elapsedSeconds())
    }

    @Test fun callDurationTrackerDoesNotResetWhileActive() {
        var nowMillis = 50_000L
        val tracker = CallDurationTracker { nowMillis }

        assertEquals(0L, tracker.startIfNeeded())
        nowMillis = 92_000L
        assertEquals(42L, tracker.elapsedSeconds())

        // Calling startIfNeeded() again (e.g. during a reconnect/re-ready) keeps the original start timestamp.
        assertEquals(42L, tracker.startIfNeeded())
        assertEquals(50_000L, tracker.activeCallStartedAtMillis)

        nowMillis = 100_000L
        assertEquals(50L, tracker.elapsedSeconds())
    }

    @Test fun callDurationTrackerResetsCleanlyBetweenSessions() {
        var nowMillis = 100_000L
        val tracker = CallDurationTracker { nowMillis }

        tracker.startIfNeeded()
        nowMillis = 135_000L
        assertEquals(35L, tracker.elapsedSeconds())

        assertEquals(0L, tracker.reset())
        assertEquals(null, tracker.activeCallStartedAtMillis)
        assertEquals(0L, tracker.elapsedSeconds())

        nowMillis = 200_000L
        assertEquals(0L, tracker.startIfNeeded())
        assertEquals(200_000L, tracker.activeCallStartedAtMillis)
        nowMillis = 207_000L
        assertEquals(7L, tracker.elapsedSeconds())
    }

    @Test fun formatCallElapsedTimeFormatsMinutesHoursAndClampsNegatives() {
        assertEquals("00:00", chat.mural.ui.formatCallElapsedTime(-5L))
        assertEquals("00:00", chat.mural.ui.formatCallElapsedTime(0L))
        assertEquals("01:05", chat.mural.ui.formatCallElapsedTime(65L))
        assertEquals("1:01:01", chat.mural.ui.formatCallElapsedTime(3_661L))
    }

    private fun spokenTurnPcm(durationMs: Int = 800): ByteArray {
        val sampleCount = durationMs * 16
        val bytes = ByteArray(sampleCount * 2)
        for (i in 0 until sampleCount) {
            val sample = (4_000.0 * sin(2.0 * Math.PI * 350.0 * i / 16_000.0)).toInt()
            bytes[i * 2] = (sample and 0xFF).toByte()
            bytes[i * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
        }
        return bytes
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun groqRefinementUpdatesFragmentViaCorrectFragmentAndPostSessionAwaitsInFlightJob() = runTest {
        val session = SessionRecord(id = "11111111-1111-1111-1111-111111111111", languageID = "en").apply {
            append(Fragment(id = "frag-1", speaker = Speaker.user, text = "I goed to work", startMS = 0, endMS = 1_000))
        }
        val passage = session.passages.single()
        session.assessments += Assessment(
            passageID = passage.id,
            revisionKey = passage.revisionKey,
            outcome = Outcome.partial,
            suggestedLevel = 2,
            nextGoal = "past tense",
            capability = "narration",
            words = listOf(WordProposal("go", "go", "goed", EvidenceKind.lapse, 0.9, listOf("frag-1"), "I goed to work", "en")),
            textAssemblyVersion = 2,
        )

        val gate = CompletableDeferred<GroqTranscriptionResult>()
        val transcriber = object : TurnTranscriber {
            override suspend fun transcribe(pcm16: ByteArray, model: GroqWhisperModel, promptHint: String): GroqTranscriptionResult =
                gate.await()
        }
        val coordinator = GroqRefinementCoordinator(this, transcriber)

        val submitted = coordinator.submit(
            sessionID = session.id,
            fragmentId = "frag-1",
            pcm16 = spokenTurnPcm(),
            model = GroqWhisperModel.LARGE_V3,
            promptHint = "Mural",
            onRefined = { _, fragmentId, refinedText ->
                session.correctFragment(fragmentId, refinedText)
            },
        )
        assertTrue(submitted)
        assertTrue(coordinator.hasPending(session.id))

        var awaitedResult: Boolean? = null
        val hangUpJob = launch {
            awaitedResult = coordinator.awaitSession(session.id, timeoutMillis = 4_000L)
        }
        runCurrent()
        assertFalse(hangUpJob.isCompleted)

        gate.complete(GroqTranscriptionResult.Success("I went to work yesterday."))
        runCurrent()
        assertTrue(hangUpJob.isCompleted)
        assertEquals(true, awaitedResult)
        assertFalse(coordinator.hasPending(session.id))

        val updated = session.fragments.single()
        assertEquals("I went to work yesterday.", updated.text)
        assertEquals(1, updated.revision)
        assertEquals(listOf("I goed to work"), updated.previousTexts)
        assertTrue(session.assessments.isEmpty())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun groqRefinementSkipsSilenceHandlesFailuresGracefullyAndRespectsTimeout() = runTest {
        val session = SessionRecord(id = "22222222-2222-2222-2222-222222222222", languageID = "en").apply {
            append(Fragment(id = "frag-1", speaker = Speaker.user, text = "Gemini fallback text", startMS = 0, endMS = 1_000))
        }
        var calls = 0
        val hangingGate = CompletableDeferred<GroqTranscriptionResult>()
        var nextResult: GroqTranscriptionResult = GroqTranscriptionResult.Failure(GroqTranscriptionResult.FailureKind.UNAUTHORIZED, 401)
        val transcriber = object : TurnTranscriber {
            override suspend fun transcribe(pcm16: ByteArray, model: GroqWhisperModel, promptHint: String): GroqTranscriptionResult {
                calls++
                return if (calls == 3) hangingGate.await() else nextResult
            }
        }
        val coordinator = GroqRefinementCoordinator(this, transcriber)

        // Silent clip must be skipped before network
        assertFalse(coordinator.submit(session.id, "frag-1", ByteArray(TurnAudioBuffer.MIN_SPEECH_BYTES), GroqWhisperModel.LARGE_V3, "Mural", { _, _, _ -> }))
        assertEquals(0, calls)

        // 401 triggers auth callback without altering Gemini fragment
        var authErrors = 0
        assertTrue(
            coordinator.submit(
                sessionID = session.id,
                fragmentId = "frag-1",
                pcm16 = spokenTurnPcm(),
                model = GroqWhisperModel.LARGE_V3,
                promptHint = "Mural",
                onRefined = { _, fId, text -> session.correctFragment(fId, text) },
                onAuthFailure = { authErrors++ },
            ),
        )
        runCurrent()
        assertEquals(1, authErrors)
        assertEquals("Gemini fallback text", session.fragments.single().text)

        // 429 fails silently without auth error and preserves Gemini fragment
        nextResult = GroqTranscriptionResult.Failure(GroqTranscriptionResult.FailureKind.RATE_LIMITED, 429)
        assertTrue(
            coordinator.submit(
                sessionID = session.id,
                fragmentId = "frag-1",
                pcm16 = spokenTurnPcm(),
                model = GroqWhisperModel.LARGE_V3,
                promptHint = "Mural",
                onRefined = { _, fId, text -> session.correctFragment(fId, text) },
                onAuthFailure = { authErrors++ },
            ),
        )
        runCurrent()
        assertEquals(1, authErrors)
        assertEquals("Gemini fallback text", session.fragments.single().text)

        // Post-session awaitSession bounds waiting at timeout without cancelling/crashing
        assertTrue(
            coordinator.submit(
                sessionID = session.id,
                fragmentId = "frag-1",
                pcm16 = spokenTurnPcm(),
                model = GroqWhisperModel.LARGE_V3,
                promptHint = "Mural",
                onRefined = { _, fId, text -> session.correctFragment(fId, text) },
            ),
        )
        var completedInTime: Boolean? = null
        val waitJob = launch {
            completedInTime = coordinator.awaitSession(session.id, timeoutMillis = 4_000L)
        }
        runCurrent()
        advanceTimeBy(4_001L)
        runCurrent()
        assertTrue(waitJob.isCompleted)
        assertEquals(false, completedInTime)
        assertEquals("Gemini fallback text", session.fragments.single().text)

        coordinator.cancelSession(session.id)
        assertFalse(coordinator.hasPending(session.id))
    }

    @Test fun pushToTalkControllerStartsMutedHandlesPressReleaseTalkBackAndNeverSticks() {
        val muteCalls = mutableListOf<Boolean>()
        var engagedCalls = 0
        val controller = PushToTalkController(
            onMuteTransport = muteCalls::add,
            onLearnerEngaged = { engagedCalls++ },
        )

        // Cannot speak before session is active.
        assertFalse(controller.setSpeaking(true))
        assertFalse(controller.toggleMute())

        // Connecting with PTT enabled starts muted.
        controller.onSessionStarted(voice = true, pushToTalkEnabled = true)
        assertTrue(controller.isMuted)
        assertTrue(controller.isPushToTalkSession)
        assertFalse(controller.setSpeaking(true))

        // Ready syncs mute(true) with transport.
        controller.onSessionReady(voice = true, pushToTalkEnabled = true)
        assertTrue(controller.isMuted)
        assertEquals(listOf(true), muteCalls)

        // Hold to speak unmutes and marks learner engaged.
        assertTrue(controller.setSpeaking(true))
        assertFalse(controller.isMuted)
        assertEquals(listOf(true, false), muteCalls)
        assertEquals(1, engagedCalls)

        // Duplicate press refreshes engagement without redundant unmute call.
        assertFalse(controller.setSpeaking(true))
        assertEquals(2, engagedCalls)
        assertEquals(listOf(true, false), muteCalls)

        // Release immediately mutes and ignores duplicate releases.
        assertTrue(controller.setSpeaking(false))
        assertTrue(controller.isMuted)
        assertEquals(listOf(true, false, true), muteCalls)
        assertFalse(controller.setSpeaking(false))
        assertEquals(listOf(true, false, true), muteCalls)

        // TalkBack / accessibility toggle flips mute state cleanly.
        assertTrue(controller.toggleMute())
        assertFalse(controller.isMuted)
        assertEquals(3, engagedCalls)
        assertTrue(controller.toggleMute())
        assertTrue(controller.isMuted)

        // Ending while held forces muted and resets cleanly.
        assertTrue(controller.setSpeaking(true))
        assertFalse(controller.isMuted)
        controller.onEnding()
        assertTrue(controller.isMuted)
        assertFalse(controller.setSpeaking(true))
        controller.onFinishedOrReset()
        assertFalse(controller.isMuted)
        assertFalse(controller.isPushToTalkSession)

        // Non-PTT voice session starts unmuted and ignores setSpeaking.
        muteCalls.clear()
        controller.onSessionStarted(voice = true, pushToTalkEnabled = false)
        assertFalse(controller.isMuted)
        controller.onSessionReady(voice = true, pushToTalkEnabled = false)
        assertFalse(controller.isMuted)
        assertTrue(muteCalls.isEmpty())
        assertFalse(controller.setSpeaking(true))
        assertTrue(controller.toggleMute())
        assertTrue(controller.isMuted)
    }

}
