package chat.mural

import android.app.Application
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import chat.mural.core.*
import chat.mural.network.*
import chat.mural.ui.AI_CONSENT_VERSION
import java.util.UUID
import kotlin.math.roundToInt
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

/** Gemini structured reviews can outlive the former 45-second cutoff; funding stays independent. */
internal fun learningReviewTimeoutMillis(vendor: AIVendor, funding: ConversationProvider): Long =
    if (vendor == AIVendor.GEMINI && funding == ConversationProvider.PERSONAL_KEY) 90_000 else 45_000

/** Maps a network/credential failure reason to its user-facing string resource, or 0 if unmapped. */
@StringRes
internal fun errorMessageRes(e: Throwable): Int = when (e) {
    is HostedFailure -> hostedErrorMessageRes(e)
    is AccountFailure.Http -> when (e.status) {
        401 -> R.string.hosted_sign_in_again
        429 -> R.string.hosted_rate_limit
        else -> R.string.hosted_request_failed
    }
    AccountFailure.Unavailable -> R.string.account_error_connection
    GeminiException.MissingKey -> R.string.error_missing_key
    GeminiException.SearchUnavailable -> R.string.gemini_search_unavailable
    GeminiException.InvalidResponse, GeminiException.Incomplete -> R.string.error_incomplete_response
    GeminiException.Refused -> R.string.error_request_refused
    GeminiException.Unavailable -> R.string.gemini_unavailable
    is GeminiException.Http -> when (e.kind) {
        ProviderFailureKind.authentication -> R.string.gemini_authentication
        ProviderFailureKind.modelAccess -> R.string.gemini_model_access
        ProviderFailureKind.quota, ProviderFailureKind.creditExhausted, ProviderFailureKind.spendLimit, ProviderFailureKind.usageLimit -> R.string.gemini_quota
        ProviderFailureKind.rateLimit -> R.string.gemini_rate_limit
        ProviderFailureKind.invalidRequest -> R.string.gemini_request_rejected
        ProviderFailureKind.unavailable -> R.string.gemini_service_busy
        else -> R.string.gemini_unavailable
    }
    is APIClient.APIException.MissingKey -> R.string.error_missing_key
    is APIClient.APIException.Refused -> R.string.error_request_refused
    is APIClient.APIException.InvalidResponse, is APIClient.APIException.Incomplete -> R.string.error_incomplete_response
    is APIClient.APIException.Http -> when (e.kind) {
        ProviderFailureKind.authentication -> R.string.error_http_401
        ProviderFailureKind.modelAccess -> R.string.error_http_403_404
        ProviderFailureKind.creditExhausted -> R.string.error_provider_credit_exhausted
        ProviderFailureKind.spendLimit -> R.string.error_provider_spend_limit
        ProviderFailureKind.usageLimit -> R.string.error_provider_usage_limit
        ProviderFailureKind.quota -> R.string.error_provider_quota
        ProviderFailureKind.rateLimit -> R.string.error_http_429
        ProviderFailureKind.unavailable -> R.string.error_provider_unavailable
        ProviderFailureKind.invalidRequest -> R.string.error_provider_request
        ProviderFailureKind.unknown -> R.string.error_http_generic
    }
    is java.net.SocketTimeoutException -> R.string.error_request_timeout
    is java.net.UnknownHostException, is java.net.ConnectException -> R.string.error_request_connection
    is CredentialStore.CredentialException.Invalid -> R.string.error_key_invalid
    is CredentialStore.CredentialException.Save -> R.string.error_key_save
    is CredentialStore.CredentialException.Remove -> R.string.error_key_remove
    else -> 0
}

@StringRes
internal fun hostedErrorMessageRes(failure: HostedFailure): Int = when (failure) {
    HostedFailure.SignInRequired -> R.string.hosted_sign_in_again
    HostedFailure.Unconfirmed -> R.string.hosted_unconfirmed
    HostedFailure.Unavailable -> R.string.hosted_unavailable
    is HostedFailure.Http -> when {
        needsAccountRecovery(failure) -> R.string.hosted_sign_in_again
        failure.code in setOf("insufficient_minutes", "insufficient_credit") -> R.string.hosted_no_minutes
        failure.code == "helper_session_limit" && failure.retryable == true -> R.string.hosted_help_busy
        failure.code == "helper_concurrency_limit" -> R.string.hosted_help_busy
        failure.code in setOf("helper_budget_exhausted", "helper_session_limit") -> R.string.hosted_extra_help_limit
        failure.code == "helper_output_refused" -> R.string.error_request_refused
        failure.code == "helper_output_incomplete" -> R.string.error_incomplete_response
        failure.code == "provider_connection_lost" -> R.string.error_transport_network_lost
        failure.code == "helper_session_funding_unavailable" -> R.string.hosted_help_funding
        failure.code in setOf("provider_reconciliation_required", "minute_balance_reconciliation_required", "minute_purchase_reconciliation_required", "helper_provider_reconciliation_required") -> R.string.hosted_balance_checking
        failure.code == "helper_session_window_closed" -> R.string.hosted_window_closed
        failure.code in setOf("helper_request_already_attempted", "helper_response_uncertain", "live_request_already_created", "provider_session_unconfirmed") -> R.string.hosted_unconfirmed
        failure.code == "live_session_unresolved" -> R.string.hosted_checking_previous
        failure.code == "provider_create_rejected" -> R.string.hosted_start_rejected
        failure.status == 429 -> R.string.hosted_rate_limit
        failure.code in setOf("hosted_voice_not_ready", "hosted_helpers_not_ready", "hosted_paid_not_ready", "hosted_funding_cap_reached") -> R.string.hosted_unavailable
        else -> R.string.hosted_request_failed
    }
    else -> R.string.hosted_request_failed
}

internal fun needsAccountRecovery(error: Throwable): Boolean = error == HostedFailure.SignInRequired ||
    (error is HostedFailure.Http && (error.status == 401 || error.code == "sign_in_to_continue")) ||
    (error is AccountFailure.Http && error.status == 401)

internal fun requestErrorReference(error: Throwable): String? = safeRequestErrorReference(when (error) {
    is HostedFailure.Http -> error.reference
    is AccountFailure.Http -> error.reference
    else -> null
})

/** Whether the failure means the learner needs to add or fix their OpenAI key in Settings. */
internal fun errorNeedsKeySetup(e: Throwable): Boolean =
    e == GeminiException.MissingKey || (e is GeminiException.Http && e.kind == ProviderFailureKind.authentication) ||
    e is APIClient.APIException.MissingKey || (e is APIClient.APIException.Http && e.status == 401)

/** Imported partial conversations are history, not local sessions awaiting cloud recovery. */
internal fun prepareImportedArchive(data: String, importedAt: Double = nowSeconds()): Archive =
    ArchiveCodec.decode(data).also { imported ->
        imported.sessions.filter { it.endedAt == null }.forEach {
            it.endedAt = importedAt
            it.endReason = "Imported unfinished conversation"
        }
    }

class MuralViewModel(application: Application) : AndroidViewModel(application) {
    // Keep intent, not an Activity-capturing callback, while the learner reviews consent.
    var pendingCloudAction: CloudAction? = null
    var archive by mutableStateOf(Archive()); private set
    var loadingHistory by mutableStateOf(true); private set
    var session by mutableStateOf<SessionRecord?>(null); private set
    var state by mutableStateOf("idle"); private set
    var targetCaptionFollowing by mutableStateOf(CaptionFollowing())
    var meaningCaptionFollowing by mutableStateOf(CaptionFollowing())
    var error by mutableStateOf<String?>(null); private set
    var errorNeedsKeySetup by mutableStateOf(false); private set
    var errorNeedsAccountSignIn by mutableStateOf(false); private set
    var typedReplyError by mutableStateOf<String?>(null); private set
    var typedRepliesSent by mutableStateOf(0); private set
    fun clearTypedReplyError() { typedReplyError = null }
    var notice by mutableStateOf<String?>(null); private set
    private var continuationSession: SessionRecord? = null
    private var continuationOwnerID: String? = null
    private var freeBoundaryOwnerID: String? = null
    var hasContinuation by mutableStateOf(false); private set
    var continuationReady by mutableStateOf(false); private set
    var continuationNeedsMinutes by mutableStateOf(false); private set
    private val continuationPreferences = application.getSharedPreferences("mural_continuation", android.content.Context.MODE_PRIVATE)
    private val continuationKey = "v1." + BuildConfig.MANAGED_API_ORIGIN
    private fun clearContinuation() {
        continuationSession = null; continuationOwnerID = null; freeBoundaryOwnerID = null
        hasContinuation = false; continuationReady = false; continuationNeedsMinutes = false
        continuationPreferences.edit().remove(continuationKey).apply()
    }
    private fun restoreContinuation(accountID: String) {
        if (isRunning || hasContinuation || conversationProvider != ConversationProvider.HOSTED_MINUTES) return
        val checkpoint = runCatching { json.decodeFromString<ConversationContinuationCheckpoint>(
            continuationPreferences.getString(continuationKey, null) ?: return) }.getOrNull() ?: return
        val saved = checkpoint.recover(archive.sessions, accountID, language.id) ?: return
        continuationSession = saved; continuationOwnerID = accountID; hasContinuation = true; continuationReady = false
        session = saved; selectedTheme = language.themes.firstOrNull { it.id == saved.themeID }; topicResult = saved.topics.lastOrNull()
        if (saved.themeID == "current") selectedTheme = topicResult?.let(::currentTheme)
        state = "ended"; resetJob?.cancel()
        notice = getApplication<Application>().getString(R.string.notice_continue_settling)
    }
    private suspend fun refreshContinuation() {
        val expected = continuationOwnerID ?: return
        if (isRunning) return
        continuationReady = false; continuationNeedsMinutes = false
        val owner = availableHostedOwner()?.takeIf { it.accountID == expected } ?: return
        val balance = hostedBalance(owner)
        if (isRunning || continuationOwnerID != expected || availableHostedOwner()?.accountID != expected) return
        continuationReady = !accountChangeBlocked && balance.canStartConversation && balance.presentation?.settlementState == "settled"
        continuationNeedsMinutes = ConversationContinuationPolicy.needsMoreMinutes(balance.presentation?.settlementState,
            balance.presentation?.availabilityReason)
        if (continuationNeedsMinutes) notice = getApplication<Application>().getString(R.string.notice_continue_insufficient)
        else if (continuationReady) notice = getApplication<Application>().getString(
            if (balance.availableMilliseconds > 0) R.string.notice_continue_remaining_free
            else R.string.notice_continue_purchased)
    }
    var meaning by mutableStateOf(""); private set
    var translating by mutableStateOf(false); private set
    var meaningFailed by mutableStateOf(false); private set
    var meaningLimitReached by mutableStateOf(false); private set
    var working by mutableStateOf(false); private set
    var isMuted by mutableStateOf(false); private set
    var inputLevel by mutableStateOf(0.0); private set
    var outputLevel by mutableStateOf(0.0); private set
    var selectedTheme by mutableStateOf<ConversationTheme?>(null); private set
    var lookupResult by mutableStateOf<String?>(null); private set
    var lookupError by mutableStateOf<String?>(null); private set
    var lookupLoading by mutableStateOf(false); private set
    private var lookupGeneration = 0L
    private var lookupJob: Job? = null
    var topicResult by mutableStateOf<TopicBrief?>(null); private set
    var hasKey by mutableStateOf(false); private set
    val language get() = LanguageRegistry.get(archive.preferences.learningLanguageID)!!
    val learner get() = LearningEngine.project(archive.sessions, language.id, archive.preferences.hiddenWords)
    val isRunning get() = state in listOf("connecting", "active", "closing")
    val isVoiceSession get() = voiceSession

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val repository = LearningRepository(application)
    private val credentials = CredentialStore(application)
    private val geminiCredentials = CredentialStore.forVendor(application, AIVendor.GEMINI)
    private val openRouterCredentials = CredentialStore.forTeachingProvider(application, TeachingProvider.OPENROUTER)
    private val sovereignEgCredentials = CredentialStore.forTeachingProvider(application, TeachingProvider.SOVEREIGN_EG)
    private val teachingProviderStore = TeachingProviderStore(application)
    var teachingProvider by mutableStateOf(teachingProviderStore.readProvider()); private set
    var openRouterModel by mutableStateOf(teachingProviderStore.readModel(TeachingProvider.OPENROUTER)); private set
    var sovereignEgModel by mutableStateOf(teachingProviderStore.readModel(TeachingProvider.SOVEREIGN_EG)); private set
    var hasOpenRouterKey by mutableStateOf(false); private set
    var hasSovereignEgKey by mutableStateOf(false); private set
    var availableTeachingModels by mutableStateOf<List<TeachingModelOption>>(
        OpenAICompatibleTeachingClient.presetModelsFor(teachingProvider)
    ); private set
    var loadingTeachingModels by mutableStateOf(false); private set
    private val api = APIClient(credentials)
    private var geminiTeaching: TeachingClient = GeminiTeachingClient(geminiCredentials, BuildConfig.GEMINI_TEACHING_MODEL)
    private val openRouterTeaching = OpenAICompatibleTeachingClient(
        provider = TeachingProvider.OPENROUTER,
        readCredential = openRouterCredentials::read,
        readModel = { openRouterModel },
    )
    private val sovereignEgTeaching = OpenAICompatibleTeachingClient(
        provider = TeachingProvider.SOVEREIGN_EG,
        readCredential = sovereignEgCredentials::read,
        readModel = { sovereignEgModel },
    )
    val activeTeachingModelLabel: String
        get() = when (teachingProvider) {
            TeachingProvider.DEFAULT_GEMINI -> TeachingProvider.DEFAULT_GEMINI.defaultModel
            TeachingProvider.OPENROUTER -> openRouterModel
            TeachingProvider.SOVEREIGN_EG -> sovereignEgModel
        }
    fun hasTeachingKey(provider: TeachingProvider = teachingProvider): Boolean = when (provider) {
        TeachingProvider.DEFAULT_GEMINI -> hasKey
        TeachingProvider.OPENROUTER -> hasOpenRouterKey
        TeachingProvider.SOVEREIGN_EG -> hasSovereignEgKey
    }
    private val transport = NativeLiveConversation(application, viewModelScope, geminiCredentials::read)
    var aiVendor by mutableStateOf(AIVendor.GEMINI); private set
    private var personalVendors = emptyMap<String, AIVendor>()
    private fun vendorFor(id: String?) = id?.let { personalVendors[it] ?: AIVendor.OPENAI } ?: aiVendor
    private fun credentialFor(vendor: AIVendor) = if (vendor == AIVendor.GEMINI) geminiCredentials else credentials
    private var liveStartedAt = 0L
    private var providerProcessing = false
    private var liveInputTokens = 0L
    private var liveOutputTokens = 0L
    var interimAssistantCaption by mutableStateOf<String?>(null); private set
    var interimUserCaption by mutableStateOf<String?>(null); private set
    var geminiElapsedSeconds by mutableStateOf(0.0); private set
    private val completedTools = mutableSetOf<String>()
    private val providerStore = ConversationProviderStore(application)
    private val hostedConfiguration = HostedConfiguration.parse(BuildConfig.MANAGED_API_ORIGIN)
    private val accountConfiguration = ManagedAccountConfiguration.parse(BuildConfig.MANAGED_API_ORIGIN, BuildConfig.GOOGLE_SERVER_CLIENT_ID)
    private val memberSessions = accountConfiguration?.let { AccountSessionStore(application, it.origin.toString()) }
    private val guests = hostedConfiguration?.let { config ->
        GuestMinuteController(GuestInstallationStore(application, config.origin.toString()), GuestMinuteClient(config), {
            java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(java.security.SecureRandom()::nextBytes))
        })
    }
    var guestState by mutableStateOf(GuestMinuteState()); private set
    var showMinuteAccess by mutableStateOf(false); private set
    private var selectedAccount = AccountState(busy = true)
    private var accessJob: Job? = null
    private val hostedBindings = HostedConversationBindings(viewModelScope)
    private var hostedSessionIDs = emptySet<String>()
    private var pendingHostedOwnerID: String? = null
    /** Reauthentication may renew this account only; another account cannot replace an unresolved owner. */
    suspend fun pendingMemberForSignIn(): String? {
        clearAcknowledgedGuestMarker()
        guests?.expectedMemberID()?.let { return it }
        val pending = pendingHostedOwnerID ?: return null
        if (guests?.owns(pending) != true) return pending
        // Renewal retains the same guest identity, allowing an interrupted conversation to settle
        // before Google login. A guest UUID is never sent as Google's expected member identity.
        if (guests.session(pending) == null) guests.acquire()
        return null
    }
    private var reconciliationJob: Job? = null
    var conversationProvider by mutableStateOf(ConversationProvider.PERSONAL_KEY); private set
    var providerIssue by mutableStateOf<ProviderFailureKind?>(null); private set
    var hostedReadiness by mutableStateOf(HostedReadiness()); private set
    private val readiness = HostedReadinessController(viewModelScope,
        readSession = { availableHostedOwner() },
        fetch = { owner ->
            val enabled = hostedClient(owner.accountID).available()
            val balance = if (enabled) hostedBalance(owner) else null
            HostedReadiness(owner.accountID, balance?.readinessMilliseconds ?: 0, enabled,
                verified = balance != null)
        }, changed = { hostedReadiness = it })
    var accountChangeBlocked by mutableStateOf(true); private set

    private val reports = ReportController(viewModelScope,
        ReportConfiguration.parse(BuildConfig.MANAGED_API_ORIGIN)?.let(::ReportClient))
    val reportState = reports.state

    fun reportUtterance(sessionID: String, passageID: String) {
        val selectedSession = session?.takeIf { it.id == sessionID }
            ?: archive.sessions.firstOrNull { it.id == sessionID } ?: return
        ReportSelection.from(selectedSession, passageID)?.let(reports::open)
    }
    fun submitReport(report: AIReportSubmission) = reports.submit(report)
    fun dismissReport() = reports.dismiss()

    private data class SaveRequest(val snapshot: LearningSnapshot, val completion: CompletableDeferred<Unit>? = null)
    private val writes = Channel<SaveRequest>(Channel.UNLIMITED)
    private var storageReady = false
    private var finalAssessmentTickets: List<FinalAssessmentTicket> = emptyList()
    var sessionReports by mutableStateOf<List<SessionReport>>(emptyList()); private set
    var sentenceCards by mutableStateOf<List<SentenceCard>>(emptyList()); private set
    var activeSessionReport by mutableStateOf<SessionReport?>(null); private set
    var showSessionReport by mutableStateOf(false); private set
    var analyzingSessionReport by mutableStateOf(false); private set
    var sessionReportError by mutableStateOf<String?>(null); private set
    private var postSessionAnalysisJob: Job? = null
    val topicDecks: List<TopicDeck> get() = Sm2Calculator.groupByTopicDecks(sentenceCards)
    val dueSentenceCards: List<SentenceCard> get() = Sm2Calculator.dueCards(sentenceCards, limit = 5)
    private val recoveredAssessmentIDs = mutableSetOf<String>()
    private val hostedFinalAssessmentJobs = mutableMapOf<String, Job>()
    private var connectionJob: Job? = null
    private var durationJob: Job? = null
    private var closeJob: Job? = null
    private var resetJob: Job? = null
    private var assessmentJob: Job? = null
    private var manualReviewJob: Job? = null
    private var activeReviewing by mutableStateOf(false)
    private var manualReviewing by mutableStateOf(false)
    var learningReviewBatchCompleted by mutableIntStateOf(0)
        private set
    var learningReviewBatchTotal by mutableIntStateOf(0)
        private set
    private var finalReviewIDs by mutableStateOf<Set<String>>(emptySet())
    private var learningReviewErrors by mutableStateOf<Map<String, String>>(emptyMap())
    private val assessmentRetryAfter = mutableMapOf<String, Double>()
    val reviewingLearning get() = activeReviewing || manualReviewing || finalReviewIDs.isNotEmpty()
    private fun reviewKey(vendor: AIVendor, funding: ConversationProvider) = vendor.name + ":" + funding.name
    private fun reviewKey(record: SessionRecord) = reviewKey(vendorFor(record.id),
        if (record.id in hostedSessionIDs) ConversationProvider.HOSTED_MINUTES else ConversationProvider.PERSONAL_KEY)
    private fun reviewDeadlineMillis(record: SessionRecord) = learningReviewTimeoutMillis(vendorFor(record.id),
        if (record.id in hostedSessionIDs) ConversationProvider.HOSTED_MINUTES else ConversationProvider.PERSONAL_KEY)
    val learningReviewError get() = learningReviewErrors[reviewKey(aiVendor, conversationProvider)]
    val pendingLearningReviewCount get() = archive.sessions.filter {
        it.languageID == language.id && it.id !in hostedSessionIDs && vendorFor(it.id) == aiVendor
    }.sumOf { record -> record.passages.count { passage -> passage.speaker == Speaker.user && passage.text.length >= 3 &&
        record.assessments.none { it.passageID == passage.id && it.revisionKey == passage.revisionKey } } }
    val canRetryLearningReview get() = storageReady && !isRunning && !reviewingLearning && hasKey &&
        conversationProvider == ConversationProvider.PERSONAL_KEY && pendingLearningReviewCount > 0 &&
        archive.preferences.aiConsentVersion == AI_CONSENT_VERSION
    private var actionJob: Job? = null
    private val meanings = MeaningController(viewModelScope, canRetryFailure = HostedHelperRetry::canRetryAtBoundary,
        retryDelay = HostedHelperRetry::automaticDelay, stream = { request, onText -> translateMeaning(request, onText) }) { request -> translateMeaning(request) }
    private suspend fun translateMeaning(request: MeaningRequest, onText: ((String) -> Unit)? = null): MeaningResult {
        if (archive.preferences.aiConsentVersion != AI_CONSENT_VERSION) throw IllegalStateException("AI processing consent is required.")
        val module = LanguageRegistry.get(request.learningLanguageID) ?: throw IllegalStateException("Unsupported language.")
        if (request.sessionID in hostedSessionIDs && request.translationInput.toByteArray(Charsets.UTF_8).size > 24_576) throw MeaningInputLimitException()
        val result = teaching(request.sessionID, HelperPurpose.MEANING, request.cacheKey,
            TeachingPolicy.translation(module, request.meaningLanguage), request.translationInput, onText = onText)
        return MeaningResult(result.text, result.usage.input, result.usage.output)
    }
    private val finalAssessments = FinalAssessmentQueue(viewModelScope, timeoutMillis = 45_000) { snapshot, passage -> requestAssessment(snapshot, passage) }
    private val languageDetector = LanguageDetector(application)
    private var languageCheckJob: Job? = null
    private var lastLanguageRedirect: String? = null
    private val delegations = mutableMapOf<String, Job>()
    private var voiceSession = false
    private var inForeground = true
    private var activity = ConversationActivity(activityNow())
    private var conversationPace = ConversationPace()
    var inactivitySeconds by mutableStateOf<Int?>(null); private set
    private fun activityNow() = System.nanoTime() / 1_000_000_000.0
    fun noteTypingActivity() { if (state == "active" && voiceSession) { activity.typing(activityNow()); inactivitySeconds = null } }
    private var generation = 0

    init {
        guests?.let { controller -> viewModelScope.launch { controller.state.collect { guestState = it } } }
        viewModelScope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) { repository.load() to credentials.hasKey }
                archive = loaded.first.archive
                sessionReports = loaded.first.sessionReports
                sentenceCards = loaded.first.sentenceCards
                val providers = providerStore.read(ConversationProvider.PERSONAL_KEY)
                hostedSessionIDs = providers.hostedIDs
                pendingHostedOwnerID = providers.pendingOwnerID
                accountChangeBlocked = providers.pendingOwnerID != null
                guests?.recoverAcknowledgedOwnerAtStartup(pendingHostedOwnerID,
                    clear = { owner -> clearAcknowledgedGuestMarker(owner) },
                    onFailure = { presentError(getApplication<Application>().getString(R.string.error_guest_secure_storage_unavailable)) })
                conversationProvider = providers.selection
                personalVendors = providers.personalVendors
                geminiElapsedSeconds = providerStore.geminiElapsedMilliseconds() / 1000.0
                aiVendor = VendorFundingPolicy.initialVendor(providers.vendorSelection, loaded.second)
                // An existing hosted selection has always meant the hosted OpenAI service.
                if (providers.vendorSelection == null && providers.selection == ConversationProvider.HOSTED_MINUTES)
                    aiVendor = AIVendor.OPENAI
                finalAssessmentTickets = ConversationProviderPolicy.recoveryTickets(loaded.first.finalAssessments, hostedSessionIDs)
                hasKey = withContext(Dispatchers.IO) { credentialFor(aiVendor).hasKey }
                hasOpenRouterKey = withContext(Dispatchers.IO) { openRouterCredentials.hasKey }
                hasSovereignEgKey = withContext(Dispatchers.IO) { sovereignEgCredentials.hasKey }
                storageReady = true
                if (teachingProvider != TeachingProvider.DEFAULT_GEMINI) {
                    refreshTeachingModels(teachingProvider)
                }
                recoverFinalAssessments()
                refreshHostedReadiness()
                if (accountChangeBlocked) reconcileHostedSessions()
            } catch (_: CancellationException) { }
            catch (_: Exception) {
                presentError(getApplication<Application>().getString(R.string.error_local_history_unavailable))
            } finally { loadingHistory = false }
        }
        viewModelScope.launch {
            for (first in writes) {
                var latest = first
                val completions = mutableListOf<CompletableDeferred<Unit>>()
                first.completion?.let(completions::add)
                // Coalesce transcript deltas while preserving every requested durability barrier.
                while (true) {
                    val next = writes.tryReceive().getOrNull() ?: break
                    latest = next
                    next.completion?.let(completions::add)
                }
                try {
                    withContext(Dispatchers.IO) { repository.save(latest.snapshot) }
                    completions.forEach { it.complete(Unit) }
                } catch (error: Exception) {
                    completions.forEach { it.completeExceptionally(error) }
                    if (error is CancellationException) throw error
                    presentError(getApplication<Application>().getString(R.string.error_save_progress_failed))
                }
            }
        }
        transport.onEvent = { event ->
            try { handle(event) }
            catch (_: IllegalArgumentException) { notice = getApplication<Application>().getString(R.string.notice_invalid_voice_update) }
            catch (_: IllegalStateException) { notice = getApplication<Application>().getString(R.string.notice_invalid_voice_update) }
        }
        transport.onFailure = { fail(it) }
        transport.onLevels = { input, output ->
            inputLevel = input; outputLevel = output
            if (state == "active" && voiceSession) {
                if (input > 0.03) activity.inputActive(activityNow())
                if (output > 0.03) activity.assistantActive(activityNow())
            }
        }
        meanings.onChange = { meaning = meanings.text; translating = meanings.isLoading; meaningFailed = meanings.error != null; meaningLimitReached = meanings.error is MeaningInputLimitException }
        meanings.onResult = { request, result ->
            if (session?.id == request.sessionID) updateSession {
                if (LiveTranscript.acceptsMeaning(it, request)) it.translations[request.cacheKey] = result.text
                addUsage(it, APIUsage(result.inputTokens, result.outputTokens))
            }
        }
        finalAssessments.beforeAssessment = { snapshot, passage ->
            val current = archive.sessions.firstOrNull { it.id == snapshot.id }
            val ticket = finalAssessmentTickets.firstOrNull { it.matches(snapshot, passage) }
            val allowed = snapshot.id !in hostedSessionIDs && storageReady &&
                vendorFor(snapshot.id) == aiVendor && credentialFor(vendorFor(snapshot.id)).hasKey &&
                archive.preferences.aiConsentVersion == AI_CONSENT_VERSION && current != null && ticket != null &&
                FinalAssessmentRecovery.canAttempt(ticket, current, nowSeconds())
            if (!allowed) {
                finalReviewIDs = finalReviewIDs - snapshot.id
                false
            } else {
                finalAssessmentTickets = finalAssessmentTickets.map {
                    if (it == ticket) it.copy(attempts = it.attempts + 1, lastAttemptAt = nowSeconds()) else it
                }
                persistAndWait()
                true
            }
        }
        finalAssessments.onResult = { result ->
            try {
                val current = archive.sessions.firstOrNull { it.id == result.sessionID }
                val updated = result.applying(current)
                if (updated != null) {
                    clearLearningReviewFailure(updated)
                    save(updated)
                    if (session?.id == updated.id) session = clone(updated)
                    persistAndWait()
                }
            } finally { finalReviewIDs = finalReviewIDs - result.sessionID }
            if (isRunning) scheduleAssessment() else recoverFinalAssessments()
        }
        finalAssessments.onFailure = { snapshot, _, failure ->
            finalReviewIDs = finalReviewIDs - snapshot.id
            recordLearningReviewFailure(snapshot, failure)
        }
    }

    private fun clearLearningReviewFailure(record: SessionRecord) {
        val key = reviewKey(record)
        learningReviewErrors = learningReviewErrors - key
        assessmentRetryAfter.remove(key)
    }
    private fun recordLearningReviewFailure(record: SessionRecord, failure: Throwable, providerBackoff: Boolean = true) {
        if (BuildConfig.DEBUG) android.util.Log.i("MuralReview", reviewFailureDiagnostic(failure))
        val key = reviewKey(record)
        val reviewMessage = learningReviewErrorMessageRes(failure)
        learningReviewErrors = learningReviewErrors + (key to if (reviewMessage != 0)
            getApplication<Application>().getString(reviewMessage) else resolveMessage(failure, R.string.words_review_failed))
        // A service/quota failure must not launch another request for every spoken turn.
        if (providerBackoff) assessmentRetryAfter[key] = nowSeconds() + 60
    }
    private fun cancelLearningReviews() {
        finalAssessments.cancelAll()
        finalReviewIDs = emptySet()
        assessmentJob?.cancel(); manualReviewJob?.cancel()
        activeReviewing = false; manualReviewing = false
        learningReviewBatchCompleted = 0; learningReviewBatchTotal = 0
    }
    private fun submitFinalReview(record: SessionRecord): Boolean {
        if (finalReviewIDs.isNotEmpty() || manualReviewing || nowSeconds() < (assessmentRetryAfter[reviewKey(record)] ?: 0.0)) return false
        val passage = FinalAssessmentRecovery.pendingPassages(record).lastOrNull { candidate ->
            finalAssessmentTickets.any { it.matches(record, candidate) && FinalAssessmentRecovery.canAttempt(it, record, nowSeconds()) }
        } ?: return false
        learningReviewBatchCompleted = 0; learningReviewBatchTotal = 0
        finalReviewIDs = finalReviewIDs + record.id
        val submitted = finalAssessments.submit(clone(record), passage.id, timeoutMillis = reviewDeadlineMillis(record))
        if (!submitted) finalReviewIDs = finalReviewIDs - record.id
        return submitted
    }
    private fun recoverFinalAssessments() {
        if (!storageReady || isRunning || reviewingLearning || archive.preferences.aiConsentVersion != AI_CONSENT_VERSION) return
        val remaining = FinalAssessmentRecovery.MAX_RECOVERED_PER_LAUNCH - recoveredAssessmentIDs.size
        if (remaining <= 0) return
        FinalAssessmentRecovery.recover(archive.sessions, finalAssessmentTickets, nowSeconds())
            .filterNot { it.id in recoveredAssessmentIDs || it.id in hostedSessionIDs }
            .filter { vendorFor(it.id) == aiVendor && credentialFor(vendorFor(it.id)).hasKey }
            .firstOrNull { submitFinalReview(it) }?.let { recoveredAssessmentIDs += it.id }
    }

    /** Explicit retry uses saved, exact evidence; it never fabricates words or changes its vendor. */
    fun retryLearningReview() {
        if (!canRetryLearningReview) return
        val vendor = aiVendor
        val candidates = archive.sessions.filter { it.endedAt != null && it.languageID == language.id &&
            it.id !in hostedSessionIDs && vendorFor(it.id) == vendor }
            .sortedByDescending { it.endedAt }
            .flatMap { saved -> FinalAssessmentRecovery.pendingPassages(saved).map { passage ->
                Triple(saved.id, passage.id, finalAssessmentTickets.firstOrNull { it.matches(saved, passage) }
                    ?.lastAttemptAt?.takeIf { it.isFinite() } ?: Double.NEGATIVE_INFINITY)
            } }
            // Give unattempted/older work a turn even when five recent passages keep failing.
            .sortedBy { it.third }
            .take(FinalAssessmentRecovery.MAX_RECOVERED_PER_LAUNCH)
            .map { it.first to it.second }
        if (candidates.isEmpty()) return
        learningReviewBatchCompleted = 0
        learningReviewBatchTotal = candidates.size
        manualReviewing = true
        learningReviewErrors = learningReviewErrors - reviewKey(vendor, ConversationProvider.PERSONAL_KEY)
        manualReviewJob = viewModelScope.launch {
            try {
                var invalidPassage: SessionRecord? = null
                var providerFailed = false
                for ((id, passageID) in candidates) {
                    if (isRunning || aiVendor != vendor || conversationProvider != ConversationProvider.PERSONAL_KEY ||
                        archive.preferences.aiConsentVersion != AI_CONSENT_VERSION || !credentialFor(vendor).hasKey) break
                    val snapshot = archive.sessions.firstOrNull { it.id == id }?.let(::clone) ?: continue
                    val passage = FinalAssessmentRecovery.pendingPassages(snapshot).firstOrNull { it.id == passageID } ?: continue
                    finalAssessmentTickets = FinalAssessmentRecovery.enqueue(snapshot, finalAssessmentTickets).map {
                        if (it.matches(snapshot, passage)) it.copy(attempts = 1, lastAttemptAt = nowSeconds()) else it
                    }
                    // The user explicitly retries up to five passages, including older/exhausted
                    // work. Commit the fresh bounded attempt before transmitting any transcript.
                    persistAndWait()
                    try {
                        val result = withTimeout(reviewDeadlineMillis(snapshot)) { requestAssessment(snapshot, passage) }
                        currentCoroutineContext().ensureActive()
                        val current = archive.sessions.firstOrNull { it.id == id } ?: continue
                        if (current.passages.none { it.id == passage.id && it.revisionKey == passage.revisionKey }) continue
                        val updated = result.applying(current) ?: throw FinalAssessmentInvalidResultException()
                        save(updated)
                        if (session?.id == id) session = clone(updated)
                        clearLearningReviewFailure(updated)
                        persistAndWait()
                        learningReviewBatchCompleted++
                    } catch (failure: TimeoutCancellationException) {
                        recordLearningReviewFailure(snapshot, FinalAssessmentTimeoutException()); providerFailed = true; break
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: FinalAssessmentInvalidResultException) {
                        // One malformed assessment must not starve the other saved passages.
                        invalidPassage = snapshot
                    } catch (failure: Exception) {
                        recordLearningReviewFailure(snapshot, failure); providerFailed = true; break
                    }
                }
                currentCoroutineContext().ensureActive()
                if (!providerFailed) invalidPassage?.let {
                    recordLearningReviewFailure(it, FinalAssessmentInvalidResultException(), providerBackoff = false)
                }
            } finally {
                manualReviewing = false; manualReviewJob = null
                if (isRunning) scheduleAssessment()
            }
        }
    }

    private fun clone(s: SessionRecord): SessionRecord = s.copy(
        fragments = s.fragments.map { it.copy(previousTexts = it.previousTexts.toList()) }.toMutableList(),
        assessments = s.assessments.map { it.copy(words = it.words.toList()) }.toMutableList(),
        translations = s.translations.toMutableMap(),
        topics = s.topics.map { it.copy(sources = it.sources.toList()) }.toMutableList(),
    )
    private fun persistenceSnapshot() = LearningSnapshot(
        archive.copy(sessions = archive.sessions.toMutableList(), preferences = archive.preferences.copy()),
        finalAssessmentTickets.toList(),
        sessionReports.toList(),
        sentenceCards.toList(),
    )
    private fun persist() {
        if (storageReady) writes.trySend(SaveRequest(persistenceSnapshot()))
    }
    private suspend fun persistAndWait() {
        check(storageReady)
        val completion = CompletableDeferred<Unit>()
        writes.send(SaveRequest(persistenceSnapshot(), completion))
        completion.await()
    }
    private fun save(s: SessionRecord) {
        finalAssessmentTickets = ConversationProviderPolicy.enqueueRecovery(s, finalAssessmentTickets, hostedSessionIDs)
        archive = archive.copy(sessions = (archive.sessions.filterNot { it.id == s.id } + clone(s)).toMutableList())
        persist()
    }
    private fun updateSession(change: (SessionRecord) -> Unit) {
        val current = session ?: return
        val next = clone(current); change(next); session = next; save(next)
    }
    private fun resolveMessage(e: Throwable, @StringRes fallback: Int): String {
        val app = getApplication<Application>()
        val res = errorMessageRes(e)
        val message = when {
            res == R.string.error_http_generic && e is APIClient.APIException.Http -> app.getString(res, e.status)
            res != 0 -> app.getString(res)
            e is LiveTransport.TransportException || e is GeminiLiveTransport.ConnectionException -> e.message ?: app.getString(fallback)
            else -> app.getString(fallback)
        }
        if (e is APIClient.APIException.Http && e.reference != null)
            return message + "\n\n" + app.getString(R.string.error_provider_reference, e.reference)
        return requestErrorReference(e)?.let { message + "\n\n" + app.getString(R.string.hosted_error_reference, it) } ?: message
    }
    private fun presentError(message: String, needsKeySetup: Boolean = false) {
        error = message; errorNeedsKeySetup = needsKeySetup; errorNeedsAccountSignIn = false
    }
    private fun presentError(e: Throwable, @StringRes fallback: Int) {
        if (conversationProvider == ConversationProvider.PERSONAL_KEY) {
            if (e is APIClient.APIException.Http) providerIssue = e.kind
            if (e is GeminiException.Http) providerIssue = e.kind
        }
        presentError(resolveMessage(e, fallback), errorNeedsKeySetup(e))
        errorNeedsAccountSignIn = needsAccountRecovery(e)
    }
    private fun cloudReady(): Boolean {
        if (!storageReady) { presentError(getApplication<Application>().getString(R.string.error_resolve_local_history_first)); return false }
        if (archive.preferences.aiConsentVersion != AI_CONSENT_VERSION) {
            presentError(getApplication<Application>().getString(R.string.error_accept_ai_consent)); return false
        }
        val currentHosted = session?.id in hostedSessionIDs
        if (!currentHosted && conversationProvider == ConversationProvider.PERSONAL_KEY && !hasKey) {
            presentError(getApplication<Application>().getString(R.string.error_missing_key), needsKeySetup = true); return false
        }
        return true
    }
    fun selectAIVendor(vendor: AIVendor) {
        if (isRunning || !storageReady) return
        if (vendor == AIVendor.GEMINI && conversationProvider == ConversationProvider.HOSTED_MINUTES) return
        generation++; actionJob?.cancel(); clearLookup(); meanings.reset(); cancelLearningReviews()
        clearContinuation(); aiVendor = vendor; hasKey = credentialFor(vendor).hasKey; providerIssue = null
        // A vendor change starts fresh UI context; historical helpers retain their creating vendor.
        resetConversation()
        viewModelScope.launch {
            try { providerStore.selectVendor(vendor) }
            catch (_: Exception) { presentError(getApplication<Application>().getString(R.string.provider_preference_save_failed)) }
        }
        recoverFinalAssessments()
    }
    /** Called by the minutes/account UI after an explicit provider choice. No failure changes it. */
    fun selectConversationProvider(provider: ConversationProvider) {
        if (isRunning || !storageReady) return
        if (!VendorFundingPolicy.supported(aiVendor, provider)) return
        if (provider != conversationProvider) { clearContinuation(); cancelLearningReviews() }
        conversationProvider = provider
        viewModelScope.launch {
            try { providerStore.select(provider) }
            catch (_: Exception) { presentError(getApplication<Application>().getString(R.string.provider_preference_save_failed)) }
        }
        if (provider == ConversationProvider.HOSTED_MINUTES) refreshHostedReadiness()
    }
    /** A source preview never creates a hosted lease or changes who pays. */
    suspend fun checkHostedBalanceForSwitch(): Long? {
        if (isRunning || accountChangeBlocked || !storageReady) return null
        return try {
            if (availableHostedOwner() == null) guests?.acquire()
            val owner = availableHostedOwner() ?: return null
            val enabled = hostedClient(owner.accountID).available()
            val balance = if (enabled) hostedBalance(owner) else return null
            balance.availableMilliseconds.takeIf { availableHostedOwner() == owner && !isRunning }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
    }

    fun onAccountChanged(account: AccountState) {
        selectedAccount = account
        continuationReady = false
        // Invalidate immediately. Slow guest or member reads may not restore access during a transition.
        readiness.selectAccount(null, true)
        accessJob?.cancel()
        if (!account.busy) refreshHostedReadiness()
    }
    fun refreshHostedReadiness() {
        if (selectedAccount.busy || !storageReady) return
        accessJob?.cancel()
        accessJob = viewModelScope.launch {
            try {
                val member = memberSessions?.read()?.takeIf { it.isValid(System.currentTimeMillis()) }
                if (member != null) {
                    clearAcknowledgedGuestMarker()
                    if (guests?.needsLink() == true) {
                        readiness.selectAccount(null, true)
                        if (!completeGuestSignIn() && guests.memberMaySpend(member.accountID) != true) return@launch
                    }
                    if (selectedAccount.busy) return@launch
                    restoreContinuation(member.accountID)
                    readiness.selectAccount(member.accountID, false)
                    readiness.refresh()
                } else {
                    if (archive.preferences.aiConsentVersion != AI_CONSENT_VERSION || conversationProvider != ConversationProvider.HOSTED_MINUTES) {
                        readiness.selectAccount(null, false); return@launch
                    }
                    guests?.acquire()
                    if (selectedAccount.busy) return@launch
                    val guest = guests?.availableSession()
                    readiness.selectAccount(guest?.accountID, false)
                    readiness.refresh()
                }
                refreshContinuation()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { readiness.selectAccount(null, false) }
        }
    }
    fun dismissMinuteAccess() { showMinuteAccess = false }
    fun needsMinuteAccess(): Boolean {
        if (conversationProvider != ConversationProvider.HOSTED_MINUTES || isRunning) return false
        if (hostedReadiness.ready && !selectedAccount.busy) return false
        showMinuteAccess = true; refreshHostedReadiness(); return true
    }
    suspend fun prepareGuestCustodyForDeletion(memberID: String) {
        try { guests?.retireAcknowledgedLinkForDeletion(memberID) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { throw AccountFailure.SecureStorage }
    }

    /** Two encrypted stores are not crash-atomic. The durable guest receipt repairs only its marker. */
    private suspend fun clearAcknowledgedGuestMarker() {
        guests?.recoverAcknowledgedOwner(pendingHostedOwnerID) { owner -> clearAcknowledgedGuestMarker(owner) }
    }
    private suspend fun clearAcknowledgedGuestMarker(owner: String) {
        providerStore.clearPending()
        pendingHostedOwnerID = null; accountChangeBlocked = false
        hostedBindings.delegateOwnerRecovery(owner)
    }

    /** Member spending requires a durable server acknowledgment; guest transfer may finish later. */
    suspend fun completeGuestSignIn(): Boolean {
        val member = memberSessions?.read()?.takeIf { it.isValid(System.currentTimeMillis()) } ?: return false
        val guestOwner = guests?.retainedOwnerID()
        if (guestOwner != null && pendingHostedOwnerID == guestOwner) {
            if (isRunning) return false
            // Finish pending local provenance writes before handing remote recovery to the server.
            connectionJob?.join()
            reconciliationJob?.cancelAndJoin()
        }
        val accepted = guests?.linkTo(member, allowDeferred = true) ?: true
        val safe = accepted || guests?.memberMaySpend(member.accountID) == true
        if (safe && guestOwner != null && pendingHostedOwnerID == guestOwner) {
            // The retained GuestInstallation still owns its bearer and acknowledged member binding.
            // Server recovery owns the old lease; never close it with the new member bearer.
            withContext(NonCancellable) {
                providerStore.clearPending()
                pendingHostedOwnerID = null; accountChangeBlocked = false
                hostedBindings.delegateOwnerRecovery(guestOwner)
            }
        }
        if (!safe) showMinuteAccess = true
        return safe
    }
    private suspend fun availableHostedOwner(): AccountSession? {
        val member = memberSessions?.read()?.takeIf { it.isValid(System.currentTimeMillis()) }
        if (member != null) return member.takeIf { guests?.needsLink() != true || guests?.memberMaySpend(member.accountID) == true }
        return guests?.availableSession()
    }
    private suspend fun requireHostedOwner(ownerID: String? = null): AccountSession {
        // The pending guest lease must still be closable after Google has stored a member bearer.
        val guest = if (ownerID != null) guests?.sessionForSettlement(ownerID) else null
        return guest ?: availableHostedOwner()?.takeIf { ownerID == null || it.accountID == ownerID }
            ?: throw HostedFailure.SignInRequired
    }
    private suspend fun hostedBalance(owner: AccountSession): MinuteBalance =
        GuestMinuteClient(hostedConfiguration ?: throw HostedFailure.Unavailable).balance(owner)

    private fun hostedClient(ownerID: String): HostedAPIClient {
        val config = hostedConfiguration ?: throw HostedFailure.Unavailable
        return HostedAPIClient(config.origin, { requireHostedOwner(ownerID) }, okhttp3.OkHttpClient())
    }
    private fun binding(lease: HostedAPIClient.HostedLease) = HostedConversationBindings.Lease(
        lease.sessionID, lease.teaching, lease::requestClose, lease::status, lease.deadlineMilliseconds)

    /** The creating session, rather than the currently selected settings option, chooses every helper. */
    private suspend fun teaching(localID: String?, purpose: HelperPurpose, logicalID: String,
        instructions: String, input: String, schema: JsonObject? = null, search: Boolean = false, onText: ((String) -> Unit)? = null): APIResult {
        if (archive.preferences.aiConsentVersion != AI_CONSENT_VERSION) throw HostedFailure.Unavailable
        if (localID != null && localID in hostedSessionIDs) {
            return hostedBindings.respond(localID, purpose, logicalID, instructions, input, schema, search, onText)
        }
        if (localID == null && conversationProvider == ConversationProvider.HOSTED_MINUTES) throw HostedFailure.Unavailable
        val client: TeachingClient = when {
            teachingProvider == TeachingProvider.OPENROUTER && hasOpenRouterKey -> openRouterTeaching
            teachingProvider == TeachingProvider.SOVEREIGN_EG && hasSovereignEgKey -> sovereignEgTeaching
            teachingProvider == TeachingProvider.OPENROUTER && !hasOpenRouterKey -> throw GeminiException.MissingKey
            teachingProvider == TeachingProvider.SOVEREIGN_EG && !hasSovereignEgKey -> throw GeminiException.MissingKey
            vendorFor(localID) == AIVendor.GEMINI -> geminiTeaching
            else -> api
        }
        return if (onText != null && purpose == HelperPurpose.MEANING) client.streamMeaning(instructions, input, onText)
        else client.respond(instructions, input, schema, search, purpose)
    }

    fun selectTeachingProvider(provider: TeachingProvider) {
        teachingProvider = provider
        teachingProviderStore.saveProvider(provider)
        availableTeachingModels = OpenAICompatibleTeachingClient.presetModelsFor(provider)
        meanings.reset()
        if (provider != TeachingProvider.DEFAULT_GEMINI) {
            refreshTeachingModels(provider)
        }
    }

    fun selectTeachingModel(provider: TeachingProvider, modelId: String) {
        val clean = modelId.trim().ifEmpty { provider.defaultModel }
        when (provider) {
            TeachingProvider.DEFAULT_GEMINI -> Unit
            TeachingProvider.OPENROUTER -> openRouterModel = clean
            TeachingProvider.SOVEREIGN_EG -> sovereignEgModel = clean
        }
        teachingProviderStore.saveModel(provider, clean)
        meanings.reset()
    }

    fun saveTeachingKey(provider: TeachingProvider, rawKey: String) {
        val store = when (provider) {
            TeachingProvider.DEFAULT_GEMINI -> geminiCredentials
            TeachingProvider.OPENROUTER -> openRouterCredentials
            TeachingProvider.SOVEREIGN_EG -> sovereignEgCredentials
        }
        try {
            store.save(rawKey)
            when (provider) {
                TeachingProvider.DEFAULT_GEMINI -> if (aiVendor == AIVendor.GEMINI) hasKey = true
                TeachingProvider.OPENROUTER -> hasOpenRouterKey = true
                TeachingProvider.SOVEREIGN_EG -> hasSovereignEgKey = true
            }
            error = null
            sessionReportError = null
            refreshTeachingModels(provider)
        } catch (e: CredentialStore.CredentialException) {
            presentError(e.message ?: "Enter a valid API key.")
        }
    }

    fun deleteTeachingKey(provider: TeachingProvider) {
        val store = when (provider) {
            TeachingProvider.DEFAULT_GEMINI -> geminiCredentials
            TeachingProvider.OPENROUTER -> openRouterCredentials
            TeachingProvider.SOVEREIGN_EG -> sovereignEgCredentials
        }
        try {
            store.delete()
            when (provider) {
                TeachingProvider.DEFAULT_GEMINI -> if (aiVendor == AIVendor.GEMINI) hasKey = false
                TeachingProvider.OPENROUTER -> hasOpenRouterKey = false
                TeachingProvider.SOVEREIGN_EG -> hasSovereignEgKey = false
            }
        } catch (e: CredentialStore.CredentialException) {
            presentError(e.message ?: "Could not remove API key.")
        }
    }

    fun refreshTeachingModels(provider: TeachingProvider = teachingProvider) {
        if (provider == TeachingProvider.DEFAULT_GEMINI) {
            availableTeachingModels = OpenAICompatibleTeachingClient.presetModelsFor(provider)
            return
        }
        loadingTeachingModels = true
        availableTeachingModels = OpenAICompatibleTeachingClient.presetModelsFor(provider)
        viewModelScope.launch {
            try {
                val client = when (provider) {
                    TeachingProvider.OPENROUTER -> openRouterTeaching
                    TeachingProvider.SOVEREIGN_EG -> sovereignEgTeaching
                    TeachingProvider.DEFAULT_GEMINI -> return@launch
                }
                val models = client.fetchAvailableModels()
                if (teachingProvider == provider && models.isNotEmpty()) {
                    availableTeachingModels = models
                }
            } catch (_: Exception) {
            } finally {
                loadingTeachingModels = false
            }
        }
    }
    private fun helperContext(snapshot: SessionRecord, passage: Passage? = null): String =
        if (snapshot.id in hostedSessionIDs) ConversationHistory.helperContext(snapshot, passage)
        else TeachingPolicy.context(snapshot, passage)

    /** Renewing a member token must not turn a retained guest transfer back into an OAuth gate. */
    suspend fun settleRenewedMember(): Boolean {
        val pending = pendingHostedOwnerID ?: return true
        if (guests?.owns(pending) == true) return true
        return prepareForAccountChange()
    }

    /** Google identity can be added while guest accounting finishes; guest credentials stay separate. */
    suspend fun prepareForSignIn(): Boolean = AccountSignInPreparation(
        finishLocalWork = {
            if (!storageReady) {
                presentError(getApplication<Application>().getString(R.string.error_resolve_local_history_first))
                false
            } else {
                hostedBindings.disableHelpers(); meanings.reset()
                if (isRunning && (session?.id in hostedSessionIDs || conversationProvider == ConversationProvider.HOSTED_MINUTES)) {
                    updateSession { it.endReason = "Signing in" }; finish(false)
                }
                hostedFinalAssessmentJobs.values.toList().forEach { it.cancel() }; hostedFinalAssessmentJobs.clear()
                // Wait only for local startup/provenance persistence, never remote guest settlement.
                awaitLocalSignInStartup(connectionJob) {
                    presentError(getApplication<Application>().getString(R.string.error_sign_in_finishing_startup))
                }
            }
        },
        pendingOwner = { pendingHostedOwnerID },
        guestOwns = { guests?.owns(it) == true },
        prepareAccountChange = { prepareForAccountChange() },
    ).prepare()

    /** Root UI wiring must use this before sign-out, deletion, account switching, or session revocation. */
    suspend fun prepareForAccountChange(): Boolean {
        if (!storageReady) {
            presentError(getApplication<Application>().getString(R.string.error_resolve_local_history_first)); return false
        }
        hostedBindings.disableHelpers(); meanings.reset()
        hostedFinalAssessmentJobs.values.toList().forEach { it.cancel() }; hostedFinalAssessmentJobs.clear()
        if (!accountChangeBlocked) {
            val member = memberSessions?.read()?.takeIf { it.isValid(System.currentTimeMillis()) }
            if (member != null && guests?.needsLink() == true && !completeGuestSignIn()) return false
            return true
        }
        if (isRunning && (session?.id in hostedSessionIDs || conversationProvider == ConversationProvider.HOSTED_MINUTES)) {
            updateSession { it.endReason = "Account change" }; finish(false)
        }
        val finished = withTimeoutOrNull(10_000) { connectionJob?.join(); reconciliationJob?.join(); true } ?: false
        val ready = finished && settleHostedSessions()
        if (!ready) presentError(getApplication<Application>().getString(R.string.hosted_checking_previous))
        return ready
    }

    private fun reconcileHostedSessions() {
        if (reconciliationJob?.isActive == true) return
        reconciliationJob = viewModelScope.launch {
            // Wait for cancelled creation/provenance writes before clearing the durable pending marker.
            connectionJob?.join()
            repeat(40) {
                if (settleHostedSessions()) { refreshHostedReadiness(); return@launch }
                delay(15_000)
            }
        }
    }

    private suspend fun settleHostedSessions(): Boolean {
        val ownerID = pendingHostedOwnerID
        if (ownerID == null) { accountChangeBlocked = false; return true }
        accountChangeBlocked = true
        return try {
            val owner = requireHostedOwner(ownerID)
            if (owner.accountID != ownerID) return false
            for (id in hostedBindings.openSessionIDs.filter { hostedBindings.owner(it) == ownerID })
                if (!hostedBindings.closeAndConfirm(id)) return false
            // An interrupted create may have committed remotely without returning its lease locally.
            val client = hostedClient(ownerID)
            val current = client.currentSession()
            if (current != null) {
                try { current.requestClose() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { }
                val closed = withTimeoutOrNull(8_000) {
                    while (current.status().state != "closed") delay(500)
                    true
                } ?: false
                if (!closed) return false
            }
            val balance = hostedBalance(owner)
            if (balance.reservedMilliseconds != 0L || balance.paid?.reservedNanoUSD?.toBigInteger()?.signum() == 1 ||
                balance.presentation?.settlementState?.let { it != "settled" } == true) return false
            guests?.recordSettledBalance(owner, balance)
            providerStore.clearPending()
            pendingHostedOwnerID = null; accountChangeBlocked = false
            readiness.refresh()
            true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { false }
    }

    fun dismissError() { error = null; errorNeedsKeySetup = false; errorNeedsAccountSignIn = false }
    fun clearLookup() {
        lookupGeneration++
        lookupJob?.cancel()
        lookupJob = null; lookupResult = null; lookupError = null; lookupLoading = false
    }
    private fun invalidatePersonalRequests() {
        generation++; actionJob?.cancel(); assessmentJob?.cancel(); clearLookup(); meanings.reset()
        cancelLearningReviews(); delegations.values.toList().forEach { it.cancel() }; delegations.clear()
    }
    fun saveKey(key: String, useAfterSave: Boolean = true) {
        if (isRunning) return
        try { invalidatePersonalRequests(); credentialFor(aiVendor).save(key); hasKey = credentialFor(aiVendor).hasKey; providerIssue = null; if (useAfterSave) selectConversationProvider(ConversationProvider.PERSONAL_KEY); recoverFinalAssessments(); notice = getApplication<Application>().getString(R.string.notice_key_saved) }
        catch (e: Exception) { presentError(e, R.string.error_key_save_failed) }
    }
    fun deleteKey() {
        if (isRunning) return
        try { invalidatePersonalRequests(); credentialFor(aiVendor).delete(); hasKey = false; providerIssue = null }
        catch (e: Exception) { presentError(e, R.string.error_key_delete_failed) }
        finally { hasKey = credentialFor(aiVendor).hasKey }
    }
    fun updatePreferences(preferences: Preferences) {
        if (isRunning || !storageReady) return
        if (LanguageRegistry.get(preferences.learningLanguageID) == null || preferences.meaningLanguage !in MeaningLanguages.all || preferences.sessionMinutes !in 1..60) return
        val languageChanged = preferences.learningLanguageID != language.id
        actionJob?.cancel(); clearLookup(); working = false
        if (preferences.aiConsentVersion != AI_CONSENT_VERSION) {
            cancelLearningReviews(); hostedBindings.disableHelpers()
            hostedFinalAssessmentJobs.values.toList().forEach { it.cancel() }; hostedFinalAssessmentJobs.clear()
        }
        generation++; meanings.reset()
        if (languageChanged) resetConversation()
        archive = archive.copy(preferences = preferences.copy(interests = preferences.interests.take(500)))
        persist(); scheduleTranslation(); recoverFinalAssessments(); refreshHostedReadiness()
    }
    fun selectLanguage(id: String) {
        if (!isRunning && LanguageRegistry.get(id) != null) updatePreferences(archive.preferences.copy(learningLanguageID = id))
    }
    private var startingWithHistory = false
    fun chooseTheme(theme: ConversationTheme?) {
        if (!isRunning && session != null) resetConversation()
        selectedTheme = theme
        if (theme?.id != "current") topicResult = null
        if (state == "active" || state == "connecting") {
            updateSession { it.themeID = theme?.id; it.title = theme?.title ?: language.defaultTitle }
            // The opening instruction applies a selection made while connecting.
            startingWithHistory = false
            if (state == "active") requestReply(TeachingPolicy.theme(theme, language))
        }
    }
    fun toggleMeaning() {
        archive = archive.copy(preferences = archive.preferences.copy(meaningVisible = !archive.preferences.meaningVisible)); persist()
        meanings.reset()
        if (archive.preferences.meaningVisible) scheduleTranslation()
    }
    fun toggleMute() { if (state == "active" && voiceSession) { isMuted = !isMuted; transport.mute(isMuted) } }
    fun help() {
        if (state != "active") return
        if (voiceSession) { activity.learnerEngaged(activityNow()); inactivitySeconds = null; conversationPace.askForHelp(session?.passages?.lastOrNull { it.speaker == Speaker.user }); updateLiveContext(conversationPace.instruction); requestReply(TeachingPolicy.help(language)); notice = getApplication<Application>().getString(R.string.notice_help_simpler) }
        else {
            if (working || !cloudReady()) return
            val snapshot = session ?: return
            val token = generation; working = true
            actionJob = viewModelScope.launch {
                try {
                    val result = teaching(snapshot.id, HelperPurpose.HELP, UUID.randomUUID().toString(),
                        TeachingPolicy.help(language) + " Return only your brief explanation.", helperContext(snapshot))
                    if (token != generation || session?.id != snapshot.id || state != "active") return@launch
                    val offset = ((nowSeconds() - snapshot.startedAt) * 1000).toInt().coerceAtLeast(0)
                    updateSession {
                        it.append(Fragment(speaker = Speaker.assistant, text = result.text, startMS = offset, endMS = offset + 1))
                        addUsage(it, result.usage)
                    }
                    scheduleTranslation()
                } catch (_: CancellationException) { }
                catch (e: Exception) { if (token == generation) presentError(e, R.string.error_help_failed) }
                finally { if (token == generation) working = false }
            }
        }
    }

    private fun newSession(voice: Boolean, id: String = UUID.randomUUID().toString()) {
        generation++; resetJob?.cancel(); assessmentJob?.cancel(); manualReviewJob?.cancel(); actionJob?.cancel(); clearLookup(); working = false; meanings.reset()
        dismissError(); notice = null; isMuted = false
        voiceSession = voice
        val record = SessionRecord(id = id, languageID = language.id, themeID = selectedTheme?.id, title = selectedTheme?.title ?: language.defaultTitle)
        topicResult?.takeIf { it.languageID == language.id && selectedTheme?.id == "current" }?.let { record.topics += it }
        session = record; completedTools.clear(); interimAssistantCaption = null; interimUserCaption = null
        if (conversationProvider == ConversationProvider.PERSONAL_KEY) {
            personalVendors = personalVendors + (id to aiVendor)
        }
        save(record)
    }
    fun start() {
        if (!inForeground || isRunning || !cloudReady()) return
        if (hasContinuation && !continuationReady) { reconcileHostedSessions(); refreshHostedReadiness(); return }
        val continuing = continuationSession
        val continuingOwner = continuationOwnerID
        val choice = conversationProvider
        val vendor = aiVendor
        if (!VendorFundingPolicy.supported(vendor, choice)) return
        if (choice == ConversationProvider.HOSTED_MINUTES && accountChangeBlocked) {
            presentError(getApplication<Application>().getString(R.string.hosted_checking_previous))
            reconcileHostedSessions(); return
        }
        if (!ConversationProviderPolicy.canStart(choice, hasKey, hostedReadiness)) {
            if (choice == ConversationProvider.HOSTED_MINUTES) {
                showMinuteAccess = true; refreshHostedReadiness()
            } else presentError(getApplication<Application>().getString(R.string.error_missing_key), true)
            return
        }
        newSession(true); state = "connecting"
        val id = session!!.id
        try {
            // Start while visible and after microphone consent, before Android 15 audio focus.
            VoiceConversationService.start(getApplication(), id) { end("Ended by you") }
        } catch (error: Exception) {
            fail(error, R.string.error_voice_connect_failed); return
        }
        val module = language
        val dueCards = Sm2Calculator.dueCards(sentenceCards, limit = 5)
        val instructions = TeachingPolicy.voice(
            module,
            learner,
            selectedTheme,
            archive.preferences.interests,
            archive.preferences.meaningLanguage,
            dueSentenceCards = dueCards,
        )
        val history = ConversationHistory.messages(continuing ?: session)
        startingWithHistory = history.isNotEmpty()
        if (choice == ConversationProvider.HOSTED_MINUTES) accountChangeBlocked = true
        connectionJob = viewModelScope.launch {
            try {
                val provider: LiveSessionProvider? = if (choice == ConversationProvider.PERSONAL_KEY) {
                    personalVendors = personalVendors + (id to vendor)
                    withContext(NonCancellable) { providerStore.markPersonal(id, vendor) }
                    if (vendor == AIVendor.OPENAI) api else null
                } else {
                    val owner = requireHostedOwner()
                    if (selectedAccount.busy || owner.accountID != hostedReadiness.accountID ||
                        (continuingOwner != null && continuingOwner != owner.accountID)) throw HostedFailure.SignInRequired
                    val hosted = hostedClient(owner.accountID)
                    val balance = hostedBalance(owner)
                    if (!balance.canStartConversation || !hosted.available()) throw HostedFailure.Unavailable
                    freeBoundaryOwnerID = if (balance.availableMilliseconds in 1 until archive.preferences.sessionMinutes * 60_000L &&
                        balance.paid?.available == true) owner.accountID else null
                    if (freeBoundaryOwnerID != null) notice = getApplication<Application>().getString(R.string.notice_free_minutes_first)
                    // Commit provider provenance and the unresolved-owner marker before making a paid create.
                    hostedSessionIDs = hostedSessionIDs + id
                    pendingHostedOwnerID = owner.accountID
                    withContext(NonCancellable) { providerStore.markHosted(id, owner.accountID) }
                    object : LiveSessionProvider {
                        override suspend fun createLiveSession(request: LiveSessionRequest): LiveSessionConnection {
                            val result = hosted.createLiveSession(request.copy(requestedMilliseconds = archive.preferences.sessionMinutes * 60_000L))
                            val lease = result.lease as? HostedAPIClient.HostedLease ?: throw HostedFailure.InvalidResponse
                            withContext(NonCancellable + Dispatchers.Main.immediate) {
                                if (lease.paid) freeBoundaryOwnerID = null
                                hostedBindings.bind(id, owner.accountID, binding(lease))
                                if (session?.id != id || state != "connecting") {
                                    hostedBindings.ended(id); reconcileHostedSessions()
                                }
                            }
                            return result
                        }
                    }
                }
                liveInputTokens = 0; liveOutputTokens = 0; liveStartedAt = System.nanoTime()
                transport.connect(provider, vendor, instructions, history, module.locale)
            } catch (cancelled: CancellationException) {
                if (choice == ConversationProvider.HOSTED_MINUTES) reconcileHostedSessions()
                throw cancelled
            } catch (e: Exception) {
                if (session?.id == id && isRunning) fail(e, R.string.error_voice_connect_failed)
                if (choice == ConversationProvider.HOSTED_MINUTES) reconcileHostedSessions()
            }
        }
    }
    fun end(reason: String = "Ended by you") {
        if (state !in listOf("active", "connecting")) return
        val connecting = state == "connecting"
        state = "closing"; isMuted = true
        connectionJob?.cancel(); durationJob?.cancel(); assessmentJob?.cancel(); actionJob?.cancel(); clearLookup()
        delegations.values.toList().forEach { it.cancel() }; delegations.clear(); working = false
        updateSession { it.endReason = reason }
        if (!voiceSession || connecting) { finish(false); return }
        transport.close()
        closeJob = viewModelScope.launch { delay(5000); if (state == "closing") finish(false) }
    }
    fun background() {
        inForeground = false
        // Screen state is not learner inactivity. The foreground service owns this call.
        if (isRunning && voiceSession && VoiceConversationService.holds(session?.id)) return
        generation++; actionJob?.cancel(); clearLookup(); meanings.reset(); working = false
        if (isRunning) { updateSession { it.endReason = "App moved to background" }; finish(false) }
    }
    fun foreground() { inForeground = true }
    private fun finish(final: Boolean) {
        if (!isRunning) return
        if (ConversationContinuationPolicy.reachedFreeBoundary(freeBoundaryOwnerID != null,
                session?.endReason, session?.id?.let(hostedBindings::reachedDeadline) == true)) {
            updateSession { it.endReason = "Reserved conversation time ended" }
        }
        connectionJob?.cancel(); durationJob?.cancel(); closeJob?.cancel(); assessmentJob?.cancel()
        actionJob?.cancel(); clearLookup(); languageCheckJob?.cancel()
        delegations.values.toList().forEach { it.cancel() }; delegations.clear()
        transport.disconnect(); inputLevel = 0.0; outputLevel = 0.0; working = false; isMuted = false
        VoiceConversationService.stop(getApplication(), session?.id)
        interimAssistantCaption = null; interimUserCaption = null; providerProcessing = false
        val geminiSession = session?.let { it.id !in hostedSessionIDs && vendorFor(it.id) == AIVendor.GEMINI } == true
        if (geminiSession) session?.id?.let { id ->
            val elapsed = ((System.nanoTime() - liveStartedAt) / 1_000_000).coerceAtLeast(0)
            if (voiceSession) {
                geminiElapsedSeconds += elapsed / 1000.0
                viewModelScope.launch { runCatching { providerStore.recordGeminiUsage(id, elapsed, liveInputTokens, liveOutputTokens) } }
            }
        }
        updateSession { it.endedAt = nowSeconds(); it.usageFinal = final && !geminiSession }
        state = "ended"
        notice = when (session?.endReason) {
            "Inactivity" -> getApplication<Application>().getString(R.string.notice_ended_inactivity)
            "Time limit" -> getApplication<Application>().getString(R.string.notice_time_limit_reached)
            else -> null
        }
        if (freeBoundaryOwnerID != null && session?.endReason in listOf("Time limit", "Reserved conversation time ended")) {
            continuationSession = session; continuationOwnerID = freeBoundaryOwnerID
            hasContinuation = true; continuationReady = false
            session?.let { saved ->
                continuationPreferences.edit().putString(continuationKey, json.encodeToString(
                    kotlinx.serialization.serializer<ConversationContinuationCheckpoint>(),
                    ConversationContinuationCheckpoint(saved.id, freeBoundaryOwnerID!!))).apply()
            }
            notice = getApplication<Application>().getString(R.string.notice_continue_settling)
        }
        session?.let {
            if (it.id in hostedSessionIDs) {
                hostedBindings.ended(it.id); reconcileHostedSessions(); finishHostedAssessment(clone(it))
            } else {
                submitFinalReview(clone(it))
                if (it.passages.any { p -> p.speaker == Speaker.user && p.text.isNotBlank() }) {
                    analyzeSessionTranscript(clone(it), openWhenReady = true)
                }
            }
        }
        scheduleTranslation(utteranceComplete = true)
        if (!hasContinuation) resetJob = viewModelScope.launch { delay(15000); if (state == "ended" && !hasContinuation) resetConversation() }
    }
    private fun fail(message: String, needsKeySetup: Boolean = false) { finish(false); resetJob?.cancel(); state = "failed"; presentError(message, needsKeySetup) }
    private fun fail(e: Throwable, @StringRes fallback: Int) {
        if (conversationProvider == ConversationProvider.PERSONAL_KEY) {
            if (e is APIClient.APIException.Http) providerIssue = e.kind
            if (e is GeminiException.Http) providerIssue = e.kind
        }
        fail(resolveMessage(e, fallback), errorNeedsKeySetup(e))
        errorNeedsAccountSignIn = needsAccountRecovery(e)
    }
    fun resetConversation() {
        if (isRunning) return
        clearContinuation()
        generation++; resetJob?.cancel(); actionJob?.cancel(); clearLookup(); assessmentJob?.cancel(); languageCheckJob?.cancel(); meanings.reset()
        session = null; selectedTheme = null; topicResult = null
        notice = null; working = false; state = "idle"; voiceSession = false
    }
    private fun requestReply(content: String): Boolean = sendLive(LiveOperation.RequestReply(content))
    private fun updateLiveContext(content: String, referenceOnly: Boolean = false): Boolean = sendLive(LiveOperation.UpdateContext(content, referenceOnly = referenceOnly))
    private fun sendLive(operation: LiveOperation): Boolean {
        if (state != "active" || !voiceSession) return false
        return transport.send(operation).also {
            if (!it) notice = getApplication<Application>().getString(R.string.notice_update_send_failed)
        }
    }
    private fun handle(event: LiveEvent) {
        if (session == null || !isRunning) return
        when (event) {
            is LiveEvent.Created -> updateSession {
                it.providerID = event.providerID
                event.minimumUsageSeconds?.let { seconds -> it.voiceSeconds = seconds }
            }
            LiveEvent.Ready -> if (state == "connecting") {
                continuationSession = null; continuationOwnerID = null; hasContinuation = false; continuationReady = false
                continuationPreferences.edit().remove(continuationKey).apply()
                state = "active"; liveStartedAt = System.nanoTime(); activity = ConversationActivity(activityNow()); conversationPace = ConversationPace(); inactivitySeconds = null
                if (conversationProvider == ConversationProvider.PERSONAL_KEY) providerIssue = null
                if (selectedTheme?.id == "current") topicResult?.let { updateLiveContext("Sourced context, data: ${it.text}", referenceOnly = true) }
                requestReply(TeachingPolicy.greeting(language, selectedTheme, startingWithHistory)); startDurationChecks()
            }
            is LiveEvent.Transcript -> {
                if (event.interim) {
                    if (event.speaker == Speaker.assistant) {
                        if (interimAssistantCaption == null) meanings.reset()
                        interimAssistantCaption = event.text
                    } else interimUserCaption = event.text
                } else {
                    if (event.speaker == Speaker.assistant) interimAssistantCaption = null else interimUserCaption = null
                    updateSession { LiveTranscript.apply(it, event, archive.preferences.meaningVisible) }
                    if (event.speaker == Speaker.assistant) { scheduleTranslation(); if (state == "active") checkLanguage() }
                    else scheduleAssessment()
                }
                if (event.text.isNotBlank()) {
                    if (event.speaker == Speaker.user) { activity.learnerEngaged(activityNow()); inactivitySeconds = null }
                    else activity.assistantActive(activityNow())
                }
            }
            is LiveEvent.ToolCall -> delegate(event.id, event.name, event.args)
            is LiveEvent.ToolCancelled -> event.ids.forEach { delegations.remove(it)?.cancel(); completedTools.add(it) }
            is LiveEvent.LegacyUsage -> {
                event.seconds?.let { seconds -> updateSession { it.voiceSeconds = seconds } }
                if (event.final) finish(true)
            }
            is LiveEvent.Usage -> {
                // Live usageMetadata describes response(s); retain the latest observation separately.
                // Never infer billable unique tokens, provider-final usage or seconds from it.
                liveInputTokens = event.inputTokens.coerceAtLeast(0).toLong()
                liveOutputTokens = event.outputTokens.coerceAtLeast(0).toLong()
            }
            LiveEvent.Interrupted -> { interimAssistantCaption = null; working = false; providerProcessing = false }
            LiveEvent.TurnComplete, LiveEvent.WaitingForInput -> { working = false; providerProcessing = false }
            LiveEvent.PlaybackComplete -> working = false
            LiveEvent.Processing -> providerProcessing = true
            LiveEvent.GenerationComplete -> Unit // The audio adapter separately reports actual playback completion.
            LiveEvent.Reconnecting -> { interimAssistantCaption = null; interimUserCaption = null; notice = getApplication<Application>().getString(R.string.gemini_reconnecting) }
            LiveEvent.Resumed -> { working = false; notice = getApplication<Application>().getString(R.string.gemini_resumed_gap) }
            LiveEvent.Closed -> finish(false)
            is LiveEvent.Failure -> fail(event.message)
            is LiveEvent.LegacyError -> {
                val issue = if (conversationProvider == ConversationProvider.PERSONAL_KEY) ProviderFailureKind.realtimeCode(event.code) else null
                if (issue != null) fail(APIClient.APIException.Http(if (issue == ProviderFailureKind.authentication) 401 else 429, event.code), R.string.error_voice_connect_failed)
                else notice = getApplication<Application>().getString(R.string.notice_voice_update_rejected)
            }
        }
    }
    private fun startDurationChecks() {
        durationJob?.cancel()
        durationJob = viewModelScope.launch {
            while (state == "active") {
                delay(1000)
                val current = session ?: break
                if (current.id in hostedSessionIDs && hostedBindings.reachedDeadline(current.id)) {
                    updateSession { it.endReason = "Reserved conversation time ended" }; finish(false); break
                }
                if (nowSeconds() - current.startedAt > archive.preferences.sessionMinutes * 60) {
                    notice = getApplication<Application>().getString(R.string.notice_time_limit_reached); end("Time limit"); break
                }
                inactivitySeconds = null
                if (voiceSession) when (val next = activity.tick(activityNow(), isMuted, working || providerProcessing || delegations.isNotEmpty())) {
                    ConversationActivity.Action.CheckIn -> requestReply(TeachingPolicy.checkIn(language))
                    is ConversationActivity.Action.Warning -> inactivitySeconds = next.seconds
                    ConversationActivity.Action.End -> { notice = getApplication<Application>().getString(R.string.notice_ended_inactivity); end("Inactivity"); break }
                    ConversationActivity.Action.Wait -> Unit
                }
            }
        }
    }

    private fun addUsage(s: SessionRecord, usage: APIUsage) {
        s.inputTokens = (s.inputTokens.toLong() + usage.input).coerceAtMost(1_000_000_000).toInt()
        s.outputTokens = (s.outputTokens.toLong() + usage.output).coerceAtMost(1_000_000_000).toInt()
        s.searchCalls = (s.searchCalls.toLong() + usage.searches).coerceAtMost(1_000_000_000).toInt()
    }
    private fun scheduleTranslation(utteranceComplete: Boolean = state == "ended") {
        if (!archive.preferences.meaningVisible || archive.preferences.aiConsentVersion != AI_CONSENT_VERSION) return
        val current = session ?: return
        val passage = current.passages.lastOrNull { it.speaker == Speaker.assistant } ?: return
        val request = MeaningRequest(current.id, passage, current.languageID, archive.preferences.meaningLanguage)
        meanings.update(request, current.translations[request.cacheKey], utteranceComplete, conversationEnded = state == "ended")
    }
    fun retryMeaning() { scheduleTranslation(); meanings.retry() }
    private fun checkLanguage() {
        val passage = session?.passages?.lastOrNull { it.speaker == Speaker.assistant }
        if (!LanguageDetector.shouldCheck(passage, lastLanguageRedirect) || languageCheckJob?.isActive == true) return
        val module = language; val passageID = passage!!.id; val text = passage.text
        languageCheckJob = viewModelScope.launch {
            val detected = languageDetector.detect(text) ?: return@launch
            if (state == "active" && lastLanguageRedirect != passageID &&
                TeachingPolicy.shouldRedirectSpeech(module, detected.languageID, detected.confidence)) {
                lastLanguageRedirect = passageID
                requestReply(TeachingPolicy.redirect(module))
            }
        }
    }
    @Serializable private data class AssessmentResponse(val outcome: Outcome, val suggestedLevel: Int, val nextGoal: String, val capability: String, val words: List<WordProposal>)
    private fun assessmentSchema(id: String): JsonObject {
        fun string() = buildJsonObject { put("type", "string") }
        fun obj(fields: Map<String, JsonElement>) = buildJsonObject {
            put("type", "object"); put("properties", JsonObject(fields)); put("required", JsonArray(fields.keys.map(::JsonPrimitive))); put("additionalProperties", false)
        }
        fun enumeration(values: List<String>) = buildJsonObject { put("type", "string"); put("enum", JsonArray(values.map(::JsonPrimitive))) }
        return obj(mapOf(
            "outcome" to enumeration(Outcome.entries.map { it.name }),
            "suggestedLevel" to buildJsonObject { put("type", "integer"); put("minimum", 0); put("maximum", 5) },
            "nextGoal" to string(), "capability" to string(),
            "words" to buildJsonObject {
                put("type", "array"); put("maxItems", 12)
                put("items", obj(mapOf("lemma" to string(), "meaning" to string(), "form" to string(), "quote" to string(),
                    "language" to enumeration(listOf(id, "en", "mixed", "uncertain").distinct()),
                    "kind" to enumeration(EvidenceKind.entries.map { it.name }),
                    "confidence" to buildJsonObject { put("type", "number"); put("minimum", 0); put("maximum", 1) },
                    "sourceIDs" to buildJsonObject { put("type", "array"); put("items", string()) }
                )))
            }
        ))
    }
    private suspend fun requestAssessment(snapshot: SessionRecord, passage: Passage): FinalAssessmentResult {
        if (archive.preferences.aiConsentVersion != AI_CONSENT_VERSION) throw IllegalStateException("AI processing consent is required.")
        val module = LanguageRegistry.get(snapshot.languageID) ?: throw IllegalStateException("Unsupported language.")
        val result = try {
            teaching(snapshot.id, HelperPurpose.ASSESSMENT, passage.revisionKey,
                TeachingPolicy.assessment(module), helperContext(snapshot, passage), assessmentSchema(module.id))
        } catch (_: GeminiException.InvalidResponse) { throw FinalAssessmentInvalidResultException() }
        catch (_: GeminiException.Incomplete) { throw FinalAssessmentInvalidResultException() }
        catch (_: APIClient.APIException.InvalidResponse) { throw FinalAssessmentInvalidResultException() }
        catch (_: APIClient.APIException.Incomplete) { throw FinalAssessmentInvalidResultException() }
        val decoded = try { json.decodeFromString<AssessmentResponse>(result.text) }
        catch (_: kotlinx.serialization.SerializationException) { throw FinalAssessmentInvalidResultException() }
        val proposal = Assessment(passage.id, passage.revisionKey, decoded.outcome, decoded.suggestedLevel, decoded.nextGoal, decoded.capability, decoded.words, context = snapshot.themeID ?: "free", textAssemblyVersion = 2)
        val validated = LearningEngine.validate(proposal, snapshot) ?: throw FinalAssessmentInvalidResultException()
        return FinalAssessmentResult(snapshot.id, snapshot.languageID, validated, result.usage.input, result.usage.output, result.usage.searches)
    }
    /** Hosted finalization awaits the original helper for its remaining lease window, without entering BYOK recovery. */
    private fun finishHostedAssessment(snapshot: SessionRecord) {
        val passage = FinalAssessmentRecovery.passage(snapshot) ?: return
        if (hostedFinalAssessmentJobs.containsKey(snapshot.id) || !hostedBindings.canAssess(snapshot.id)) return
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                withTimeout(HostedConversationBindings.POST_END_MILLIS) {
                    val result = requestAssessment(snapshot, passage)
                    if (archive.preferences.aiConsentVersion != AI_CONSENT_VERSION) return@withTimeout
                    result.applying(archive.sessions.firstOrNull { it.id == result.sessionID })?.let { updated ->
                        save(updated)
                        if (session?.id == updated.id) session = clone(updated)
                        persistAndWait()
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Keep uncertain evidence unassessed; never submit a replacement request. */ }
            finally { hostedFinalAssessmentJobs.remove(snapshot.id) }
        }
        hostedFinalAssessmentJobs[snapshot.id] = job
        job.start()
    }

    private fun scheduleAssessment() {
        // A new spoken turn must not cancel an in-flight assessment or discard earlier turns.
        if (assessmentJob?.isActive == true || manualReviewing || finalReviewIDs.isNotEmpty()) return
        val id = session?.id ?: return
        assessmentJob = viewModelScope.launch(start = CoroutineStart.LAZY) {
            learningReviewBatchCompleted = 0; learningReviewBatchTotal = 0
            activeReviewing = true
            try {
                delay(3000)
                val attempted = mutableSetOf<String>()
                var invalidPassage: SessionRecord? = null
                for (attempt in 0 until 8) {
                    val snapshot = session?.takeIf { it.id == id && state == "active" }?.let(::clone) ?: break
                    if (nowSeconds() < (assessmentRetryAfter[reviewKey(snapshot)] ?: 0.0)) break
                    val passage = snapshot.passages.firstOrNull { candidate -> candidate.speaker == Speaker.user &&
                        candidate.text.length >= 3 && candidate.revisionKey !in attempted &&
                        snapshot.assessments.none { it.passageID == candidate.id && it.revisionKey == candidate.revisionKey }
                    } ?: break
                    attempted += passage.revisionKey
                    try {
                        persistAndWait()
                        val result = withTimeout(reviewDeadlineMillis(snapshot)) { requestAssessment(snapshot, passage) }
                        currentCoroutineContext().ensureActive()
                        val current = archive.sessions.firstOrNull { it.id == id && it.languageID == snapshot.languageID } ?: return@launch
                        val valid = LearningEngine.validate(result.assessment, current) ?: continue
                        val updated = clone(current)
                        updated.assessments.removeAll { it.passageID == valid.passageID }; updated.assessments += valid
                        addUsage(updated, APIUsage(result.inputTokens, result.outputTokens, result.searchCalls)); save(updated)
                        clearLearningReviewFailure(updated)
                        if (session?.id == updated.id) session = clone(updated)
                        if (state == "active" && session?.id == id &&
                            session?.passages?.lastOrNull { it.speaker == Speaker.user }?.revisionKey == passage.revisionKey &&
                            voiceSession && conversationPace.observe(valid, passage, snapshot.languageID)) {
                            updateLiveContext(conversationPace.instruction)
                        }
                    } catch (failure: TimeoutCancellationException) {
                        recordLearningReviewFailure(snapshot, FinalAssessmentTimeoutException()); return@launch
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: FinalAssessmentInvalidResultException) { invalidPassage = snapshot }
                    catch (failure: Exception) {
                        recordLearningReviewFailure(snapshot, failure); return@launch
                    }
                }
                currentCoroutineContext().ensureActive()
                invalidPassage?.let {
                    // Finish this bounded batch before cooling down automatic reviews. A bad
                    // passage must not be resubmitted on every subsequent microphone turn.
                    recordLearningReviewFailure(it, FinalAssessmentInvalidResultException())
                }
            } finally {
                if (assessmentJob === currentCoroutineContext()[Job]) { assessmentJob = null; activeReviewing = false }
            }
        }
        assessmentJob?.start()
    }
    private fun delegate(id: String, name: String, args: JsonObject) {
        if (state != "active" || delegations.containsKey(id) || id in completedTools || completedTools.size >= 128) return
        if (name !in setOf("legacy_delegation", "mural_teaching_help")) return
        val request = (args["request"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        if (name == "mural_teaching_help" && (request.isNullOrBlank() || request.length > 4000)) {
            completedTools.add(id); sendLive(LiveOperation.ToolResult(id, name, "Requested help is unavailable.")); return
        }
        val sessionID = session?.id ?: return
        delegations[id] = viewModelScope.launch {
            try {
                delay(500)
                val snapshot = session ?: return@launch
                val result = withTimeout(30_000) { teaching(snapshot.id, HelperPurpose.DELEGATION, id,
                    TeachingPolicy.delegation(language) + (if (vendorFor(snapshot.id) == AIVendor.GEMINI)
                        " Web search and external retrieval are unavailable. If current facts or sources are required, say they are unavailable; never claim to have searched or invent a current answer." else ""), helperContext(snapshot) + (request?.let { "\nRequested help (data): $it" } ?: ""),
                    search = vendorFor(snapshot.id) == AIVendor.OPENAI && snapshot.searchCalls < 3) }
                if (state != "active" || session?.id != sessionID) return@launch
                updateSession { addUsage(it, result.usage); if (result.sources.isNotEmpty()) it.topics += TopicBrief(languageID = it.languageID, query = getApplication<Application>().getString(R.string.topics_from_conversation), text = result.text, sources = result.sources) }
                sendLive(LiveOperation.ToolResult(id, name, result.text))
            } catch (_: TimeoutCancellationException) {
                if (session?.id == sessionID) sendLive(LiveOperation.ToolResult(id, name, language.lookupUnavailableReply))
            } catch (_: CancellationException) { }
            catch (_: Exception) { if (session?.id == sessionID) sendLive(LiveOperation.ToolResult(id, name, language.lookupUnavailableReply)) }
            finally { completedTools.add(id); delegations.remove(id) }
        }
    }
    fun sendTyped(text: String) {
        typedReplyError = null
        val clean = text.trim().take(2000)
        if (clean.isEmpty() || working || state in listOf("connecting", "closing") || !cloudReady()) return
        if (state != "active") {
            if (conversationProvider == ConversationProvider.HOSTED_MINUTES) {
                presentError(getApplication<Application>().getString(R.string.hosted_typed_start)); return
            }
            newSession(false); state = "active"; startDurationChecks()
        }
        val id = session!!.id; val token = generation
        val offset = if (voiceSession) session!!.nextTypedVoiceOffsetMS
            else ((nowSeconds() - session!!.startedAt) * 1000).toInt().coerceAtLeast(0)
        val fragment = Fragment(speaker = Speaker.user, text = clean, startMS = offset, endMS = offset + 1, meaningVisible = archive.preferences.meaningVisible, typed = true)
        val draft = clone(session!!).also { it.append(fragment) }
        if (voiceSession) { activity.learnerEngaged(activityNow()); inactivitySeconds = null }; working = true
        if (voiceSession && vendorFor(id) == AIVendor.GEMINI) {
            if (sendLive(LiveOperation.TypedTurn(clean))) {
                updateSession { it.append(fragment) }; typedRepliesSent++; scheduleAssessment()
            } else { typedReplyError = getApplication<Application>().getString(R.string.error_send_message_failed) }
            working = false
            return
        }
        actionJob = viewModelScope.launch {
            try {
                if (!voiceSession) providerStore.markPersonal(id, vendorFor(id))
                val instructions = if (voiceSession) TeachingPolicy.typedReply(language) else TeachingPolicy.voice(language, learner, selectedTheme, archive.preferences.interests, archive.preferences.meaningLanguage, dueSentenceCards) + "\n" + TeachingPolicy.typedReply(language)
                val result = teaching(id, HelperPurpose.TYPED_REPLY, UUID.randomUUID().toString(), instructions, helperContext(draft))
                if (token != generation || session?.id != id || state != "active") return@launch
                updateSession { addUsage(it, result.usage) }
                if (voiceSession) {
                    if (!sendLive(LiveOperation.UpdateContext("The learner typed (data): ${clean.take(650)}", referenceOnly = true)) || !sendLive(LiveOperation.ToolResult("", "legacy_delegation", result.text))) {
                        typedReplyError = getApplication<Application>().getString(R.string.error_send_message_failed)
                        return@launch
                    }
                    updateSession { it.append(fragment) }
                } else {
                    updateSession { it.append(fragment) }
                    val end = ((nowSeconds() - session!!.startedAt) * 1000).toInt().coerceAtLeast(offset + 2)
                    updateSession { it.append(Fragment(speaker = Speaker.assistant, text = result.text, startMS = end, endMS = end + 1)) }
                    scheduleTranslation()
                }
                typedRepliesSent++
                scheduleAssessment()
            } catch (_: CancellationException) { }
            catch (e: Exception) {
                if (session?.id == id) {
                    typedReplyError = resolveMessage(e, R.string.error_send_message_failed)
                    if (errorNeedsKeySetup(e) || needsAccountRecovery(e)) presentError(e, R.string.error_send_message_failed)
                }
            }
            finally { if (token == generation) working = false }
        }
    }
    fun lookup(word: String, sentence: String) {
        if (!cloudReady() || word.isBlank()) return
        clearLookup()
        val token = generation; val id = session?.id; val request = lookupGeneration
        lookupLoading = true
        lookupJob = viewModelScope.launch {
            try {
                val result = teaching(id, HelperPurpose.LOOKUP, UUID.randomUUID().toString(),
                    TeachingPolicy.lookup(language, archive.preferences.meaningLanguage), "Selected: ${word.take(200)}\nSentence: ${sentence.take(2200)}")
                if (token != generation || request != lookupGeneration) return@launch
                lookupResult = result.text
                if (session?.id == id) updateSession { addUsage(it, result.usage) }
            } catch (_: CancellationException) { }
            catch (e: Exception) {
                if (token == generation && request == lookupGeneration) lookupError = resolveMessage(e, R.string.error_lookup_word_failed)
            }
            finally { if (request == lookupGeneration) lookupLoading = false }
        }
    }
    fun currentTopic(query: String) {
        if (query.isBlank() || working || !cloudReady()) return
        archive.sessions.filter { it.languageID == language.id }.flatMap { it.topics }.firstOrNull { it.query.equals(query.trim(), true) && it.isFresh }?.let { topicResult = it; return }
        val token = generation; val module = language; working = true; topicResult = null
        actionJob = viewModelScope.launch {
            try {
                val result = teaching(session?.id, HelperPurpose.TOPIC, UUID.randomUUID().toString(), TeachingPolicy.currentTopic(module), query.trim().take(500), search = true)
                if (token != generation) return@launch
                if (result.sources.isEmpty()) { presentError(getApplication<Application>().getString(R.string.error_topic_unsourced)); return@launch }
                val brief = TopicBrief(languageID = module.id, query = query.trim().take(500), text = result.text, sources = result.sources)
                topicResult = brief
                if (isRunning) updateSession { it.topics += brief; addUsage(it, result.usage) }
                else { val record = SessionRecord(languageID = module.id, title = brief.query, endedAt = nowSeconds()); record.topics += brief; addUsage(record, result.usage); save(record) }
            } catch (_: CancellationException) { }
            catch (e: Exception) { if (token == generation) presentError(e, R.string.error_find_topic_failed) }
            finally { if (token == generation) working = false }
        }
    }
    fun discuss(brief: TopicBrief) {
        if (brief.languageID != language.id) return
        if (state == "active") {
            topicResult = brief; selectedTheme = currentTheme(brief)
            updateSession {
                it.themeID = selectedTheme?.id; it.title = brief.query
                if (it.topics.none { t -> t.id == brief.id }) it.topics += brief
            }
            if (voiceSession) {
                updateLiveContext("Sourced context, data: ${brief.text}", referenceOnly = true); requestReply(TeachingPolicy.theme(selectedTheme, language))
                return
            }
        } else resetConversation()
        topicResult = brief; selectedTheme = currentTheme(brief)
        notice = getApplication<Application>().getString(R.string.notice_topic_ready)
    }
    private fun currentTheme(brief: TopicBrief) = ConversationTheme("current", brief.query, getApplication<Application>().getString(R.string.topics_current_theme_subtitle), "newspaper", "Interests", "Discuss this sourced reference data: ${brief.text.take(3000)}", 0)
    fun deleteSession(id: String) {
        if (isRunning) return
        if (reportState.value.selection?.sessionID == id) reports.dismiss()
        if (activeSessionReport?.sessionID == id) dismissSessionReport()
        finalAssessments.cancel(id); finalReviewIDs = finalReviewIDs - id; manualReviewJob?.cancel()
        hostedFinalAssessmentJobs.remove(id)?.cancel(); hostedBindings.forgetLearning(id)
        finalAssessmentTickets = finalAssessmentTickets.filterNot { it.sessionID == id }
        sessionReports = sessionReports.filterNot { it.sessionID == id }
        sentenceCards = sentenceCards.filterNot { it.sourceSessionID == id }
        if (session?.id == id) resetConversation()
        archive = archive.copy(sessions = archive.sessions.filterNot { it.id == id }.toMutableList()); persist()
        personalVendors = personalVendors - id
        viewModelScope.launch { runCatching { providerStore.forgetPersonal(setOf(id)); geminiElapsedSeconds = providerStore.geminiElapsedMilliseconds() / 1000.0 } }
    }
    fun hideWord(id: String) {
        archive = archive.copy(preferences = archive.preferences.copy(hiddenWords = (archive.preferences.hiddenWords + id).distinct())); persist()
    }
    fun correctPassage(sessionID: String, passageID: String, text: String) {
        if (isRunning) return
        val record = archive.sessions.firstOrNull { it.id == sessionID }?.let(::clone) ?: return
        val passage = record.passages.firstOrNull { it.id == passageID && it.speaker == Speaker.user } ?: return
        finalAssessments.cancel(sessionID); finalReviewIDs = finalReviewIDs - sessionID; manualReviewJob?.cancel()
        hostedFinalAssessmentJobs.remove(sessionID)?.cancel(); generation++; meanings.reset()
        passage.fragments.forEachIndexed { index, f -> record.correctFragment(f.id, if (index == 0) text.take(10000) else "") }
        save(record); if (session?.id == record.id) session = record
    }
    fun deleteLearningData() {
        if (isRunning || !storageReady) return
        reports.dismiss()
        dismissSessionReport()
        cancelLearningReviews(); hostedSessionIDs.forEach(hostedBindings::forgetLearning)
        hostedFinalAssessmentJobs.values.toList().forEach { it.cancel() }; hostedFinalAssessmentJobs.clear(); resetConversation()
        finalAssessmentTickets = emptyList()
        sessionReports = emptyList()
        sentenceCards = emptyList()
        val deleted = archive.sessions.map { it.id }.toSet()
        personalVendors = personalVendors - deleted; geminiElapsedSeconds = 0.0
        viewModelScope.launch { runCatching { providerStore.forgetPersonal(deleted) } }
        archive = archive.copy(sessions = mutableListOf(), preferences = archive.preferences.copy(hiddenWords = emptyList())); persist()
    }

    private val postSessionJson = Json { ignoreUnknownKeys = true }

    fun reportForSession(sessionID: String): SessionReport? =
        sessionReports.firstOrNull { it.sessionID == sessionID }

    fun openSessionReport(report: SessionReport) {
        activeSessionReport = report
        sessionReportError = null
        showSessionReport = true
    }

    fun openReportForSession(sessionID: String) {
        val existing = reportForSession(sessionID)
        if (existing != null) {
            openSessionReport(existing)
            return
        }
        val snapshot = (session?.takeIf { it.id == sessionID } ?: archive.sessions.firstOrNull { it.id == sessionID })?.let(::clone) ?: return
        analyzeSessionTranscript(snapshot, openWhenReady = true)
    }

    fun dismissSessionReport() {
        showSessionReport = false
        sessionReportError = null
    }

    fun retryPostSessionAnalysis(sessionID: String? = activeSessionReport?.sessionID ?: session?.id) {
        val targetID = sessionID ?: return
        val snapshot = (session?.takeIf { it.id == targetID } ?: archive.sessions.firstOrNull { it.id == targetID })?.let(::clone) ?: return
        analyzeSessionTranscript(snapshot, openWhenReady = true)
    }

    internal fun analyzeSessionTranscript(snapshot: SessionRecord, openWhenReady: Boolean = true) {
        if (snapshot.passages.none { it.speaker == Speaker.user && it.text.isNotBlank() }) return
        if (teachingProvider == TeachingProvider.DEFAULT_GEMINI && !cloudReady()) return
        if (teachingProvider != TeachingProvider.DEFAULT_GEMINI && !hasTeachingKey()) {
            if (openWhenReady) showSessionReport = true
            sessionReportError = "Add your ${teachingProvider.shortName} API key in Settings to analyze this session."
            return
        }
        val module = LanguageRegistry.get(snapshot.languageID) ?: language
        val durationSec = ((snapshot.endedAt ?: nowSeconds()) - snapshot.startedAt).coerceAtLeast(1.0)
        val durationMin = (durationSec / 60.0).roundToInt().coerceAtLeast(1)
        if (openWhenReady) {
            showSessionReport = true
        }
        analyzingSessionReport = true
        sessionReportError = null
        postSessionAnalysisJob?.cancel()
        postSessionAnalysisJob = viewModelScope.launch {
            try {
                val vendor = vendorFor(snapshot.id)
                val funding = if (snapshot.id in hostedSessionIDs) ConversationProvider.HOSTED_MINUTES else ConversationProvider.PERSONAL_KEY
                val result = withTimeout(learningReviewTimeoutMillis(vendor, funding)) {
                    teaching(
                        localID = snapshot.id,
                        purpose = HelperPurpose.ASSESSMENT,
                        logicalID = "post-session:${snapshot.id}",
                        instructions = TeachingPolicy.postSessionAnalysis(module),
                        input = TeachingPolicy.postSessionTranscript(snapshot),
                        schema = TeachingPolicy.postSessionSchema(),
                    )
                }
                val payload = postSessionJson.decodeFromString<PostSessionAnalysisPayload>(result.text)
                val cleanTopic = payload.topic.trim().ifBlank {
                    snapshot.title.ifBlank { "General Conversation" }
                }
                val cleanMistakes = payload.mistakes.mapNotNull { m ->
                    val said = m.userSaid.trim()
                    val corr = m.correction.trim()
                    val arab = m.arabicMeaning.trim()
                    val expl = m.explanation.trim()
                    if (said.isBlank() || corr.isBlank() || arab.isBlank()) null
                    else SessionMistake(
                        userSaid = said,
                        correction = corr,
                        arabicMeaning = arab,
                        explanation = expl.ifBlank { "Use the natural corrected phrasing." }
                    )
                }
                val nowMillis = System.currentTimeMillis()
                val reportDateMillis = snapshot.endedAt?.let {
                    ((it + APPLE_EPOCH_UNIX_SECONDS) * 1000.0).toLong()
                } ?: nowMillis
                val report = SessionReport(
                    id = reportForSession(snapshot.id)?.id ?: UUID.randomUUID().toString(),
                    sessionID = snapshot.id,
                    languageID = snapshot.languageID,
                    date = reportDateMillis,
                    topic = cleanTopic,
                    durationMinutes = durationMin,
                    mistakeCount = cleanMistakes.size,
                    mistakes = cleanMistakes
                )
                sessionReports = (listOf(report) + sessionReports.filterNot { it.sessionID == snapshot.id })
                    .sortedByDescending { it.date }
                sentenceCards = Sm2Calculator.mergeMistakesIntoCards(
                    existing = sentenceCards,
                    topic = cleanTopic,
                    languageID = snapshot.languageID,
                    sessionID = snapshot.id,
                    mistakes = cleanMistakes,
                    nowMillis = nowMillis
                )
                activeSessionReport = report
                if (snapshot.title.isBlank() || snapshot.title == "Conversation") {
                    val updatedSession = (archive.sessions.firstOrNull { it.id == snapshot.id } ?: snapshot).let(::clone)
                    updatedSession.title = cleanTopic
                    addUsage(updatedSession, result.usage)
                    save(updatedSession)
                    if (session?.id == updatedSession.id) session = updatedSession
                } else {
                    persist()
                }
                if (openWhenReady) showSessionReport = true
            } catch (_: CancellationException) {
            } catch (e: Exception) {
                sessionReportError = resolveMessage(e, R.string.hosted_request_failed)
            } finally {
                analyzingSessionReport = false
            }
        }
    }

    fun reviewSentenceCard(cardID: String, rating: ReviewRating) {
        val nowMillis = System.currentTimeMillis()
        sentenceCards = sentenceCards.map { card ->
            if (card.id == cardID) Sm2Calculator.review(card, rating, nowMillis) else card
        }
        persist()
    }

    fun deleteSentenceCard(cardID: String) {
        sentenceCards = sentenceCards.filterNot { it.id == cardID }
        persist()
    }
    fun exportData(): String = ArchiveCodec.encode(archive)
    fun importData(data: String): Boolean {
        if (isRunning || !storageReady) return false
        return try { archive = ArchiveCodec.merge(archive, prepareImportedArchive(data)); persist(); notice = getApplication<Application>().getString(R.string.notice_backup_imported); true }
        catch (_: Exception) { presentError(getApplication<Application>().getString(R.string.error_import_failed)); false }
    }
    override fun onCleared() {
        if (isRunning) finish(false)
        VoiceConversationService.stop(getApplication(), session?.id)
        hostedBindings.disableHelpers(); transport.disconnect(); super.onCleared()
    }
}
