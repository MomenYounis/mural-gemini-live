package chat.mural

import androidx.compose.runtime.MutableState
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import chat.mural.core.*
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SentenceSrsDeviceTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private lateinit var vm: MuralViewModel
    private lateinit var originalArchive: String
    private var originalReports: List<SessionReport> = emptyList()
    private var originalCards: List<SentenceCard> = emptyList()

    @Suppress("UNCHECKED_CAST")
    private fun state(name: String, value: Any?) {
        val field = MuralViewModel::class.java.getDeclaredField(name + "\$delegate").apply { isAccessible = true }
        (field.get(vm) as MutableState<Any?>).value = value
    }

    @Before
    fun prepare() {
        vm = compose.awaitHistoryLoaded()
        compose.runOnIdle {
            originalArchive = ArchiveCodec.encode(vm.archive)
            originalReports = vm.sessionReports
            originalCards = vm.sentenceCards
            vm.updatePreferences(
                vm.archive.preferences.copy(
                    hasOnboarded = true,
                    aiConsentVersion = chat.mural.ui.AI_CONSENT_VERSION,
                    learningLanguageID = "en",
                    meaningLanguage = "Arabic",
                    meaningVisible = true
                )
            )
        }
    }

    @After
    fun restore() {
        if (!::originalArchive.isInitialized) return
        compose.runOnIdle {
            vm.dismissSessionReport()
            state("sessionReports", originalReports)
            state("sentenceCards", originalCards)
            state("archive", ArchiveCodec.decode(originalArchive))
        }
    }

    @Test
    fun postSessionReportAndTopicDecksFlashcardSm2FlowOnDevice() {
        val now = System.currentTimeMillis()
        val report = SessionReport(
            id = "rep-1",
            sessionID = "sess-1",
            languageID = "en",
            date = now,
            topic = "Tech Interviews",
            durationMinutes = 12,
            mistakeCount = 2,
            mistakes = listOf(
                SessionMistake(
                    userSaid = "I have three years experience on backend.",
                    correction = "I have three years of experience in backend development.",
                    arabicMeaning = "لدي ثلاث سنوات من الخبرة في تطوير الواجهات الخلفية.",
                    explanation = "Use 'years of experience in' instead of 'experience on'."
                ),
                SessionMistake(
                    userSaid = "Yesterday I deploy the database migration.",
                    correction = "Yesterday I deployed the database migration.",
                    arabicMeaning = "بالأمس قمت بنشر ترحيل قاعدة البيانات.",
                    explanation = "Use past tense 'deployed' for completed actions yesterday."
                )
            )
        )
        val cards = Sm2Calculator.mergeFromReport(emptyList(), report, nowMillis = now - 10_000L)

        compose.runOnIdle {
            state("sessionReports", listOf(report))
            state("sentenceCards", cards)
            vm.openSessionReport(report)
        }

        // 1. Verify Post-Session Report Dialog on device
        compose.onNodeWithTag("post-session-report-dialog").assertIsDisplayed()
        compose.onNodeWithText("Tech Interviews").assertIsDisplayed()
        compose.onNodeWithTag("mistake-user-said-1", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("mistake-correction-1", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("mistake-tts-1").performClick()
        compose.onNodeWithTag("close-session-report").performClick()
        compose.waitUntil(timeoutMillis = 5_000) { !vm.showSessionReport }
        compose.waitForIdle()

        // 2. Navigate to Words / Sentence Decks tab
        compose.onNodeWithTag("tab-words").performClick()
        compose.onNodeWithTag("words-screen").assertIsDisplayed()
        compose.onNodeWithTag("srs-summary-card").assertIsDisplayed()
        compose.onNodeWithTag("topic-deck-Tech Interviews").assertIsDisplayed()
        compose.onNodeWithTag("deck-due-count-Tech Interviews", useUnmergedTree = true)
            .assertTextEquals("2 due")

        // 3. Open Topic Deck -> Flip Flashcard -> Trigger Local TTS -> Rate SM-2 Good
        compose.onNodeWithTag("topic-deck-Tech Interviews").performClick()
        compose.onNodeWithTag("sentence-flashcards-modal").assertIsDisplayed()
        compose.onNodeWithTag("sentence-card-front", useUnmergedTree = true).assertIsDisplayed()

        // Flip card to Back
        compose.onNodeWithTag("flip-sentence-card").performClick()
        compose.onNodeWithTag("sentence-card-back", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("sentence-card-explanation", useUnmergedTree = true).assertIsDisplayed()

        // Trigger Local On-Device TTS on Back of Flashcard
        compose.onNodeWithTag("sentence-card-tts").performClick()

        // Rate card as Good using SM-2 button
        compose.onNodeWithTag("sm2-rate-good").performClick()

        // Verify 1st card was updated in ViewModel with SM-2 interval = 1 day, repetitionCount = 1
        compose.runOnIdle {
            val reviewedCount = vm.sentenceCards.count { it.repetitionCount == 1 && it.intervalDays == 1 }
            assertEquals(1, reviewedCount)
        }

        // Flip 2nd card and rate Easy
        compose.onNodeWithTag("flip-sentence-card").performClick()
        compose.onNodeWithTag("sm2-rate-easy").performClick()

        compose.runOnIdle {
            val easyCard = vm.sentenceCards.firstOrNull { it.intervalDays == 4 }
            assertTrue(easyCard != null)
            assertEquals(2.65f, easyCard!!.easeFactor, 0.01f)
        }
    }

    @Test
    fun analyzeSessionTranscriptExtractsMistakesCreatesDecksAndInjectsDueContextOnDevice() {
        val originalTeachingField = MuralViewModel::class.java.getDeclaredField("geminiTeaching").apply { isAccessible = true }
        val originalTeaching = originalTeachingField.get(vm)
        val personalVendorsField = MuralViewModel::class.java.getDeclaredField("personalVendors").apply { isAccessible = true }
        val originalPersonalVendors = personalVendorsField.get(vm)
        val originalHasKeyField = MuralViewModel::class.java.getDeclaredField("hasKey\$delegate").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val hasKeyState = originalHasKeyField.get(vm) as MutableState<Boolean>
        val priorHasKey = hasKeyState.value

        try {
            val fakeClient = object : chat.mural.network.TeachingClient {
                override suspend fun respond(
                    instructions: String,
                    input: String,
                    schema: kotlinx.serialization.json.JsonObject?,
                    search: Boolean,
                    purpose: chat.mural.network.HelperPurpose?
                ): chat.mural.network.APIResult {
                    return chat.mural.network.APIResult(
                        text = """
                            {
                              "topic": "Daily Routine",
                              "mistakes": [
                                {
                                  "user_said": "Every morning I am drink coffee before study.",
                                  "correction": "Every morning I drink coffee before studying.",
                                  "arabic_meaning": "كل صباح أشرب القهوة قبل المذاكرة.",
                                  "explanation": "Use simple present 'drink' for habits, and a gerund ('studying') after 'before'."
                                }
                              ]
                            }
                        """.trimIndent(),
                        sources = emptyList(),
                        usage = chat.mural.network.APIUsage(120, 80, 0)
                    )
                }

                override suspend fun streamMeaning(
                    instructions: String,
                    input: String,
                    onText: (String) -> Unit
                ): chat.mural.network.APIResult = respond(instructions, input, null, false, chat.mural.network.HelperPurpose.MEANING)
            }

            val sampleSession = SessionRecord(languageID = "en", startedAt = nowSeconds() - 300.0, endedAt = nowSeconds()).apply {
                append(Fragment(speaker = Speaker.user, text = "Every morning I am drink coffee before study.", startMS = 0, endMS = 2000))
                append(Fragment(speaker = Speaker.assistant, text = "That sounds like a great morning routine!", startMS = 2500, endMS = 5000))
            }

            compose.runOnIdle {
                originalTeachingField.set(vm, fakeClient)
                personalVendorsField.set(vm, mapOf(sampleSession.id to AIVendor.GEMINI))
                hasKeyState.value = true
                state("sessionReports", emptyList<SessionReport>())
                state("sentenceCards", emptyList<SentenceCard>())
                vm.analyzeSessionTranscript(sampleSession, openWhenReady = true)
            }

            compose.waitUntil(timeoutMillis = 5_000) {
                !vm.analyzingSessionReport && vm.sessionReports.isNotEmpty()
            }

            // Verify Post-Session Report dialog automatically opened with extracted topic & mistake
            compose.onNodeWithTag("post-session-report-dialog").assertIsDisplayed()
            compose.onNodeWithText("Daily Routine").assertIsDisplayed()
            compose.onNodeWithTag("mistake-user-said-1", useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithTag("mistake-correction-1", useUnmergedTree = true).assertIsDisplayed()

            compose.runOnIdle {
                assertEquals(1, vm.sentenceCards.size)
                assertEquals("كل صباح أشرب القهوة قبل المذاكرة.", vm.sentenceCards.single().frontText)
                assertEquals("Every morning I drink coffee before studying.", vm.sentenceCards.single().backText)
                assertEquals(1, vm.dueSentenceCards.size)

                // Verify Smart Context Injection into Gemini Live system_instruction
                val livePrompt = TeachingPolicy.voice(
                    language = vm.language,
                    learner = vm.learner,
                    theme = null,
                    interests = "",
                    meaningLanguage = "Arabic",
                    dueSentenceCards = vm.dueSentenceCards
                )
                assertTrue(livePrompt.contains("Every morning I drink coffee before studying."))
            }
        } finally {
            compose.runOnIdle {
                originalTeachingField.set(vm, originalTeaching)
                personalVendorsField.set(vm, originalPersonalVendors)
                hasKeyState.value = priorHasKey
            }
        }
    }
}
