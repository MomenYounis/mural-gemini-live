package chat.mural.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.password
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogWindowProvider
import chat.mural.MuralViewModel
import chat.mural.R
import chat.mural.core.CEFR_LEVELS
import chat.mural.core.CoachStyle
import chat.mural.core.LanguageRegistry
import chat.mural.core.AIVendor
import chat.mural.core.LearnerMemory
import chat.mural.core.MeaningLanguages
import chat.mural.core.MinuteBalanceTime
import chat.mural.core.Passage
import chat.mural.core.SessionRecord
import chat.mural.core.Speaker
import chat.mural.core.UsageSummary
import chat.mural.core.ProviderFailureKind
import chat.mural.network.GeminiLiveProtocol
import chat.mural.network.GroqWhisperModel
import chat.mural.network.TeachingModelOption
import chat.mural.network.TeachingProvider
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(vm: MuralViewModel, onExport: () -> Unit, onImport: () -> Unit, onReviewConsent: () -> Unit,
                   onAccount: (() -> Unit)? = null, accountSummary: String? = null,
                   focusAdvanced: Boolean = false, onDismiss: () -> Unit = {}) {
    var page by rememberSaveable { mutableStateOf("main") }
    var keyDialog by rememberSaveable { mutableStateOf(false) }
    var keyDialogUseAfterSave by rememberSaveable { mutableStateOf(false) }
    var deleteKey by rememberSaveable { mutableStateOf(false) }
    var deleteAll by rememberSaveable { mutableStateOf(false) }
    var permissionDetails by rememberSaveable { mutableStateOf(false) }
    var revokeConsent by rememberSaveable { mutableStateOf(false) }
    var notices by rememberSaveable { mutableStateOf(false) }
    var history by rememberSaveable { mutableStateOf(false) }
    var transcript by remember { mutableStateOf<SessionRecord?>(null) }
    var deleteSession by remember { mutableStateOf<SessionRecord?>(null) }
    var teachingKeyDialogProvider by remember { mutableStateOf<TeachingProvider?>(null) }
    var deleteTeachingKeyProvider by remember { mutableStateOf<TeachingProvider?>(null) }
    var showTeachingModelPicker by remember { mutableStateOf(false) }
    var groqKeyDialog by rememberSaveable { mutableStateOf(false) }
    var deleteGroqKey by rememberSaveable { mutableStateOf(false) }
    var sourceDialog by rememberSaveable { mutableStateOf(false) }
    var pendingSource by rememberSaveable { mutableStateOf<chat.mural.core.ConversationProvider?>(null) }
    var memoryEditDialog by remember { mutableStateOf<MemoryEditRequest?>(null) }
    var confirmClearMemory by rememberSaveable { mutableStateOf(false) }
    var switchBalance by remember { mutableStateOf<Long?>(null) }
    var checkingSwitch by remember { mutableStateOf(false) }
    var switchTicket by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    androidx.compose.runtime.LaunchedEffect(focusAdvanced) {
        if (focusAdvanced) listState.scrollToItem(5)
    }
    var reminderPermissionDenied by rememberSaveable { mutableStateOf(false) }
    val reminderPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            reminderPermissionDenied = false
            vm.setReminderEnabled(true)
        } else {
            reminderPermissionDenied = true
            vm.setReminderEnabled(false)
        }
    }
    val prefs = vm.archive.preferences
    val usesGemini = vm.aiVendor == AIVendor.GEMINI
    val keyTitle = stringResource(if (usesGemini) R.string.settings_gemini_key else R.string.settings_openai_key)
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    val version = remember(context) { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() }
    fun open(url: String) { runCatching { uriHandler.openUri(url) } }
    fun chooseSource(id: String) {
        if (vm.isRunning) return
        val choice = chat.mural.core.ConversationProvider.valueOf(id)
        if (choice == vm.conversationProvider) {
            if (choice == chat.mural.core.ConversationProvider.PERSONAL_KEY && !vm.hasKey) {
                keyDialogUseAfterSave = true; keyDialog = true
            }
            return
        }
        if (choice == chat.mural.core.ConversationProvider.HOSTED_MINUTES) {
            checkingSwitch = true
            switchBalance = null
        }
        // Selecting personal funding must not require an OpenAI key before Gemini can be chosen.
        switchTicket++; pendingSource = choice; sourceDialog = true
    }
    androidx.compose.runtime.LaunchedEffect(sourceDialog, pendingSource) {
        if (sourceDialog && pendingSource == chat.mural.core.ConversationProvider.HOSTED_MINUTES) {
            checkingSwitch = true; switchBalance = vm.checkHostedBalanceForSwitch(); checkingSwitch = false
        }
    }

    Column(Modifier.fillMaxSize()) {
        val title = when (page) {
            "memory" -> "Memory & ${vm.language.name} Level"
            "data" -> stringResource(R.string.settings_learning_data)
            "key" -> keyTitle
            "about" -> stringResource(R.string.settings_about)
            else -> stringResource(R.string.settings_navigation_title)
        }
        SettingsSheetHeader(title, onDismiss = { if (page == "main") onDismiss() else page = "main" },
            actionLabel = if (page == "main") R.string.settings_done else R.string.settings_back)
        LazyColumn(Modifier.weight(1f).testTag("settings-screen"), state = listState,
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp)) {
            when (page) {
                "main" -> {
                    item {
                        SettingsGroup {
                            Row(Modifier.fillMaxWidth().heightIn(min = 68.dp)
                                .testTag("managed-account-settings")
                                .clickable(enabled = onAccount != null && !vm.isRunning) { onAccount?.invoke() }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                MuralOrb(modifier = Modifier.size(38.dp), active = false)
                                Column(Modifier.weight(1f)) {
                                    Text(stringResource(R.string.account_title), style = MaterialTheme.typography.bodyLarge)
                                    Text(accountSummary ?: stringResource(R.string.settings_sign_in_optional),
                                        style = MaterialTheme.typography.bodySmall, color = MuralColors.Secondary)
                                }
                                MuralIcon(MuralSymbol.ChevronRight, Modifier.size(13.dp), color = MuralColors.Secondary)
                            }
                        }
                    }
                    item {
                        SettingsGroup(stringResource(R.string.settings_your_learning),
                            stringResource(if (vm.isRunning) R.string.settings_language_running_footer else R.string.settings_language_footer)) {
                            SettingsChoiceRow(stringResource(R.string.settings_learning_language), vm.language.settingsTitle, vm.language.id,
                                LanguageRegistry.all.map { it.id to it.settingsTitle }, "settings-learning-language", !vm.isRunning, vm::selectLanguage)
                            SettingsDivider()
                            SettingsMeaningSwitch(prefs.meaningVisible, vm::toggleMeaning)
                            SettingsDivider()
                            SettingsChoiceRow(stringResource(R.string.settings_meaning_language), prefs.meaningLanguage, prefs.meaningLanguage,
                                MeaningLanguages.all.map { it to it }, "settings-meaning-language", !vm.isRunning) {
                                vm.updatePreferences(prefs.copy(meaningLanguage = it))
                            }
                            SettingsDivider()
                            val activeMemory = vm.activeLearnerMemory
                            val memorySummary = buildList {
                                if (activeMemory.effectiveLevel.isNotEmpty()) add(activeMemory.effectiveLevel)
                                val noteCount = activeMemory.profileFacts.size + activeMemory.interestsAndGoals.size
                                if (noteCount > 0) add("$noteCount ${if (noteCount == 1) "note" else "notes"}")
                            }.joinToString(" · ")
                            SettingsRow(
                                "Memory & ${vm.language.name} Level",
                                memorySummary,
                                enabled = !vm.isRunning,
                                chevron = true,
                                modifier = Modifier.testTag("settings-memory"),
                                onClick = { page = "memory" },
                            )
                            Text(stringResource(R.string.settings_corrections_note), style = MaterialTheme.typography.bodySmall,
                                color = MuralColors.Secondary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
                        }
                    }
                    item {
                        SettingsGroup(stringResource(R.string.settings_section_conversation),
                            stringResource(R.string.settings_session_limit_note)) {
                            val limits = (listOf(5, 10, 15, 20, 30, 60) + prefs.sessionMinutes).distinct().sorted()
                            SettingsChoiceRow(stringResource(R.string.settings_conversation_limit), stringResource(R.string.settings_limit_minutes, prefs.sessionMinutes),
                                prefs.sessionMinutes.toString(), limits.map { it.toString() to stringResource(R.string.settings_limit_minutes, it) },
                                "settings-conversation-limit", !vm.isRunning) { vm.updatePreferences(prefs.copy(sessionMinutes = it.toInt())) }
                            SettingsDivider()
                            SettingsChoiceRow(
                                title = "AI Voice",
                                value = vm.activeGeminiVoice,
                                selected = vm.activeGeminiVoice,
                                options = GeminiLiveProtocol.PREBUILT_VOICES.map { it.name to it.label },
                                tag = "settings-gemini-voice",
                                enabled = !vm.isRunning,
                                onSelect = vm::selectGeminiVoice,
                            )
                            SettingsDivider()
                            SettingsChoiceRow(
                                title = "Coach Style",
                                value = vm.coachStyle.displayName,
                                selected = vm.coachStyle.name,
                                options = CoachStyle.entries.map { it.name to it.label },
                                tag = "settings-coach-style",
                                enabled = !vm.isRunning,
                                onSelect = { vm.selectCoachStyle(CoachStyle.fromNameOrDefault(it)) },
                            )
                        }
                    }
                    item {
                        val reminder = vm.learningSettings.reminder
                        val goal = vm.learningSettings.dailyGoal
                        val reminderHours = listOf(7, 8, 9, 10, 12, 14, 17, 18, 19, 20, 21, 22)
                        val goalMinutesOptions = listOf(5, 10, 15, 20)
                        val goalCardsOptions = listOf(5, 10, 15, 20)
                        SettingsGroup(
                            title = "Daily Goal & Reminder",
                            footer = if (reminderPermissionDenied) {
                                "Allow notifications in phone settings to receive daily reminders."
                            } else {
                                "Gentle local reminder only when you haven't practiced yet and have due cards or an open daily goal."
                            },
                        ) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .testTag("settings-daily-reminder-switch")
                                    .toggleable(
                                        value = reminder.enabled,
                                        role = Role.Switch,
                                        onValueChange = { turnOn ->
                                            if (!turnOn) {
                                                reminderPermissionDenied = false
                                                vm.setReminderEnabled(false)
                                            } else if (
                                                Build.VERSION.SDK_INT >= 33 &&
                                                ContextCompat.checkSelfPermission(
                                                    context,
                                                    Manifest.permission.POST_NOTIFICATIONS,
                                                ) != PackageManager.PERMISSION_GRANTED
                                            ) {
                                                reminderPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                            } else {
                                                reminderPermissionDenied = false
                                                vm.setReminderEnabled(true)
                                            }
                                        },
                                    )
                                    .heightIn(min = 52.dp)
                                    .padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("Daily Reminder", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                                Switch(
                                    checked = reminder.enabled,
                                    onCheckedChange = null,
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Color.White,
                                        checkedTrackColor = MuralColors.Secondary,
                                        uncheckedThumbColor = Color.White,
                                        uncheckedTrackColor = MuralColors.Secondary.copy(alpha = .18f),
                                        uncheckedBorderColor = Color.Transparent,
                                    ),
                                )
                            }
                            SettingsDivider()
                            SettingsChoiceRow(
                                title = "Reminder Time",
                                value = formatReminderHour(reminder.hour),
                                selected = reminder.hour.toString(),
                                options = reminderHours.map { it.toString() to formatReminderHour(it) },
                                tag = "settings-reminder-hour",
                                enabled = reminder.enabled,
                                onSelect = { vm.setReminderHour(it.toInt()) },
                            )
                            SettingsDivider()
                            SettingsChoiceRow(
                                title = "Daily Speaking Goal",
                                value = "${goal.targetSpeakingMinutes} min",
                                selected = goal.targetSpeakingMinutes.toString(),
                                options = goalMinutesOptions.map { it.toString() to "$it min" },
                                tag = "settings-daily-goal-minutes",
                                enabled = true,
                                onSelect = { vm.setDailyGoal(it.toInt(), goal.targetCardsReviewed) },
                            )
                            SettingsDivider()
                            SettingsChoiceRow(
                                title = "Daily Cards Goal",
                                value = "${goal.targetCardsReviewed} cards",
                                selected = goal.targetCardsReviewed.toString(),
                                options = goalCardsOptions.map { it.toString() to "$it cards" },
                                tag = "settings-daily-goal-cards",
                                enabled = true,
                                onSelect = { vm.setDailyGoal(goal.targetSpeakingMinutes, it.toInt()) },
                            )
                        }
                    }
                    item {
                        SettingsGroup(stringResource(R.string.settings_your_data)) {
                            SettingsRow(stringResource(R.string.settings_learning_data), symbol = SettingsSymbol.HISTORY,
                                chevron = true, modifier = Modifier.testTag("settings-data"), onClick = { page = "data" })
                        }
                    }
                    item {
                        SettingsGroup(stringResource(R.string.settings_advanced)) {
                            SettingsChoiceRow(stringResource(R.string.settings_conversation_access),
                                stringResource(if (vm.conversationProvider == chat.mural.core.ConversationProvider.HOSTED_MINUTES)
                                    R.string.settings_hosted_openai else R.string.settings_my_openai_key),
                                vm.conversationProvider.name,
                                listOf(chat.mural.core.ConversationProvider.HOSTED_MINUTES.name to stringResource(R.string.settings_hosted_openai),
                                    chat.mural.core.ConversationProvider.PERSONAL_KEY.name to stringResource(R.string.settings_my_openai_key)),
                                "settings-conversation-access", !vm.isRunning, ::chooseSource)
                            if (vm.conversationProvider == chat.mural.core.ConversationProvider.PERSONAL_KEY) {
                                SettingsDivider()
                                SettingsChoiceRow(stringResource(R.string.settings_ai_vendor),
                                    stringResource(if (usesGemini) R.string.settings_vendor_gemini else R.string.settings_vendor_openai),
                                    vm.aiVendor.name,
                                    listOf(AIVendor.GEMINI.name to stringResource(R.string.settings_vendor_gemini),
                                        AIVendor.OPENAI.name to stringResource(R.string.settings_vendor_openai)),
                                    "settings-ai-vendor", !vm.isRunning) { vm.selectAIVendor(AIVendor.valueOf(it)) }
                                Text(stringResource(R.string.settings_personal_key_note), style = MaterialTheme.typography.bodySmall,
                                    color = MuralColors.Secondary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
                            } else {
                                Text(stringResource(R.string.settings_gemini_hosted_unavailable), style = MaterialTheme.typography.bodySmall,
                                    color = MuralColors.Secondary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
                            }
                            if (vm.hasKey || vm.conversationProvider == chat.mural.core.ConversationProvider.PERSONAL_KEY) {
                                SettingsDivider()
                                SettingsRow(keyTitle,
                                    stringResource(if (vm.hasKey) R.string.settings_key_saved_short else R.string.settings_key_required),
                                    symbol = SettingsSymbol.KEY, chevron = true, modifier = Modifier.testTag("advanced-api-key"),
                                    onClick = { page = "key" })
                            }
                            vm.providerIssue?.let { issue ->
                                SettingsDivider()
                                SettingsRow(stringResource(R.string.settings_provider_issue), issueLabel(issue, context),
                                    chevron = true, modifier = Modifier.testTag("advanced-provider-issue"), onClick = { page = "key" })
                            }
                        }
                    }
                    item {
                        SettingsGroup(
                            title = "ANALYSIS & HELPER AI (FLASHCARDS & MEANING)",
                            footer = "Choose the AI provider and model used for Post-Session Mistake Reports & Flashcards, live Meaning translation, A little help, and Assessments. Live voice stays on Gemini Live.",
                        ) {
                            SettingsChoiceRow(
                                title = "Analysis Provider",
                                value = vm.teachingProvider.shortName,
                                selected = vm.teachingProvider.id,
                                options = TeachingProvider.entries.map { it.id to it.displayName },
                                tag = "settings-teaching-provider",
                                enabled = !vm.isRunning,
                            ) { selectedId ->
                                vm.selectTeachingProvider(TeachingProvider.fromId(selectedId))
                            }
                            SettingsDivider()
                            SettingsRow(
                                title = "Active Model",
                                value = vm.activeTeachingModelLabel,
                                chevron = vm.teachingProvider != TeachingProvider.DEFAULT_GEMINI,
                                enabled = vm.teachingProvider != TeachingProvider.DEFAULT_GEMINI,
                                modifier = Modifier.testTag("settings-teaching-model"),
                                onClick = {
                                    if (vm.teachingProvider != TeachingProvider.DEFAULT_GEMINI) {
                                        vm.refreshTeachingModels(vm.teachingProvider)
                                        showTeachingModelPicker = true
                                    }
                                },
                            )
                            if (vm.teachingProvider != TeachingProvider.DEFAULT_GEMINI) {
                                val activeProvider = vm.teachingProvider
                                val hasProviderKey = vm.hasTeachingKey(activeProvider)
                                SettingsDivider()
                                SettingsRow(
                                    title = "${activeProvider.shortName} API Key",
                                    value = stringResource(if (hasProviderKey) R.string.settings_key_saved_short else R.string.settings_key_required),
                                    symbol = SettingsSymbol.KEY,
                                    chevron = true,
                                    modifier = Modifier.testTag("settings-teaching-api-key"),
                                    onClick = { teachingKeyDialogProvider = activeProvider },
                                )
                                SettingsDivider()
                                SettingsRow(
                                    title = "Get ${activeProvider.shortName} API Key",
                                    chevron = true,
                                    onClick = { open(activeProvider.keysUrl) },
                                )
                                if (hasProviderKey) {
                                    SettingsDivider()
                                    SettingsRow(
                                        title = "Remove ${activeProvider.shortName} Key",
                                        tint = MuralColors.Red,
                                        onClick = { deleteTeachingKeyProvider = activeProvider },
                                    )
                                }
                            }
                        }
                    }
                    item {
                        SettingsGroup(
                            title = "Speech-to-Text (Groq Whisper)",
                            footer = "Free Groq tier requires no credit card. Live voice stays on Gemini Live while Groq Whisper refines your spoken transcripts for better reports, flashcards, and memory. Raw audio stays in memory only and is never saved to disk.",
                        ) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .testTag("settings-groq-stt-switch")
                                    .toggleable(
                                        value = vm.groqSttEnabled && vm.hasGroqKey,
                                        enabled = !vm.isRunning,
                                        role = Role.Switch,
                                        onValueChange = { turnOn ->
                                            if (turnOn && !vm.hasGroqKey) {
                                                groqKeyDialog = true
                                            } else {
                                                vm.updateGroqSttEnabled(turnOn)
                                            }
                                        },
                                    )
                                    .heightIn(min = 52.dp)
                                    .padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "Accurate Transcripts (Groq Whisper)",
                                    Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = if (!vm.isRunning) MuralColors.Ink else MuralColors.Secondary.copy(alpha = .55f),
                                )
                                Switch(
                                    checked = vm.groqSttEnabled && vm.hasGroqKey,
                                    onCheckedChange = null,
                                    enabled = !vm.isRunning,
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Color.White,
                                        checkedTrackColor = MuralColors.Secondary,
                                        uncheckedThumbColor = Color.White,
                                        uncheckedTrackColor = MuralColors.Secondary.copy(alpha = .18f),
                                        uncheckedBorderColor = Color.Transparent,
                                    ),
                                )
                            }
                            if (vm.groqAuthError && vm.hasGroqKey) {
                                SettingsDivider()
                                Text(
                                    "Groq API key was not accepted (401). Gemini transcripts remain active — replace your key below.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MuralColors.Red,
                                    modifier = Modifier
                                        .padding(horizontal = 16.dp, vertical = 10.dp)
                                        .testTag("settings-groq-auth-error"),
                                )
                            }
                            SettingsDivider()
                            SettingsChoiceRow(
                                title = "Whisper Model",
                                value = vm.groqSttModel.shortName,
                                selected = vm.groqSttModel.id,
                                options = GroqWhisperModel.entries.map { it.id to it.displayName },
                                tag = "settings-groq-model",
                                enabled = !vm.isRunning,
                                onSelect = { vm.selectGroqSttModel(GroqWhisperModel.fromId(it)) },
                            )
                            SettingsDivider()
                            SettingsRow(
                                title = "Groq API Key",
                                value = if (vm.hasGroqKey) "Saved" else "Optional",
                                symbol = SettingsSymbol.KEY,
                                chevron = true,
                                enabled = !vm.isRunning,
                                modifier = Modifier.testTag("settings-groq-api-key"),
                                onClick = { groqKeyDialog = true },
                            )
                            SettingsDivider()
                            SettingsRow(
                                title = "Get Free Groq API Key",
                                chevron = true,
                                enabled = !vm.isRunning,
                                modifier = Modifier.testTag("settings-groq-get-key"),
                                onClick = { open("https://console.groq.com/keys") },
                            )
                            if (vm.hasGroqKey) {
                                SettingsDivider()
                                SettingsRow(
                                    title = "Remove Groq Key",
                                    tint = MuralColors.Red,
                                    enabled = !vm.isRunning,
                                    modifier = Modifier.testTag("settings-groq-remove-key"),
                                    onClick = { deleteGroqKey = true },
                                )
                            }
                        }
                    }
                    item {
                        SettingsGroup(stringResource(R.string.settings_section_help_privacy)) {
                            SettingsRow(stringResource(R.string.common_contact_support), onClick = { open("https://mural.chat/support/") })
                            SettingsDivider()
                            SettingsRow(stringResource(R.string.common_privacy_policy), onClick = { open("https://mural.chat/privacy/") })
                            SettingsDivider()
                            SettingsRow(stringResource(R.string.common_terms_of_use), onClick = { open("https://mural.chat/terms/") })
                            SettingsDivider()
                            SettingsRow(stringResource(R.string.settings_about), chevron = true,
                                modifier = Modifier.testTag("settings-about"), onClick = { page = "about" })
                        }
                    }
                }
                "memory" -> {
                    val mem = vm.activeLearnerMemory
                    val autoLevelLabel = if (mem.estimatedLevel.isNotEmpty()) {
                        "Auto (detected: ${mem.estimatedLevel})"
                    } else {
                        "Auto (detect from calls)"
                    }
                    val selectedLevel = mem.manualLevelOverride ?: "AUTO"
                    val displayedLevel = mem.manualLevelOverride ?: autoLevelLabel
                    val levelOptions = listOf("AUTO" to autoLevelLabel) + CEFR_LEVELS.map { it to it }

                    item {
                        SettingsGroup(
                            title = "Adaptation & Level",
                            footer = "Stored locally on this phone per language. Manual level selection overrides automatic detection.",
                        ) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .testTag("memory-auto-update-switch")
                                    .toggleable(
                                        value = mem.autoUpdateEnabled,
                                        enabled = !vm.isRunning,
                                        role = Role.Switch,
                                        onValueChange = { vm.setLearnerMemoryAutoUpdate(it) },
                                    )
                                    .heightIn(min = 52.dp)
                                    .padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("Auto-update memory after calls", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                                Switch(
                                    checked = mem.autoUpdateEnabled,
                                    onCheckedChange = null,
                                    enabled = !vm.isRunning,
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Color.White,
                                        checkedTrackColor = MuralColors.Secondary,
                                        uncheckedThumbColor = Color.White,
                                        uncheckedTrackColor = MuralColors.Secondary.copy(alpha = .18f),
                                        uncheckedBorderColor = Color.Transparent,
                                    ),
                                )
                            }
                            SettingsDivider()
                            SettingsChoiceRow(
                                title = "${vm.language.name} Level",
                                value = displayedLevel,
                                selected = selectedLevel,
                                options = levelOptions,
                                tag = "memory-level-picker",
                                enabled = !vm.isRunning,
                            ) { choice ->
                                vm.setLearnerManualLevel(if (choice == "AUTO") null else choice)
                            }
                        }
                    }

                    item {
                        SettingsGroup(title = "About You (Profile)") {
                            if (mem.profileFacts.isEmpty()) {
                                Text(
                                    "No profile facts yet (e.g., name, job, studies, city).",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MuralColors.Secondary,
                                    modifier = Modifier.padding(16.dp),
                                )
                            } else {
                                mem.profileFacts.forEachIndexed { idx, fact ->
                                    if (idx > 0) SettingsDivider()
                                    MemoryItemRow(
                                        text = fact,
                                        enabled = !vm.isRunning,
                                        editTag = "memory-profile-item-$idx",
                                        deleteTag = "memory-delete-profile-$idx",
                                        onEdit = { memoryEditDialog = MemoryEditRequest.ProfileFact(idx, fact) },
                                        onDelete = { vm.deleteProfileFact(idx) },
                                    )
                                }
                            }
                            SettingsDivider()
                            SettingsRow(
                                title = "+ Add profile fact",
                                enabled = !vm.isRunning && mem.profileFacts.size < LearnerMemory.MAX_PROFILE_FACTS,
                                modifier = Modifier.testTag("memory-add-profile-fact"),
                                onClick = { memoryEditDialog = MemoryEditRequest.ProfileFact(null, "") },
                            )
                        }
                    }

                    item {
                        SettingsGroup(title = "Interests & Goals") {
                            if (mem.interestsAndGoals.isEmpty()) {
                                Text(
                                    "No interests or learning goals saved yet.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MuralColors.Secondary,
                                    modifier = Modifier.padding(16.dp),
                                )
                            } else {
                                mem.interestsAndGoals.forEachIndexed { idx, interest ->
                                    if (idx > 0) SettingsDivider()
                                    MemoryItemRow(
                                        text = interest,
                                        enabled = !vm.isRunning,
                                        editTag = "memory-interest-item-$idx",
                                        deleteTag = "memory-delete-interest-$idx",
                                        onEdit = { memoryEditDialog = MemoryEditRequest.InterestOrGoal(idx, interest) },
                                        onDelete = { vm.deleteInterestOrGoal(idx) },
                                    )
                                }
                            }
                            SettingsDivider()
                            SettingsRow(
                                title = "+ Add interest or goal",
                                enabled = !vm.isRunning && mem.interestsAndGoals.size < LearnerMemory.MAX_INTERESTS_AND_GOALS,
                                modifier = Modifier.testTag("memory-add-interest"),
                                onClick = { memoryEditDialog = MemoryEditRequest.InterestOrGoal(null, "") },
                            )
                        }
                    }

                    item {
                        SettingsGroup(
                            title = "Speaking Adaptation & Weaknesses",
                            footer = "Guides sentence length, vocabulary simplicity, pace, and focus areas during calls.",
                        ) {
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .testTag("memory-edit-speaking-guidance")
                                    .clickable(enabled = !vm.isRunning) {
                                        memoryEditDialog = MemoryEditRequest.SpeakingGuidance(mem.speakingGuidance)
                                    }
                                    .padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    mem.speakingGuidance.ifBlank {
                                        "Tap to add guidance (e.g., speak calmly, keep sentences concise, practice past tense)."
                                    },
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = if (mem.speakingGuidance.isBlank()) MuralColors.Secondary else MuralColors.Ink,
                                )
                            }
                        }
                    }

                    item {
                        SettingsGroup(
                            title = "Recent Topics",
                            footer = "Helps Mural open free conversations with fresh topics without repeating recent calls.",
                        ) {
                            if (mem.recentTopics.isEmpty()) {
                                Text(
                                    "Recent conversation topics will appear after your calls.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MuralColors.Secondary,
                                    modifier = Modifier.padding(16.dp),
                                )
                            } else {
                                mem.recentTopics.forEachIndexed { idx, topicMem ->
                                    if (idx > 0) SettingsDivider()
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                            Text(topicMem.topic, style = MaterialTheme.typography.titleSmall)
                                            if (topicMem.oneLineSummary.isNotBlank()) {
                                                Text(
                                                    topicMem.oneLineSummary,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MuralColors.Secondary,
                                                )
                                            }
                                            if (topicMem.followUpHint.isNotBlank()) {
                                                Text(
                                                    "Follow-up: ${topicMem.followUpHint}",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MuralColors.Secondary,
                                                )
                                            }
                                            Text(
                                                formatEpochMillis(topicMem.dateMillis),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MuralColors.Secondary.copy(alpha = .75f),
                                            )
                                        }
                                        SoftRoundButton(
                                            symbol = MuralSymbol.Trash,
                                            description = "Remove ${topicMem.topic}",
                                            onClick = { vm.deleteRecentTopic(idx) },
                                            enabled = !vm.isRunning,
                                            diameter = 34.dp,
                                            tint = Color(0xFFFDECE9),
                                            modifier = Modifier.testTag("memory-delete-recent-topic-$idx"),
                                        )
                                    }
                                }
                            }
                        }
                    }

                    item {
                        SettingsGroup {
                            SettingsRow(
                                title = "Clear Memory",
                                enabled = !vm.isRunning && (mem.hasContent() || !mem.autoUpdateEnabled || mem.manualLevelOverride != null),
                                tint = MuralColors.Red,
                                modifier = Modifier.testTag("memory-clear-all"),
                                onClick = { confirmClearMemory = true },
                            )
                        }
                    }
                }
                "data" -> item {
                    SettingsGroup(footer = stringResource(R.string.settings_backup_footer)) {
                        SettingsRow(stringResource(R.string.settings_export_backup), enabled = !vm.isRunning,
                            symbol = SettingsSymbol.EXPORT, onClick = onExport)
                        SettingsDivider()
                        SettingsRow(stringResource(R.string.settings_import_backup), enabled = !vm.isRunning,
                            symbol = SettingsSymbol.IMPORT, onClick = onImport)
                        SettingsDivider()
                        SettingsRow(stringResource(R.string.history_section_title), chevron = true,
                            modifier = Modifier.testTag("settings-history"), onClick = { history = true })
                        SettingsDivider()
                        SettingsRow(stringResource(R.string.settings_delete_all_data), enabled = !vm.isRunning,
                            tint = MuralColors.Red, onClick = { deleteAll = true })
                    }
                }
                "key" -> item {
                    SettingsGroup(footer = stringResource(if (usesGemini) R.string.settings_gemini_key_owner_footer else R.string.settings_key_owner_footer)) {
                        SettingsRow(keyTitle,
                            stringResource(if (vm.hasKey) R.string.settings_key_saved_short else R.string.settings_key_required))
                        SettingsDivider()
                        SettingsRow(stringResource(if (vm.hasKey) R.string.settings_replace_key else R.string.settings_save_key),
                            enabled = !vm.isRunning, chevron = true, onClick = {
                                keyDialogUseAfterSave = vm.conversationProvider == chat.mural.core.ConversationProvider.PERSONAL_KEY && !vm.hasKey
                                keyDialog = true
                            })
                        SettingsDivider()
                        SettingsRow(stringResource(R.string.settings_open_api_keys), onClick = {
                            open(if (usesGemini) "https://aistudio.google.com/api-keys" else "https://platform.openai.com/api-keys")
                        })
                        if (vm.hasKey) {
                            SettingsDivider()
                            SettingsRow(stringResource(R.string.settings_remove_key), enabled = !vm.isRunning,
                                tint = MuralColors.Red, onClick = { deleteKey = true })
                        }
                        vm.providerIssue?.let { issue ->
                            SettingsDivider()
                            Text(issueLabel(issue, context), style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(16.dp).testTag("settings-provider-issue"))
                            if (vm.conversationProvider == chat.mural.core.ConversationProvider.PERSONAL_KEY)
                                SettingsRow(stringResource(R.string.settings_switch_hosted_title), onClick = {
                                    pendingSource = chat.mural.core.ConversationProvider.HOSTED_MINUTES; sourceDialog = true
                                })
                        }
                        SettingsDivider()
                        SettingsRow(stringResource(R.string.settings_usage_billing_link),
                            onClick = { open(if (usesGemini) "https://aistudio.google.com/usage" else "https://platform.openai.com/usage") })
                        if (vm.conversationProvider == chat.mural.core.ConversationProvider.PERSONAL_KEY) {
                            val usage = UsageSummary.of(vm.archive.sessions)
                            SettingsDivider()
                            SettingsRow(stringResource(R.string.settings_voice_time_label), usage.voiceTime)
                            SettingsDivider()
                            val elapsedSeconds = vm.geminiElapsedSeconds.toLong().coerceAtLeast(0)
                            SettingsRow(stringResource(R.string.settings_gemini_elapsed_label),
                                stringResource(R.string.settings_elapsed_format, elapsedSeconds / 60, elapsedSeconds % 60))
                            SettingsDivider()
                            SettingsRow(stringResource(R.string.settings_search_calls_label), usage.searchCalls.toString())
                            Text(stringResource(R.string.settings_recorded_here), style = MaterialTheme.typography.bodySmall,
                                color = MuralColors.Secondary, modifier = Modifier.padding(16.dp))
                        }
                    }
                }
                "about" -> item {
                    SettingsGroup {
                        Text(stringResource(R.string.settings_app_version_footer, version),
                            style = MaterialTheme.typography.bodySmall, color = MuralColors.Secondary,
                            modifier = Modifier.padding(16.dp))
                        SettingsDivider()
                        SettingsRow(stringResource(R.string.settings_section_ai_permission), chevron = true,
                            modifier = Modifier.testTag("settings-ai-permission"), onClick = { permissionDetails = true })
                        SettingsDivider()
                        SettingsRow(stringResource(R.string.settings_openai_data_controls),
                            onClick = { open("https://developers.openai.com/api/docs/guides/your-data") })
                        SettingsDivider()
                        SettingsRow(stringResource(R.string.settings_google_data_controls),
                            onClick = { open("https://ai.google.dev/gemini-api/docs/zdr") })
                        Text(stringResource(R.string.settings_data_use_footer), style = MaterialTheme.typography.bodySmall,
                            color = MuralColors.Secondary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                        SettingsDivider()
                        SettingsRow(stringResource(R.string.settings_open_source_notices), chevron = true,
                            onClick = { notices = true })
                    }
                }
            }
        }
    }
    if (sourceDialog) AlertDialog(onDismissRequest = { switchTicket++; sourceDialog = false },
        title = { Text(stringResource(if (pendingSource == chat.mural.core.ConversationProvider.HOSTED_MINUTES)
            R.string.settings_switch_hosted_title else R.string.settings_switch_key_title)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(if (pendingSource == chat.mural.core.ConversationProvider.HOSTED_MINUTES)
                R.string.settings_switch_hosted_note else R.string.settings_switch_key_note))
            if (pendingSource == chat.mural.core.ConversationProvider.HOSTED_MINUTES) {
                Text(when {
                    checkingSwitch -> stringResource(R.string.settings_checking_minutes)
                    switchBalance == null -> stringResource(R.string.settings_minutes_unavailable)
                    switchBalance == 0L -> stringResource(R.string.settings_no_minutes)
                    else -> MinuteBalanceTime.roundedSeconds(switchBalance!!).let { seconds ->
                        stringResource(R.string.account_time_remaining_format, seconds / 60, seconds % 60)
                    }
                })
                if (!checkingSwitch && switchBalance == null) MuralTextButton(onClick = {
                    val ticket = switchTicket
                    scope.launch {
                        checkingSwitch = true
                        val latest = vm.checkHostedBalanceForSwitch()
                        if (sourceDialog && switchTicket == ticket && pendingSource == chat.mural.core.ConversationProvider.HOSTED_MINUTES) {
                            switchBalance = latest
                            checkingSwitch = false
                        }
                    }
                }) { Text(stringResource(R.string.settings_try_again)) }
            }
        } },
        confirmButton = { MuralTextButton(onClick = {
            if (pendingSource == chat.mural.core.ConversationProvider.PERSONAL_KEY) {
                vm.selectConversationProvider(chat.mural.core.ConversationProvider.PERSONAL_KEY); sourceDialog = false
            } else {
                val ticket = switchTicket
                scope.launch {
                checkingSwitch = true
                val latest = vm.checkHostedBalanceForSwitch()
                if (sourceDialog && switchTicket == ticket && pendingSource == chat.mural.core.ConversationProvider.HOSTED_MINUTES &&
                    !vm.isRunning) {
                    checkingSwitch = false
                    switchBalance = latest
                    if (latest != null && latest > 0) {
                        // The confirmation names both changes: provider and funding stay separate preferences.
                        vm.selectAIVendor(AIVendor.OPENAI)
                        vm.selectConversationProvider(chat.mural.core.ConversationProvider.HOSTED_MINUTES)
                        sourceDialog = false
                    }
                }
                }
            }
        }, modifier = Modifier.testTag("settings-source-confirm"), enabled = !vm.isRunning && (pendingSource != chat.mural.core.ConversationProvider.HOSTED_MINUTES ||
            (!checkingSwitch && switchBalance != null && switchBalance!! > 0))) {
            Text(stringResource(if (pendingSource == chat.mural.core.ConversationProvider.HOSTED_MINUTES)
                R.string.settings_confirm_hosted_openai else R.string.settings_my_openai_key))
        } }, dismissButton = { MuralTextButton(onClick = { switchTicket++; sourceDialog = false }) { Text(stringResource(R.string.common_cancel)) } })
    if (keyDialog) KeyDialog(vm, keyDialogUseAfterSave, onDismiss = { keyDialog = false })
    if (notices) NoticesDialog(onDismiss = { notices = false })
    if (history) SettingsHistorySheet(vm, onDismiss = { history = false }, onSelect = { transcript = it }, onDelete = { deleteSession = it })
    if (permissionDetails) AlertDialog(onDismissRequest = { permissionDetails = false },
        title = { Text(stringResource(R.string.settings_section_ai_permission)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(stringResource(if (prefs.aiConsentVersion == AI_CONSENT_VERSION) R.string.settings_ai_consent_accepted else R.string.settings_ai_consent_not_accepted),
                style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.settings_ai_permission_summary), color = MuralColors.Secondary)
        } },
        confirmButton = {
            if (prefs.aiConsentVersion == AI_CONSENT_VERSION) MuralTextButton(onClick = { permissionDetails = false; revokeConsent = true },
                enabled = !vm.isRunning, modifier = Modifier.testTag("revoke-ai-consent")) {
                Text(stringResource(R.string.settings_revoke_consent_button), color = MuralColors.Red)
            } else MuralTextButton(onClick = { permissionDetails = false; onReviewConsent() },
                enabled = !vm.isRunning, modifier = Modifier.testTag("review-ai-consent")) { Text(stringResource(R.string.settings_review_consent_button)) }
        }, dismissButton = { MuralTextButton(onClick = { permissionDetails = false }) { Text(stringResource(R.string.common_close)) } })
    if (deleteKey) ConfirmDialog(stringResource(R.string.settings_delete_key_confirm_title), stringResource(R.string.settings_delete_key_confirm_message), stringResource(R.string.common_delete), {
        vm.deleteKey(); deleteKey = false
    }, { deleteKey = false })
    if (deleteAll) ConfirmDialog(stringResource(R.string.settings_delete_all_confirm_title), stringResource(R.string.settings_delete_all_confirm_message), stringResource(R.string.settings_delete_all_confirm_button), {
        vm.deleteLearningData(); deleteAll = false
    }, { deleteAll = false })
    if (revokeConsent) ConfirmDialog(stringResource(R.string.settings_revoke_consent_confirm_title),
        stringResource(R.string.settings_revoke_consent_confirm_message), stringResource(R.string.settings_revoke_consent_button), {
            vm.updatePreferences(vm.archive.preferences.copy(aiConsentVersion = null)); revokeConsent = false
        }, { revokeConsent = false })
    deleteSession?.let { session -> ConfirmDialog(stringResource(R.string.history_delete_session_confirm_title), session.title, stringResource(R.string.common_delete), {
        vm.deleteSession(session.id); deleteSession = null
    }, { deleteSession = null }) }
    transcript?.let { TranscriptDialog(vm, it, onDismiss = { transcript = null }) }
    teachingKeyDialogProvider?.let { provider ->
        TeachingKeyDialog(
            provider = provider,
            onSave = { rawKey ->
                vm.saveTeachingKey(provider, rawKey)
                teachingKeyDialogProvider = null
            },
            onDismiss = { teachingKeyDialogProvider = null },
        )
    }
    deleteTeachingKeyProvider?.let { provider ->
        ConfirmDialog(
            title = "Remove ${provider.shortName} Key",
            message = "Remove your saved ${provider.shortName} API key from this device?",
            confirm = stringResource(R.string.common_delete),
            onConfirm = {
                vm.deleteTeachingKey(provider)
                deleteTeachingKeyProvider = null
            },
            onDismiss = { deleteTeachingKeyProvider = null },
        )
    }
    if (showTeachingModelPicker && vm.teachingProvider != TeachingProvider.DEFAULT_GEMINI) {
        TeachingModelPickerDialog(
            vm = vm,
            provider = vm.teachingProvider,
            onDismiss = { showTeachingModelPicker = false },
        )
    }
    if (groqKeyDialog) {
        GroqKeyDialog(
            onSave = { rawKey ->
                vm.saveGroqKey(rawKey)
                groqKeyDialog = false
            },
            onDismiss = { groqKeyDialog = false },
        )
    }
    if (deleteGroqKey) {
        ConfirmDialog(
            title = "Remove Groq Key",
            message = "Remove your saved Groq API key from this device?",
            confirm = stringResource(R.string.common_delete),
            onConfirm = {
                vm.deleteGroqKey()
                deleteGroqKey = false
            },
            onDismiss = { deleteGroqKey = false },
        )
    }
    memoryEditDialog?.let { req ->
        MemoryEditDialog(
            request = req,
            onSave = { value ->
                when (req) {
                    is MemoryEditRequest.ProfileFact -> vm.upsertProfileFact(req.index, value)
                    is MemoryEditRequest.InterestOrGoal -> vm.upsertInterestOrGoal(req.index, value)
                    is MemoryEditRequest.SpeakingGuidance -> vm.updateSpeakingGuidance(value)
                }
                memoryEditDialog = null
            },
            onDismiss = { memoryEditDialog = null },
        )
    }
    if (confirmClearMemory) {
        ConfirmDialog(
            title = "Clear ${vm.language.name} memory?",
            message = "Remove saved profile facts, interests, speaking guidance, and recent topics for ${vm.language.name}? Conversations and sentence flashcards remain intact.",
            confirm = "Clear Memory",
            onConfirm = {
                vm.clearLearnerMemory()
                confirmClearMemory = false
            },
            onDismiss = { confirmClearMemory = false },
        )
    }
}

private sealed interface MemoryEditRequest {
    data class ProfileFact(val index: Int?, val initial: String) : MemoryEditRequest
    data class InterestOrGoal(val index: Int?, val initial: String) : MemoryEditRequest
    data class SpeakingGuidance(val initial: String) : MemoryEditRequest
}

@Composable
private fun MemoryItemRow(
    text: String,
    enabled: Boolean,
    editTag: String,
    deleteTag: String,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .testTag(editTag)
            .clickable(enabled = enabled, onClick = onEdit)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) MuralColors.Ink else MuralColors.Secondary,
            modifier = Modifier.weight(1f),
        )
        SoftRoundButton(
            symbol = MuralSymbol.Trash,
            description = "Remove $text",
            onClick = onDelete,
            enabled = enabled,
            diameter = 34.dp,
            tint = Color(0xFFFDECE9),
            modifier = Modifier.testTag(deleteTag),
        )
    }
}

@Composable
private fun MemoryEditDialog(
    request: MemoryEditRequest,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val maxChars = when (request) {
        is MemoryEditRequest.SpeakingGuidance -> LearnerMemory.MAX_SPEAKING_GUIDANCE_CHARS
        else -> LearnerMemory.MAX_FACT_CHARS
    }
    val initial = when (request) {
        is MemoryEditRequest.ProfileFact -> request.initial
        is MemoryEditRequest.InterestOrGoal -> request.initial
        is MemoryEditRequest.SpeakingGuidance -> request.initial
    }
    val title = when (request) {
        is MemoryEditRequest.ProfileFact -> if (request.index == null) "Add profile fact" else "Edit profile fact"
        is MemoryEditRequest.InterestOrGoal -> if (request.index == null) "Add interest or goal" else "Edit interest or goal"
        is MemoryEditRequest.SpeakingGuidance -> "Speaking adaptation & weaknesses"
    }
    val placeholder = when (request) {
        is MemoryEditRequest.ProfileFact -> "e.g., Computer science student in Cairo"
        is MemoryEditRequest.InterestOrGoal -> "e.g., Backend engineering, chess, fluent tech interviews"
        is MemoryEditRequest.SpeakingGuidance -> "e.g., Speak calmly, keep turns concise, remind about past tense"
    }
    var value by remember(request) { mutableStateOf(initial) }
    val allowSave = when (request) {
        is MemoryEditRequest.SpeakingGuidance -> true
        else -> value.isNotBlank()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MuralTextField(
                    value = value,
                    onValueChange = { value = it.take(maxChars) },
                    modifier = Modifier.fillMaxWidth().testTag("memory-edit-dialog-input"),
                    minLines = if (request is MemoryEditRequest.SpeakingGuidance) 3 else 1,
                    maxLines = if (request is MemoryEditRequest.SpeakingGuidance) 6 else 3,
                    label = { Text(placeholder) },
                )
                Text(
                    "${value.length}/$maxChars",
                    style = MaterialTheme.typography.labelSmall,
                    color = MuralColors.Secondary,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(value.trim()) },
                enabled = allowSave,
                modifier = Modifier.testTag("memory-edit-dialog-save"),
            ) {
                Text(stringResource(R.string.common_save))
            }
        },
        dismissButton = {
            MuralTextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}

private fun issueLabel(kind: ProviderFailureKind, context: android.content.Context): String = context.getString(when (kind) {
    ProviderFailureKind.creditExhausted -> R.string.settings_issue_credit
    ProviderFailureKind.spendLimit -> R.string.settings_issue_spend
    ProviderFailureKind.usageLimit -> R.string.settings_issue_usage
    ProviderFailureKind.quota -> R.string.settings_issue_quota
    ProviderFailureKind.authentication -> R.string.settings_issue_key
    else -> R.string.settings_issue_other
})

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsHistorySheet(vm: MuralViewModel, onDismiss: () -> Unit, onSelect: (SessionRecord) -> Unit, onDelete: (SessionRecord) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MuralColors.Cream,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxHeight(.9f)) {
            SettingsSheetHeader(stringResource(R.string.history_section_title), onDismiss)
            LazyColumn(Modifier.weight(1f).testTag("settings-history-list"), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (vm.archive.sessions.isEmpty()) item {
                    Text(stringResource(R.string.history_empty), style = MaterialTheme.typography.bodyLarge, color = MuralColors.Secondary,
                        modifier = Modifier.padding(16.dp))
                }
                items(vm.archive.sessions.sortedByDescending { it.startedAt }, key = { it.id }) { session ->
                    SettingsGroup {
                        Column(Modifier.fillMaxWidth().clickable { onSelect(session) }.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(session.title, style = MaterialTheme.typography.titleMedium)
                            Text("${LanguageRegistry.get(session.languageID)?.name ?: session.languageID} · ${formatDate(session.startedAt)}",
                                color = MuralColors.Secondary, style = MaterialTheme.typography.bodySmall)
                        }
                        SettingsDivider()
                        SettingsRow(stringResource(R.string.common_delete), enabled = !vm.isRunning, tint = MuralColors.Red, onClick = { onDelete(session) })
                    }
                }
            }
        }
    }
}

@Composable
private fun KeyDialog(vm: MuralViewModel, useAfterSave: Boolean, onDismiss: () -> Unit) {
    // Intentionally starts empty even when a key exists; secrets never flow back into Compose state.
    var key by remember { mutableStateOf("") }
    Dialog(onDismissRequest = { key = ""; onDismiss() }) {
        val view = LocalView.current
        DisposableEffect(view) {
            val window = (view.parent as? DialogWindowProvider)?.window
            val wasSecure = window?.attributes?.flags?.and(WindowManager.LayoutParams.FLAG_SECURE)?.let { it != 0 } ?: false
            window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            onDispose { if (!wasSecure) window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
        }
        Surface(shape = RoundedCornerShape(28.dp), color = MuralColors.Surface) {
            Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(15.dp)) {
                Text(stringResource(if (vm.aiVendor == AIVendor.GEMINI) R.string.settings_gemini_key else R.string.settings_openai_key),
                    style = MaterialTheme.typography.headlineMedium)
                Text(stringResource(R.string.settings_key_dialog_note), color = MuralColors.Secondary)
                MuralTextField(
                    key,
                    { key = it.take(500) },
                    Modifier.fillMaxWidth().testTag("api-key-input").semantics { password() },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    label = { Text(stringResource(R.string.settings_key_dialog_field_label)) },
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    MuralTextButton(onClick = { key = ""; onDismiss() }) { Text(stringResource(R.string.common_cancel)) }
                    Button(onClick = { vm.saveKey(key.trim(), useAfterSave); key = ""; onDismiss() }, enabled = key.isNotBlank()) { Text(stringResource(R.string.common_save)) }
                }
            }
        }
    }
}

@Composable
private fun ConfirmDialog(title: String, message: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { MuralTextButton(onClick = onConfirm) { Text(confirm, color = MuralColors.Red) } },
        dismissButton = { MuralTextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
private fun TeachingKeyDialog(
    provider: TeachingProvider,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var key by remember { mutableStateOf("") }
    Dialog(onDismissRequest = { key = ""; onDismiss() }) {
        val view = LocalView.current
        DisposableEffect(view) {
            val window = (view.parent as? DialogWindowProvider)?.window
            val wasSecure = window?.attributes?.flags?.and(WindowManager.LayoutParams.FLAG_SECURE)?.let { it != 0 } ?: false
            window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            onDispose { if (!wasSecure) window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
        }
        Surface(shape = RoundedCornerShape(28.dp), color = MuralColors.Surface) {
            Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(15.dp)) {
                Text("${provider.shortName} API Key", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Your ${provider.shortName} key is encrypted in Android Keystore and used for Post-Session Mistake Reports & Flashcards, Meaning translation, A little help, and Assessments.",
                    color = MuralColors.Secondary,
                )
                MuralTextField(
                    key,
                    { key = it.take(500) },
                    Modifier.fillMaxWidth().testTag("teaching-api-key-input").semantics { password() },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    label = { Text("${provider.shortName} API Key") },
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    MuralTextButton(onClick = { key = ""; onDismiss() }) { Text(stringResource(R.string.common_cancel)) }
                    Button(
                        onClick = { onSave(key.trim()); key = "" },
                        enabled = key.trim().length >= 20,
                        modifier = Modifier.testTag("save-teaching-api-key"),
                    ) { Text(stringResource(R.string.common_save)) }
                }
            }
        }
    }
}

@Composable
private fun GroqKeyDialog(
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var key by remember { mutableStateOf("") }
    Dialog(onDismissRequest = { key = ""; onDismiss() }) {
        val view = LocalView.current
        DisposableEffect(view) {
            val window = (view.parent as? DialogWindowProvider)?.window
            val wasSecure = window?.attributes?.flags?.and(WindowManager.LayoutParams.FLAG_SECURE)?.let { it != 0 } ?: false
            window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            onDispose { if (!wasSecure) window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
        }
        Surface(shape = RoundedCornerShape(28.dp), color = MuralColors.Surface) {
            Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(15.dp)) {
                Text("Groq API Key", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Your Groq key is encrypted in Android Keystore and never included in backups. Spoken audio stays in memory only while refining your transcripts.",
                    color = MuralColors.Secondary,
                )
                MuralTextField(
                    key,
                    { key = it.take(500) },
                    Modifier.fillMaxWidth().testTag("groq-api-key-input").semantics { password() },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    label = { Text("Groq API Key") },
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    MuralTextButton(onClick = { key = ""; onDismiss() }) { Text(stringResource(R.string.common_cancel)) }
                    Button(
                        onClick = { onSave(key.trim()); key = "" },
                        enabled = key.trim().length >= 20,
                        modifier = Modifier.testTag("save-groq-api-key"),
                    ) { Text(stringResource(R.string.common_save)) }
                }
            }
        }
    }
}

@Composable
private fun TeachingModelPickerDialog(
    vm: MuralViewModel,
    provider: TeachingProvider,
    onDismiss: () -> Unit,
) {
    val currentModel = vm.activeTeachingModelLabel
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var customModelInput by rememberSaveable(currentModel) { mutableStateOf(currentModel) }
    val allModels = vm.availableTeachingModels
    val filteredModels = remember(allModels, searchQuery) {
        val q = searchQuery.trim()
        if (q.isEmpty()) allModels
        else allModels.filter {
            it.id.contains(q, ignoreCase = true) ||
                it.name.contains(q, ignoreCase = true) ||
                it.description.contains(q, ignoreCase = true)
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth().heightIn(max = 640.dp).testTag("teaching-model-picker-dialog"),
            shape = RoundedCornerShape(28.dp),
            color = MuralColors.Cream,
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${provider.shortName} Models", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            if (vm.loadingTeachingModels) "Fetching live models from ${provider.shortName}..."
                            else "${allModels.size} models available · Select or enter any model ID",
                            style = MaterialTheme.typography.bodySmall,
                            color = MuralColors.Secondary,
                        )
                    }
                    MuralTextButton(onClick = { vm.refreshTeachingModels(provider) }) {
                        Text("Refresh")
                    }
                }

                MuralTextField(
                    value = customModelInput,
                    onValueChange = { customModelInput = it.take(160) },
                    modifier = Modifier.fillMaxWidth().testTag("custom-teaching-model-input"),
                    singleLine = true,
                    label = { Text("Selected / Custom Model ID") },
                )

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    MuralTextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
                    Button(
                        onClick = {
                            vm.selectTeachingModel(provider, customModelInput)
                            onDismiss()
                        },
                        enabled = customModelInput.isNotBlank(),
                        modifier = Modifier.testTag("confirm-teaching-model"),
                    ) {
                        Text("Use Model")
                    }
                }

                MuralTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it.take(100) },
                    modifier = Modifier.fillMaxWidth().testTag("search-teaching-models-input"),
                    singleLine = true,
                    label = { Text("Search ${provider.shortName} models...") },
                )

                LazyColumn(
                    modifier = Modifier.weight(1f, fill = false).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(filteredModels, key = { it.id }) { option ->
                        val isSelected = option.id == currentModel
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    vm.selectTeachingModel(provider, option.id)
                                    onDismiss()
                                },
                            shape = RoundedCornerShape(16.dp),
                            color = if (isSelected) MuralColors.Butter else MuralColors.Surface,
                        ) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(
                                    option.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                )
                                Text(
                                    option.id,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MuralColors.Secondary,
                                )
                                if (option.description.isNotBlank()) {
                                    Text(
                                        option.description,
                                        style = MaterialTheme.typography.bodySmall,
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

@Composable
fun TranscriptDialog(vm: MuralViewModel, session: SessionRecord, onDismiss: () -> Unit) {
    var correcting by remember { mutableStateOf<Passage?>(null) }
    val liveSession = vm.archive.sessions.firstOrNull { it.id == session.id }
        ?: vm.session?.takeIf { it.id == session.id }
        ?: session
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(28.dp), color = MuralColors.Surface) {
            LazyColumn(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                item {
                    Text(liveSession.title, style = MaterialTheme.typography.headlineMedium)
                    Text("${formatDate(liveSession.startedAt)} · ${pluralStringResource(R.plurals.history_passages_count, liveSession.passages.size, liveSession.passages.size)}", color = MuralColors.Secondary)
                }
                if (!vm.isRunning) {
                    item {
                        Button(
                            onClick = {
                                onDismiss()
                                vm.openReportForSession(liveSession.id)
                            },
                            modifier = Modifier.fillMaxWidth().testTag("transcript-open-session-report"),
                        ) {
                            Text("View Mistake Report & Flashcards")
                        }
                    }
                }
                items(liveSession.passages, key = { it.id }) { passage ->
                    Column(
                        Modifier.fillMaxWidth().background(
                            if (passage.speaker == Speaker.user) MuralColors.SurfaceBright else MuralColors.Peach,
                            RoundedCornerShape(18.dp),
                        ).padding(15.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(if (passage.speaker == Speaker.user) R.string.history_speaker_you else R.string.history_speaker_mural),
                                style = MaterialTheme.typography.labelSmall, color = MuralColors.Secondary, modifier = Modifier.weight(1f))
                            if (passage.speaker == Speaker.assistant && passage.text.isNotBlank()) {
                                ReportUtteranceAction(onClick = {
                                    vm.reportUtterance(liveSession.id, passage.id)
                                    onDismiss()
                                }, modifier = Modifier.testTag("report-history-${passage.id}"))
                            }
                        }
                        SelectionContainer { Text(passage.text) }
                        if (liveSession.languageID == "zh") PinyinHelp(passage.text)
                        if (passage.speaker == Speaker.user && !vm.isRunning) MuralTextButton(onClick = { correcting = passage }) { Text(stringResource(R.string.history_edit_passage_button)) }
                    }
                }
                liveSession.topics.flatMap { it.sources }.filter { it.safeUrl() != null }.takeIf { it.isNotEmpty() }?.let { sources ->
                    item { Text(stringResource(R.string.history_saved_sources), fontWeight = FontWeight.SemiBold) }
                    items(sources) { source ->
                        val uriHandler = LocalUriHandler.current
                        Text("↗ ${source.title}", color = MuralColors.Orange, modifier = Modifier.clickable { source.safeUrl()?.let(uriHandler::openUri) }.padding(vertical = 6.dp))
                    }
                }
                item { MuralTextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.common_close)) } }
            }
        }
    }
    correcting?.let { passage -> CorrectionDialog(passage, onSave = {
        vm.correctPassage(liveSession.id, passage.id, it); correcting = null
    }, onDismiss = { correcting = null }) }
}

@Composable
private fun CorrectionDialog(passage: Passage, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable(passage.id) { mutableStateOf(passage.text) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.history_correction_dialog_title)) },
        text = { MuralTextField(text, { text = it.take(10_000) }, minLines = 3, maxLines = 9) },
        confirmButton = { Button(onClick = { onSave(text.trim()) }, enabled = text.isNotBlank()) { Text(stringResource(R.string.common_save)) } },
        dismissButton = { MuralTextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

private fun formatReminderHour(hour: Int): String {
    val cleanHour = hour.coerceIn(0, 23)
    val displayHour = when {
        cleanHour == 0 -> 12
        cleanHour > 12 -> cleanHour - 12
        else -> cleanHour
    }
    val period = if (cleanHour < 12) "AM" else "PM"
    return String.format(Locale.US, "%d:00 %s", displayHour, period)
}
