# MedScribe validation

CI must pass:

- `:scribe:testDebugUnitTest`
- `:scribe:lintDebug`
- `:scribe:assembleDebug`
- APK SHA-256 generation

Physical-device smoke test after installation:

1. Install Whisper Base and speaker models.
2. Record 10–30 seconds of a single speaker; lock/unlock the phone during recording and verify the file finalizes.
3. Enroll that recording as `Me`.
4. Record/import a two-speaker English/Telugu/Hindi discussion and verify speaker separation, enrolled-speaker matching and editable transcript.
5. Record/import a >45-minute file and verify full transcription completes with the documented diarization memory fallback.
6. Link a Google Drive folder through Android Files and verify a transcript appears under `MedScribe/Transcripts/YYYY/MM-MMM`.
7. Verify original audio is not uploaded unless the opt-in switch is enabled.
8. Use Review / learn with ChatGPT and confirm the share sheet contains source transcript plus evidence-checking instructions.
