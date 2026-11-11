package chat.mural.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

enum class HelperPurpose(val wireValue: String) {
    MEANING("meaning"), ASSESSMENT("assessment"), LOOKUP("lookup"), DELEGATION("delegation"),
    TYPED_REPLY("typed_reply"), TOPIC("topic"), HELP("help")
}

interface TeachingClient {
    suspend fun respond(instructions: String, input: String, schema: JsonObject? = null,
        search: Boolean = false, purpose: HelperPurpose? = null): APIResult
    suspend fun streamMeaning(instructions: String, input: String, onText: (String) -> Unit): APIResult {
        val result = respond(instructions, input, purpose = HelperPurpose.MEANING)
        onText(result.text)
        return result
    }
}

sealed interface LiveOffer {
    data class WebRTC(val sdp: String) : LiveOffer { override fun toString() = "WebRTC([redacted])" }
    data object NativePCM : LiveOffer
}

data class LiveSessionRequest(val offer: LiveOffer, val instructions: String, val history: JsonArray = JsonArray(emptyList()),
    val language: String? = null, val requestID: String = UUID.randomUUID().toString(), val requestedMilliseconds: Long? = null) {
    constructor(sdp: String, instructions: String, history: JsonArray = JsonArray(emptyList()), language: String? = null) :
        this(LiveOffer.WebRTC(sdp), instructions, history, language)
    val sdp: String get() = (offer as? LiveOffer.WebRTC)?.sdp ?: error("This provider requires WebRTC")
    override fun toString() = "LiveSessionRequest([redacted])"
}

interface LiveSessionProvider {
    suspend fun createLiveSession(request: LiveSessionRequest): LiveSessionConnection
}

/** Lease metadata is opaque to the native audio layer. It never contains credentials or prompt text. */
interface LiveSessionLease {
    val sessionID: String
    suspend fun requestClose()
}

sealed interface LiveConnection {
    data class WebRTC(val sdp: String) : LiveConnection { override fun toString() = "WebRTC([redacted])" }
    data object NativePCM : LiveConnection
}

data class LiveSessionConnection(val connection: LiveConnection, val providerSessionID: String?, val lease: LiveSessionLease? = null) {
    constructor(sdp: String, providerSessionID: String?, lease: LiveSessionLease? = null) : this(LiveConnection.WebRTC(sdp), providerSessionID, lease)
    val sdp: String get() = (connection as? LiveConnection.WebRTC)?.sdp ?: error("This connection uses native PCM")
    override fun toString() = "LiveSessionConnection([redacted])"
}

/** A create result can arrive after local disposal. Either ordering must still request server cutoff. */
internal class LiveSessionOwnership(private val cleanupScope: CoroutineScope) {
    private var lease: LiveSessionLease? = null
    private var disposed = false
    private val requested = AtomicBoolean(false)

    @Synchronized fun adopt(value: LiveSessionLease?) {
        check(lease == null) { "A voice attempt can own only one hosted lease" }
        lease = value
        if (disposed) closeLease()
    }

    @Synchronized fun close() { disposed = true; closeLease() }

    private fun closeLease() {
        val current = lease ?: return
        if (!requested.compareAndSet(false, true)) return
        // Local cleanup must not wait for the network, and a UI cancellation must not cancel cutoff.
        cleanupScope.launch { try { current.requestClose() } catch (_: Exception) { } }
    }
}
