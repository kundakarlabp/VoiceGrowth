# VoiceGrowth Android Companion — v1.3.3

VoiceGrowth is a privacy-first Android companion for low-friction capture of iQOO/Funtouch call recordings, bedside/academic discussions, voice reflections, and imported audio. It performs local speech-to-text, de-identification, optional on-device Gemma/LiteRT-LM synthesis, searchable local knowledge retrieval, structured Markdown, and optional Google Drive sync.

## Core pipeline

```text
iQOO/Funtouch OEM call recording
OR VoiceGrowth discussion/reflection recording
OR imported/shared audio
        ↓
Dedicated local Whisper file-ASR (preferred)
        ↓
Android on-device SpeechRecognizer (fallback only)
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

## v1.3.3 — reliable prerecorded-file transcription

A real iQOO device exposed a weakness in the previous ASR architecture: Android may report that on-device speech recognition exists while the vendor recognizer still does not reliably consume prerecorded audio supplied through `RecognizerIntent.EXTRA_AUDIO_SOURCE`. The result can be a valid recording remaining **Pending** with `On-device speech recognition failed (no speech match)`.

v1.3.3 removes that dependency for normal recorded-file transcription.

### Dedicated offline Whisper ASR

- Adds sherpa-onnx non-streaming Android ASR.
- Adds a one-tap installer for **Whisper tiny multilingual INT8** (~104 MB model download).
- Model encoder/decoder downloads are size- and SHA-256-validated before activation.
- Downloads use staged `.part` files so an interrupted install is not treated as a valid model.
- Recorded audio is decoded directly to mono PCM16 and passed to Whisper; no microphone input is involved.
- Long recordings are processed in overlapping 26-second chunks with deterministic overlap de-duplication.
- Whisper handles Auto/multilingual, English, Telugu and Hindi modes.
- Android SpeechRecognizer remains only as a compatibility fallback.
- If the reliable ASR model is missing, the recording remains **Pending** without consuming its retry budget.
- Successful model installation automatically re-enqueues pending recordings.

Use:

1. Open **Settings → Automation & processing → Reliable offline file transcription**.
2. Tap **Install offline Whisper (~104 MB)**.
3. Keep the app/network available until the progress reaches 100%.
4. Pending recordings are retried automatically when installation completes.

The Whisper model performs **speech-to-text only**. Gemma/LiteRT-LM remains a separate post-transcription intelligence layer for summarization, decisions/actions, learning points, Ask AI and daily digest.

### Device build optimization

The v1.3.3 personal/debug build is packaged for **arm64-v8a**, matching modern 64-bit Android phones such as the target iQOO device. This avoids bundling unused ARM32/x86/x86_64 copies of ONNX Runtime and sherpa-onnx.

## v1.3.2 — Google Drive reliability retained

The recommended Drive path does not depend on VoiceGrowth OAuth registration.

### Recommended Drive connection — Android Storage Access Framework

1. Open **Settings → Google Drive**.
2. Tap **Choose Google Drive folder**.
3. In Android Files, open the side menu and choose **Google Drive**.
4. Choose **My Drive** or another parent folder, then tap **Use this folder / Allow**.
5. VoiceGrowth persists read/write tree access and performs a create/write/delete verification before accepting the folder.
6. Existing waiting transcripts are queued for sync automatically.

VoiceGrowth creates the configured hierarchy inside the selected folder, by default:

```text
VoiceGrowth/Transcripts/YYYY/MM-MMM
```

Original audio, when explicitly enabled, uses the corresponding `VoiceGrowth/Audio/...` hierarchy.

This path relies on Android's system document provider. The Google Drive app/provider owns Google-account authentication and cloud transport. VoiceGrowth receives access only to the selected folder tree. Normal sync therefore requires **no VoiceGrowth OAuth client, no package/SHA-1 registration, no embedded Google login, and no access-token handling**.

The Google Identity Services `AuthorizationClient` + Drive REST path remains available as an optional advanced fallback.

## Capture and screen-off reliability

- Persistent **VoiceGrowth ready** notification with **Record** and **Scan now** controls.
- Active recording notification shows a chronometer and direct **Stop** action.
- Microphone recording holds a bounded partial wake lock while active.
- Quick Settings and notification capture are routed through a show-when-locked activity before creating the microphone foreground service on current Android versions.
- No permanent 60-second foreground polling service; OEM-call discovery uses WorkManager fallback scans plus explicit Scan-now/setup-triggered scans.

## Call-recording folder

- SAF read permission is validated and persisted before a folder is accepted.
- Settings reports folder health and visible supported audio count.
- Scanning is recursive with bounded depth/node count for nested iQOO/Funtouch folders.
- Lost permissions produce an actionable re-select message rather than a silent empty scan.

## Recording and capture modes

### Calls

VoiceGrowth does **not** intercept privileged call audio. Enable native automatic call recording in iQOO/Funtouch, select its recording folder through Android SAF, then use **Test & scan**.

### Bedside / academic discussion

Tap **Record discussion → Bedside / Consult**. The finished recording enters the ASR → privacy → optional AI → Markdown → Drive pipeline.

### Voice reflection / quick capture

Use **Record discussion → Reflection**, the **VoiceGrowth capture** Quick Settings tile, or the persistent notification **Record** action. VoiceGrowth does not continuously listen in the background.

### Imported audio

Use the **+** button or Android **Share → VoiceGrowth**. Shared audio is copied into VoiceGrowth-owned storage before queueing.

## On-device AI

1. Open **Settings → On-device AI**.
2. Import a compatible `.litertlm` model; `gemma3-1b-it-int4.litertlm` is the recommended starting model.
3. Keep **GPU first** normally; LiteRT-LM falls back to CPU when GPU initialization fails.
4. Enable **On-device AI synthesis**.
5. Optionally enable the approximately 9 PM local **Daily AI digest**.

Android sandboxing prevents VoiceGrowth from directly reading another application's private model storage. The Gemma model must be accessible through Android's document picker before import.

## AI safety model

```text
local speech model
        ↓
source transcript
        ↓
forced de-identification for AI
        ↓
Gemma/LiteRT-LM organization and synthesis
```

The LLM does not replace the primary speech recognizer. Source transcript text is retained separately from AI synthesis. Library questions and daily digests use forcibly de-identified source evidence, and prior AI-generated synthesis is excluded from retrieval evidence.

VoiceGrowth does not claim speaker diarization and does not use direct long-audio Gemma transcription.

## Important limitations

1. Dedicated Whisper transcription requires the optional ~104 MB offline ASR model to be installed once.
2. If Whisper is not installed, Android 13+/API 33+ recorded-file injection is attempted only as a fallback and may be unsupported by some vendor recognizers.
3. `RECORD_AUDIO` is required for VoiceGrowth microphone capture; dedicated offline file-ASR does not need microphone input.
4. Clinical de-identification is heuristic and cannot guarantee anonymization; manual review remains required.
5. LLM output can be wrong despite evidence-constrained prompting and must be checked against source transcripts.
6. Original audio is not de-identified. Original-audio Drive upload is therefore off by default.
7. LiteRT-LM speed/GPU support and Whisper inference speed are device dependent. AI synthesis failure does not block a successfully produced transcript.
8. Google Drive must be exposed as a DocumentsProvider in Android Files for the recommended SAF sync path; optional OAuth remains available otherwise.
9. iQOO/Funtouch background-management behavior still requires physical-device smoke testing.
10. The v1.3.3 personal/debug APK is arm64-v8a only.

## iQOO / Funtouch smoke test

1. Grant microphone and notification permissions.
2. Select the OEM call-recording folder and press **Test & scan**.
3. Install **Reliable offline file transcription** in Settings.
4. Record a 60–90 second discussion, lock the screen for part of it, then stop.
5. Confirm **Pending → Transcribing → Waiting sync / Local** and verify the transcript text is genuine.
6. Confirm Gemma synthesis appears separately from the source transcript.
7. Choose a Google Drive folder through Android Files and press **Test & sync**.
8. Test English first, then Auto/Telugu/Hindi as needed.

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
- sherpa-onnx Android 1.13.4

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

Never commit the private release keystore or passwords to this public repository. Stable signing is required for seamless upgrades between independently built APKs.

## Privacy defaults

- Clinical privacy mode: **ON**
- On-device AI synthesis: **OFF**
- Daily AI digest: **OFF**
- Upload transcript: **ON**
- Upload original audio: **OFF**
- Automatic deletion of original source audio: **OFF**
- Android app backup: **OFF**
