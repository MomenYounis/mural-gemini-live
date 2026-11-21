# Beginner Guide: How to Install & Use Mural Gemini Live on Android

[العربية (Arabic Guide)](BEGINNER_GUIDE_AR.md) · [Back to README](../README.md)

Welcome! You don't need to know anything about GitHub, programming, or AI setup to use Mural. Follow these steps from start to finish and you will be having your first live English voice conversation in about **5 minutes** — completely **free** and **without a credit card**.

---

## What You Need Before Starting

- An **Android 8.0 or newer** phone
- A working **internet connection** (Wi-Fi or mobile data)
- A **Google account** (the same Gmail account you use on Android)

---

## Step 1 — Download & Install the APK on Android

An **APK** is simply the normal installer file for an Android app (like `.exe` on Windows). Because this community edition isn't on Google Play yet, you download it straight from GitHub Releases.

1. On your Android phone, open **[the latest Mural Releases page](https://github.com/MomenYounis/mural-gemini-live/releases/latest)** in Chrome or your browser.
2. Scroll down to the **Assets** section and tap the file ending in **`.apk`** (for example, `Mural-Gemini-Live-Android-v0.1.0.apk`).
3. When the download finishes, tap **Open** (or find it in your phone's **Files / Downloads** app and tap it).
4. **If Android asks for permission to install:**
   - Tap **Settings** on the popup → turn ON **"Allow from this source"** (or **"Install unknown apps"**) for your browser/Files app → go back and tap **Install**.
   - **Why does Android show this warning?** Android shows this standard message whenever you install an app outside the Google Play Store. It does not mean the app is unsafe — it simply reminds you to only install APKs from sources you trust.
   - If Google Play Protect asks *"Scan app?"* or shows a prompt, tap **Install anyway** (or **Scan app** and then install).
5. Once installed, tap **Open** to launch **Mural**.

---

## Step 2 — First Launch

When Mural opens for the first time, you'll see three short setup screens:

1. **Choose the language you want to speak:**
   - Tap the picker under *"What would you like to speak?"* and choose **English · International** (or any of the 13 supported languages), then tap **Continue**.
2. **Choose your subtitle / meaning language:**
   - Under *"A little help, in your language"*, pick the language you understand best for meanings and explanations — for example, **Arabic** — then tap **Choose and continue**.
3. **Accept the AI processing notice:**
   - On the *"Before we talk"* screen, Mural explains that your speech and text are sent directly to your chosen AI provider (such as Google Gemini) while your conversations and flashcards stay saved locally on your phone.
   - Tap **Agree and continue**.

---

## Step 3 — Get & Add Your Gemini API Key (Required for Live Voice Calls)

**What is an API key?** Think of it like a personal free pass from Google that lets the Mural app on your phone talk directly to Google Gemini without needing a middleman server or subscription.

1. On your phone, open **[Google AI Studio API Keys](https://aistudio.google.com/api-keys)** (you can also reach this inside Mural via **Settings → Advanced → Gemini API key → Manage API keys**).
2. Sign in with your **Google account** and accept Google's terms if asked.
3. Tap **Create API key** (if asked to pick or create a project, confirm creating one — **no credit card is required** for the free tier).
4. Tap **Copy** to copy the key (it usually starts with `AIza...`).
5. Now open **Mural**:
   - Tap the **Settings** gear icon in the top-right corner.
   - Scroll down to **Advanced** and verify:
     - `Conversation funding` is set to **My API key**
     - `AI provider` is set to **Google Gemini**
   - Tap **Gemini API key**.
   - Tap **Save key**, paste your copied key, and tap **Save**.

Your Gemini key is now encrypted safely inside **Android Keystore** on your phone.

---

## Step 4 — (Strongly Recommended, Free, No Credit Card) Add Groq Whisper for Accurate Transcripts

**Why add Groq?** Google Gemini handles the live voice call, while **Groq Whisper** quietly re-transcribes what you said in the background with dramatically higher accuracy — especially when you have an accent or mix English and Arabic words — giving you much cleaner transcripts, mistake reports, flashcards, and learner memory (up to ~8 hours/day free, **no credit card required**).

1. Open **[Groq Console API Keys](https://console.groq.com/keys)** (or in Mural go to **Settings → Speech-to-Text (Groq Whisper) → Get Free Groq API Key**).
2. Sign up or sign in for free (you can sign in with Google).
3. Tap **Create API Key**, give it any short name (like `mural`), tap **Submit**, and **Copy** the key (it usually starts with `gsk_...`).
4. In **Mural**, open **Settings** (gear icon) and scroll to **Speech-to-Text (Groq Whisper)**:
   - Tap **Groq API Key**, paste your key, and tap **Save**.
   - Make sure the **Accurate Transcripts (Groq Whisper)** switch is turned **ON**.
   - Leave **Whisper Model** on **Whisper Large v3 (Best Accuracy)** *(you can switch to `Whisper Large v3 Turbo` later if you prefer slightly faster refinement)*.

---

## Step 5 — (Optional) Analysis & Helper AI Provider (OpenRouter / SovereignEG)

By default, Mural uses your same **Google Gemini** key for post-session mistake reports, flashcard generation, and live word translations (`Default (Gemini 3.5 Flash)`).

If your free Gemini text quota ever runs low after heavy practice, you can offload just those text/report tasks without changing your live voice experience:

1. Go to **Settings → Analysis & Helper AI (Flashcards & Meaning)**.
2. Tap **Analysis Provider** and choose **OpenRouter (`openrouter.ai`)** or **SovereignEG (`sovereigneg.com`)**.
3. Tap **Get OpenRouter / SovereignEG API Key**, create a key, then paste it under **`<Provider> API Key`**.
4. Tap **Active Model** to pick from the live searchable model list (including free models on OpenRouter) or type any model ID.

Your **live voice calls always stay on Google Gemini Live**!

---

## Step 6 — Daily Workflow & Tips for Beginners

### 🎙️ 1. Starting a Voice Call
- On the **Talk** tab, tap the large orange **microphone button** and allow microphone permission when Android asks.
- You can also open the **Themes** tab to pick:
  - **Just talk** — opens a fresh, personalized topic tailored to your memory and level.
  - **Suggested for You** — AI-generated topics and **ROLEPLAY** cards based on your interests (tap **Refresh** anytime for new ideas).
  - **24 Built-in Themes** — everyday scenarios like ordering coffee, talking about your weekend, job/office chats, or travel.
- **🎧 Microphone tip (Bluetooth vs. Phone Mic):** Bluetooth earbuds automatically switch Android into lower-quality "call mode" (SCO) during voice sessions, which can make speech recognition less accurate. If your transcripts look slightly garbled, try speaking directly into your **phone's microphone** or using **wired earphones**.

### 💬 2. Useful Controls During a Call
- **Meaning subtitles:** Tap the captions/speech bubble button on the left of the mic to show or hide Arabic (or your chosen language) translations under Mural's replies.
- **Tap any word:** Tap any English word in Mural's caption bubble to pause and see its instant translation and explanation (**A little meaning**).
- **Type instead:** In a noisy room or on public transport? Tap **Type instead** to reply by text while still listening or reading.
- **A little help:** Stuck and don't know what to say? Tap **A little help** for a gentle hint.
- **End & Report:** Tap **End** when you're done. Mural automatically saves the conversation and builds your **Post-Session Report**.

### 📊 3. Reading Your Post-Session Report
Right after a call ends (or anytime from **Words → Session Reports**), review three helpful parts:
- **What You Did Well:** Celebrates real wins from your call and lists any saved flashcards you naturally used (**`SM-2 +1`** — their review intervals advance automatically!).
- **FIX #1, #2…:** Shows your original sentence crossed out, the natural correction, its Arabic meaning, a clear explanation, and a speaker icon to hear it pronounced offline.
- **Sound More Natural:** Gives smoother, native-sounding alternatives for things you said okay — tap **Add to Deck** on any upgrade you want to practice later.

### 🧠 4. Studying Flashcards in the `Words` Tab
- **Switch views cleanly:** Use the **Topic Decks** / **Session Reports** switcher near the top, plus the **search bar** to find any topic, sentence, or Arabic meaning.
- **Tap Study Due (or any Topic Deck)** to open the flashcard viewer:
  - **Normal & Slow Audio:** Tap the speaker button (`1.0x`) or **`0.75x`** to hear the sentence clearly offline without using any API quota.
  - **Auto-play:** Toggle the **Auto-play** chip ON to hear the answer spoken automatically every time you flip a card.
  - **Listening Mode:** Toggle the **Listening** chip ON to test your ear first — Mural plays the audio on the front of the card before showing any text!
  - **Rate honestly:** After flipping, choose **Again**, **Hard**, **Good**, or **Easy** so Anki SM-2 schedules your next review at the optimal day.
- **Practice in Call:** Tap **Practice in Call** on any Topic Deck to start a voice call where the AI weaves those exact sentences into real conversation.
- **Deleting safely:** You can delete one card (trash icon inside the flashcard viewer), delete a mistake fix inside a report, delete an entire Topic Deck, or delete a session's flashcards from **Session Reports** *(with an optional checkbox to keep or remove the report itself — your full conversation transcript in Settings is never deleted)*.

### ⚙️ 5. Customizing Voice, Coach Style, Memory, Reminders & Backups
Open **Settings** (top-right gear icon):
- **Memory & English Level:** View, add, edit, or delete what Mural remembers about you (*About You*, *Interests & Goals*, *Speaking Adaptation & Weaknesses*, *Recent Topics*), toggle **Auto-update memory after calls**, or set your CEFR level manually (**Auto**, or `A1`–`C2`).
- **AI Voice & Coach Style:** Pick from **8 voices** (like `Kore`, `Puck`, `Aoede`, `Fenrir`) and **4 coaching styles** (`Relaxed Friend`, `Balanced Coach`, `Strict Coach`, `Roleplay Immersion`).
- **Audio Environment & Push-to-Talk:** Under **Conversation**, choose **Quiet Room (Faster Reply)** for quicker responses at home or **Noisy Place (Filter Background)** to enable the on-device RMS noise gate and block distant background chatter. You can also turn on **Push-to-Talk (Hold Mic to Speak)** to keep the mic muted until you hold the orange mic button and send your turn immediately when you release.
- **Daily Goal & Reminder:** Set your daily speaking target (`5–20 min`), daily card target (`5–20 cards`), and an optional gentle **Daily Reminder** (only fires if you haven't practiced yet and have due cards or an open goal).
- **Backups (`Learning backup & data`):** Tap **Export learning backup** regularly to save a JSON file of all your flashcards, reports, memory, and transcripts to Google Drive or local storage. *(Backups never include your API keys.)*

---

## ❓ Troubleshooting FAQ

### 1. Android won't install the APK or Play Protect shows a warning
- Make sure you enabled **Allow from this source** for the exact browser or file manager you used to open the `.apk`.
- If Google Play Protect pauses installation because the app isn't from the Play Store yet, tap **More details → Install anyway** (or allow Play Protect to scan it first).

### 2. Mural says my API key is invalid or wasn't accepted
- Make sure you pasted the **entire key** with no extra spaces before or after it.
- Check that you pasted the **Google Gemini** key (`AIza...`) inside **Settings → Advanced → Gemini API key**, and the **Groq** key (`gsk_...`) inside **Settings → Speech-to-Text (Groq Whisper) → Groq API Key** — don't swap them!
- Make sure you didn't accidentally delete the key in Google AI Studio or Groq Console.

### 3. I see "Gemini quota was reached" or rate-limit errors (HTTP 429)
- Free Gemini keys have per-minute and daily limits. Wait 1–2 minutes and try again.
- If live voice still works but Post-Session Reports or meanings hit limits, switch **Settings → Analysis & Helper AI → Analysis Provider** to **OpenRouter** (which offers free models) or **SovereignEG**.

### 4. My speech transcripts look inaccurate or miss words
- Make sure **Groq Whisper** is enabled in **Settings → Speech-to-Text (Groq Whisper)** with **`Whisper Large v3 (Best Accuracy)`** selected.
- If you are wearing **Bluetooth earbuds**, switch to the **phone's built-in mic** or **wired earphones** — Bluetooth hands-free mode severely lowers microphone audio quality on Android.
- In noisy rooms or around other people talking, switch **Audio Environment** to **Noisy Place (Filter Background)** or enable **Push-to-Talk (Hold Mic to Speak)** in **Settings → Conversation** so background voices don't cut in.
- Speak at a natural pace in a reasonably quiet spot before pausing so Gemini's turn detection doesn't cut in too early.

### 5. Mural remembered a wrong fact about me or guessed the wrong level
- Go to **Settings → Memory & English Level**.
- Tap any fact or interest to **edit** it, tap the **trash icon** to delete it, or tap **English Level** to override `Auto` with your exact CEFR level (`A1`–`C2`).

### 6. How do I update to a newer APK without losing my flashcards and history?
- **Do NOT uninstall Mural first.** Simply download the new `.apk` from Releases and tap **Install / Update** directly over your existing app.
- Even better: tap **Settings → Learning backup & data → Export learning backup** first so you always have a safe JSON copy of your entire learning history.
