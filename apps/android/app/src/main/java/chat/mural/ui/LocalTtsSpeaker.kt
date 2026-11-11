package chat.mural.ui

import android.content.Context
import android.speech.tts.TextToSpeech
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

/**
 * Strictly local on-device Text-to-Speech wrapper using [android.speech.tts.TextToSpeech].
 * Zero cloud AI or Gemini API calls are made, enabling offline flashcard and correction pronunciation.
 */
class LocalTtsSpeaker(context: Context) {
    private val appContext = context.applicationContext
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pendingText: String? = null

    init {
        runCatching {
            tts = TextToSpeech(appContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    val engine = tts ?: return@TextToSpeech
                    val result = engine.setLanguage(Locale.US)
                    if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                        engine.setLanguage(Locale.ENGLISH)
                    }
                    ready = true
                    pendingText?.let { text ->
                        pendingText = null
                        speak(text)
                    }
                }
            }
        }
    }

    fun speak(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        val engine = tts
        if (!ready || engine == null) {
            pendingText = clean
            return
        }
        runCatching {
            engine.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "mural-local-tts-${System.nanoTime()}")
        }
    }

    fun stop() {
        pendingText = null
        runCatching { tts?.stop() }
    }

    fun shutdown() {
        pendingText = null
        ready = false
        runCatching {
            tts?.stop()
            tts?.shutdown()
        }
        tts = null
    }
}

@Composable
fun rememberLocalTtsSpeaker(): LocalTtsSpeaker {
    val context = LocalContext.current
    val speaker = remember(context.applicationContext) { LocalTtsSpeaker(context) }
    DisposableEffect(speaker) {
        onDispose { speaker.shutdown() }
    }
    return speaker
}
