# Deprecated: Android local ASR

VoiceGrowth no longer performs transcription or diarization on Android.

The prior Sherpa/Whisper-base on-device path was retired because Android SAF identifiers are not stable Google Drive provenance and the local worker introduced duplicate/cached-audio failure modes. Android is now intentionally limited to recording/import and upload of original audio to the canonical private `VoiceGrowth/Audio` tree.

Canonical transcription is performed by the backend Drive-ID-first bridge:

`Drive file ID -> fresh Drive download -> byte-count verification -> SHA-256 -> OpenAI transcription -> provenance-rich transcript in VoiceGrowth/Transcripts`

See `backend/DRIVE_SETUP.md` for the active architecture and configuration.
