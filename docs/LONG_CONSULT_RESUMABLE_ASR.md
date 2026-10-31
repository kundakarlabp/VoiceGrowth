# VoiceGrowth resumable offline transcription — technical review, 31 October 2026

## Scope

**Experimental preview, not a production in-place upgrade.** Preserves the
recording database schema, original audio, Android DocumentsProvider cloud
root and all existing recording IDs. Do not uninstall the existing app.
No Gemini/Google consumer AI is invoked automatically. Audio is processed
on-device with Whisper AAR 1.0.0 and a one-time imported multilingual GGML
model. The Google AI Edge Gallery shortcut continues to work but cannot
provide unattended inference/return-text services.

## New flow

1. Existing VoiceGrowth capture completes and is queued to the selected
   Android Drive provider using the existing idempotent, byte-verified
   provider sync. Its provider-saved result is **not cloud proof**.
2. If Settings → Offline transcription is explicitly opted in and a private
   Whisper model is installed, a WorkManager job runs while **charging**,
   battery/storage not low, and no recording is active. Also wakes
   approximately every 15 minutes if Android allows.
3. Eligible local recordings are between 1 second and **two hours** and have
   `driveAudioFileId != null`. Source is read via its original file/content
   URI. Only a **28-second** AAC/M4A window is decoded by Android MediaCodec
   to a temporary mono PCM WAV (~2–3 MB at 48 kHz), then Whisper transcribes
   that window; 1-second overlap helps protect boundary words.
4. A private checkpoint JSON for each window is durably written and renamed
   atomically with source metadata and model fingerprint. At most 3 windows
   are transcribed in one worker pass (150-second session budget); subsequent
   jobs and the periodic wake-up **resume the first missing window**.
5. When all checkpoints exist, one timestamped Markdown transcription is
   atomically written under private `files/transcripts/`, and **only then**
   is its original Room row's `transcriptPath` updated. Checkpoints are
   cleared after the DB confirms the committed transcript.
6. The existing Drive provider uploads the transcript idempotently under
   the sibling `VoiceGrowth/Transcripts/YYYY/MM-MMM` folder. Provider byte
   verification is performed, but a `content://` path is **not** a cloud
   Drive file ID and does not prove remote upload.
7. The draft contains exact model text and relative-to-recording timestamp
   windows, an explicit warning that overlap may duplicate words, and an
   EMPTY JSON clinical consultation template. No diagnosis, organism, CR,
   drug, outcome, or recommendation is inferred by this ASR worker.
8. A separate, explicitly authorized Gemini Spark text-processing pilot
   could read approved/deidentified Drive transcripts and draft clinical
   extraction fields. It has **not** been configured or validated. The
   canonical KBP registry is not written automatically by this branch.

## Important failure modes

- A transient inference failure records `Local ASR:` on the original
  recording row, but keeps previous valid window checkpoints. Press
  `Resume pending transcription` in Settings to explicitly clear ASR-only
  errors and try again. Audio status and original file remain untouched.
- A repeated transcript-Drive error records `Local ASR sync:` and avoids
  blocking every other recording; manual retry clears the error.
- All processed windows are private JSON checkpoints; no database migration
  and no new paid subscription. A change in model path/length/mtime or
  source metadata creates a new checkpoint fingerprint.
- Android background processes are opportunistic; long recordings may take
  many charging sessions, particularly with Whisper Small. The app does
  not guarantee real-time transcription or an overnight backlog completion.
- The WAV decoder supports platform-decodable mono/stereo/multichannel
  16-bit PCM after Android MediaCodec decode. Unsupported sample formats
  fail visibly rather than fabricating transcript text. Device-specific
  codec issues still require testing.
- Window-level recognition is not speaker diarization, semantic context
  fusion, silence-aware VAD or a guarantee of accurate bilingual transcription.
  A naive concatenation may repeat words in the overlap region.
- A foreign Gallery-downloaded Gemma model stays in **Gallery's own
  app storage**, inaccessible for direct native Whisper inference. This
  workflow imports a **Whisper** model into the VoiceGrowth preview, not
  a Gemma model.
- The existing experimental debug preview may be signed with an ephemeral
  debug certificate and might not update a previous preview in place.
  Never delete the old app to install a preview. For production upgrades,
  require same package ID, original signing certificate, higher versionCode,
  nondestructive migration, and a synthetic-data in-place update test.

## Test gates before release

1. CI: `testDebugUnitTest`, `lintDebug`, `assembleDebug`.
2. Test 5-second, 29-second, 5-minute, 30-minute and 75-minute synthetic
   recordings with known Telugu/English speech and an original human
   transcript. Compare text timestamps, boundary overlap, WER/CER, medical
   term/dose exact match and clinically significant hallucinations.
3. Interrupt a 5-minute sample after window 3 and relaunch; verify persisted
   checkpoint skipping, no deleted originals, and no duplicate transcript.
4. Simulate intermittent Android Drive provider access, failed upload,
   exhausted storage, app crash, charging interruption and simultaneous
   new recording. Confirm backoff and manual retry.
5. Verify original audio and transcript from a *second authorized device*.
6. Confirm consent, cloud-storage approval and research/secondary-use
   permissions before processing actual patient recordings.
7. Validate the installed app signing certificate against any candidate
   in-place update. This branch does not make an update compatibility claim.

## Why not stock Gallery or consumer Gemini?

The public Gallery Audio Scribe UI requires a user-selected short clip
(up to 30 s), and it does not expose a documented background ASR service
or return-result API. Consumer Gemini Pro supports manual file uploads,
and eligible Gemini Spark users can schedule Drive/Docs/Sheets processing;
automatic binary M4A watcher/transcription and consented clinical writeback
have not been verified without an external API.

Sources: https://github.com/ffmpegkit-maintained/whisper ;
https://support.google.com/gemini/answer/17094710 ;
https://developer.android.com/develop/background-work/background-tasks/persistent
