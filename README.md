# VoiceGrowth Android Capture — v2.0.0

VoiceGrowth is a small Android capture and Google Drive transport app for clinical consult discussions, bedside/academic conversations, voice notes, imported audio, and optional OEM call recordings.

## v2 design

VoiceGrowth deliberately does **not** transcribe or run a local LLM. The original recording is the primary artifact.

```text
VoiceGrowth consult recording
OR imported audio
OR optional OEM call recording
        ↓
Original audio preserved locally
        ↓
WorkManager upload queue
        ↓
User-selected Google Drive folder via Android SAF
        ↓
VoiceGrowth/Audio/YYYY/MM-MMM
        ↓
Downstream ChatGPT / cloud transcription and clinical analysis
```

The Android app therefore has one job: **capture the best practical source audio reliably and move it safely to the user's private Drive folder.**

## Recording quality

Manual consult/discussion recordings use:

- MPEG-4 container (`.m4a`)
- AAC-LC encoder
- mono audio
- 48 kHz sampling
- 160 kbps target bitrate
- Android `VOICE_RECOGNITION` audio source

The recording service runs as a microphone foreground service, holds a bounded partial wake lock while recording, and provides a direct Stop action from the active-recording notification.

For quiet speakers, physical microphone placement still matters: keep the phone unobstructed and reasonably central between speakers.

## Capture methods

### Consult / bedside discussion

Tap **Record consult** in the app, or use the persistent notification / Quick Settings capture control. Stop when the discussion is complete. A valid recording is queued directly for Drive sync.

### Imported audio

Use **+** in VoiceGrowth or Android **Share → VoiceGrowth**. Imported audio is copied to VoiceGrowth-owned storage and queued for Drive.

### OEM call recordings

Optional. Select the highest iQOO/Funtouch folder containing the phone's native recordings. VoiceGrowth retains SAF read permission and scans nested folders with a stability window so files still being written are not uploaded prematurely.

## Google Drive

The supported v2 path is Android Storage Access Framework (SAF):

1. Open **Settings → Google Drive**.
2. Choose a Google Drive folder through Android Files.
3. Grant **Use this folder / Allow**.
4. VoiceGrowth verifies persisted read/write access with a create/write/delete test.
5. Recordings are stored under:

```text
VoiceGrowth/Audio/YYYY/MM-MMM
```

The Drive app/provider owns Google authentication and cloud transport. VoiceGrowth does not require a Google OAuth client, embedded Google login, or Drive REST access token.

## Upload behavior

- A finished manual recording is immediately inserted as `WAITING_FOR_SYNC`.
- Imported and discovered OEM recordings use the same queue.
- WorkManager retries transient failures with exponential backoff.
- Wi-Fi-only upload is optional.
- Legacy v1 queue states are included as sync candidates so older recordings can still be uploaded after upgrading.
- A recording becomes `UPLOADED` only after a Drive audio file reference has been stored.

## Local retention

Automatic local deletion is **off by default**.

If enabled, VoiceGrowth deletes local source audio only after:

1. the configured retention period has elapsed, and
2. the corresponding Drive audio copy exists.

## Removed from v2

The following v1 components were intentionally removed from the Android app:

- sherpa-onnx / local Whisper transcription
- Android prerecorded-file speech-recognition fallback
- Gemma / LiteRT-LM on-device synthesis
- local transcript Markdown generation
- local transcript knowledge search
- local daily AI digest
- Google Drive REST/OAuth path

These functions are better handled downstream by higher-capability cloud transcription and ChatGPT-based medical reasoning, without making the phone app large or brittle.

## Privacy boundary

Original audio may contain patient identifiers and is **not de-identified**. Use an access-restricted Drive location and follow applicable institutional requirements for clinical recordings. Downstream transcription/clinical processing should explicitly separate source transcript, normalized transcript, extracted facts, and AI interpretation.

## Build

Requirements:

- JDK 17
- Android SDK 34
- Gradle 9.2.1
- Android Gradle Plugin 8.13.0
- Kotlin 2.2.0
- Room 2.8.4

Validation target:

```bash
gradle testDebugUnitTest lintDebug assembleDebug
```

GitHub Actions runs the same validation and publishes the debug APK artifact when the workflow is triggered.

## Physical-device smoke test

1. Install the v2 build on the target phone.
2. Grant microphone and notification permissions.
3. Choose the private Google Drive destination through Android Files and run **Test & sync**.
4. Record a 60–90 second two-speaker consult-style discussion, including a quieter second speaker.
5. Lock the screen for part of the recording.
6. Stop from the notification.
7. Confirm **Waiting sync → Drive synced**.
8. Confirm the original `.m4a` exists under `VoiceGrowth/Audio/YYYY/MM-MMM` and plays clearly.
9. Repeat once with background noise typical of the ward and once with the phone placed between both speakers.

The downstream transcription/ChatGPT layer should be implemented only after this capture-and-upload path is verified on the real device.
