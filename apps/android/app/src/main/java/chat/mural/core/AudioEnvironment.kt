package chat.mural.core

import kotlinx.serialization.Serializable

/**
 * Local audio environment preset controlling Gemini Live VAD sensitivity/timing
 * and optional on-device RMS noise gating.
 */
@Serializable
enum class AudioEnvironment(val displayName: String) {
    QUIET(displayName = "Quiet Room (Faster Reply)"),
    NOISY(displayName = "Noisy Place (Filter Background)");

    companion object {
        fun fromNameOrDefault(name: String?): AudioEnvironment =
            entries.firstOrNull { it.name.equals(name?.trim(), ignoreCase = true) } ?: QUIET
    }
}
