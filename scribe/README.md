# MedScribe Local

Independent Android application (`com.voicegrowth.medscribe`) for local-first long-form medical transcription and case-learning workflows.

## Core pipeline

1. Foreground microphone recording or imported audio.
2. Disk-backed PCM decode so multi-hour recordings do not need to fit in memory.
3. Offline multilingual Whisper (Tiny/Base/Small INT8) for English, Telugu, Hindi or automatic multilingual transcription.
4. Optional offline speaker diarization using Pyannote segmentation + 3D-Speaker embeddings.
5. Optional enrolled-speaker identification. Voice embeddings remain in app-private storage and are created only after explicit enrollment.
6. Conservative medical terminology cleanup; source audio remains available for verification.
7. Editable timestamped Markdown transcript.
8. Automatic Android Storage Access Framework sync to a user-selected Google Drive folder under `MedScribe/Transcripts/YYYY/MM-MMM`.
9. Explicit Share / Review with ChatGPT handoff for evidence checking, unclear terminology and case-based learning.

## Models

Models are downloaded only when selected and are integrity-checked before activation. Base Whisper is the default balanced choice. Small is substantially larger and slower but can improve difficult multilingual audio. Speaker models are approximately 46 MB.

## Voice recognition

Speaker identification is probabilistic. The user first records/imports a clean 10–30 second single-speaker sample and explicitly enrolls a name such as `Me`. The resulting embedding is stored only inside the Android application. The app does not silently create biometric profiles for people in conversations. Speaker labels should be verified before clinical or research use.

## Long recordings

Recording supports up to a 12-hour foreground session. Full transcription uses bounded overlapping windows. Offline diarization is limited to the first 45 minutes of a single file to protect phone memory; longer files are still fully transcribed, and the transcript records when diarization was skipped.

## Phone-call limitation

Android reserves direct cellular call uplink/downlink capture for privileged/system components. A normal third-party APK cannot guarantee two-sided direct cellular call recording on every device. MedScribe therefore supports microphone/speakerphone capture and import of recordings produced by an OEM/native dialer.

## Privacy

Original audio upload is off by default. Google Drive access uses Android's persisted document-tree permission and is limited to the folder selected by the user. The ChatGPT learning handoff is explicit rather than automatic so clinical transcript material is not sent to an external AI service without a user action.
