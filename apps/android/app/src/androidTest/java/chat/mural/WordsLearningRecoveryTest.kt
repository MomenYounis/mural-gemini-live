package chat.mural

import android.content.Context
import androidx.compose.runtime.MutableState
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import chat.mural.core.*
import chat.mural.network.*
import java.security.KeyStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Runs the real retry coordinator and persistence with an in-process teaching fake, without sockets. */
@RunWith(AndroidJUnit4::class)
class WordsLearningRecoveryTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var vm: MuralViewModel
    private lateinit var originalSnapshot: LearningSnapshot
    private lateinit var originalTeaching: TeachingClient
    private lateinit var originalVendors: Map<String, AIVendor>
    private lateinit var originalVendor: AIVendor
    private lateinit var originalFunding: ConversationProvider
    private var originalHasKey = false
    private var originalSession: SessionRecord? = null
    private var originalState = "idle"
    private var originalNotice: String? = null
    private var originalErrors = emptyMap<String, String>()
    private var originalRetryAfter = emptyMap<String, Double>()
    private var originalBatchCompleted = 0
    private var originalBatchTotal = 0
    private var credentialPreferences: Map<String, *> = emptyMap<String, Any?>()
    private var hadGeminiAlias = false
    private val fake = OfflineTeaching()
    private lateinit var record: SessionRecord

    private fun field(name: String) = MuralViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }
    @Suppress("UNCHECKED_CAST") private fun state(name: String, value: Any?) {
        (field(name + "\$delegate").get(vm) as MutableState<Any?>).value = value
    }
    private fun call(name: String) = MuralViewModel::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(vm)
    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun quantityText(resource: Int, count: Int) = compose.activity.resources.getQuantityString(resource, count, count)

    private fun installIndependentPassages(forms: List<String>): List<String> {
        val ids = forms.map { "learn-batch-$it" }
        compose.runOnIdle {
            record = SessionRecord(languageID = "en", endedAt = nowSeconds()).apply {
                forms.forEachIndexed { index, form ->
                    val id = ids[index]
                    val quote = "I like $form."
                    fake.evidence[id] = OfflineTeaching.Evidence(form, quote)
                    append(Fragment(id = id, speaker = Speaker.user, text = quote,
                        startMS = index * 5_000, endMS = index * 5_000 + 1_000))
                    if (index < forms.lastIndex) append(Fragment(id = "batch-tutor-$index", speaker = Speaker.assistant,
                        text = "Tell me more.", startMS = index * 5_000 + 2_000, endMS = index * 5_000 + 3_000))
                }
            }
            field("personalVendors").set(vm, mapOf(record.id to AIVendor.GEMINI))
            state("archive", vm.archive.copy(sessions = mutableListOf(record)))
            assertEquals(forms.size, vm.pendingLearningReviewCount)
            assertNull(vm.learningReviewError)
        }
        return ids
    }

    @Before fun prepare() {
        assertEquals("chat.mural.android.uitest", compose.activity.packageName)
        vm = compose.awaitHistoryLoaded()
        compose.runOnIdle {
            assertFalse("Recovery fixture requires an idle isolated app", vm.isRunning)
            call("cancelLearningReviews")
            originalSnapshot = call("persistenceSnapshot") as LearningSnapshot
            originalTeaching = field("geminiTeaching").get(vm) as TeachingClient
            @Suppress("UNCHECKED_CAST")
            originalVendors = field("personalVendors").get(vm) as Map<String, AIVendor>
            originalVendor = vm.aiVendor; originalFunding = vm.conversationProvider
            originalHasKey = vm.hasKey; originalNotice = vm.notice
            originalSession = vm.session; originalState = vm.state
            originalBatchCompleted = vm.learningReviewBatchCompleted
            originalBatchTotal = vm.learningReviewBatchTotal
            @Suppress("UNCHECKED_CAST")
            originalErrors = (field("learningReviewErrors\$delegate").get(vm) as MutableState<Map<String, String>>).value
            @Suppress("UNCHECKED_CAST")
            originalRetryAfter = (field("assessmentRetryAfter").get(vm) as Map<String, Double>).toMap()
            credentialPreferences = compose.activity.getSharedPreferences("mural_gemini_credentials", Context.MODE_PRIVATE).all.toMap()
            hadGeminiAlias = keyStore().containsAlias("chat.mural.gemini.aes")
            // Install the fake before any consent/key operation can consider recovery.
            field("geminiTeaching").set(vm, fake)
            field("finalAssessmentTickets").set(vm, emptyList<FinalAssessmentTicket>())
            state("archive", Archive(preferences = Preferences(learningLanguageID = "en", hasOnboarded = true,
                aiConsentVersion = chat.mural.ui.AI_CONSENT_VERSION)))
            state("session", null); state("state", "idle")
            state("aiVendor", AIVendor.GEMINI); state("conversationProvider", ConversationProvider.PERSONAL_KEY)
            state("learningReviewErrors", emptyMap<String, String>())
            vm.saveKey("offline-uitest-gemini-placeholder-never-sent", useAfterSave = false)
            assertTrue("The dummy key must use Android's normal isolated credential store", vm.hasKey)
            record = SessionRecord(languageID = "en", endedAt = nowSeconds()).apply {
                append(Fragment(id = "learn-tea", speaker = Speaker.user, text = "I like tea.", startMS = 0, endMS = 1_000))
                append(Fragment(id = "tutor-separator", speaker = Speaker.assistant, text = "Tell me more.", startMS = 2_000, endMS = 3_000))
                append(Fragment(id = "learn-coffee", speaker = Speaker.user, text = "I enjoy coffee.", startMS = 5_000, endMS = 6_000, typed = true))
            }
            field("personalVendors").set(vm, mapOf(record.id to AIVendor.GEMINI))
            state("archive", vm.archive.copy(sessions = mutableListOf(record)))
            assertEquals(2, vm.pendingLearningReviewCount)
            assertTrue(vm.canRetryLearningReview)
        }
        compose.onNodeWithTag("tab-words").performClick()
    }

    /** Waits for the actual archive writer before restoring the isolated app's original state. */
    private fun flushArchive() = runBlocking {
        withContext(Dispatchers.Main) {
            suspendCoroutine<Unit> { continuation ->
                try {
                    val result = MuralViewModel::class.java.getDeclaredMethod("persistAndWait", Continuation::class.java)
                        .apply { isAccessible = true }.invoke(vm, continuation)
                    if (result !== COROUTINE_SUSPENDED) continuation.resume(Unit)
                } catch (failure: Exception) { continuation.resumeWithException(failure) }
            }
        }
    }

    @After fun restore() {
        if (!::vm.isInitialized || !::originalSnapshot.isInitialized) return
        compose.runOnIdle {
            call("cancelLearningReviews")
            state("session", originalSession); state("state", originalState)
            field("geminiTeaching").set(vm, originalTeaching)
            field("personalVendors").set(vm, originalVendors)
            field("finalAssessmentTickets").set(vm, originalSnapshot.finalAssessments)
            state("aiVendor", originalVendor); state("conversationProvider", originalFunding)
            state("hasKey", originalHasKey); state("notice", originalNotice)
            state("learningReviewErrors", originalErrors)
            state("learningReviewBatchCompleted", originalBatchCompleted)
            state("learningReviewBatchTotal", originalBatchTotal)
            @Suppress("UNCHECKED_CAST")
            (field("assessmentRetryAfter").get(vm) as MutableMap<String, Double>).apply {
                clear(); putAll(originalRetryAfter)
            }
            state("archive", originalSnapshot.archive)
            val editor = compose.activity.getSharedPreferences("mural_gemini_credentials", Context.MODE_PRIVATE).edit().clear()
            credentialPreferences.forEach { (name, value) ->
                // This store contains only ciphertext and IV strings; no credential is decrypted here.
                check(value is String); editor.putString(name, value)
            }
            assertTrue(editor.commit())
            if (!hadGeminiAlias) keyStore().deleteEntry("chat.mural.gemini.aes")
        }
        flushArchive()
    }

    @Test fun failedReviewKeepsEvidenceAndExplicitRetryPopulatesWordsFromBothSavedPassages() {
        compose.onNodeWithTag("words-learning-review").assertIsDisplayed()
        compose.onNodeWithTag("words-retry-learning-review").performClick()
        compose.waitUntil(timeoutMillis = 15_000) { compose.runOnUiThread { !vm.reviewingLearning && vm.learningReviewError != null } }
        lateinit var safeError: String
        compose.runOnIdle {
            safeError = requireNotNull(vm.learningReviewError)
            assertEquals(1, fake.calls)
            assertEquals(listOf("learn-tea"), fake.requestedIDs)
            assertEquals(2, vm.pendingLearningReviewCount)
            assertEquals(0, vm.learningReviewBatchCompleted)
            assertEquals(2, vm.learningReviewBatchTotal)
            assertTrue(vm.learner.words.isEmpty())
            val saved = vm.archive.sessions.single()
            assertTrue(saved.assessments.isEmpty())
            assertEquals(listOf("I like tea.", "Tell me more.", "I enjoy coffee."), saved.fragments.map { it.text })
            assertTrue(vm.canRetryLearningReview)
        }
        compose.onNodeWithText(safeError).assertIsDisplayed()
        compose.onNodeWithText(quantityText(R.plurals.words_review_pending_replies, 2)).assertIsDisplayed()
        compose.onNodeWithTag("words-retry-learning-review")
            .assertTextContains(quantityText(R.plurals.words_review_retry_remaining, 2))
        compose.onNodeWithText(quantityText(R.plurals.words_review_remaining, 2)).assertDoesNotExist()
        compose.onNodeWithTag("words-retry-learning-review").performClick()
        compose.waitUntil(timeoutMillis = 15_000) { compose.runOnUiThread { !vm.reviewingLearning && vm.pendingLearningReviewCount == 0 } }
        compose.runOnIdle {
            assertEquals(3, fake.calls)
            assertEquals(listOf("learn-tea", "learn-coffee", "learn-tea"), fake.requestedIDs)
            assertNull(vm.learningReviewError)
            assertEquals(2, vm.archive.sessions.single().assessments.size)
            assertEquals(setOf("tea", "coffee"), vm.learner.words.map { it.lemma }.toSet())
            assertEquals(1, vm.learner.words.single { it.lemma == "tea" }.independentCount)
            assertEquals(0, vm.learner.words.single { it.lemma == "coffee" }.independentCount)
            val exported = vm.exportData()
            assertFalse(exported.contains("androidFinalAssessments"))
            assertFalse(exported.contains("offline-uitest-gemini"))
            assertEquals(2, ArchiveCodec.decode(exported).sessions.single().assessments.size)
        }
        compose.onNodeWithTag("words-learning-review").assertDoesNotExist()
        compose.onNodeWithText("tea", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("coffee", useUnmergedTree = true).assertExists()
    }

    @Test fun malformedReviewKeepsItsErrorWhileLaterPassageSucceedsAndCanBeRetried() {
        compose.runOnIdle { fake.firstFailure = OfflineTeaching.FirstFailure.MALFORMED_JSON }
        compose.onNodeWithTag("words-retry-learning-review").performClick()
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.runOnUiThread { !vm.reviewingLearning && fake.calls > 0 }
        }
        lateinit var invalidError: String
        compose.runOnIdle {
            invalidError = requireNotNull(vm.learningReviewError)
            assertEquals(compose.activity.getString(R.string.words_review_invalid), invalidError)
            assertEquals(listOf("learn-tea", "learn-coffee"), fake.requestedIDs)
            assertEquals(1, vm.pendingLearningReviewCount)
            assertEquals(setOf("coffee"), vm.learner.words.map { it.lemma }.toSet())
            val saved = vm.archive.sessions.single()
            assertEquals(1, saved.assessments.size)
            assertEquals(listOf("I like tea.", "Tell me more.", "I enjoy coffee."), saved.fragments.map { it.text })
            assertTrue(vm.canRetryLearningReview)
        }
        compose.onNodeWithText(invalidError).assertIsDisplayed()
        compose.onNodeWithTag("words-retry-learning-review").performClick()
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.runOnUiThread { !vm.reviewingLearning && vm.pendingLearningReviewCount == 0 }
        }
        compose.runOnIdle {
            assertEquals(listOf("learn-tea", "learn-coffee", "learn-tea"), fake.requestedIDs)
            assertNull(vm.learningReviewError)
            assertEquals(2, vm.archive.sessions.single().assessments.size)
            assertEquals(setOf("tea", "coffee"), vm.learner.words.map { it.lemma }.toSet())
        }
        compose.onNodeWithTag("words-learning-review").assertDoesNotExist()
    }

    @Test fun unavailableProviderStopsBatchBeforeRequestingLaterSavedPassage() {
        compose.runOnIdle { fake.firstFailure = OfflineTeaching.FirstFailure.UNAVAILABLE_503 }
        compose.onNodeWithTag("words-retry-learning-review").performClick()
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.runOnUiThread { !vm.reviewingLearning && vm.learningReviewError != null }
        }
        lateinit var safeError: String
        compose.runOnIdle {
            assertEquals(listOf("learn-tea"), fake.requestedIDs)
            assertEquals(1, fake.calls)
            assertEquals(2, vm.pendingLearningReviewCount)
            assertEquals(0, vm.learningReviewBatchCompleted)
            assertEquals(2, vm.learningReviewBatchTotal)
            assertTrue(vm.learner.words.isEmpty())
            val saved = vm.archive.sessions.single()
            assertTrue(saved.assessments.isEmpty())
            assertEquals(listOf("I like tea.", "Tell me more.", "I enjoy coffee."), saved.fragments.map { it.text })
            safeError = requireNotNull(vm.learningReviewError)
            assertTrue(vm.canRetryLearningReview)
        }
        compose.onNodeWithText(safeError).assertIsDisplayed()
        compose.onNodeWithText(quantityText(R.plurals.words_review_pending_replies, 2)).assertIsDisplayed()
        compose.onNodeWithTag("words-retry-learning-review")
            .assertTextContains(quantityText(R.plurals.words_review_retry_remaining, 2))
        compose.onNodeWithText(quantityText(R.plurals.words_review_remaining, 2)).assertDoesNotExist()
    }

    @Test fun successfulBoundedBatchShowsOneRemainingPassageAndNextTapFinishesIt() {
        val responseGate = CompletableDeferred<Unit>()
        val forms = listOf("tea", "coffee", "juice", "milk", "water", "soda")
        val expectedIDs = installIndependentPassages(forms)
        compose.runOnIdle {
            fake.firstFailure = OfflineTeaching.FirstFailure.NONE
            fake.firstResponseGate = responseGate
        }
        compose.onNodeWithText(quantityText(R.plurals.words_review_pending_replies, 6)).assertIsDisplayed()
        compose.onNodeWithTag("words-retry-learning-review")
            .assertTextContains(quantityText(R.plurals.words_review_next, 5))
        compose.onNodeWithTag("words-retry-learning-review").performClick()
        try {
            compose.waitUntil(timeoutMillis = 15_000) { compose.runOnUiThread { fake.calls == 1 } }
            compose.runOnIdle {
                assertTrue(vm.reviewingLearning)
                assertEquals(0, vm.learningReviewBatchCompleted)
                assertEquals(5, vm.learningReviewBatchTotal)
                assertEquals(6, vm.pendingLearningReviewCount)
            }
            compose.onNodeWithText(compose.activity.resources.getQuantityString(R.plurals.words_review_batch_progress, 5, 0, 5)).assertIsDisplayed()
            compose.onNodeWithTag("words-retry-learning-review").assertIsNotEnabled()
        } finally { responseGate.complete(Unit) }
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.runOnUiThread { !vm.reviewingLearning && vm.pendingLearningReviewCount == 1 }
        }
        compose.runOnIdle {
            assertEquals(expectedIDs.take(5), fake.requestedIDs)
            assertEquals(5, vm.learningReviewBatchCompleted)
            assertEquals(5, vm.learningReviewBatchTotal)
            assertEquals(5, vm.archive.sessions.single().assessments.size)
            assertEquals(forms.take(5).toSet(), vm.learner.words.map { it.lemma }.toSet())
            assertNull(vm.learningReviewError)
            assertTrue(vm.canRetryLearningReview)
        }
        compose.onNodeWithTag("words-learning-review").assertIsDisplayed()
        compose.onNodeWithText(quantityText(R.plurals.words_review_pending_replies, 1)).assertIsDisplayed()
        compose.onNodeWithTag("words-retry-learning-review")
            .assertTextContains(quantityText(R.plurals.words_review_remaining, 1))
        compose.onNodeWithText(quantityText(R.plurals.words_review_retry_remaining, 1)).assertDoesNotExist()
        compose.onNodeWithTag("words-retry-learning-review").assertIsEnabled().performClick()
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.runOnUiThread { !vm.reviewingLearning && vm.pendingLearningReviewCount == 0 }
        }
        compose.runOnIdle {
            assertEquals(expectedIDs, fake.requestedIDs)
            assertEquals(1, vm.learningReviewBatchCompleted)
            assertEquals(1, vm.learningReviewBatchTotal)
            assertEquals(6, vm.archive.sessions.single().assessments.size)
            assertEquals(forms.toSet(), vm.learner.words.map { it.lemma }.toSet())
            assertTrue(vm.learner.words.all { it.independentCount == 1 })
            assertNull(vm.learningReviewError)
        }
        compose.onNodeWithTag("words-learning-review").assertDoesNotExist()
    }

    @Test fun repeatedInvalidFirstBatchDoesNotStarveTheSixthSavedPassage() {
        val forms = listOf("tea", "coffee", "juice", "milk", "water", "soda")
        val expectedIDs = installIndependentPassages(forms)
        compose.runOnIdle {
            fake.firstFailure = OfflineTeaching.FirstFailure.NONE
            fake.badEvidenceIDs = expectedIDs.take(5).toSet()
        }
        compose.onNodeWithTag("words-retry-learning-review").performClick()
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.runOnUiThread { !vm.reviewingLearning && fake.calls == 5 }
        }
        lateinit var invalidError: String
        compose.runOnIdle {
            invalidError = compose.activity.getString(R.string.words_review_invalid)
            assertEquals(expectedIDs.take(5), fake.requestedIDs)
            assertEquals(0, vm.learningReviewBatchCompleted)
            assertEquals(5, vm.learningReviewBatchTotal)
            assertEquals(6, vm.pendingLearningReviewCount)
            assertEquals(invalidError, vm.learningReviewError)
            assertTrue(vm.archive.sessions.single().assessments.isEmpty())
            assertTrue(vm.learner.words.isEmpty())
        }
        compose.onNodeWithText(invalidError).assertIsDisplayed()
        compose.onNodeWithTag("words-retry-learning-review")
            .assertTextContains(quantityText(R.plurals.words_review_retry_next, 5)).performClick()
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.runOnUiThread { !vm.reviewingLearning && fake.calls == 10 }
        }
        compose.runOnIdle {
            assertEquals("The previously unattempted sixth passage must lead the next batch", expectedIDs.last(), fake.requestedIDs[5])
            assertEquals(1, vm.learningReviewBatchCompleted)
            assertEquals(5, vm.learningReviewBatchTotal)
            assertEquals(5, vm.pendingLearningReviewCount)
            assertEquals(invalidError, vm.learningReviewError)
            assertEquals(1, vm.archive.sessions.single().assessments.size)
            assertEquals(setOf("soda"), vm.learner.words.map { it.lemma }.toSet())
            assertEquals(1, vm.learner.words.single().independentCount)
        }
        compose.onNodeWithText(invalidError).assertIsDisplayed()
        compose.onNodeWithText(quantityText(R.plurals.words_review_pending_replies, 5)).assertIsDisplayed()
        compose.onNodeWithTag("words-retry-learning-review")
            .assertTextContains(quantityText(R.plurals.words_review_retry_remaining, 5)).assertIsEnabled()
    }

    private class OfflineTeaching : TeachingClient {
        data class Evidence(val form: String, val quote: String)
        enum class FirstFailure { NONE, QUOTA_429, UNAVAILABLE_503, MALFORMED_JSON }
        var firstFailure = FirstFailure.QUOTA_429
        var firstResponseGate: CompletableDeferred<Unit>? = null
        var badEvidenceIDs = emptySet<String>()
        var calls = 0
        val requestedIDs = mutableListOf<String>()
        val evidence = mutableMapOf(
            "learn-tea" to Evidence("tea", "I like tea."),
            "learn-coffee" to Evidence("coffee", "I enjoy coffee."),
        )
        override suspend fun respond(instructions: String, input: String, schema: JsonObject?, search: Boolean,
            purpose: HelperPurpose?): APIResult {
            check(purpose == HelperPurpose.ASSESSMENT && schema != null && !search)
            calls++
            val target = input.substringAfter("\nTARGET (assess only this passage)\n", "")
            val line = target.lineSequence().single { it.startsWith("id=") }
            val id = line.substringAfter("id=").substringBefore(',')
            requestedIDs += id
            val quote = line.substringAfter(": ")
            val expected = evidence[id] ?: error("Unexpected fixture evidence")
            val form = expected.form
            check(quote == expected.quote && quote.contains(form))
            if (calls == 1) firstResponseGate?.await()
            if (calls == 1) when (firstFailure) {
                FirstFailure.NONE -> Unit
                FirstFailure.QUOTA_429 -> throw GeminiException.Http(429, "RESOURCE_EXHAUSTED")
                FirstFailure.UNAVAILABLE_503 -> throw GeminiException.Http(503, "UNAVAILABLE")
                FirstFailure.MALFORMED_JSON -> return APIResult("{", emptyList(), APIUsage())
            }
            if (id in badEvidenceIDs) return APIResult("{", emptyList(), APIUsage())
            val payload = buildJsonObject {
                put("outcome", "success"); put("suggestedLevel", 1)
                put("nextGoal", "Share another preference."); put("capability", "Expresses a drink preference")
                putJsonArray("words") { add(buildJsonObject {
                    put("lemma", form); put("meaning", "$form drink")
                    put("form", form); put("quote", quote); put("language", "en")
                    put("kind", "independent"); put("confidence", .99)
                    putJsonArray("sourceIDs") { add(id) }
                }) }
            }
            return APIResult(payload.toString(), emptyList(), APIUsage())
        }
    }
}
