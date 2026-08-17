# VoiceGrowth Android Companion — v1.3.0

VoiceGrowth is a privacy-first Android companion for low-friction capture of iQOO/Funtouch call recordings, bedside/academic discussions, voice reflections, and imported audio. It performs local speech-to-text, de-identification, optional on-device Gemma/LiteRT-LM synthesis, searchable local knowledge retrieval, structured Markdown, and optional Google Drive sync.

## Core pipeline

```text
iQOO/Funtouch OEM call recording
OR VoiceGrowth discussion/reflection recording
OR imported/shared audio
        ↓
Android on-device SpeechRecognizer
        ↓
Clinical identifier scrubber + manual-review warning
        ↓
OPTIONAL Gemma-compatible LiteRT-LM model
        ↓
AI title / summary / decisions / actions / questions / learning points
        ↓
Markdown preserves BOTH AI synthesis and source ASR transcript
        ↓
Optional Google Drive sync
```

## New in v1.3

- **Quick Settings capture tile:** add `VoiceGrowth capture` to Android Quick Settings. One tap starts a private voice reflection; tapping again stops it and queues normal processing.
- **Audio import/share:** select audio from the VoiceGrowth home screen or use Android **Share → VoiceGrowth** from Files, WhatsApp, or another app. Shared audio is copied into app-owned storage first so temporary URI permissions cannot strand later transcription.
- **Local knowledge search:** search processed transcript Markdown by filename, detected themes, and transcript text. Results are ranked locally and show the matched passage.
- **Ask AI over your library:** when on-device AI is enabled, VoiceGrowth sends only the best matching transcript passages to Gemma and asks it to answer from that evidence only. Retrieved text is forcibly de-identified first, regardless of the normal display/privacy toggle, and AI answers are instructed to cite `[Recording <id>]`.
- **Daily AI digest:** manually open **Today's digest**, or opt in to an approximately 9 PM device-local daily digest. It summarizes the day's processed transcripts locally when battery/storage conditions are suitable.
- Daily digest is **OFF by default** and is automatically disabled if on-device AI/model configuration is removed.
- App version is `1.3.0` / versionCode `5`.

## Recording and capture modes

### Calls

VoiceGrowth does **not** attempt privileged call-audio interception. Enable native automatic call recording in iQOO/Funtouch, select its folder once through Android Storage Access Framework, and VoiceGrowth detects completed files.

### Bedside / academic discussion

Tap **Record discussion → Bedside / Consult**. The finished recording enters the standard ASR → privacy → optional AI → Markdown → Drive pipeline.

### Voice reflection / quick capture

Use **Record discussion → Reflection**, or add the **VoiceGrowth capture** Quick Settings tile for one-tap start/stop recording. VoiceGrowth does not continuously listen in the background.

### Imported audio

Use the **+** button in VoiceGrowth or Android **Share → VoiceGrowth**. Up to 20 audio files can be imported in one action. Imports are copied into VoiceGrowth-owned storage before queueing.

## On-device AI

1. Open **Settings → On-device AI**.
2. Import an accessible `.litertlm` model.
3. VoiceGrowth copies it into private app storage.
4. Use **GPU first** normally; initialization automatically falls back to CPU when necessary.
5. Enable **On-device AI synthesis**.
6. Optionally enable the **Daily AI digest around 9 PM**.

Android sandboxing prevents VoiceGrowth from directly reading a model held only inside AI Edge Gallery's private storage. If needed, provide an accessible copy of the `.litertlm` file through Android's document picker.

## AI safety model

VoiceGrowth uses a hybrid architecture:

```text
specialized speech model → transcript → forced de-identification for AI → LLM organization
```

The LLM does not replace the primary long-audio speech recognizer. Source transcript text is retained separately from AI synthesis. Prompts explicitly prohibit inventing diagnoses, drug doses, laboratory values, decisions, plans, assignments, or recommendations.

For **library questions and daily digests**, text is forcibly de-identified before LiteRT-LM even if ordinary Clinical Privacy Mode is disabled.

The local search in v1.3 is ranked full-text retrieval, not a vector-embedding database. **Ask AI** adds semantic interpretation over the retrieved passages while remaining grounded in those passages.

VoiceGrowth v1.3 does not claim speaker diarization and does not use direct long-audio Gemma transcription.

## Daily digest

- Stored locally at `VoiceGrowth` app external files under `digests/digest_YYYY-MM-DD.md`.
- Manual generation is available from the home screen.
- Optional periodic generation is scheduled near 9 PM device-local time using WorkManager and requires healthy battery/storage conditions.
- The digest is currently **local only**; normal individual transcript Drive uploads continue as configured.

## Important limitations

1. Recorded-file transcription requires Android 13 / API 33+ because VoiceGrowth supplies decoded PCM through `RecognizerIntent.EXTRA_AUDIO_SOURCE`.
2. The phone must expose an on-device speech recognition service and have the needed offline language model installed. English (India), Telugu, Hindi, and Auto are available in settings.
3. `RECORD_AUDIO` permission is required for Android speech recognition and VoiceGrowth microphone recording. Missing permission leaves discovered recordings recoverable rather than consuming retry budget.
4. Clinical de-identification is pattern based and cannot guarantee anonymization; Markdown remains marked for manual review.
5. LLM output can be wrong despite evidence-constrained prompting and must be checked against source transcripts.
6. Original audio is not de-identified. Original-audio Drive upload remains off by default.
7. LiteRT-LM GPU support, speed, memory use, and model quality are device/model dependent. AI failure does not block ordinary transcript creation.

## Google Drive

VoiceGrowth uses the narrow `drive.file` scope. Configure an Android OAuth client for:

```text
package: com.voicegrowth.app
SHA-1: fingerprint of the APK signing certificate installed on the phone
```

For durable OAuth, use the secret-backed stable release-signing workflow rather than ephemeral GitHub debug certificates.

## Build

Requirements:

- JDK 17
- Android SDK 34
- Gradle 9.2.1
- Android Gradle Plugin 8.13.0
- Kotlin 2.2.0
- Room 2.8.4
- LiteRT-LM Android 0.11.0

Validation:

```bash
gradle testDebugUnitTest lintDebug assembleDebug
```

GitHub Actions runs the same validation and publishes `VoiceGrowth-debug-apk`.

### Stable signed release APK

The manual **Signed Android Release** workflow requires these repository Actions secrets:

```text
ANDROID_KEYSTORE_BASE64
ANDROID_KEYSTORE_PASSWORD
ANDROID_KEY_ALIAS
ANDROID_KEY_PASSWORD
```

Never commit the private release keystore or passwords to this public repository.

## iQOO / Funtouch setup

1. Enable native automatic call recording.
2. Install VoiceGrowth and grant microphone/notification permissions.
3. Select the OEM call-recording directory.
4. Add the **VoiceGrowth capture** Quick Settings tile if desired.
5. Import a compatible `.litertlm` model if AI features are wanted.
6. Connect Google Drive if transcript sync is required.
7. Exempt VoiceGrowth from aggressive Funtouch battery restrictions if the foreground monitor is repeatedly stopped; the periodic folder scan remains a fallback.

## Privacy defaults

- Clinical privacy mode: **ON**
- On-device AI synthesis: **OFF**
- Daily AI digest: **OFF**
- Upload transcript: **ON**
- Upload original audio: **OFF**
- Automatic deletion of original source audio: **OFF**
- Android app backup: **OFF**
