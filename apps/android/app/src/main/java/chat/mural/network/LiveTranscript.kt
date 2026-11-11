package chat.mural.network

import chat.mural.core.*

/** A stable provider fragment is revised through the learning core's invalidation rules. */
internal object LiveTranscript {
    fun acceptsMeaning(session: SessionRecord, request: MeaningRequest): Boolean =
        session.id == request.sessionID && session.languageID == request.learningLanguageID &&
            session.passages.any { it.id == request.passageID && it.revisionKey == request.revisionKey && it.text == request.text }

    fun apply(session: SessionRecord, event: LiveEvent.Transcript, meaningVisible: Boolean) {
        require(event.startMS >= 0 && event.endMS >= event.startMS && event.text.length <= 50_000)
        if (event.interim) return // Ephemeral UI captions are never demonstrated learning evidence.
        val old = session.fragments.firstOrNull { it.id == event.id }
        if (old == null) session.append(Fragment(id = event.id, speaker = event.speaker, text = event.text,
            startMS = event.startMS, endMS = event.endMS, meaningVisible = meaningVisible))
        else {
            require(old.speaker == event.speaker)
            if (old.text != event.text) session.correctFragment(event.id, event.text)
        }
    }
}
