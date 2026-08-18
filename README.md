# VoiceGrowth Android Companion — v1.3.1

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

## v1.3.1 production hardening

This release focuses on the device issues found during real iQOO use rather than adding another AI layer.

### Capture and screen-off reliability

- A persistent **VoiceGrowth ready** notification exposes **Record** and **Scan now** controls in the notification shade/lock screen when notifications are allowed.
- During recording, the foreground notification shows a chronometer and a direct **Stop** action.
- Microphone recording holds a bounded partial wake lock while active so screen-off CPU sleep does not interrupt capture.
- Quick Settings capture and notification capture are routed through a tiny show-when-locked activity before the microphone foreground service is created on current Android versions.
- VoiceGrowth no longer keeps a permanent 60-second `dataSync` foreground monitor alive. OEM-call discovery uses 15-minute WorkManager fallback scans plus explicit Scan-now and setup-triggered scans.

### Call-recording folder

- Storage Access Framework read permission is validated and persisted before a folder is accepted.
- Settings reports whether folder access is healthy and how many supported audio files are visible.
- Scanning is recursive (bounded depth/node count) so nested iQOO/Funtouch recording folders are supported.
- Lost permissions produce an actionable re-select message instead of silently returning an empty scan.
- Older raw labels such as `primary:Recordings/Record` are repaired to the folder's readable display name during diagnostics.

### Google Drive

- Drive authorization now uses **Google Identity Services `AuthorizationClient`** rather than the deprecated `GoogleSignInClient` scope flow.
- The app requests only `https://www.googleapis.com/auth/drive.file` and sends the resulting short-lived bearer token to the Drive REST client.
- Authorization/configuration failures do not consume a recording's per-file retry budget.
- **Status 10 / OAuth client mismatch** is translated into an actionable diagnostic that displays the exact installed package name and signing SHA-1.
- The Settings screen provides **Connect**, **Recheck**, and **Disconnect** actions.

A Google Cloud Android OAuth client still has to match the APK actually installed on the phone:

```text
package: com.voicegrowth.app
SHA-1: shown live in VoiceGrowth → Settings → Google Drive
```

The Google Drive API must be enabled in the same Cloud project. GitHub debug APKs may have different signing certificates between builds; the durable configuration is the repository's secret-backed stable release-signing workflow.

### On-device AI model setup

- Model import checks available private storage before copying when the provider exposes file size.
- Import progress is shown as percentage/MB rather than appearing frozen during a large copy.
- Model replacement is staged atomically; a failed replacement attempts to preserve the previously working model.
- Settings includes a **Get Gemma 3 1B** link to `https://huggingface.co/litert-community/Gemma3-1B-IT` and explains that Hugging Face sign-in / Gemma license acceptance may be required before downloading the `.litertlm` file.
- A practical starting file is `gemma3-1b-it-int4.litertlm`; select the downloaded file through Android Files and VoiceGrowth copies it into app-private storage.

## Recording and capture modes

### Calls

VoiceGrowth does **not** intercept privileged call audio. Enable native automatic call recording in iQOO/Funtouch, select its recording folder through Android Storage Access Framework, then use **Test & scan** to verify access.

### Bedside / academic discussion

Tap **Record discussion → Bedside / Consult**. The finished recording enters the standard ASR → privacy → optional AI → Markdown → Drive pipeline.

### Voice reflection / quick capture

Use **Record discussion → Reflection**, the **VoiceGrowth capture** Quick Settings tile, or the persistent notification **Record** action. VoiceGrowth does not continuously listen in the background.

### Imported audio

Use the **+** button in VoiceGrowth or Android **Share → VoiceGrowth**. Shared audio is copied into VoiceGrowth-owned storage before queueing so temporary URI grants cannot strand later processing.

## On-device AI

1. Open **Settings → On-device AI**.
2. Tap **Get Gemma 3 1B** if a compatible `.litertlm` model has not yet been downloaded.
3. Complete any required model-license/sign-in step, download the `.litertlm` file, then choose **Import downloaded model**.
4. Keep **GPU first** normally; LiteRT-LM automatically falls back to CPU when GPU initialization fails.
5. Enable **On-device AI synthesis**.
6. Optionally enable the approximately 9 PM local **Daily AI digest**.

Android sandboxing prevents VoiceGrowth from directly reading another application's private model storage. The model must be accessible to Android's document picker.

## AI safety model

VoiceGrowth uses a hybrid architecture:

```text
specialized speech model → transcript → forced de-identification for AI → LLM organization
```

The LLM does not replace the primary long-audio speech recognizer. Source transcript text is retained separately from AI synthesis. For library questions and daily digests, evidence is forcibly de-identified before LiteRT-LM and prior AI-generated synthesis is excluded from retrieval evidence.

VoiceGrowth does not claim speaker diarization and does not use direct long-audio Gemma transcription.

## Important limitations

1. Recorded-file transcription requires Android 13 / API 33+ because VoiceGrowth supplies decoded PCM through `RecognizerIntent.EXTRA_AUDIO_SOURCE`.
2. The device must expose an on-device speech-recognition service and have the required offline language model installed.
3. `RECORD_AUDIO` is required for VoiceGrowth microphone capture and Android speech recognition.
4. Clinical de-identification is heuristic and cannot guarantee anonymization; manual review remains required.
5. LLM output can be wrong despite evidence-constrained prompting and must be checked against source transcripts.
6. Original audio is not de-identified. Original-audio Drive upload is therefore off by default.
7. LiteRT-LM speed/GPU support are device/model dependent. AI failure does not block transcript creation.
8. iQOO/Funtouch background-management behavior cannot be fully validated in GitHub CI; perform the screen-off smoke test below on the target device.

## iQOO / Funtouch setup and smoke test

1. Enable native automatic call recording.
2. Grant VoiceGrowth microphone and notification permissions.
3. In Settings, select the OEM recording folder and press **Test & scan**. Confirm access is healthy and visible audio is non-zero when recordings exist.
4. Open **Notification settings** and keep the **VoiceGrowth Capture Controls** channel enabled. If Funtouch removes controls, also review the app/battery settings and allow background activity/auto-start as appropriate for the device.
5. Lock the phone, start a short VoiceGrowth recording from the notification or Quick Settings tile, leave the screen off for 2–3 minutes, then use **Stop**. Confirm the recording appears and processes.
6. For Drive, read the package + SHA-1 displayed in Settings, configure that Android OAuth client in Google Cloud, then press **Recheck / Connect**.
7. For AI, download a compatible `.litertlm`, import it, confirm visible progress, then test one short transcript before enabling the daily digest.

## Build

Requirements:

- JDK 17
- Android SDK 34
- Gradle 9.2.1
- Android Gradle Plugin 8.13.0
- Kotlin 2.2.0
- Google Play services auth 21.6.0
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

## Privacy defaults

- Clinical privacy mode: **ON**
- On-device AI synthesis: **OFF**
- Daily AI digest: **OFF**
- Upload transcript: **ON**
- Upload original audio: **OFF**
- Automatic deletion of original source audio: **OFF**
- Android app backup: **OFF**
