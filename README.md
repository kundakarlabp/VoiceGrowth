# VoiceGrowth Android Companion — v1.2.0

VoiceGrowth is a privacy-first Android companion for low-friction capture of iQOO/Funtouch call recordings, bedside/academic discussions, and voice reflections, followed by local speech-to-text, de-identification, optional on-device Gemma/LiteRT-LM synthesis, structured Markdown, and optional Google Drive sync.

## Pipeline

```text
iQOO/Funtouch OEM call recording OR manual discussion/reflection recording
        ↓
SAF folder scan / VoiceGrowth microphone recorder
        ↓
Android on-device SpeechRecognizer (API 33+ recorded-audio injection)
        ↓
Clinical identifier pattern scrubber + manual-review warning
        ↓
OPTIONAL: Gemma-compatible LiteRT-LM model on the phone
        ↓
Evidence-grounded title / summary / decisions / actions / questions / learning points
        ↓
Local Markdown containing BOTH the AI note and the de-identified source ASR transcript
        ↓
Network-constrained WorkManager Drive queue
        ↓
VoiceGrowth/Transcripts/YYYY/MM-MMM/
```

## What changed in v1.2

- Added an optional **on-device LiteRT-LM intelligence layer** after speech-to-text and de-identification.
- Added model import from Android's document picker. The selected `.litertlm` model is copied into VoiceGrowth's private app storage so inference does not depend on another app's private files.
- Added **GPU-first with automatic CPU fallback** and a CPU-only option.
- Added long-transcript hierarchical processing: transcript chunks are converted to evidence notes, condensed when necessary, then synthesized into one final note.
- Added source-aware AI templates:
  - phone calls → commitments, decisions, actions and follow-up;
  - bedside/academic discussions → stated reasoning, decisions, teaching points and uncertainties;
  - reflections → ideas, lessons, questions and next actions.
- AI is explicitly instructed **not to invent diagnoses, doses, laboratory values, plans, assignments or recommendations**.
- AI receives only the **already de-identified transcript**, not the original audio or unsanitized ASR text.
- The AI-generated note is clearly separated from the source transcript and never silently replaces it.
- AI is **off by default**. Missing models, GPU incompatibility or inference failures do not fail the recording; VoiceGrowth falls back to the normal transcript pipeline.
- Bumped app version to `1.2.0` / versionCode `4`.
- Updated the Android/Kotlin build stack to the toolchain used by the current Google AI Edge Gallery generation and pinned LiteRT-LM Android to `0.11.0`.

## Recording modes

### Calls

VoiceGrowth does not attempt privileged call-audio interception. Enable the native iQOO/Funtouch automatic call recorder, select its recording folder once, and VoiceGrowth detects completed files through Android's Storage Access Framework.

### Bedside / academic discussion

Tap **Record discussion → Bedside / Consult**. VoiceGrowth records through the microphone and queues the finished recording automatically when automatic processing is enabled.

### Voice reflection

Tap **Record discussion → Reflection** for an on-demand voice note using the same processing pipeline.

## On-device AI setup

1. Open **Settings → On-device AI**.
2. Tap **Import .litertlm model**.
3. Select an accessible LiteRT-LM model file, for example a compatible Gemma model package.
4. VoiceGrowth copies the file into its own private storage. A multi-gigabyte model therefore requires equivalent free storage during import.
5. Leave **GPU first** selected for normal use. If GPU initialization is not supported on the phone, VoiceGrowth automatically retries with CPU.
6. Enable **On-device AI synthesis**.

Android application sandboxing prevents VoiceGrowth from directly reading a model stored only inside AI Edge Gallery's private app directory. If the model is still available in Downloads/shared storage, select that file. Otherwise obtain/export an accessible `.litertlm` copy and import it once into VoiceGrowth.

### AI safety model

VoiceGrowth deliberately uses a hybrid architecture:

```text
speech model → faithful transcript → de-identification → LLM organization
```

It does **not** ask Gemma to replace the primary long-audio speech recognizer. The Markdown file preserves the de-identified ASR transcript as the source of record and labels the Gemma output as an AI-generated synthesis that must be checked against that transcript.

The initial v1.2 AI layer does not claim speaker diarization and does not generate new clinical treatment recommendations.

## Important limitations

1. **Recorded-file transcription requires Android 13 / API 33+** because VoiceGrowth supplies decoded PCM through `RecognizerIntent.EXTRA_AUDIO_SOURCE`.
2. The phone must expose an **on-device speech recognition service** and have the needed offline language model installed. English (India), Telugu, Hindi, and Auto are exposed in settings.
3. `RECORD_AUDIO` permission is required by Android's `SpeechRecognizer` API and for manual discussion recording. If it is denied, discovered recordings remain pending and recover after permission is granted.
4. Clinical de-identification is pattern based. It reduces obvious identifiers but **cannot guarantee anonymization**; every transcript is marked for manual review.
5. LLM output can still be incorrect even with evidence-constrained prompting. It is supplementary and must be verified against the source transcript.
6. Original audio is never de-identified. Original-audio cloud upload is therefore off by default.
7. On-device LLM speed and GPU support are device/model dependent. AI failure falls back to the non-AI transcript rather than losing the recording.

## Google Drive

The app requests the narrow `drive.file` scope. Configure an Android OAuth client for package:

```text
com.voicegrowth.app
```

with the SHA-1 fingerprint of the signing certificate used on the device. Then connect the Google account from **Settings → Google Drive**.

For durable OAuth configuration, use the stable release-signing workflow rather than relying on GitHub's ephemeral debug signing certificate.

## Build

Requirements:

- JDK 17
- Android SDK 34
- Gradle 9.2.1
- Android Gradle Plugin 8.13.0
- Kotlin 2.2.0
- LiteRT-LM Android 0.11.0

Local debug validation with an installed Gradle:

```bash
gradle testDebugUnitTest lintDebug assembleDebug
```

GitHub Actions runs the same validation and publishes `app-debug.apk` as the `VoiceGrowth-debug-apk` workflow artifact.

### Stable signed release APK

The manual **Signed Android Release** workflow validates tests/lint, builds `assembleRelease`, verifies the APK signature, prints its SHA-256 checksum, and uploads `VoiceGrowth-release-apk`.

Configure these repository Actions secrets before running it:

```text
ANDROID_KEYSTORE_BASE64
ANDROID_KEYSTORE_PASSWORD
ANDROID_KEY_ALIAS
ANDROID_KEY_PASSWORD
```

`ANDROID_KEYSTORE_BASE64` must contain the base64-encoded private Android release keystore. Never commit the keystore or its passwords to this public repository.

## iQOO / Funtouch setup

1. Enable native automatic call recording in the Phone app.
2. Open VoiceGrowth and grant microphone/notification permissions.
3. Select the OEM call-recording directory using **Choose folder**.
4. Import a compatible `.litertlm` model if on-device AI synthesis is wanted.
5. If Drive sync is required, connect Google Drive in Settings.
6. Exempt VoiceGrowth from aggressive battery restrictions if Funtouch OS repeatedly stops the foreground monitor; the periodic WorkManager scan remains a fallback.

## Privacy defaults

- Clinical privacy mode: **ON**
- On-device AI synthesis: **OFF**
- Upload transcript: **ON**
- Upload original audio: **OFF**
- Automatic deletion of original source audio: **OFF**
- Retention period if source-audio deletion is explicitly enabled: **7 days after completed processing/sync**
- Android app backup: **OFF**
