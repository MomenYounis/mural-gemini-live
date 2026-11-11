# Android Gemini Live migration

Scope: `apps/android/` only. Starting revision: `bb45a7b0af230f966970b587047611cac9e0f9cc` on isolated branch `android/gemini-live-byok`. Official Google contracts were rechecked on 2026-10-16. No iOS, backend, shared contract, deployment or commercial configuration changes are part of this work. The new personal Gemini path connects directly to Google, so its core tutoring flow does not depend on a Mural server deployment.

## Baseline flow and coupling

The baseline is OpenAI's **Live API**, not an old `/v1/realtime` integration:

1. `MuralViewModel` gates consent, selected funding, stored key, microphone permission and local persistence. It captures the hosted owner and starts `VoiceConversationService` while visible. The foreground service, notification End action and bounded wake lock keep one conversation active in background or locked-screen operation.
2. `LiveTransport` acquires communication routing/focus, creates a WebRTC peer and microphone track, an `oai-events` data channel and SDP offer. `APIClient.createLiveSession` posts `gpt-live-1` / `marin` to OpenAI `/v1/live/sessions`; hosted funding instead uses the unchanged Mural session contract. The response supplies the SDP answer. `LiveSessionOwnership` ensures a late create result is still closed after cancellation, using its original lease owner.
3. The coordinator waits for `session.started`, then deliberately sends the existing greeting through `session.instructions.append` once. WebRTC supplies input/output media, voice processing and level measurements.
4. Input/output transcript deltas require `event_id`, `start_ms` and `end_ms`. Core transcript revisions group passages, invalidate stale translations/assessments and preserve quotation-based evidence. Learning helpers and meanings use `/v1/responses` with `gpt-6-luna` in the direct path; hosted helpers have their own owner, budget and recovery contract.
5. Provider delegation invokes bounded client teaching helpers. The coordinator injects `session.thinking.append` and `session.commentary.append` responses. Typed replies previously followed the teaching-helper/injection route. These protocol assumptions lived in the coordinator and teaching policy, outside WebRTC itself.
6. Native interruption/mute/close release media and routes. OpenAI usage events, the existing fifteen-second initial usage value and hosted final receipts feed the prior accounting path. Local disconnect does not constitute a hosted final receipt.

## Adapter structure

`LiveOperation` and `LiveEvent` carry provider-neutral intent and events. The ViewModel requests a greeting, context update, typed user turn or tool result without building OpenAI event JSON. `NativeLiveConversation` selects the explicit vendor adapter. `OpenAILiveProtocol` wraps the existing `LiveTransport` and keeps the hosted/OpenAI WebRTC path, lease cleanup and server wire contracts separate.

Gemini uses distinct raw protocol, WebSocket, transcript and native PCM audio components. `GeminiLiveProtocol` owns setup/input/tool JSON and bounded server-frame decoding. The PCM component owns capture, conversion, playback queues and actual audio levels. Transcript assembly preserves stable fragment identities across interim revisions. `GeminiTeachingClient` separately implements ordinary Google REST requests and streaming meanings behind `TeachingClient`; Live output is never asked for unsupported structured JSON.

Funding and vendor are independent Android preferences. Persisted `PERSONAL_KEY` and `HOSTED_MINUTES` keep their original funding and ownership meanings. Gemini + hosted minutes is rejected. Settings explicitly confirms selecting **OpenAI + Mural minutes** before changing both choices. Existing OpenAI keys and legacy personal records retain OpenAI provenance. Automatic final-assessment recovery is restricted to the explicitly selected vendor, so selecting Gemini does not launch old OpenAI helpers. New vendor/token/timing metadata stays in Android's local provider sidecar, outside the unchanged exported learning archive.

## Google contract and configuration

| Area | Implementation contract |
| --- | --- |
| Live model | Configurable `gemini-3.8-live`, currently documented stable; the Live API itself remains preview |
| Teaching model | Configurable `gemini-3.8-flash` with Google `generateContent` / `streamGenerateContent` v1beta |
| Voice | Configurable supported prebuilt `Kore` by default |
| Live endpoint | `wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent` |
| Personal authentication | Owner-entered API key; encoded query authentication for Live and `x-goog-api-key` for REST; production Google origin is fixed, redirects rejected |
| Setup | First frame only; readiness/media wait for `setupComplete`; response modalities and speech settings nest under raw `setup.generationConfig` |
| WebSocket envelopes | JSON is accepted in text and binary frames; binary byte length is bounded before strict UTF-8 decoding; malformed envelopes use a protocol error rather than a fabricated HTTP status |
| Audio | AUDIO response with dedicated input/output transcription; signed mono PCM16 little-endian input at 16 kHz, output at 24 kHz |
| VAD and mute | Automatic VAD with learner pause tolerance; mute flushes `audioStreamEnd`, later audio reopens input; no manual activity signals with automatic VAD |
| Teaching instructions | Common language policy in setup system instructions; supported context/input operations for later teaching updates (passive updates wait for generation and playback to finish); no arbitrary mutable setup or hidden reasoning channels |
| Typed reply | One completed Live user turn gives one spoken response; a completed text turn interrupts current generation on 3.8 |
| Tools | Narrow declared function, explicit blocking behavior, matching call IDs, validation/cancellation/deduplication; internal tool text is not learning evidence |
| Resumption | In-memory scoped handle, bounded reconnection and sliding-window compression; resume does not replay history, acknowledged audio, tools or greeting |
| Dependencies | Existing OkHttp 4.12.0, coroutines 1.10.1 and Kotlin serialization 1.8.0; retained Android WebRTC 150.7871.01; no new SDK dependency |

Do not add `thinkingConfig`, removed affective-dialog fields, `proactiveAudio:false`, OpenAI `store:false`, response schemas or a text response modality to this Live setup. Target-language instructions select speech language; native audio does not support forcing the language through `speechConfig.languageCode`. Model access is project-specific and is verified only by an authorized call. The app does not silently substitute an extended-thinking or text model.

The raw API reference is authoritative when an SDK/tutorial object's nesting differs. The regular endpoint and permanent key are intentionally distinct from constrained ephemeral authentication. Future ephemeral Live tokens require trusted server provisioning and cannot authorize ordinary teaching REST calls; none is added here.

## Transcripts, interruption and evidence

Verbatim transcription is requested so the learner's grammar, disfluencies and Unicode survive. Input transcription, revised interim input transcription, output transcription and audio may be interleaved. Dedicated transcript offsets are consumed when available. Missing alignment uses a monotonic local media timeline and explicit estimated-timing metadata rather than invented exact provider timestamps. Fragment identity persists across interim revisions so the existing stale assessment/meaning invalidation rules apply. Interim-only hypotheses are ephemeral captions and cannot become evidence at a tutor boundary. Committed transcript text is assembled without substring deduplication, preserving repetitions and whitespace. With no final marker, committed input is flushed after a bounded 750 ms quiet period or a provider turn boundary; timing is estimated when offsets are absent. Assistant text is committed only after actual playback drains, and an interrupted unaligned partial reply is omitted from evidence. Meanings wait for that committed reply; this can add subtitle latency relative to captions.

Provider generation completion and actual playback completion are different events. Interruption immediately empties queued/scheduled speech; an interrupted turn can end with `turnComplete` without `generationComplete`. Unheard queued speech and helper/tool instructions must not be accepted as demonstrated learner evidence. Missing or unusable resumption state ends with a recoverable gap; it does not authorize replaying uncertain audio or silently starting a fresh paid call.

## Credential and privacy boundaries

OpenAI keeps the existing encrypted `mural_openai_credentials` store and Keystore alias `chat.mural.openai.aes`. Gemini uses `mural_gemini_credentials` and `chat.mural.gemini.aes`; Google keys have no invented permanent prefix restriction. Neither key is renamed, relabeled, exported, uploaded to Mural or sent to the other vendor. Input dialogs start empty, hide content from screenshots and clear entered text on dismissal. Replacement/deletion invalidates requests. AI consent version 2 names both provider routes and preserves local browsing after withdrawal.

Secure storage is a personal-install BYOK design, not a claim that client-held credentials cannot be extracted. No developer-owned permanent key belongs in source, resources, BuildConfig, Gradle properties or the APK. Model/voice properties in the README contain only public identifiers. Never expose keys, authenticated URLs, resumption handles, PCM or provider response bodies in logs, screenshots, test arguments or reports.

Google state retention is distinct from handle validity: resumption can retain text/audio/video state for **up to 24 hours**, while a handle lasts **2 hours after termination**. The approximately **10-minute WebSocket lifetime** is separate from the **15-minute uncompressed audio-session limit**. Compression does not prevent socket resets or credential expiry. Google provider/project rules and abuse monitoring still apply. Grounded Google Search retains contextual input/output for **30 days** if that feature is enabled in a future version; current Gemini search is unavailable. Mural does not save raw audio or promise zero Google retention.

## Current parity and deferred work

- Personal Gemini covers live audio, live captions, typed turns, meanings, structured assessment, word lookup and teaching help using a Gemini key alone. Existing language content is preserved rather than regenerated.
- **Optional current-topic/sourced web search is OpenAI-only in this version.** Google grounding may issue multiple billable queries without a strict call cap and requires associated Search Suggestions. The current native presentation and archive/search-budget contracts do not satisfy those requirements. Gemini rejects this feature before a provider request, never invents sources and never falls back to OpenAI.
- Gemini local elapsed time and the latest observed Live usage metadata are separate sidecar measurements. Live counters are not summed as unique billable token usage. Ordinary stateless helper requests retain their separately reported token counts in the existing archive fields. They are not final billed audio seconds, OpenAI rate estimates or wallet receipts. The shared archive format remains unchanged for the iOS client; new Android provider metadata is not included in exports.
- Gemini resumption is best effort. An uncertain input gap is not replayed. Nonresumable state requires an explicit fresh conversation; business/funding continuation is independent.
- **Hosted Gemini is deferred.** It would require trusted credential provisioning, provider-specific metering/cutoff/settlement, owner recovery and a separately authorized backend/commercial design. The retained hosted provider is OpenAI; existing fifteen-second policy and receipt recovery stay attached to that path.
- Unit/fake transport evidence does not establish microphone recognition, speaker echo handling, pronunciation, correction quality or locked-phone behavior. Those remain real-device checks below.

## Verification

Ordinary verification is offline and uses isolated test storage, fake transport state, loopback WebSocket/HTTP responses and generated PCM. `GeminiWebSocketTest` exercises actual OkHttp upgrades and binary/text callbacks, including binary `setupComplete`, interleaved transcript/audio JSON, invalid UTF-8, payload limits and sanitized connection errors. `GeminiNativeAudioDeviceTest` additionally uses real native capture, silent playback, mute, interruption, drain and repeated resource release without any provider connection; it requires a visible test Activity and microphone permission. `VoiceBackgroundTest` checks Activity/service/notification ownership using synthetic conversation state. Neither substitutes for a human locked-screen live conversation. Run from `apps/android/`:

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

If a connected emulator/device is available, the ordinary UI variant stays offline and installs separately from learner data:

```sh
./gradlew :app:connectedUiTestAndroidTest
```

Read-only repository checks can consume the unchanged shared fixtures and generated content. Android provider wiring must not be forced into canonical Swift language policy or shared files to silence a parity checker. The final handover must report actual command results, unavailable checks, fake versus device evidence and the built APK location. Running these commands does not authorize paid API calls.

### Optional console check — separate paid authorization required

`GeminiAuthorizedSmokeCheck` is a standalone JVM entry point under test sources, with no test annotations. It exercises the real Kotlin `GeminiLiveTransport` and OkHttp factory against the fixed Google production origin, using the built model/voice configuration and common English teaching policy. Synthetic audio counts received PCM and drains it at turn completion. The check requests one greeting, one spoken reply to a short typed learner sentence, then disconnects Live and immediately reports its measurements before making one short Arabic translation stream through `GeminiTeachingClient`. An HTTP 503 from that meaning request permits exactly one retry after a one-second delay; other failures are not retried. This verifies transport and helper integration; it provides no microphone, audible playback, routing, echo, lock-screen or human correction-quality evidence.

After explicit authorization for those provider calls, run from `apps/android/` in a terminal:

```sh
./gradlew :app:prepareGeminiSmokeCheck
java -cp "$(cat app/build/gemini-smoke-check.classpath)" chat.mural.network.GeminiAuthorizedSmokeCheck
```

The only optional argument is `--meaning-only`; appending it to the Java command skips all Live setup and spoken turns, so a helper retry does not purchase another Live session. No other arguments are accepted. Only the hidden console password prompt accepts a credential; credential arguments, environment variables and files are not supported. The input buffer is cleared in cleanup, and the same hard deadline exits the process within 75 seconds after credential entry, including any meaning retry. Output contains booleans and counts, including received WebSocket opcode counts, output PCM bytes, transcript presence and Arabic-text presence; provider text, tool arguments, keys, authenticated URLs and resumption handles are never printed. Failures expose fixed categories and an HTTP status when available. `prepareGeminiSmokeCheck` only compiles and writes a runtime classpath containing dependency paths; neither it nor ordinary tests launches the check or a provider request.

### Initial local verification result — 2026-10-16

Initial checks passed with Java 17.0.20.1, Gradle 8.11.1, Android Gradle Plugin 8.10.1, Kotlin 2.1.20 and Android SDK 36:

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleUiTest :app:assembleUiTestAndroidTest
```

- **482 unit tests, 0 failures/errors/skips**, including fake Gemini WebSocket/audio lifecycle, PCM conversion, transcript revisions, streaming teaching/cancellation and legacy archive/provider contracts. Existing retained-path regressions passed: 22 HostedAPIClient, 12 APIClient, 3 LiveSessionOwnership and 11 CommunicationAudioRoute tests.
- **Lint passed:** 0 errors and 59 warnings; the new Gemini adapter files have no reported lint issues. The build also reports the existing clipboard deprecation and native-library symbol packaging warnings.
- **Debug and offline UI/instrumentation APKs assembled.** The debug artifact is `app/build/outputs/apk/debug/app-debug.apk`. Android's existing application ID, version and debug signing configuration were preserved; nothing was installed over a learner's app.
- From the repository root, `python3 scripts/export_android_content.py --check` and `python3 scripts/check_cross_platform.py` passed without modifying generated content or shared files. `git diff --check` passed; all 46 changed files are under `apps/android/`.
- `adb devices -l` showed no device; the SDK emulator inventory contained no configured AVD. Instrumentation was compiled but **not executed**. The native audio test therefore provides coverage ready to run, not evidence that the local microphone/router has been exercised.
- No provider API call, key retrieval, purchase, backend action, iOS build, merge or deployment was performed. Google project/model access, human recognition/correction quality, speaker echo, Bluetooth, actual lock-screen operation and live resumption remain unverified. Initial compiler/test issues were fixed before the final successful run.

### Binary-frame fix verification — 2026-10-16

- The latest main/unit/lint/debug/UI/instrumentation build **succeeded in 54 seconds**, with **496 offline unit tests passed, 0 failures/errors/skips**, and lint at **0 errors, 59 warnings**. Coverage includes actual OkHttp/MockWebServer binary JSON envelopes and seven new pure native-playback regressions. Malformed UTF-8 and oversized binary messages use a safe protocol failure instead of an invented HTTP 400.
- An explicitly authorized JVM check using the actual Kotlin Live transport reached readiness and received audio and transcripts for one greeting and one typed learner reply. It observed **84 binary frames, 0 text frames, 1 connection, 3 outbound frames and 2 synthetic drains**. Received PCM totaled **114,720 greeting bytes** and **478,560 typed-reply bytes**; the output transcript contained the expected correction. Audio consumption was synthetic: this provides no human microphone, audible playback, routing, echo or pronunciation evidence.
- The separate Arabic meaning helper returned **HTTP 503 on three requests across two runs**, including one bounded retry. Real helper parity remains **unverified**. The configured model and request adapter were retained; the [official model page](https://ai.google.dev/gemini-api/docs/models/gemini-3.8-flash) and [raw generation reference](https://ai.google.dev/api/generate-content) support this text model, streaming method and `thinkingConfig.thinkingLevel: LOW`. Google documents 503 as a transient service-availability error in its [troubleshooting guide](https://ai.google.dev/gemini-api/docs/troubleshooting).
- On the connected Android 16 phone, a human Live trial produced **5 microphone user fragments and 6 assistant fragments, with no typed user input**. The user explicitly confirmed clear, audible audio. This trial used the binary-frame-fixed APK before the additional native short-clip fix; it verifies a real microphone conversation and audible output, without establishing pronunciation/correction quality, Bluetooth, background/lock behavior or live resumption.
- During that phone trial, the voice foreground service and notification **139** were observed with `microphone|mediaPlayback` types. After the in-app End button, the service was absent and `RECORD_AUDIO` app-ops showed a stopped duration without a running marker.
- Earlier isolated device testing passed five vendor/persistence cases. The native test initially required a manual UI microphone grant after the OEM denied the shell grant; capture and mute then passed, but a 100 ms playback drain failed. The built fix sets the API 31+ playback start threshold to one frame after creation/flush/reroute, separates bounded API 26–30 startup padding from spoken frames through a ledger, handles nonblocking partial writes, and requires actual playback-head completion with a five-second watchdog. Seven pure regressions and a 1 ms after-flush device case were added. The final isolated device rerun **passed all 6 tests in 3.901 seconds**, including real capture/mute, 100 ms playback, same-track 1 ms playback after flush, repeated ownership/release, vendor credentials and persistence. The final debug APK was then **installed in place successfully**; the temporary test apps were removed. API 26–30 padding has deterministic coverage but was not exercised on older hardware. The final audit found **48 changed files, all under `apps/android/`**; both read-only cross-platform checks and signature verification passed. The refreshed delivered artifact is `mural-gemini-debug-fixed.apk`.

### Follow-up: output bursts and Words recovery

A longer real conversation exposed the original eight-second pending-output limit. The [raw Live contract](https://ai.google.dev/api/live) states that content can arrive faster than real time. The adapter now has a 60-second/2.88 MB in-memory PCM budget, 30-second/15-second pressure hysteresis, 20 ms nonblocking writes and a 1,024-output-token setup ceiling. Optional greetings/check-ins/responding context coalesce while generation or playback is pending; typed replies still interrupt immediately. Native failures no longer masquerade as capacity overflow. These are bounds, not conversions into billed seconds. Nothing writes PCM to disk.

Assessment coordination no longer cancels in-flight work when the next learner turn arrives. It processes pending exact passages, validates the latest revision, surfaces safe provider errors and keeps retry metadata for every unresolved passage. Personal final review has a 45-second deadline; automatic failures back off for one minute per vendor/funding pair. Explicit **Words → Retry learning review** processes at most five saved passages, can restart exhausted/older work, and stops at a provider failure. It preserves the original vendor and transcript, and does not manufacture evidence. Local retry metadata is bounded to the newest 10,000 entries; learner records and the public export format remain intact.

The revised setup was accepted in an authorized actual Kotlin Live check: 98 binary frames, no text frames, one connection, greeting/typed output audio and transcripts, and the expected correction. Playback was synthetic in this check. The separate real Kotlin teaching request returned **HTTP 429**; a minimal REST request also returned 429. Successful real Words/Arabic meaning output therefore remains unverified. The offline coordinator regression models quota failure followed by valid recovery; it does not demonstrate that the owner's Google text quota is available. No Google account/billing change, backend change or automatic model/vendor fallback was performed.

Follow-up verification completed: **515 unit tests passed** with no failures/errors/skips; lint passed with **0 errors/59 warnings**. Debug, UI and instrumentation APKs assembled. **10 isolated device tests passed in 5.478 seconds**, including a 30-second native PCM burst followed by interruption, exact heard-frame accounting, multi-passage metadata restart, and the actual Words coordinator recovering from a fake HTTP 429 to two validated words. The final APK was installed in place. An actual saved-conversation retry still produced Google quota status; the main app retained all 10 sessions/17 learner fragments and no real assessments succeeded. Temporary test apps were removed. Both read-only cross-platform checks, APK signature verification and the final **53-file Android-only audit** passed.

### Follow-up: partial Words reviews and account expectations — 2026-10-17

The owner subsequently reported real saved Words alongside the generic learning-review banner. A content-free count on the main installation found 11 sessions, 56 fragments and 4 saved assessments containing 11 word proposals. This supersedes the earlier zero-assessment snapshot; it does not establish that every passage or Arabic meaning request succeeds. The generic banner alone does not identify a quota failure. The prior HTTP 429 observations remain historical evidence, rather than a diagnosis of every new error.

Assessment decoding/validation failures now have a review-specific safe error, separate from the 45-second timeout and existing vendor authentication/quota messages. Invalid output for one passage no longer stops a manual or active bounded batch from reviewing later passages. The failed evidence remains pending and its error survives a later success in the same batch. Provider-wide failures and timeouts still stop the batch; cancellation never becomes an invalid review. Automatic active batches retain their cooldown to avoid resubmitting a failed passage on every microphone turn. No transcript, vendor response or credentials are included in these messages.

Mural account login and Gemini authorization remain separate. This debug build has empty account origin/client-ID configuration, so Google account sign-in is unavailable. Existing Mural accounts store identity and minute records; they do not synchronize local learning history. Multi-device learning sync would require a separate service contract and backend work, explicitly outside this Android-only implementation. Existing local archive export/import is available for manual transfer.

This follow-up passed **518 offline unit tests**, lint with **0 errors/59 warnings**, and debug/UI/instrumentation assembly. Three Words coordinator device cases compile, including invalid-first/valid-second recovery and provider-failure batch stopping. Their execution was blocked: both streamed and file-based ADB installation remained committed but unapplied, without an installer prompt; the owner confirmed no prompt appeared. Both test-install sessions were abandoned, the copied temporary APK was removed, and the existing main app was not changed. These new device tests are **not claimed as run**; the earlier 10-test device result applies to the preceding build. No new paid provider request was made for this follow-up. Delivered artifact: `mural-gemini-debug-words-review.apk`.

### Follow-up: verified slow reviews and completed Words recovery — 2026-10-17

The installed debug build exposed a fixed `assessment_timeout` category: a real review exceeded the former 45-second coordinator deadline while other reviews on the same key succeeded. This was distinct from the earlier observed HTTP 429/503 failures. Gemini assessment now uses `streamGenerateContent?alt=sse` with the unchanged structured schema, `LOW` thinking and 6,144-token ceiling. It saves nothing until `STOP`, full schema validation and exact current-evidence validation. The personal Gemini coordinator deadline is 90 seconds; its assessment-only HTTP client has a 90-second read-idle and 95-second call timeout. The final queue accepts a per-submission override, bounded to 120 seconds; default and OpenAI/hosted budgets remain unchanged. Cancellation still aborts the HTTP call and rejects late results. No automatic request replay or model/provider switch was added. Google's [structured streaming guide](https://ai.google.dev/gemini-api/docs/generate-content/structured-output) supports this transport; streaming does not guarantee a particular provider latency.

Words now distinguishes pending work from failure, displays the exact pending reply count and successful progress within each five-reply batch, and labels the next/remaining action explicitly. Least-recently attempted work comes first on explicit retry, so five repeatedly invalid passages cannot starve a sixth. Safe messages distinguish rejected HTTP requests, service errors, network errors, quota, timeout and invalid assessment output. Debug-only diagnostics contain fixed categories and allowlisted status/code fields, without transcript, credential, URL, owner identifier or raw provider response.

On the actual owner installation, saved assessments increased from 17 to **31**, with **61 word proposals** (not a claim of 61 unique Words). All **12 sessions and 66 fragments** remained. The final Words screen had **no pending-review card, no error and no retry action**; persisted recovery tickets numbered **zero**. A real HTTP **429 RESOURCE_EXHAUSTED** occurred during recovery, followed by later successful reviews. This establishes that a restriction cleared, not a particular project RPM limit; no billing, quota or account setting was changed. No new voice call or Arabic-meaning verification was performed in this follow-up.

Deterministic coverage includes split Unicode/JSON streaming, schema and completion gating, delayed responses, assessment-only timeout scope, cancellation, 90-second final-queue completion/expiry, effective timeout clamps and replacement-token safety. Device coverage includes successful five-plus-one batches, visible counts/progress, malformed-first recovery, five-invalid/sixth-valid fairness, provider-failure stopping, and archive compatibility. The delivered verification report records the final test counts and APK hash.

### Real Android checklist — paid calls require explicit authorization

Enter the owner's Gemini key inside the device Settings; never retrieve it through automation or paste it into chat. Confirm personal funding, Google Gemini, learning language English and meaning language Arabic. Then verify:

1. The greeting is spoken once, after readiness; captions match heard output.
2. A person speaks English, including natural thinking pauses. Check recognition, one gentle correction and a short follow-up. Synthetic typed input is not microphone evidence.
3. Arabic meaning subtitles and contextual word lookup work with no OpenAI key or Mural backend.
4. A typed reply produces exactly one spoken response and one learner evidence turn; failed submission preserves the draft.
5. Barge-in stops all pending tutor audio immediately. Mute/unmute does not create a phantom learner turn or remain permanently busy.
6. Speaker, wired/USB headphones and Bluetooth transitions route both microphone and output correctly without tutor echo becoming learner speech.
7. Network loss and a server connection reset resume the same local conversation when a valid handle is available, without duplicated greeting/history/audio/tools. A failed resume clearly exposes the gap and requires a fresh conversation.
8. Background, lock, unlock and Activity recreation preserve one active call and the ongoing microphone/media notification. Check screen lock separately from merely going Home.
9. An OS audio-focus interruption ends/releases audio correctly. The notification **End** action, in-app End, permission denial, setup cancellation and failed connection all release the microphone and playback. A stale callback or process recreation must not start another call.
10. Local learning history survives restart/export/import; both saved provider keys stay separate. The provider dashboard is authoritative for charges, and Gemini does not create a hosted receipt.

### Official references checked on 2026-10-16

- [Gemini 3.8 Live model](https://ai.google.dev/gemini-api/docs/models/gemini-3.8-live), [Gemini 3.8 Flash model](https://ai.google.dev/gemini-api/docs/models/gemini-3.8-flash)
- [Raw WebSocket reference](https://ai.google.dev/api/live), [authentication guide](https://ai.google.dev/gemini-api/docs/live-api/get-started-websocket)
- [Google JavaScript SDK binary JSON handling](https://github.com/googleapis/js-genai/blob/main/src/live.ts#L40-L49)
- [Audio capabilities](https://ai.google.dev/gemini-api/docs/live-api/capabilities), [streaming practices](https://ai.google.dev/gemini-api/docs/live-api/best-practices), [realtime input semantics](https://googleapis.github.io/js-genai/release_docs/interfaces/types.LiveSendRealtimeInputParameters.html)
- [Server-content semantics](https://googleapis.github.io/js-genai/release_docs/interfaces/types.LiveServerContent.html), [transcription metadata](https://googleapis.github.io/js-genai/release_docs/interfaces/types.Transcription.html), [tools](https://ai.google.dev/gemini-api/docs/live-api/tools)
- [Session management](https://ai.google.dev/gemini-api/docs/live-api/session-management), [ephemeral tokens](https://ai.google.dev/gemini-api/docs/live-api/ephemeral-tokens), [key security](https://ai.google.dev/gemini-api/docs/api-key), [retention](https://ai.google.dev/gemini-api/docs/zdr)
- [Teaching REST reference](https://ai.google.dev/api/generate-content), [structured output](https://ai.google.dev/gemini-api/docs/structured-output), [GenerateContent grounding](https://ai.google.dev/gemini-api/docs/generate-content/google-search), [Google grounding terms](https://ai.google.dev/gemini-api/terms#grounding-with-google-search)
