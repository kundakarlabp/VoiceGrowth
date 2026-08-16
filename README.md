# VoiceGrowth Android Companion — v1.1.0

VoiceGrowth is a privacy-first Android companion for a clinician who wants low-friction capture of call recordings, bedside/academic discussions, and voice reflections, followed by local transcription, de-identification, structured Markdown, and optional Google Drive sync.

## Pipeline

```text
iQOO/Funtouch OEM call recording OR manual discussion recording
        ↓
SAF folder scan + persistent URI permission
        ↓
Android on-device SpeechRecognizer (API 33+ recorded-audio injection)
        ↓
Clinical identifier pattern scrubber + manual-review warning
        ↓
Local Markdown transcript
        ↓
Network-constrained WorkManager Drive queue
        ↓
VoiceGrowth/Transcripts/YYYY/MM-MMM/
```

## What changed in v1.1

- Restored the complete Android source tree; the previous repository upload contained only build stubs.
- Removed the former demo transcription implementation that returned hard-coded clinical text.
- Added real recorded-file transcription through Android's **on-device** `SpeechRecognizer`, with PCM decoding and medical vocabulary biasing.
- Separated local transcription from Google Drive sync so transcription works offline and **Wi-Fi only** is enforced by WorkManager network constraints.
- Persisted the selected OEM recording folder correctly and added a 15-minute WorkManager scan fallback in addition to the foreground monitor.
- Added URI-based deduplication and a file-stability window so an OEM recording is not processed while still being written.
- Added distinct `LOCAL_READY` and `WAITING_FOR_SYNC` states.
- Implemented optional original-audio upload (off by default) and clearly labels it as non-de-identified.
- Fixed retention cleanup for both app files and SAF/content URIs.
- Disabled Android backup for the clinical-data app and removed unnecessary broad media-storage permissions.
- Added Room migration, basic privacy/Markdown unit tests, lint, and an APK-producing GitHub Actions workflow.

## Important limitations

1. **Recorded-file transcription currently requires Android 13 / API 33+** because VoiceGrowth supplies decoded PCM through `RecognizerIntent.EXTRA_AUDIO_SOURCE`.
2. The phone must expose an **on-device speech recognition service** and have the needed offline language model installed. English (India), Telugu, Hindi, and Auto are exposed in settings.
3. `RECORD_AUDIO` permission is required by Android's `SpeechRecognizer` API and is also used for manual discussion recording.
4. Clinical de-identification is pattern based. It reduces obvious identifiers but **cannot guarantee anonymization**; every transcript is marked for manual review.
5. Original audio is never de-identified. The original-audio cloud upload toggle is therefore off by default.
6. VoiceGrowth reads OEM call recordings selected by the user through Android's Storage Access Framework. It does not attempt to capture privileged call audio directly.

## Google Drive

The app requests the narrow `drive.file` scope. Configure an Android OAuth client for package:

```text
com.voicegrowth.app
```

with the SHA-1 fingerprint of the signing certificate used on the device. Then connect the Google account from **Settings → Google Drive**.

## Build

Requirements:

- JDK 17
- Android SDK 34
- Gradle 8.4

Local build with an installed Gradle:

```bash
gradle testDebugUnitTest lintDebug assembleDebug
```

GitHub Actions runs the same validation and publishes `app-debug.apk` as the `VoiceGrowth-debug-apk` workflow artifact.

## iQOO / Funtouch setup

1. Enable native automatic call recording in the Phone app.
2. Open VoiceGrowth and grant microphone/notification permissions.
3. Select the OEM call-recording directory using **Choose folder**.
4. If Drive sync is required, connect Google Drive in Settings.
5. Exempt VoiceGrowth from aggressive battery restrictions if Funtouch OS repeatedly stops the foreground monitor; the periodic WorkManager scan remains a fallback.

## Privacy defaults

- Clinical privacy mode: **ON**
- Upload transcript: **ON**
- Upload original audio: **OFF**
- Delete local source audio: **7 days after completed processing/sync**
- Android app backup: **OFF**
