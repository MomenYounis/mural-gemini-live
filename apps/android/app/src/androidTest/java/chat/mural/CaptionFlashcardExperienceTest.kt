package chat.mural

import android.graphics.Bitmap
import java.io.File
import androidx.compose.runtime.MutableState
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import chat.mural.core.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

internal fun SemanticsNodeInteraction.revealPageViewport(): SemanticsNodeInteraction {
    var ancestor = fetchSemanticsNode().parent
    while (ancestor != null) {
        if (ancestor.config.contains(androidx.compose.ui.semantics.SemanticsActions.ScrollBy)) {
            performScrollTo()
            break
        }
        ancestor = ancestor.parent
    }
    return this
}

@RunWith(AndroidJUnit4::class)
class CaptionFlashcardExperienceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var vm: MuralViewModel
    private lateinit var original: String
    private var originalSentenceCards: List<SentenceCard> = emptyList()
    @Before fun prepare() {
        vm = compose.awaitHistoryLoaded()
        compose.runOnIdle {
            original = ArchiveCodec.encode(vm.archive)
            originalSentenceCards = vm.sentenceCards
            vm.updatePreferences(vm.archive.preferences.copy(hasOnboarded = true, aiConsentVersion = chat.mural.ui.AI_CONSENT_VERSION, learningLanguageID = "ru", meaningVisible = true))
        }
    }
    @Suppress("UNCHECKED_CAST") private fun state(name: String, value: Any?) {
        val field = MuralViewModel::class.java.getDeclaredField(name + "\$delegate").apply { isAccessible = true }
        (field.get(vm) as MutableState<Any?>).value = value
    }
    @After fun restore() {
        if (!::original.isInitialized) return
        compose.runOnIdle {
            state("session", null); state("state", "idle"); state("meaning", "")
            state("sentenceCards", originalSentenceCards)
            state("archive", ArchiveCodec.decode(original))
        }
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val folder = File(instrumentation.targetContext.filesDir, "feature-review").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use { instrumentation.uiAutomation.takeScreenshot()!!.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun legacyWordSample(id: String, lemma: String, meaning: String, day: Int = 0): SessionRecord {
        val date = nowSeconds() - day * 86400
        val record = SessionRecord(languageID = id, startedAt = date)
        record.append(Fragment(id = "word-$id-$lemma-$day", speaker = Speaker.user, text = lemma, startMS = 0, endMS = 1000))
        val p = record.passages.single()
        record.assessments += Assessment(p.id, p.revisionKey, Outcome.success, 1, "", "",
            listOf(WordProposal(lemma, meaning, lemma, EvidenceKind.independent, .95, p.fragments.map { it.id }, lemma, id)), createdAt = date)
        return record
    }
    private fun seedSentenceCards() {
        val now = System.currentTimeMillis()
        compose.runOnIdle {
            state(
                "sentenceCards",
                listOf(
                    SentenceCard(
                        id = "ru-card-1",
                        topic = "Reading",
                        languageID = "ru",
                        sourceSessionID = "sess-ru-1",
                        sourceSessionIDs = listOf("sess-ru-1"),
                        frontText = "كتاب ممتع",
                        backText = "Я люблю читать книги.",
                        explanation = "Use the infinitive 'читать' after 'люблю'.",
                        userOriginalMistake = "Я люблю читаю книги.",
                        nextReviewDate = now - 1_000L,
                    ),
                    SentenceCard(
                        id = "ru-card-2",
                        topic = "Cafe",
                        languageID = "ru",
                        sourceSessionID = "sess-ru-2",
                        sourceSessionIDs = listOf("sess-ru-2"),
                        frontText = "كوب قهوة",
                        backText = "Можно мне чашку кофе?",
                        explanation = "Polite request phrasing at a cafe.",
                        userOriginalMistake = "Дай мне кофе.",
                        nextReviewDate = now - 500L,
                    ),
                    SentenceCard(
                        id = "nl-card-1",
                        topic = "Cycling",
                        languageID = "nl",
                        sourceSessionID = "sess-nl-1",
                        sourceSessionIDs = listOf("sess-nl-1"),
                        frontText = "دراجة",
                        backText = "Ik fiets naar mijn werk.",
                        explanation = "Dutch phrasing for cycling to work.",
                        userOriginalMistake = "",
                        nextReviewDate = now - 1_000L,
                    ),
                ),
            )
        }
        compose.onNodeWithTag("tab-words").performClick()
    }
    private fun presentForRecording() {
        if (androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("experienceRecording") == "true") {
            compose.waitForIdle()
            Thread.sleep(1500)
        }
    }
    @Test fun flashcardsTriggerAlignsWithHeadingAndPracticeUsesSentenceFlashcards() {
        seedSentenceCards()
        val title = compose.onNodeWithText("Your words.").fetchSemanticsNode().boundsInRoot
        val trigger = compose.onNodeWithTag("open-flashcards").fetchSemanticsNode().boundsInRoot
        assertTrue(trigger.left >= title.right)
        assertEquals(title.center.y, trigger.center.y, 5f)
        compose.onNodeWithTag("open-flashcards").performClick()
        compose.onNodeWithTag("sentence-flashcards-modal").assertIsDisplayed()
        compose.onNodeWithTag("sentence-card-front", useUnmergedTree = true).assertIsDisplayed()
        presentForRecording()
        compose.onNodeWithTag("flip-sentence-card").performClick()
        compose.onNodeWithTag("sentence-card-back", useUnmergedTree = true).assertIsDisplayed()
        capture("flashcards-revealed")
        compose.onNodeWithTag("close-sentence-flashcards").performClick()
        compose.onNodeWithTag("words-screen").assertIsDisplayed()
    }
    @Test fun emptySentenceCardsKeepPracticeDisabledEvenWithLegacyWordsAndSearchDoesNotShrinkDeck() {
        compose.runOnIdle {
            state("sentenceCards", emptyList<SentenceCard>())
            state("archive", vm.archive.copy(sessions = mutableListOf(legacyWordSample("ru", "книга", "book"))))
        }
        compose.onNodeWithTag("tab-words").performClick()
        compose.onNodeWithTag("open-flashcards").assertIsNotEnabled()
        compose.onNodeWithTag("words-empty-state").assertIsDisplayed()
        seedSentenceCards()
        compose.onNodeWithTag("open-flashcards").assertIsEnabled()
        compose.onNodeWithTag("topic-deck-Cycling").assertDoesNotExist()
        compose.onNode(hasSetTextAction()).performTextInput("кофе")
        compose.onNodeWithTag("topic-deck-Reading").assertDoesNotExist()
        compose.onNodeWithTag("topic-deck-Cafe").assertIsDisplayed()
        compose.onNodeWithTag("open-flashcards").performClick()
        compose.onNodeWithText("Card 1 of 2").assertIsDisplayed()
    }
    @Test fun longExplanationScrollsInsideSentenceCardWithoutClosingModal() {
        compose.runOnIdle {
            val longExplanation = "Used when a person wants to do something now, such as meeting a friend for coffee. ".repeat(4)
            state(
                "sentenceCards",
                listOf(
                    SentenceCard(
                        id = "ru-long-1",
                        topic = "Cafe",
                        languageID = "ru",
                        frontText = "أريد شرب القهوة مع صديقي الآن.",
                        backText = "Я хочу выпить кофе с другом.",
                        explanation = longExplanation,
                        userOriginalMistake = "Я хочу пить кофе.",
                        nextReviewDate = System.currentTimeMillis() - 1000L,
                    ),
                ),
            )
        }
        compose.onNodeWithTag("tab-words").performClick()
        compose.onNodeWithTag("open-flashcards").performClick()
        compose.onNodeWithTag("flip-sentence-card").performClick()
        compose.onNodeWithTag("sentence-card-explanation", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("sentence-flashcard-face").performTouchInput { swipeUp() }
        compose.onNodeWithTag("sentence-flashcards-modal").assertIsDisplayed()
        compose.onNodeWithTag("close-sentence-flashcards").assertIsDisplayed()
    }
    private fun offset(tag: String): Float = compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
    @Test fun targetAndMeaningFollowPauseForStreamingAndResumeWithNextCaption() {
        val text = "Я люблю читать книги и разговаривать за чашкой кофе. ".repeat(10)
        fun show(fragment: Fragment) = compose.runOnIdle {
            state("session", SessionRecord(languageID = "ru", fragments = mutableListOf(fragment)))
            state("meaning", "I enjoy reading books and talking over a cup of coffee. ".repeat(10)); state("state", "active")
        }
        val first = Fragment(id = "caption-first", speaker = Speaker.assistant, text = text, startMS = 0, endMS = 1000)
        show(first)
        val mic = compose.onNodeWithTag("start-conversation").fetchSemanticsNode().boundsInRoot
        compose.waitUntil(5000) { offset("target-passage-scroll") > 20f && offset("meaning-passage-scroll") > 20f }
        capture("captions-following")
        compose.onNodeWithTag("target-passage-scroll").revealPageViewport()
        compose.onNodeWithTag("target-passage-scroll").performTouchInput { swipeDown() }
        var paused = offset("target-passage-scroll")
        compose.onNodeWithTag("meaning-passage-scroll").revealPageViewport()
        compose.onNodeWithTag("meaning-passage-scroll").performTouchInput { swipeDown() }
        compose.runOnIdle { assertTrue(vm.targetCaptionFollowing.interrupted); assertTrue(vm.meaningCaptionFollowing.interrupted) }
        compose.runOnIdle { vm.toggleMeaning() }
        compose.onNodeWithTag("meaning-passage-scroll").assertDoesNotExist()
        compose.runOnIdle { vm.toggleMeaning() }
        compose.runOnIdle { assertTrue(vm.meaningCaptionFollowing.interrupted) }
        compose.onNodeWithTag("tab-words").performClick()
        compose.onNodeWithTag("tab-talk").performClick()
        compose.runOnIdle { assertTrue(vm.targetCaptionFollowing.interrupted); assertTrue(vm.meaningCaptionFollowing.interrupted) }
        paused = offset("target-passage-scroll")
        compose.runOnIdle { state("session", vm.session!!.copy(fragments = mutableListOf(first.copy(text = text + "Ещё немного.")))) }
        Thread.sleep(1000)
        assertEquals(paused, offset("target-passage-scroll"), 1f)
        assertEquals(mic.bottom, compose.onNodeWithTag("start-conversation").fetchSemanticsNode().boundsInRoot.bottom, 2f)
        show(first.copy(id = "caption-next", startMS = 5000))
        compose.waitUntil(5000) { offset("target-passage-scroll") > 20f }
    }
    @Test fun dutchAndRussianGreetingsAppearInTheActualTalkScreen() {
        for ((id, greeting) in listOf("nl" to "Hoi!", "ru" to "Привет!")) {
            compose.runOnIdle { state("session", null); state("state", "idle"); vm.updatePreferences(vm.archive.preferences.copy(learningLanguageID = id, meaningLanguage = if (id == "nl") "Russian" else "Dutch")) }
            compose.onNodeWithTag("target-caption").assertTextEquals(greeting)
            compose.onNodeWithTag("meaning-caption").assertTextEquals(if (id == "nl") "Привет!" else "Hoi!")
        }
    }
}
