package chat.mural.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import chat.mural.MuralViewModel
import chat.mural.R
import chat.mural.core.AIVendor
import chat.mural.core.ConversationTheme
import chat.mural.core.LearningProgressSummary
import chat.mural.core.NaturalUpgrade
import chat.mural.core.PracticedPhrase
import chat.mural.core.ReviewRating
import chat.mural.core.SentenceCard
import chat.mural.core.SessionDeletionImpact
import chat.mural.core.SessionMistake
import chat.mural.core.SessionReport
import chat.mural.core.Sm2Calculator
import chat.mural.core.SuggestedTopic
import chat.mural.core.TopicBrief
import chat.mural.core.TopicDeck
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun TopicsScreen(vm: MuralViewModel, onChoose: () -> Unit, onCurrentTopic: (String) -> Unit) {
    var search by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf("All") }
    var current by rememberSaveable { mutableStateOf(false) }
    val categories = remember(vm.language.id) { listOf("All") + vm.language.themes.map { it.category }.distinct() }
    val suggestedTopics = if (category == "All") {
        vm.activeSuggestedTopics.filter {
            search.isBlank() ||
                it.title.contains(search, true) ||
                it.subtitle.contains(search, true) ||
                it.situation.contains(search, true)
        }
    } else {
        emptyList()
    }
    val themes = vm.language.themes.filter {
        (category == "All" || it.category == category) &&
            (search.isBlank() || it.title.contains(search, true) || it.subtitle.contains(search, true) || it.category.contains(search, true))
    }

    LazyVerticalGrid(columns = GridCells.Adaptive(if (LocalDensity.current.fontScale > 1.3f) 260.dp else 150.dp),
        modifier = Modifier.fillMaxSize().testTag("topics-screen").padding(horizontal = 24.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 120.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            MuralSearchField(search, { search = it.take(80) }, stringResource(R.string.topics_search_placeholder))
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            PageHeading(stringResource(R.string.topics_eyebrow), stringResource(R.string.topics_title),
                stringResource(R.string.topics_subtitle), Modifier.padding(top = 20.dp, bottom = 10.dp))
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Surface(Modifier.fillMaxWidth().clickable(role = Role.Button) { vm.chooseTheme(null); onChoose() },
                shape = RoundedCornerShape(26.dp), color = MuralColors.Surface.copy(alpha = .85f)) {
                Row(Modifier.padding(22.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MuralIcon(MuralSymbol.Wave)
                    Text(stringResource(R.string.topics_just_talk), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    MuralIcon(MuralSymbol.ChevronRight, Modifier.size(19.dp))
                }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(categories) { item ->
                    FilterChip(selected = category == item, onClick = { category = item }, label = { Text(categoryLabel(item), style = MaterialTheme.typography.bodySmall) },
                        shape = CircleShape, border = null,
                        colors = FilterChipDefaults.filterChipColors(containerColor = MuralColors.Surface.copy(alpha = .70f),
                            selectedContainerColor = MuralColors.Peach, selectedLabelColor = MuralColors.Ink),
                        modifier = Modifier.height(44.dp))
                }
            }
        }
        if (suggestedTopics.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "SUGGESTED FOR YOU",
                        style = MaterialTheme.typography.labelMedium,
                        color = MuralColors.Secondary,
                        modifier = Modifier.weight(1f).semantics { heading() },
                    )
                    if (vm.activeLearnerMemory.hasContent() && !vm.isRunning) {
                        MuralTextButton(
                            onClick = vm::refreshSuggestedTopics,
                            enabled = !vm.refreshingSuggestedTopics,
                            modifier = Modifier.testTag("refresh-suggested-topics"),
                        ) {
                            Text(
                                if (vm.refreshingSuggestedTopics) "Refreshing…" else "Refresh",
                                style = MaterialTheme.typography.labelMedium,
                                color = MuralColors.Secondary,
                            )
                        }
                    }
                }
            }
            itemsIndexed(
                suggestedTopics,
                key = { idx, topic -> "suggested-$idx-${topic.id}" },
                span = { _, _ -> GridItemSpan(maxLineSpan) },
            ) { index, topic ->
                SuggestedTopicCard(topic = topic, index = index) {
                    vm.chooseTheme(topic.toConversationTheme(index))
                    onChoose()
                }
            }
        }
        itemsIndexed(themes, key = { _, theme -> theme.id }) { index, theme ->
            ThemeCard(theme, index) {
                if (theme.id == "today") current = true
                else { vm.chooseTheme(theme); onChoose() }
            }
        }
        if (themes.isEmpty() && suggestedTopics.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
            Text(stringResource(R.string.topics_no_results), color = MuralColors.Secondary, modifier = Modifier.padding(vertical = 28.dp))
        }
    }
    if (current) CurrentTopicDialog(vm, onDismiss = { current = false }, onFind = onCurrentTopic, onDiscuss = {
        vm.discuss(it); current = false; onChoose()
    })
}

@Composable
private fun SuggestedTopicCard(topic: SuggestedTopic, index: Int, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("suggested-topic-${topic.id}")
            .clickable(role = Role.Button, onClick = onClick),
        shape = RoundedCornerShape(24.dp),
        color = MuralColors.Panels[index % MuralColors.Panels.size],
    ) {
        Row(
            Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MuralIcon(
                if (topic.isRoleplay) MuralSymbol.Sparkles else MuralSymbol.Wave,
                Modifier.size(24.dp),
                color = MuralColors.Secondary,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        topic.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (topic.isRoleplay) {
                        Surface(
                            shape = CircleShape,
                            color = MuralColors.Surface.copy(alpha = .78f),
                        ) {
                            Text(
                                "ROLEPLAY",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MuralColors.Secondary,
                            )
                        }
                    }
                }
                if (topic.subtitle.isNotBlank()) {
                    Text(
                        topic.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MuralColors.Secondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            MuralIcon(MuralSymbol.ChevronRight, Modifier.size(18.dp), color = MuralColors.Secondary)
        }
    }
}

@Composable
private fun ThemeCard(theme: ConversationTheme, index: Int, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("theme-${theme.id}").clickable(role = Role.Button, onClick = onClick),
        shape = RoundedCornerShape(26.dp),
        color = MuralColors.Panels.getOrElse(theme.colorIndex) { MuralColors.Panels[index % MuralColors.Panels.size] },
    ) {
        Column(Modifier.fillMaxWidth().heightIn(min = 180.dp).padding(19.dp), verticalArrangement = Arrangement.spacedBy(28.dp)) {
            MuralIcon(themeSymbol(theme.id), Modifier.size(31.dp), color = MuralColors.Secondary)
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(theme.title, style = MaterialTheme.typography.titleMedium)
                Text(theme.subtitle, color = MuralColors.Secondary, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun CurrentTopicDialog(
    vm: MuralViewModel,
    onDismiss: () -> Unit,
    onFind: (String) -> Unit,
    onDiscuss: (TopicBrief) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val searchAvailable = vm.aiVendor != AIVendor.GEMINI
    val uriHandler = LocalUriHandler.current
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(28.dp), color = MuralColors.Surface) {
            LazyColumn(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item { PageHeading(stringResource(R.string.topics_current_dialog_eyebrow), stringResource(R.string.topics_current_dialog_title), stringResource(R.string.topics_current_dialog_subtitle)) }
                if (!searchAvailable) item {
                    Text(stringResource(R.string.gemini_search_unavailable), color = MuralColors.Secondary,
                        modifier = Modifier.testTag("gemini-current-topic-unavailable"))
                }
                item {
                    MuralTextField(
                        query,
                        { query = it.take(160) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(vm.language.topicPlaceholder) },
                        minLines = 2,
                        maxLines = 4,
                        enabled = searchAvailable,
                    )
                }
                item {
                    Button(onClick = { onFind(query.trim()) }, enabled = searchAvailable && query.isNotBlank() && !vm.working,
                        modifier = Modifier.fillMaxWidth().testTag("current-topic-find")) {
                        if (vm.working) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(if (vm.working) "  " + stringResource(R.string.topics_finding_button) else stringResource(R.string.topics_find_button))
                    }
                }
                vm.topicResult?.let { brief ->
                    item { Text(inlineMarkdown(brief.text), style = MaterialTheme.typography.bodyLarge) }
                    if (brief.sources.isNotEmpty()) {
                        item { Text(stringResource(R.string.topics_sources_heading), fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() }) }
                        items(brief.sources.filter { it.safeUrl() != null }) { source ->
                            Text(
                                "↗ ${source.title}",
                                color = MuralColors.Orange,
                                modifier = Modifier.fillMaxWidth().clickable { source.safeUrl()?.let(uriHandler::openUri) }.padding(vertical = 7.dp),
                            )
                        }
                    }
                    item {
                        Button(
                            onClick = { onDiscuss(brief) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = MuralColors.Orange, contentColor = MuralColors.Ink),
                        ) { Text(stringResource(R.string.topics_talk_about_button)) }
                    }
                }
                item { Text(stringResource(R.string.topics_sources_note), style = MaterialTheme.typography.bodySmall, color = MuralColors.Secondary) }
                item { MuralTextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.common_close)) } }
            }
        }
    }
}

@Composable
fun WordsScreen(
    vm: MuralViewModel,
    onPracticeDeck: (TopicDeck) -> Unit = {},
) {
    var search by rememberSaveable { mutableStateOf("") }
    var activeDeckTitle by rememberSaveable { mutableStateOf<String?>(null) }
    var activeDeckCards by remember { mutableStateOf<List<SentenceCard>?>(null) }
    var activePracticeDeck by remember { mutableStateOf<TopicDeck?>(null) }
    var deckToDelete by remember { mutableStateOf<TopicDeck?>(null) }
    var reportToDelete by remember { mutableStateOf<SessionReport?>(null) }

    val now = System.currentTimeMillis()
    val allDecks = vm.topicDecks
    val filteredDecks = allDecks.filter { deck ->
        search.isBlank() ||
            deck.topic.contains(search, ignoreCase = true) ||
            deck.cards.any {
                it.frontText.contains(search, ignoreCase = true) ||
                    it.backText.contains(search, ignoreCase = true) ||
                    it.explanation.contains(search, ignoreCase = true)
            }
    }
    val languageCards = vm.activeLanguageSentenceCards
    val dueCards = languageCards.filter { it.isDue(now) }.sortedBy { it.nextReviewDate }
    val reports = vm.activeLanguageSessionReports
    val progressSummary = vm.learningProgressSummary

    LazyColumn(
        Modifier.fillMaxSize().testTag("words-screen").padding(horizontal = 22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(bottom = 120.dp),
    ) {
        item {
            Column(Modifier.padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "SENTENCE SRS · ${vm.language.name.uppercase()}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MuralColors.Secondary,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.words_title),
                        style = MaterialTheme.typography.headlineLarge,
                        modifier = Modifier.weight(1f).semantics { heading() },
                    )
                    SoftRoundButton(
                        MuralSymbol.Flashcards,
                        stringResource(R.string.flashcards_open),
                        onClick = {
                            if (languageCards.isNotEmpty()) {
                                val practiceList = if (dueCards.isNotEmpty()) dueCards else languageCards
                                activeDeckTitle = if (dueCards.isNotEmpty()) "Due Review (${dueCards.size})" else "All Sentence Cards"
                                activeDeckCards = practiceList
                                activePracticeDeck = null
                            }
                        },
                        enabled = languageCards.isNotEmpty(),
                        modifier = Modifier.testTag("open-flashcards"),
                    )
                }
                Text(
                    "Review topic-grouped sentence flashcards with Anki SM-2 spaced repetition and post-session mistake reports.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MuralColors.Secondary,
                )
            }
        }

        // Calm streak & weekly progress card
        item {
            LearningProgressCard(summary = progressSummary)
        }

        // Due cards summary banner
        if (languageCards.isNotEmpty()) {
            item {
                Surface(
                    Modifier.fillMaxWidth().testTag("srs-summary-card"),
                    shape = RoundedCornerShape(24.dp),
                    color = if (dueCards.isNotEmpty()) MuralColors.Peach else MuralColors.Sage,
                ) {
                    Row(
                        Modifier.padding(18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                if (dueCards.isNotEmpty()) "${dueCards.size} due ${if (dueCards.size == 1) "card" else "cards"} ready"
                                else "All decks caught up!",
                                style = MaterialTheme.typography.titleLarge,
                            )
                            Text(
                                "${allDecks.size} ${if (allDecks.size == 1) "topic deck" else "topic decks"} · ${languageCards.size} total sentences",
                                style = MaterialTheme.typography.bodySmall,
                                color = MuralColors.Secondary,
                            )
                        }
                        if (dueCards.isNotEmpty()) {
                            Button(
                                onClick = {
                                    activeDeckTitle = "Due Cards"
                                    activeDeckCards = dueCards
                                    activePracticeDeck = null
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MuralColors.Orange,
                                    contentColor = MuralColors.Ink,
                                ),
                                shape = CircleShape,
                                modifier = Modifier.testTag("review-due-cards-button"),
                            ) {
                                Text("Study Due (${dueCards.size})")
                            }
                        }
                    }
                }
            }
        }

        item {
            MuralSearchField(search, { search = it.take(80) }, "Search topics, Arabic, or English sentences")
        }

        // Topic Decks list
        if (filteredDecks.isNotEmpty()) {
            item {
                Text(
                    "TOPIC DECKS",
                    style = MaterialTheme.typography.labelMedium,
                    color = MuralColors.Secondary,
                    modifier = Modifier.padding(top = 6.dp).semantics { heading() },
                )
            }
            itemsIndexed(filteredDecks, key = { _, deck -> deck.topic }) { index, deck ->
                TopicDeckCard(
                    deck = deck,
                    colorIndex = index,
                    practiceEnabled = !vm.isRunning,
                    onClick = {
                        val dueInDeck = deck.cards.filter { it.isDue(now) }
                        val orderedCards = if (dueInDeck.isNotEmpty()) {
                            dueInDeck + deck.cards.filterNot { it.isDue(now) }
                        } else {
                            deck.cards
                        }
                        activeDeckTitle = deck.topic
                        activeDeckCards = orderedCards
                        activePracticeDeck = deck
                    },
                    onPracticeInCall = { onPracticeDeck(deck) },
                    onDelete = { deckToDelete = deck },
                )
            }
        } else {
            item {
                Surface(shape = RoundedCornerShape(28.dp), color = MuralColors.Sage, modifier = Modifier.testTag("words-empty-state")) {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        MuralIcon(MuralSymbol.Leaf, Modifier.size(34.dp), color = MuralColors.Secondary)
                        Text(
                            if (search.isBlank()) "No sentence decks yet." else "No matching sentence decks.",
                            style = MaterialTheme.typography.headlineMedium,
                        )
                        Text(
                            if (search.isBlank()) {
                                "Finish a live voice conversation to get a Post-Session Mistake Report and automatically build SM-2 sentence flashcard decks by topic."
                            } else {
                                "Try another topic, translation, or target sentence."
                            },
                            color = MuralColors.Secondary,
                        )
                    }
                }
            }
        }

        // Recent Session Reports
        if (reports.isNotEmpty()) {
            item {
                Text(
                    "RECENT SESSION REPORTS",
                    style = MaterialTheme.typography.labelMedium,
                    color = MuralColors.Secondary,
                    modifier = Modifier.padding(top = 10.dp).semantics { heading() },
                )
            }
            items(reports, key = { it.id }) { report ->
                Surface(
                    Modifier.fillMaxWidth().testTag("session-report-item-${report.id}").clickable(role = Role.Button) {
                        vm.openSessionReport(report)
                    },
                    shape = RoundedCornerShape(20.dp),
                    color = MuralColors.Surface,
                ) {
                    Column(
                        Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    report.topic,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    "${formatEpochMillis(report.date)} · ${report.durationMinutes} min",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MuralColors.Secondary,
                                )
                            }
                            MuralIcon(MuralSymbol.ChevronRight, Modifier.size(18.dp), color = MuralColors.Secondary)
                        }
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(
                                Modifier.weight(1f),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = if (report.mistakeCount > 0) MuralColors.Peach else MuralColors.Sage,
                                ) {
                                    Text(
                                        "${report.mistakeCount} ${if (report.mistakeCount == 1) "fix" else "fixes"}",
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MuralColors.Ink,
                                    )
                                }
                                if (report.practicedPhrases.isNotEmpty()) {
                                    Surface(
                                        shape = CircleShape,
                                        color = Color(0xFFE7F6EC),
                                    ) {
                                        Text(
                                            "${report.practicedPhrases.size} used",
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color(0xFF14532D),
                                        )
                                    }
                                }
                            }
                            SoftRoundButton(
                                symbol = MuralSymbol.Trash,
                                description = "Delete flashcards for ${report.topic}",
                                onClick = { reportToDelete = report },
                                diameter = 36.dp,
                                tint = Color(0xFFFDECE9),
                                modifier = Modifier.testTag("delete-session-cards-${report.sessionID}"),
                            )
                        }
                    }
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }

    activeDeckCards?.let { cards ->
        val linkedDeck = activePracticeDeck
        SentenceFlashcardsDialog(
            deckTitle = activeDeckTitle ?: "Sentence Flashcards",
            initialCards = cards,
            vm = vm,
            onPracticeInCall = linkedDeck?.takeIf { !vm.isRunning }?.let { deck ->
                {
                    activeDeckCards = null
                    activeDeckTitle = null
                    activePracticeDeck = null
                    onPracticeDeck(deck)
                }
            },
            onDismiss = {
                activeDeckCards = null
                activeDeckTitle = null
                activePracticeDeck = null
            },
        )
    }

    deckToDelete?.let { deck ->
        AlertDialog(
            onDismissRequest = { deckToDelete = null },
            modifier = Modifier.testTag("confirm-delete-topic-deck-dialog"),
            title = { Text("Delete topic deck?") },
            text = {
                Text(
                    "Delete \"${deck.topic}\" (${deck.totalCount} ${if (deck.totalCount == 1) "flashcard" else "flashcards"}) for ${vm.language.name}? Your past conversations stay in Settings > Past conversations.",
                )
            },
            confirmButton = {
                MuralTextButton(
                    onClick = {
                        vm.deleteTopicDeck(deck.topic)
                        deckToDelete = null
                    },
                    modifier = Modifier.testTag("confirm-delete-topic-deck-button"),
                ) {
                    Text(stringResource(R.string.common_delete), color = MuralColors.Red)
                }
            },
            dismissButton = {
                MuralTextButton(onClick = { deckToDelete = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    reportToDelete?.let { report ->
        val impact = vm.sessionFlashcardDeletionImpact(report.sessionID)
        DeleteSessionCardsDialog(
            report = report,
            impact = impact,
            onConfirm = { alsoRemoveReport ->
                vm.deleteSessionFlashcards(report.sessionID, alsoDeleteReport = alsoRemoveReport)
                reportToDelete = null
            },
            onDismiss = { reportToDelete = null },
        )
    }
}

@Composable
private fun LearningProgressCard(summary: LearningProgressSummary) {
    Surface(
        Modifier.fillMaxWidth().testTag("learning-progress-card"),
        shape = RoundedCornerShape(22.dp),
        color = MuralColors.Surface,
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    if (summary.currentStreakDays > 0) "${summary.currentStreakDays}-day streak"
                    else "Start a calm daily streak",
                    style = MaterialTheme.typography.titleSmall,
                    color = MuralColors.Ink,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (summary.effectiveCefrLevel.isNotEmpty()) {
                    Surface(shape = CircleShape, color = MuralColors.Butter) {
                        Text(
                            summary.effectiveCefrLevel,
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MuralColors.Ink,
                        )
                    }
                }
                Surface(
                    shape = CircleShape,
                    color = if (summary.dailyGoalMet) MuralColors.Sage else MuralColors.Peach,
                ) {
                    Text(
                        if (summary.dailyGoalMet) "Daily goal met"
                        else "${summary.todaySpeakingMinutes}/${summary.dailyGoal.targetSpeakingMinutes}m today",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MuralColors.Ink,
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ProgressStat(
                    label = "TODAY",
                    value = "${summary.todaySpeakingMinutes}m · ${summary.todayCardsReviewed} cards",
                )
                ProgressStat(
                    label = "7 DAYS",
                    value = "${summary.weeklySpeakingMinutes} min",
                )
                ProgressStat(
                    label = "MASTERED",
                    value = "${summary.masteredSentenceCount}/${summary.masteredSentenceCount + summary.learningSentenceCount}",
                )
            }
        }
    }
}

@Composable
private fun ProgressStat(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MuralColors.Secondary)
        Text(value, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold), color = MuralColors.Ink)
    }
}

@Composable
private fun TopicDeckCard(
    deck: TopicDeck,
    colorIndex: Int,
    practiceEnabled: Boolean,
    onClick: () -> Unit,
    onPracticeInCall: () -> Unit,
    onDelete: () -> Unit,
) {
    val panelColor = MuralColors.Panels[colorIndex % MuralColors.Panels.size]
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("topic-deck-${deck.topic}")
            .clickable(role = Role.Button, onClick = onClick),
        shape = RoundedCornerShape(24.dp),
        color = MuralColors.Surface,
    ) {
        Column(
            Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Surface(
                    modifier = Modifier.size(42.dp),
                    shape = RoundedCornerShape(14.dp),
                    color = panelColor,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        MuralIcon(MuralSymbol.Flashcards, Modifier.size(21.dp), color = MuralColors.Ink)
                    }
                }
                Text(
                    deck.topic,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                MuralIcon(MuralSymbol.ChevronRight, Modifier.size(18.dp), color = MuralColors.Secondary)
            }
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Surface(
                        shape = CircleShape,
                        color = if (deck.dueCount > 0) MuralColors.Orange else MuralColors.Sage,
                    ) {
                        Text(
                            if (deck.dueCount > 0) "${deck.dueCount} due" else "0 due",
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp).testTag("deck-due-count-${deck.topic}"),
                            style = MaterialTheme.typography.labelMedium,
                            color = MuralColors.Ink,
                            maxLines = 1,
                        )
                    }
                    Text(
                        "${deck.totalCount} ${if (deck.totalCount == 1) "sentence" else "sentences"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MuralColors.Secondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Surface(
                        shape = CircleShape,
                        color = if (practiceEnabled) MuralColors.Butter else MuralColors.Butter.copy(alpha = .5f),
                        modifier = Modifier
                            .testTag("practice-deck-in-call-${deck.topic}")
                            .clickable(enabled = practiceEnabled, role = Role.Button, onClick = onPracticeInCall),
                    ) {
                        Row(
                            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            MuralIcon(
                                MuralSymbol.Mic,
                                Modifier.size(14.dp),
                                color = if (practiceEnabled) MuralColors.Ink else MuralColors.Secondary,
                            )
                            Text(
                                "Practice in Call",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (practiceEnabled) MuralColors.Ink else MuralColors.Secondary,
                                maxLines = 1,
                            )
                        }
                    }
                    SoftRoundButton(
                        symbol = MuralSymbol.Trash,
                        description = "Delete ${deck.topic} deck",
                        onClick = onDelete,
                        diameter = 36.dp,
                        tint = Color(0xFFFDECE9),
                        modifier = Modifier.testTag("delete-topic-deck-${deck.topic}"),
                    )
                }
            }
        }
    }
}

@Composable
internal fun DeleteSessionCardsDialog(
    report: SessionReport,
    impact: SessionDeletionImpact,
    onConfirm: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var alsoRemoveReport by remember(report.id) { mutableStateOf(false) }
    val deleteSummary = when {
        impact.deletedCardCount > 0 && impact.unlinkedSharedCardCount > 0 ->
            "This will delete ${impact.deletedCardCount} ${if (impact.deletedCardCount == 1) "flashcard" else "flashcards"} from \"${report.topic}\" and keep ${impact.unlinkedSharedCardCount} shared ${if (impact.unlinkedSharedCardCount == 1) "card" else "cards"} also linked to other conversations."
        impact.deletedCardCount > 0 ->
            "This will delete ${impact.deletedCardCount} ${if (impact.deletedCardCount == 1) "flashcard" else "flashcards"} created from \"${report.topic}\"."
        impact.unlinkedSharedCardCount > 0 ->
            "All ${impact.unlinkedSharedCardCount} ${if (impact.unlinkedSharedCardCount == 1) "flashcard" else "flashcards"} from \"${report.topic}\" also belong to other conversations, so they will only be unlinked from this conversation."
        else ->
            "No saved flashcards currently belong to \"${report.topic}\"."
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("delete-session-cards-dialog"),
        title = { Text("Delete conversation flashcards?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(deleteSummary)
                Text(
                    "Your conversation transcript in Settings > Past conversations will never be deleted.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MuralColors.Secondary,
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { alsoRemoveReport = !alsoRemoveReport }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Checkbox(
                        checked = alsoRemoveReport,
                        onCheckedChange = { alsoRemoveReport = it },
                        modifier = Modifier.testTag("delete-session-also-remove-report"),
                    )
                    Text("Also remove this session report", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            MuralTextButton(
                onClick = { onConfirm(alsoRemoveReport) },
                modifier = Modifier.testTag("confirm-delete-session-cards-button"),
            ) {
                Text(stringResource(R.string.common_delete), color = MuralColors.Red)
            }
        },
        dismissButton = {
            MuralTextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}

@Composable
internal fun SentenceFlashcardsDialog(
    deckTitle: String,
    initialCards: List<SentenceCard>,
    vm: MuralViewModel,
    onPracticeInCall: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    val tts = rememberLocalTtsSpeaker()
    var cards by remember(initialCards) { mutableStateOf(initialCards) }
    var currentIndex by remember(initialCards) { mutableStateOf(0) }
    var revealed by remember(currentIndex, cards.getOrNull(currentIndex)?.id) { mutableStateOf(false) }
    var cardPendingDelete by remember { mutableStateOf<SentenceCard?>(null) }

    val currentCard = cards.getOrNull(currentIndex)
    val autoPlayOnFlip = vm.flashcardAutoPlayOnFlip
    val listeningMode = vm.flashcardListeningMode

    LaunchedEffect(currentCard?.id, revealed, autoPlayOnFlip) {
        if (revealed && autoPlayOnFlip && currentCard != null) {
            tts.speak(currentCard.backText, 1.0f)
        }
    }
    LaunchedEffect(currentCard?.id, revealed, listeningMode) {
        if (!revealed && listeningMode && currentCard != null) {
            tts.speak(currentCard.backText, 1.0f)
        }
    }

    val flipAngle by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (revealed) 180f else 0f,
        animationSpec = androidx.compose.animation.core.tween(
            if (android.animation.ValueAnimator.areAnimatorsEnabled()) 420 else 0,
        ),
        label = "sentence-card-flip",
    )
    val showingBack = flipAngle >= 90f

    Dialog(
        onDismissRequest = { tts.stop(); onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .heightIn(max = 680.dp)
                .testTag("sentence-flashcards-modal"),
            shape = RoundedCornerShape(32.dp),
            color = MuralColors.Cream,
        ) {
            Column(
                Modifier.padding(vertical = 20.dp, horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            deckTitle,
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (cards.isNotEmpty() && currentCard != null) {
                            Text(
                                "Card ${currentIndex + 1} of ${cards.size}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MuralColors.Secondary,
                            )
                        }
                    }
                    if (currentCard != null) {
                        SoftRoundButton(
                            symbol = MuralSymbol.Trash,
                            description = "Delete flashcard",
                            onClick = { cardPendingDelete = currentCard },
                            diameter = 42.dp,
                            tint = Color(0xFFFDECE9),
                            modifier = Modifier.testTag("delete-current-sentence-card"),
                        )
                    }
                    SoftRoundButton(
                        MuralSymbol.Close,
                        stringResource(R.string.common_close),
                        onClick = { tts.stop(); onDismiss() },
                        modifier = Modifier.testTag("close-sentence-flashcards"),
                    )
                }

                if (currentCard != null) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FilterChip(
                            selected = autoPlayOnFlip,
                            onClick = { vm.setFlashcardAutoPlayOnFlip(!autoPlayOnFlip) },
                            label = { Text("Auto-play", style = MaterialTheme.typography.labelMedium) },
                            shape = CircleShape,
                            border = null,
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = MuralColors.Surface,
                                selectedContainerColor = MuralColors.Butter,
                                selectedLabelColor = MuralColors.Ink,
                            ),
                            modifier = Modifier.testTag("flashcard-toggle-autoplay"),
                        )
                        FilterChip(
                            selected = listeningMode,
                            onClick = { vm.setFlashcardListeningMode(!listeningMode) },
                            label = { Text("Listening", style = MaterialTheme.typography.labelMedium) },
                            shape = CircleShape,
                            border = null,
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = MuralColors.Surface,
                                selectedContainerColor = MuralColors.Peach,
                                selectedLabelColor = MuralColors.Ink,
                            ),
                            modifier = Modifier.testTag("flashcard-toggle-listening"),
                        )
                    }
                }

                if (currentCard == null) {
                    Surface(
                        Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        shape = RoundedCornerShape(24.dp),
                        color = MuralColors.Sage,
                    ) {
                        Column(
                            Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            MuralIcon(MuralSymbol.Check, Modifier.size(36.dp), color = MuralColors.Ink)
                            Text("Deck Review Complete!", style = MaterialTheme.typography.headlineSmall)
                            Text(
                                "Your SM-2 review intervals have been updated.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MuralColors.Secondary,
                                textAlign = TextAlign.Center,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                if (onPracticeInCall != null) {
                                    Button(
                                        onClick = { tts.stop(); onPracticeInCall() },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MuralColors.Butter,
                                            contentColor = MuralColors.Ink,
                                        ),
                                    ) {
                                        Text("Practice in Call")
                                    }
                                }
                                Button(
                                    onClick = { tts.stop(); onDismiss() },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MuralColors.Orange,
                                        contentColor = MuralColors.Ink,
                                    ),
                                ) {
                                    Text("Done")
                                }
                            }
                        }
                    }
                } else {
                    Surface(
                        Modifier
                            .weight(1f, fill = false)
                            .fillMaxWidth()
                            .heightIn(min = 320.dp)
                            .testTag("sentence-flashcard-face")
                            .graphicsLayer {
                                rotationY = if (showingBack) flipAngle - 180f else flipAngle
                                cameraDistance = 12 * density
                            }
                            .clickable { revealed = !revealed },
                        shape = RoundedCornerShape(28.dp),
                        color = MuralColors.Surface,
                    ) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            Column(
                                Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Surface(shape = CircleShape, color = MuralColors.Butter) {
                                    Text(
                                        currentCard.topic,
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MuralColors.Secondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                val srsStatusText = if (currentCard.repetitionCount == 0) {
                                    "New card · Due for review"
                                } else {
                                    "Reviews: ${currentCard.repetitionCount} · Interval: ${currentCard.intervalDays}d · Ease: ${String.format(Locale.US, "%.1f", currentCard.easeFactor)}"
                                }
                                Text(
                                    srsStatusText,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MuralColors.Secondary,
                                    textAlign = TextAlign.Center,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }

                            if (!showingBack) {
                                Spacer(Modifier.height(12.dp))
                                if (listeningMode) {
                                    Text(
                                        "LISTENING PROMPT",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MuralColors.Secondary,
                                    )
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        SoftRoundButton(
                                            symbol = MuralSymbol.Speaker,
                                            description = "Play sentence at normal speed",
                                            onClick = { tts.speak(currentCard.backText, 1.0f) },
                                            tint = Color(0xFFE7F6EC),
                                            diameter = 52.dp,
                                            modifier = Modifier.testTag("listening-front-tts-normal"),
                                        )
                                        Surface(
                                            shape = CircleShape,
                                            color = MuralColors.Butter,
                                            modifier = Modifier
                                                .testTag("listening-front-tts-slow")
                                                .clickable(role = Role.Button) {
                                                    tts.speak(currentCard.backText, 0.75f)
                                                },
                                        ) {
                                            Text(
                                                "0.75x",
                                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                                                style = MaterialTheme.typography.labelLarge,
                                                color = MuralColors.Ink,
                                            )
                                        }
                                    }
                                    Text(
                                        "Listen carefully, recall the sentence and meaning, then tap to flip.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MuralColors.Secondary,
                                        textAlign = TextAlign.Center,
                                    )
                                    MuralTextButton(
                                        onClick = { revealed = true },
                                        modifier = Modifier.testTag("flip-sentence-card"),
                                    ) {
                                        Text("Show Answer")
                                    }
                                } else {
                                    Text(
                                        "ARABIC PROMPT",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MuralColors.Secondary,
                                    )
                                    Text(
                                        currentCard.frontText,
                                        style = MaterialTheme.typography.headlineMedium.copy(
                                            textDirection = TextDirection.Content,
                                        ),
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.fillMaxWidth().testTag("sentence-card-front"),
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        "Say or think of the natural target sentence, then tap to flip.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MuralColors.Secondary,
                                        textAlign = TextAlign.Center,
                                    )
                                    MuralTextButton(
                                        onClick = { revealed = true },
                                        modifier = Modifier.testTag("flip-sentence-card"),
                                    ) {
                                        Text("Show English Correction")
                                    }
                                }
                            } else {
                                Text(
                                    "CORRECT TARGET SENTENCE",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFF1F6E3D),
                                )
                                Surface(
                                    Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(20.dp),
                                    color = Color(0xFFE7F6EC),
                                ) {
                                    Row(
                                        Modifier.padding(16.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Text(
                                            currentCard.backText,
                                            style = MaterialTheme.typography.titleLarge,
                                            color = Color(0xFF14532D),
                                            modifier = Modifier.weight(1f).testTag("sentence-card-back"),
                                        )
                                        Surface(
                                            shape = CircleShape,
                                            color = MuralColors.Butter,
                                            modifier = Modifier
                                                .testTag("sentence-card-tts-slow")
                                                .clickable(role = Role.Button) {
                                                    tts.speak(currentCard.backText, 0.75f)
                                                },
                                        ) {
                                            Text(
                                                "0.75x",
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MuralColors.Ink,
                                            )
                                        }
                                        SoftRoundButton(
                                            symbol = MuralSymbol.Speaker,
                                            description = "Pronounce correction",
                                            onClick = { tts.speak(currentCard.backText, 1.0f) },
                                            tint = Color.White,
                                            diameter = 44.dp,
                                            modifier = Modifier.testTag("sentence-card-tts"),
                                        )
                                    }
                                }

                                if (currentCard.userOriginalMistake.isNotBlank()) {
                                    Surface(
                                        Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(16.dp),
                                        color = Color(0xFFFDECE9),
                                    ) {
                                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Text(
                                                "YOUR ORIGINAL SENTENCE",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MuralColors.Red,
                                            )
                                            Text(
                                                currentCard.userOriginalMistake,
                                                style = MaterialTheme.typography.bodyMedium.copy(
                                                    textDecoration = TextDecoration.LineThrough,
                                                ),
                                                color = MuralColors.Red,
                                            )
                                        }
                                    }
                                }

                                Surface(
                                    Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(16.dp),
                                    color = MuralColors.SurfaceBright,
                                ) {
                                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(
                                            "WHY THIS FIX WORKS",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MuralColors.Secondary,
                                        )
                                        Text(
                                            currentCard.explanation,
                                            style = MaterialTheme.typography.bodyMedium.copy(
                                                textDirection = TextDirection.Content,
                                            ),
                                            color = MuralColors.Ink,
                                            modifier = Modifier.fillMaxWidth().testTag("sentence-card-explanation"),
                                        )
                                    }
                                }

                                Text(
                                    currentCard.frontText,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        textDirection = TextDirection.Content,
                                    ),
                                    color = MuralColors.Secondary,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }

                    if (showingBack) {
                        val previewNow = System.currentTimeMillis()
                        Row(
                            Modifier.fillMaxWidth().testTag("sm2-rating-row"),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            ReviewRating.entries.forEach { rating ->
                                val previewCard = Sm2Calculator.review(currentCard, rating, previewNow)
                                val intervalLabel = "${previewCard.intervalDays}d"
                                val containerColor = when (rating) {
                                    ReviewRating.Again -> Color(0xFFFDECE9)
                                    ReviewRating.Hard -> MuralColors.Peach
                                    ReviewRating.Good -> MuralColors.Sage
                                    ReviewRating.Easy -> MuralColors.Butter
                                }
                                val textColor = when (rating) {
                                    ReviewRating.Again -> MuralColors.Red
                                    else -> MuralColors.Ink
                                }
                                Surface(
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("sm2-rate-${rating.name.lowercase()}")
                                        .clickable(role = Role.Button) {
                                            tts.stop()
                                            vm.reviewSentenceCard(currentCard.id, rating)
                                            val updated = Sm2Calculator.review(currentCard, rating, previewNow)
                                            cards = cards.toMutableList().also { it[currentIndex] = updated }
                                            if (currentIndex < cards.lastIndex) {
                                                currentIndex += 1
                                                revealed = false
                                            } else {
                                                currentIndex = cards.size
                                            }
                                        },
                                    shape = RoundedCornerShape(18.dp),
                                    color = containerColor,
                                ) {
                                    Column(
                                        Modifier.padding(vertical = 12.dp, horizontal = 6.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(2.dp),
                                    ) {
                                        Text(
                                            rating.label,
                                            style = MaterialTheme.typography.labelLarge,
                                            color = textColor,
                                        )
                                        Text(
                                            intervalLabel,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MuralColors.Secondary,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    cardPendingDelete?.let { cardToDelete ->
        AlertDialog(
            onDismissRequest = { cardPendingDelete = null },
            modifier = Modifier.testTag("confirm-delete-sentence-card-dialog"),
            title = { Text("Delete this flashcard?") },
            text = { Text("Remove \"${cardToDelete.backText}\" from your SM-2 flashcard deck?") },
            confirmButton = {
                MuralTextButton(
                    onClick = {
                        tts.stop()
                        vm.deleteSentenceCard(cardToDelete.id)
                        val remaining = cards.filterNot { it.id == cardToDelete.id }
                        cards = remaining
                        revealed = false
                        currentIndex = if (remaining.isEmpty()) 0 else currentIndex.coerceAtMost(remaining.lastIndex)
                        cardPendingDelete = null
                    },
                    modifier = Modifier.testTag("confirm-delete-sentence-card-button"),
                ) {
                    Text(stringResource(R.string.common_delete), color = MuralColors.Red)
                }
            },
            dismissButton = {
                MuralTextButton(onClick = { cardPendingDelete = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

@Composable
fun PostSessionReportDialog(
    vm: MuralViewModel,
    onDismiss: () -> Unit,
) {
    val tts = rememberLocalTtsSpeaker()
    val report = vm.activeSessionReport
    val analyzing = vm.analyzingSessionReport
    val error = vm.sessionReportError
    var showDeleteSessionDialog by remember(report?.id) { mutableStateOf(false) }
    var mistakeIndexToDelete by remember(report?.id) { mutableStateOf<Int?>(null) }

    Dialog(
        onDismissRequest = { tts.stop(); onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .heightIn(max = 700.dp)
                .testTag("post-session-report-dialog"),
            shape = RoundedCornerShape(32.dp),
            color = MuralColors.Cream,
        ) {
            LazyColumn(
                Modifier.padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                "POST-SESSION REPORT",
                                style = MaterialTheme.typography.labelSmall,
                                color = MuralColors.Secondary,
                            )
                            Text(
                                report?.topic ?: "Session Analysis",
                                style = MaterialTheme.typography.headlineMedium,
                                modifier = Modifier.semantics { heading() },
                            )
                        }
                        if (report != null && !analyzing) {
                            SoftRoundButton(
                                symbol = MuralSymbol.Trash,
                                description = "Delete flashcards for this conversation",
                                onClick = { showDeleteSessionDialog = true },
                                diameter = 40.dp,
                                tint = Color(0xFFFDECE9),
                                modifier = Modifier.testTag("report-delete-session-cards"),
                            )
                        }
                        SoftRoundButton(
                            MuralSymbol.Close,
                            stringResource(R.string.common_close),
                            onClick = { tts.stop(); onDismiss() },
                            modifier = Modifier.testTag("close-session-report"),
                        )
                    }
                }

                if (report != null) {
                    item {
                        Surface(
                            Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(20.dp),
                            color = MuralColors.Surface,
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                ReportMetric("DATE", formatEpochMillis(report.date))
                                ReportMetric("DURATION", "${report.durationMinutes} min")
                                ReportMetric("MISTAKES", "${report.mistakeCount}")
                            }
                        }
                    }
                }

                if (analyzing) {
                    item {
                        Surface(
                            Modifier.fillMaxWidth().testTag("session-report-loading"),
                            shape = RoundedCornerShape(22.dp),
                            color = MuralColors.Surface,
                        ) {
                            Row(
                                Modifier.padding(20.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("Analyzing your sentences...", style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        "Extracting grammar fixes, Arabic translations, and SM-2 flashcards.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MuralColors.Secondary,
                                    )
                                }
                            }
                        }
                    }
                }

                if (error != null) {
                    item {
                        Surface(
                            Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(20.dp),
                            color = Color(0xFFFDECE9),
                        ) {
                            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(error, style = MaterialTheme.typography.bodyMedium, color = MuralColors.Red)
                                Button(
                                    onClick = { vm.retryPostSessionAnalysis() },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MuralColors.Orange,
                                        contentColor = MuralColors.Ink,
                                    ),
                                ) {
                                    Text("Retry Session Analysis")
                                }
                            }
                        }
                    }
                }

                if (report != null) {
                    if (report.wins.isNotEmpty() || report.practicedPhrases.isNotEmpty()) {
                        item {
                            WhatYouDidWellSection(
                                wins = report.wins,
                                practicedPhrases = report.practicedPhrases,
                                onSpeak = { tts.speak(it) },
                            )
                        }
                    }

                    if (report.mistakes.isEmpty() && !analyzing) {
                        item {
                            Surface(
                                Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(22.dp),
                                color = MuralColors.Sage,
                            ) {
                                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("Great speaking!", style = MaterialTheme.typography.titleLarge)
                                    Text(
                                        "No sentence-level grammar or phrasing mistakes were flagged in this session.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MuralColors.Secondary,
                                    )
                                }
                            }
                        }
                    } else {
                        itemsIndexed(report.mistakes) { idx, mistake ->
                            MistakeReportCard(
                                index = idx + 1,
                                mistake = mistake,
                                onSpeakCorrection = { tts.speak(mistake.correction) },
                                onDelete = { mistakeIndexToDelete = idx },
                            )
                        }
                    }

                    if (report.naturalUpgrades.isNotEmpty()) {
                        item {
                            Text(
                                "SOUND MORE NATURAL",
                                style = MaterialTheme.typography.labelMedium,
                                color = MuralColors.Secondary,
                                modifier = Modifier.padding(top = 6.dp).semantics { heading() },
                            )
                        }
                        item {
                            NaturalUpgradesSection(
                                report = report,
                                upgrades = report.naturalUpgrades,
                                isInDeck = { alt -> vm.isUpgradeInDeck(report.languageID, alt) },
                                onSpeak = { tts.speak(it) },
                                onAddToDeck = { upgrade -> vm.addNaturalUpgradeToDeck(report, upgrade) },
                            )
                        }
                    }
                }

                item {
                    Button(
                        onClick = { tts.stop(); onDismiss() },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MuralColors.Orange,
                            contentColor = MuralColors.Ink,
                        ),
                    ) {
                        Text(stringResource(R.string.common_close))
                    }
                }
            }
        }
    }

    if (showDeleteSessionDialog && report != null) {
        val impact = vm.sessionFlashcardDeletionImpact(report.sessionID)
        DeleteSessionCardsDialog(
            report = report,
            impact = impact,
            onConfirm = { alsoRemoveReport ->
                vm.deleteSessionFlashcards(report.sessionID, alsoDeleteReport = alsoRemoveReport)
                showDeleteSessionDialog = false
            },
            onDismiss = { showDeleteSessionDialog = false },
        )
    }

    val pendingMistakeIdx = mistakeIndexToDelete
    if (pendingMistakeIdx != null && report != null && pendingMistakeIdx in report.mistakes.indices) {
        val targetMistake = report.mistakes[pendingMistakeIdx]
        AlertDialog(
            onDismissRequest = { mistakeIndexToDelete = null },
            modifier = Modifier.testTag("confirm-delete-report-mistake-dialog"),
            title = { Text("Remove this flashcard & fix?") },
            text = {
                Text(
                    "Remove \"${targetMistake.correction}\" from this session report and unlink/delete its sentence flashcard for this conversation?",
                )
            },
            confirmButton = {
                MuralTextButton(
                    onClick = {
                        vm.deleteMistakeFromReport(report.id, pendingMistakeIdx)
                        mistakeIndexToDelete = null
                    },
                    modifier = Modifier.testTag("confirm-delete-report-mistake-button"),
                ) {
                    Text(stringResource(R.string.common_delete), color = MuralColors.Red)
                }
            },
            dismissButton = {
                MuralTextButton(onClick = { mistakeIndexToDelete = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

@Composable
private fun WhatYouDidWellSection(
    wins: List<String>,
    practicedPhrases: List<PracticedPhrase>,
    onSpeak: (String) -> Unit,
) {
    Surface(
        Modifier.fillMaxWidth().testTag("what-you-did-well-section"),
        shape = RoundedCornerShape(24.dp),
        color = Color(0xFFE7F6EC),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MuralIcon(MuralSymbol.Check, Modifier.size(20.dp), color = Color(0xFF1F6E3D))
                Text(
                    "What You Did Well",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color(0xFF14532D),
                    modifier = Modifier.weight(1f),
                )
            }
            if (wins.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    wins.forEachIndexed { idx, win ->
                        Surface(
                            Modifier.fillMaxWidth().testTag("session-win-item-${idx + 1}"),
                            shape = RoundedCornerShape(14.dp),
                            color = Color.White.copy(alpha = .78f),
                        ) {
                            Row(
                                Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.Top,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                MuralIcon(
                                    MuralSymbol.Sparkles,
                                    Modifier.size(16.dp).padding(top = 2.dp),
                                    color = Color(0xFF1F6E3D),
                                )
                                Text(
                                    win,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        textDirection = TextDirection.Content,
                                    ),
                                    color = Color(0xFF14532D),
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            }
            if (practicedPhrases.isNotEmpty()) {
                PracticedPhrasesSection(
                    phrases = practicedPhrases,
                    onSpeak = onSpeak,
                )
            }
        }
    }
}

@Composable
private fun NaturalUpgradesSection(
    report: SessionReport,
    upgrades: List<NaturalUpgrade>,
    isInDeck: (String) -> Boolean,
    onSpeak: (String) -> Unit,
    onAddToDeck: (NaturalUpgrade) -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().testTag("natural-upgrades-section"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        upgrades.forEachIndexed { idx, upgrade ->
            val added = isInDeck(upgrade.naturalAlternative)
            Surface(
                Modifier.fillMaxWidth().testTag("natural-upgrade-card-${idx + 1}"),
                shape = RoundedCornerShape(24.dp),
                color = MuralColors.Surface,
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "UPGRADE #${idx + 1}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MuralColors.Secondary,
                    )
                    Surface(
                        Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        color = MuralColors.SurfaceBright,
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("YOU SAID", style = MaterialTheme.typography.labelSmall, color = MuralColors.Secondary)
                            Text(upgrade.userSaid, style = MaterialTheme.typography.bodyMedium, color = MuralColors.Ink)
                        }
                    }
                    Surface(
                        Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        color = Color(0xFFE7F6EC),
                    ) {
                        Row(
                            Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("MORE NATURAL", style = MaterialTheme.typography.labelSmall, color = Color(0xFF1F6E3D))
                                Text(
                                    upgrade.naturalAlternative,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Color(0xFF14532D),
                                )
                            }
                            SoftRoundButton(
                                symbol = MuralSymbol.Speaker,
                                description = "Pronounce natural alternative",
                                onClick = { onSpeak(upgrade.naturalAlternative) },
                                tint = Color.White,
                                diameter = 40.dp,
                                modifier = Modifier.testTag("natural-upgrade-tts-${idx + 1}"),
                            )
                        }
                    }
                    if (upgrade.arabicMeaning.isNotBlank()) {
                        Text(
                            upgrade.arabicMeaning,
                            style = MaterialTheme.typography.titleMedium.copy(
                                textDirection = TextDirection.Content,
                            ),
                            color = MuralColors.Ink,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (upgrade.explanation.isNotBlank()) {
                        Text(
                            upgrade.explanation,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                textDirection = TextDirection.Content,
                            ),
                            color = MuralColors.Secondary,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Button(
                        onClick = { onAddToDeck(upgrade) },
                        enabled = !added,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (added) MuralColors.Sage else MuralColors.Butter,
                            contentColor = MuralColors.Ink,
                            disabledContainerColor = MuralColors.Sage,
                            disabledContentColor = Color(0xFF14532D),
                        ),
                        shape = CircleShape,
                        modifier = Modifier.testTag("add-upgrade-to-deck-${idx + 1}"),
                    ) {
                        Text(if (added) "Added to ${Sm2Calculator.normalizedTopic(report.topic)}" else "Add to Deck")
                    }
                }
            }
        }
    }
}

@Composable
private fun PracticedPhrasesSection(
    phrases: List<PracticedPhrase>,
    onSpeak: (String) -> Unit,
) {
    Surface(
        Modifier.fillMaxWidth().testTag("practiced-phrases-section"),
        shape = RoundedCornerShape(24.dp),
        color = Color(0xFFE7F6EC),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MuralIcon(MuralSymbol.Check, Modifier.size(20.dp), color = Color(0xFF1F6E3D))
                Text(
                    "Phrases Used Successfully",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color(0xFF14532D),
                    modifier = Modifier.weight(1f),
                )
                Surface(shape = CircleShape, color = Color.White.copy(alpha = .8f)) {
                    Text(
                        "SM-2 +1",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF1F6E3D),
                    )
                }
            }
            Text(
                "You naturally used ${phrases.size} learning ${if (phrases.size == 1) "phrase" else "phrases"} in context — review intervals advanced automatically.",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF1F6E3D),
            )
            phrases.forEachIndexed { idx, item ->
                Surface(
                    Modifier.fillMaxWidth().testTag("practiced-phrase-item-${idx + 1}"),
                    shape = RoundedCornerShape(16.dp),
                    color = Color.White.copy(alpha = .82f),
                ) {
                    Row(
                        Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (item.phrase.isNotBlank()) {
                                Text(
                                    item.phrase,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = Color(0xFF14532D),
                                )
                            }
                            Text(
                                "“${item.userQuote}”",
                                style = MaterialTheme.typography.bodySmall,
                                color = MuralColors.Secondary,
                            )
                        }
                        if (item.phrase.isNotBlank()) {
                            SoftRoundButton(
                                symbol = MuralSymbol.Speaker,
                                description = "Pronounce practiced phrase",
                                onClick = { onSpeak(item.phrase) },
                                tint = Color(0xFFE7F6EC),
                                diameter = 36.dp,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReportMetric(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MuralColors.Secondary)
        Text(value, style = MaterialTheme.typography.titleMedium, color = MuralColors.Ink)
    }
}

@Composable
private fun MistakeReportCard(
    index: Int,
    mistake: SessionMistake,
    onSpeakCorrection: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        Modifier.fillMaxWidth().testTag("mistake-card-$index"),
        shape = RoundedCornerShape(24.dp),
        color = MuralColors.Surface,
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "FIX #$index",
                    style = MaterialTheme.typography.labelSmall,
                    color = MuralColors.Secondary,
                    modifier = Modifier.weight(1f),
                )
                SoftRoundButton(
                    symbol = MuralSymbol.Trash,
                    description = "Delete fix #$index",
                    onClick = onDelete,
                    diameter = 34.dp,
                    tint = Color(0xFFFDECE9),
                    modifier = Modifier.testTag("delete-report-mistake-$index"),
                )
            }

            // 1. User said (highlighted in red with strikethrough)
            Surface(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = Color(0xFFFDECE9),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "YOU SAID",
                        style = MaterialTheme.typography.labelSmall,
                        color = MuralColors.Red,
                    )
                    Text(
                        mistake.userSaid,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            textDecoration = TextDecoration.LineThrough,
                        ),
                        color = MuralColors.Red,
                        modifier = Modifier.testTag("mistake-user-said-$index"),
                    )
                }
            }

            // 2. Correction right below it (highlighted in green + Local TTS speaker button)
            Surface(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = Color(0xFFE7F6EC),
            ) {
                Row(
                    Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "CORRECTION",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF1F6E3D),
                        )
                        Text(
                            mistake.correction,
                            style = MaterialTheme.typography.titleMedium,
                            color = Color(0xFF14532D),
                            modifier = Modifier.testTag("mistake-correction-$index"),
                        )
                    }
                    SoftRoundButton(
                        symbol = MuralSymbol.Speaker,
                        description = "Pronounce correction",
                        onClick = onSpeakCorrection,
                        tint = Color.White,
                        diameter = 40.dp,
                        modifier = Modifier.testTag("mistake-tts-$index"),
                    )
                }
            }

            // 3. Arabic meaning & Explanation
            if (mistake.arabicMeaning.isNotBlank()) {
                Text(
                    mistake.arabicMeaning,
                    style = MaterialTheme.typography.titleMedium.copy(
                        textDirection = TextDirection.Content,
                    ),
                    color = MuralColors.Ink,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Text(
                mistake.explanation,
                style = MaterialTheme.typography.bodyMedium.copy(
                    textDirection = TextDirection.Content,
                ),
                color = MuralColors.Secondary,
                modifier = Modifier.fillMaxWidth().testTag("mistake-explanation-$index"),
            )
        }
    }
}

internal fun formatDate(secondsSince2001: Double): String = runCatching {
    val unix = (secondsSince2001 + chat.mural.core.APPLE_EPOCH_UNIX_SECONDS).toLong()
    DateTimeFormatter.ofPattern("d MMM yyyy").format(Instant.ofEpochSecond(unix).atZone(ZoneId.systemDefault()))
}.getOrDefault("—")

internal fun formatEpochMillis(epochMillis: Long): String = runCatching {
    DateTimeFormatter.ofPattern("d MMM yyyy").format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))
}.getOrDefault("—")

@Composable
private fun categoryLabel(value: String) = when (value) {
    "All" -> stringResource(R.string.topics_category_all)
    "Everyday" -> stringResource(R.string.topics_category_everyday)
    "Connection" -> stringResource(R.string.topics_category_connection)
    "Local life" -> stringResource(R.string.topics_category_local_life)
    "Interests" -> stringResource(R.string.topics_category_interests)
    else -> value
}

private fun themeSymbol(id: String) = when (id) {
    "coffee" -> MuralSymbol.Coffee
    "weekend" -> MuralSymbol.Sun
    "walk", "cabin" -> MuralSymbol.Leaf
    "dinner", "restaurant" -> MuralSymbol.Food
    "music" -> MuralSymbol.Music
    "books" -> MuralSymbol.Words
    "today", "travel", "travelstories" -> MuralSymbol.Globe
    else -> MuralSymbol.Sparkles
}
