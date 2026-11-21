# Mural Gemini Live — Android Edition

**Learn English (and 12 other languages) by actually speaking — with live AI voice calls, instant mistake reports, smart flashcards, and on-device memory.**

[⬇️ Download Ready-to-Install Android APK](https://github.com/MomenYounis/mural-gemini-live/releases/latest) · [📖 Beginner Guide (English)](docs/BEGINNER_GUIDE.md) · [📖 دليل المبتدئين خطوة بخطوة (العربية)](docs/BEGINNER_GUIDE_AR.md)

---

## ⬇️ Download Ready-to-Install Android APK

You do **not** need a computer, Android Studio, or coding skills to use this app.

1. **[Download the latest Android APK from GitHub Releases](https://github.com/MomenYounis/mural-gemini-live/releases/latest)**
2. Tap the `.apk` file on your phone to install it.
3. Follow our step-by-step setup guide (**no credit card required**):
   - **English:** [Beginner Setup Guide (`docs/BEGINNER_GUIDE.md`)](docs/BEGINNER_GUIDE.md)
   - **العربية:** [دليل المبتدئين باللغة العربية (`docs/BEGINNER_GUIDE_AR.md`)](docs/BEGINNER_GUIDE_AR.md)

### What You Need
- **Android 8.0 or later** phone
- **Internet connection** for live calls and reports
- **A free Google Gemini API key** from [Google AI Studio](https://aistudio.google.com/api-keys) *(required for live voice calls — free tier works with no credit card)*
- **An optional free Groq API key** from [Groq Console](https://console.groq.com/keys) *(strongly recommended for the cleanest speech transcripts — free tier, no credit card required)*

---

## 💡 What is Mural Gemini Live?

Most language apps trap you in tapping flashcards and memorizing isolated words you never actually say out loud. **Mural is built around a simple idea: you learn to speak by having real conversations.**

Think of Mural as a warm, patient speaking partner in your pocket that remembers what you struggle with and helps you fix it naturally over time.

### 🔄 The 6-Step Closed-Loop Learning Cycle

1. **Talk naturally in real time with Google Gemini Live**
   Tap the orange microphone button on **Talk** and start speaking. Choose from **8 AI voices** (`Kore`, `Puck`, `Aoede`, `Fenrir`, `Charon`, `Leda`, `Orus`, `Zephyr`) and **4 coaching styles**:
   - **Relaxed Friend** — warm & low-pressure; fixes only what blocks understanding
   - **Balanced Coach** — natural conversation with up to 1 clear fix per turn
   - **Strict Coach** — thorough grammar & phrasing fixes with quick spoken repairs
   - **Roleplay Immersion** — stays in character for real-life scenarios with subtle corrections
   - Need help mid-call? Toggle **Meaning** subtitles in your native language, tap any word in Mural's reply for an instant lookup, tap **A little help** for a hint, or tap **Type instead** when you are in a quiet place.

2. **Crystal-clear transcripts with optional free Groq Whisper**
   Live voice stays fast on Google Gemini Live, while optional **Groq Whisper** (`Whisper Large v3`) quietly refines what you said in the background. That gives you far more accurate transcripts — even when you naturally mix English and Arabic in the same sentence.

3. **Motivating Post-Session Report after every call**
   When you hang up, Mural prepares a structured review card by card:
   - **What You Did Well:** Highlights your real speaking wins and celebrates phrases you used successfully.
   - **Mistakes & Fixes:** Shows what you said crossed out next to the natural correction, with your **Arabic (or native) meaning**, a clear explanation, and an offline speaker button.
   - **Sound More Natural:** Suggests everyday native upgrades for sentences that were grammatically okay but could sound smoother — with a one-tap **Add to Deck** button.

4. **Automatic Anki SM-2 Sentence Flashcards (`Words` tab)**
   Your fixes turn directly into topic-grouped sentence flashcards scheduled with the proven **Anki SM-2** spaced-repetition algorithm (`Again`, `Hard`, `Good`, `Easy`):
   - **Offline audio at `1.0x` normal & `0.75x` slow speed**
   - **Auto-play** (reads the answer aloud automatically when flipped) and **Listening** Mode (trains your ear before showing text)
   - Browse cleanly by **Topic Decks** or **Session Reports** with instant search and flexible delete options
   - Tap **Practice in Call** on any deck to launch a focused voice call centered on those exact sentences

5. **Your future calls automatically reinforce what you're learning**
   During later calls, the AI checks your still-learning flashcards locally on your device and gently steers the conversation so you get chances to use them naturally. When you succeed, your Post-Session Report credits those phrases automatically (**`SM-2 +1`**).

6. **Persistent On-Device Learner Memory & Personalization**
   Mural remembers key facts about you (your background, interests, goals, recent conversation topics, and English CEFR level `A1`–`C2` — auto-detected or set manually):
   - You never have to re-introduce yourself every call.
   - The AI adapts its sentence length, vocabulary, and pace to match your real level.
   - Free **Just talk** calls open with fresh, personalized topics without repeating yesterday's chat.
   - The **Themes** tab generates personalized **Suggested for You** conversation and **Roleplay** cards alongside 24 built-in everyday themes, while the **Words** tab tracks a calm daily streak, speaking time, and optional local daily reminders.

> **Tip:** If your Google Gemini Flash quota ever runs low for reports or translations, you can switch **Analysis & Helper AI** in Settings to **OpenRouter** or **SovereignEG** while keeping live voice calls on **Google Gemini Live**.

---

## 🔒 Privacy & Local-First

Mural is **local-first**. There is no Mural cloud account required for personal API key use, no ads, and no tracking SDKs:

- **Everything stays on your phone:** Conversations, transcripts, Post-Session Reports, SM-2 flashcards, Learner Memory, and streaks are stored locally on your device.
- **Portable JSON backups:** Export or import your entire learning archive anytime from `Settings → Learning backup & data` (API keys are never included in backups).
- **Encrypted API keys:** Your Gemini, Groq, OpenRouter, or SovereignEG keys are encrypted locally using **Android Keystore** and sent directly from your phone only to the provider you chose.
- **No raw audio saved on disk:** Your microphone audio is held in memory only during active turns for live speech and optional Groq refinement; it is never saved as audio files on your phone. *(Note: standard provider-side retention rules for Google Gemini, Groq, OpenRouter, or SovereignEG still apply when your device communicates with their APIs.)*

---

## 🛠️ For Developers (Build from Source)

This edition lives in `apps/android/` and is built with Kotlin, Jetpack Compose, OkHttp (`v1beta` Gemini Live WebSocket + REST), and native `AudioRecord`/`AudioTrack`.

**Prerequisites:** Java 17 (JDK 17), Android SDK 36, Build Tools 35.0.0.

```sh
cd apps/android
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

The debug APK is written to `apps/android/app/build/outputs/apk/debug/app-debug.apk`. Never commit `.apk` binaries, `.keystore`/`.jks` files, or API keys into Git. For technical architecture, Gradle properties, and ADB install notes, see [`apps/android/README.md`](apps/android/README.md) and [`docs/run-on-android.md`](docs/run-on-android.md).

---

## 📄 Upstream Credit, License & Trademark Notice

This repository (`MomenYounis/mural-gemini-live`) is an Android Gemini Live edition forked from the open-source **[Chuloo/mural](https://github.com/Chuloo/mural)** project (`Copyright (c) 2026 Hackmamba`).

Mural is released under the [MIT License](LICENSE). Third-party components retain their own licenses (bundled notices are included in [`apps/android/app/src/main/assets/THIRD-PARTY-NOTICES.txt`](apps/android/app/src/main/assets/THIRD-PARTY-NOTICES.txt)).

The **Mural** name and logo identify the original project; the software license does not grant trademark rights.
