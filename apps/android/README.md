# Mural for Android

Native Kotlin and Jetpack Compose client for Android 8.0 or later, with live voice, typed replies, language modules, meanings, vocabulary and local history. Personal-key conversations can use **Google Gemini** directly without an OpenAI key, Mural account or running Mural backend. The retained OpenAI path remains a separate, explicit choice. Mural minutes continue to use the existing **Mural + OpenAI** service; Gemini with Mural minutes is unavailable.

In **Settings → Advanced**, choose **Conversation funding → My API key**, then **AI provider → Google Gemini**, open **Gemini API key**, and enter your own Google project key on the device. Accept the updated AI permission notice and allow microphone access when starting voice. Existing OpenAI installations keep their original credential and provider selection. Changing AI provider preserves both saved keys. There is no automatic vendor or funding fallback.

Choose **English** as the learning language and **Arabic** as the meaning language for English practice with Arabic meaning subtitles. The other language modules, local learning records and iPhone-compatible JSON backups are preserved. English and Spanish interface strings follow the phone language. Mandarin retains contextual word links and optional pinyin on Android 10 or later.

## Build and configuration

Requirements: Java 17, Android SDK 36 and Build Tools 35.0.0. From this directory:

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

A successful debug assembly writes `app/build/outputs/apk/debug/app-debug.apk`. Preserve the existing application ID and signing identity when updating an installed app. `local.properties`, private signing keys and build outputs remain excluded from Git. For app use, enter the API key inside Settings; never put one in Gradle, source, a test argument or a build artifact.

Only nonsecret model and voice identifiers are configurable, through Gradle properties or the ignored Android `local.properties`:

```properties
mural.geminiLiveModel=gemini-3.8-live
mural.geminiLiveVoice=Kore
mural.geminiTeachingModel=gemini-3.8-flash
```

These are documented defaults, not a guarantee of access for every Google project. Authentication, permission and model-access failures are shown safely; the app does not substitute another model or provider. The Android app uses its existing pinned OkHttp 4.12.0 for Google's raw v1beta WebSocket and REST APIs, with native `AudioRecord`/`AudioTrack` audio. It retains WebRTC 150.7871.01 for OpenAI and hosted conversations. No Google SDK, browser runtime or shared app-owned key is added.

Google's JSON WebSocket messages can arrive in text or binary frames. Both use the same bounded protocol decoder; binary envelopes are checked against the byte limit before strict UTF-8 decoding. Malformed binary messages produce a safe protocol failure, without inventing an HTTP 400 configuration error. Offline loopback tests exercise the actual OkHttp upgrade and both frame types.

## Optional authorized provider check

Ordinary builds and tests do not call Google. After explicit authorization for provider charges, an optional JVM console check can exercise the actual Kotlin Live transport with synthetic audio, then make one short Arabic meaning request. It uses the configured production model and voice, sends one greeting and one typed learner reply, and prints completed Live measurements before starting the meaning request. A meaning HTTP 503 permits one retry after one second; other failures stop immediately. It does not open a microphone or speaker or establish real-device audio quality.

Run from this directory in a terminal:

```sh
./gradlew :app:prepareGeminiSmokeCheck
java -cp "$(cat app/build/gemini-smoke-check.classpath)" chat.mural.network.GeminiAuthorizedSmokeCheck
```

Enter the authorized project key only at the hidden console prompt. Do not pass a key through arguments, environment variables or files. The runner clears its input buffer and exits within 75 seconds after reading the key. Its classpath file contains dependency paths only. This runner has no test annotations and is never launched by the ordinary test tasks.

To check meanings after Live has already been verified, append `--meaning-only` to the Java command. This skips the Live connection and both spoken responses. No other arguments are accepted. Reports contain only boolean/count measurements and fixed failure categories/statuses.

## Audio bursts and missing Words

Gemini can generate output faster than playback. Android keeps a bounded 60-second (2.88 MB) PCM queue in memory, uses 20 ms native writes, and paces optional replies until playback finishes. Live setup also limits each generated reply to 1,024 output tokens. The PCM duration and token ceiling are separate safeguards, not billing measurements. Interruption clears pending speech; audio is not spooled to disk.

Words requires a valid assessment of actual learner speech or typed evidence. A working Live connection does not establish availability or quota for the separate Gemini text API. Provider failures appear in Words while the transcript stays saved. After ending a call, use **Words → Retry learning review** to retry up to five pending saved passages with their original vendor and exact quotes. An explicit retry can revisit older/exhausted work. A provider failure stops that batch; automatic retries observe a one-minute provider/funding backoff. No words are fabricated or inferred from unheard tutor audio. Google HTTP 429 requires checking the selected project's [rate limits and quota](https://ai.google.dev/gemini-api/docs/rate-limits); HTTP 503 indicates availability failure. Neither changes provider or funding automatically.

Saved words and pending reviews can coexist: the banner applies to unfinished passages, not to the whole Words library or the validity of the API key. A timeout and an invalid/unverifiable assessment have distinct safe messages. One invalid passage no longer prevents the remaining bounded batch from being reviewed; the invalid passage stays pending, even if a later passage succeeds. Authentication, quota and service failures still stop the batch. Generic failures do not assert that Google is unavailable.

Words shows the exact number of saved replies awaiting review, the maximum five replies per action, and successful saves within the current batch. A successful five-reply batch can leave older work pending; the next/remaining action says how many replies it will review. Already saved Words remain usable. Explicit retries prioritize replies that have not been attempted recently, so repeated invalid results for five replies cannot permanently block a sixth reply. Service HTTP errors and rejected requests have separate messages from network failures.

Gemini assessment uses structured `streamGenerateContent` responses with the same schema and a bounded 90-second per-passage deadline. Partial JSON never becomes learning evidence: the response must finish and pass schema/evidence validation before saving. The assessment transport has a 90-second read-idle limit and 95-second call limit; the coordinator also enforces its overall 90-second deadline. Other Gemini helper timeouts, OpenAI reviews and hosted request contracts retain their existing budgets. This does not switch models, add automatic request replay or increase the output-token ceiling.

Debug builds emit only fixed failure categories, allowlisted provider codes and bounded HTTP statuses under the `MuralReview` Android log tag. These diagnostics contain no transcript, response body, credential, URL, owner/session identifier or provider reference. Release builds do not emit them.

## Optional Mural account

Personal Gemini does not require a Mural account. **Settings → Account** exposes Google sign-in, but an unconfigured local build leaves it unavailable. An account-enabled build requires the nonsecret `mural.apiOrigin` and `mural.googleServerClientID` settings plus matching Android package/signature registration and an existing compatible account service. This personal debug APK has neither setting and cannot sign in. No account server, production configuration or purchase activation is part of this Android migration. Mural login does not replace the Gemini key, change its Google project limits or sync this device's learning records.

## Data and costs

Gemini mode sends audio, selected text and teaching context to Google. Separate Gemini REST helpers provide meanings, validated assessment, word lookup and learning help using the same owner-entered Gemini key. A Gemini key never goes to OpenAI or Mural. The existing OpenAI credential stays in `mural_openai_credentials` under Keystore alias `chat.mural.openai.aes`; Gemini has a separate `mural_gemini_credentials` store and `chat.mural.gemini.aes` alias. Keys use Android Keystore-backed local encryption and stay out of learning exports. Encryption reduces local exposure but cannot make a credential held by a client impossible to extract.

Mural does not save raw audio. Google provider and project data rules apply: enabling Live resumption can retain conversation state, including audio, for up to **24 hours**, even though the resumption handle is valid for **2 hours after termination**. Google Search grounding, when supported, retains input and output for **30 days**. This Android version keeps Gemini sourced current-topic search unavailable: the native presentation and existing search budget cannot yet satisfy Google's Search Suggestions and per-query contracts. Core conversation and teaching do not need that feature. The optional OpenAI selection retains its existing current-topic search; the app never changes provider automatically. [Google data guidance](https://ai.google.dev/gemini-api/docs/zdr), [Google key guidance](https://ai.google.dev/gemini-api/docs/api-key), [OpenAI data controls](https://developers.openai.com/api/docs/guides/your-data)

Your selected provider bills your own project. Local elapsed conversation time, provider token counts and Mural wallet settlement are different measurements. Google token counts are not converted into authoritative billable seconds or a hosted receipt. The session time limit is not a provider spending cap; check your provider dashboard. Hosted OpenAI contracts, minimum charges, receipts and original-owner recovery are preserved. Hosted Gemini needs a separately authorized backend and commercial design.

See [the Android migration and device verification note](GEMINI_LIVE_MIGRATION.md) for protocol details, parity limits and a real-device checklist. Existing [installation](../../docs/run-on-android.md), [design](../../docs/android/design.md) and [verification](../../verification/android-validation.md) documents describe the earlier OpenAI baseline and remain read-only in this Android-only migration.
