# VoiceGrowth Drive-first transcription setup

VoiceGrowth Android is intentionally a thin capture + Google Drive transport client. The backend transcription bridge reads only canonical audio objects from Drive, transcribes those exact bytes, and writes a provenance-rich transcript back to the canonical `Transcripts` folder. ChatGPT remains the intelligence/orchestration layer after a transcript exists.

## Required canonical Drive structure

Configure `GOOGLE_DRIVE_ROOT_FOLDER_ID` to the single VoiceGrowth root containing direct child folders named `Audio` and `Transcripts`.

```text
VoiceGrowth/
  Audio/
  Transcripts/
  Summaries/
  Growth Knowledge & Research/
  Master/
  Daily Digests/
  VoiceGrowth Processing Tracker
```

If duplicate `Audio` or `Transcripts` folders exist under the configured root, the bridge fails closed instead of guessing. You can explicitly pin the canonical folders with:

```text
GOOGLE_DRIVE_AUDIO_FOLDER_ID=<canonical Audio folder ID>
GOOGLE_DRIVE_TRANSCRIPTS_FOLDER_ID=<canonical Transcripts folder ID>
```

## One-time Google setup

1. Enable Google Drive API in a Google Cloud project.
2. Create a dedicated VoiceGrowth service account.
3. Store its JSON credential only in the backend secret manager.
4. Share only the private VoiceGrowth root with that service account as **Editor**. The worker needs download access to `Audio` and create access in `Transcripts`.
5. Never put the service-account JSON in Android, GitHub, screenshots, issue comments, or chat.

The backend does not change Drive sharing or permissions.

## Backend environment

Required:

```text
GOOGLE_DRIVE_ROOT_FOLDER_ID=<canonical VoiceGrowth root ID>
GOOGLE_SERVICE_ACCOUNT_JSON=<entire service-account JSON secret>
OPENAI_API_KEY=<server-side OpenAI API key>
```

Recommended explicit folder pins:

```text
GOOGLE_DRIVE_AUDIO_FOLDER_ID=<canonical Audio folder ID>
GOOGLE_DRIVE_TRANSCRIPTS_FOLDER_ID=<canonical Transcripts folder ID>
```

Optional:

```text
GOOGLE_DRIVE_POLL_SECONDS=300
GOOGLE_DRIVE_MAX_SCAN_ITEMS=5000
GOOGLE_DRIVE_MAX_DEPTH=6
OPENAI_TRANSCRIBE_MODEL=gpt-transcribe
OPENAI_DIARIZE_MODEL=gpt-4o-transcribe-diarize
```

## Integrity contract

For every source audio file the bridge:

1. Uses the Google Drive **file ID** as the immutable deduplication key.
2. Downloads a fresh processing copy from Drive; Android `content://` URIs are never accepted as provenance.
3. Verifies the downloaded byte count against Drive metadata when size is available.
4. Computes SHA-256 over the exact downloaded bytes.
5. Transcribes those bytes.
6. Writes a Markdown transcript into canonical `Transcripts` with Drive `appProperties` containing:
   - `sourceDriveFileId`
   - `sourceSha256`
   - `sourceBytes`
   - `voiceGrowthSchema=2`
7. Embeds the same Drive ID, source link, bytes, SHA-256, timestamps, transcription model and diarization state in the transcript body.
8. Deletes the temporary server audio copy after the transcript is safely persisted.

A transcript is never matched to audio by filename alone.

## Idempotency

Before transcribing, the bridge checks both its local processing record and Drive `appProperties` for an existing transcript with the exact source Drive ID. A source Drive object therefore has at most one canonical worker transcript.

## Responsibility boundaries

### Android
- Record/import audio.
- Upload original audio to canonical `Audio`.
- Persist the returned Drive file ID locally for transport status.
- No ASR, diarization, transcript generation, summarization, or research logic.

### Transcription bridge
- Drive discovery by ID.
- Verified download + SHA-256.
- OpenAI speech-to-text and optional diarization.
- Persist canonical transcript with exact source Drive provenance.
- No clinical interpretation or longitudinal intelligence.

### ChatGPT
- Reconcile Processing Tracker.
- Read provenance-valid transcripts.
- Classify and summarize conservatively.
- Maintain Learning & Growth, Research Candidates, Master, actions and Daily Digests.
- Perform literature/registry verification when warranted.

## Privacy

Original audio and patient-identifiable transcripts remain within the private Drive/backend workflow. Use a private backend, encrypted secrets, restricted service-account access, and appropriate clinical data governance. Do not expose patient identifiers in routine notifications.
