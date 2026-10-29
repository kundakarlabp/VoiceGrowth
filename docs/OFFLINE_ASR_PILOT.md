# VoiceGrowth offline ASR pilot (30 October 2026)

## Intent and production boundary

This branch is an **experimental, opt-in preview** for transcription without a paid
inference API, extra subscription, laptop, or always-on server. It does not
invoke Gemini Pro, ChatGPT, Google APIs or any other consumer AI for inference.

**DO NOT merge or use with patient audio until CI, device tests, vendor review
and institutional privacy/consent approval have been completed.** Keep the
production VoiceGrowth app installed and unchanged. Its existing Android SAF
original-audio archive remains the source of truth.

The pilot uses the third-party MIT-licensed `dev.ffmpegkit-maintained:whisper-android:1.0.0`
wrapper around whisper.cpp. The upstream project is young; inspect the source,
binary provenance and dependency chain before clinical deployment. It must
not communicate with any internet AI inference service. Android built-in
MediaExtractor/MediaCodec decodes mono AAC/M4A to a temporary app-private WAV.
The model is NOT included in the APK and is imported once through Android Files.

## Setup (preview only)

1. Build/install the preview APK **without uninstalling production**. The
   `.preview` application ID preserves existing production storage.
2. Download a multilingual `ggml-base.bin` (or `ggml-small.bin`) from
   the [upstream whisper.cpp model collection](https://huggingface.co/ggerganov/whisper.cpp).
   Do NOT use `.en` variants for Telugu/English discussions.
3. In VoiceGrowth Preview → Settings → Offline transcription pilot, import
   the downloaded `.bin` once. The model stays in private app storage.
4. Select the private **VoiceGrowth root** in Settings → Google Drive.
   Selecting Audio itself cannot create sibling Transcripts through SAF.
5. Enable **Automatically transcribe short recordings**. This starts a
   WorkManager queue that runs on the phone **only while charging**, with
   adequate storage/battery, including on future app launches.
6. Record a **synthetic, non-patient** 60–90 second discussion in English
   and Telugu, then sync the audio. Check the original first.
7. Check `VoiceGrowth/Transcripts/YYYY/MM-MMM` from another device.
   This is a draft; compare exact wording, CR placeholders, species and
   antibiotic terminology with the audio.

## Deliberate limits

- Only recordings already archived through the Android document provider,
  with recorded duration **1–180 seconds**, are eligible. It will not yet
  process the existing 10–40 minute consultation backlog.
- Only one recording is processed per worker run, avoiding parallel model
  loads, excess RAM and recording interference. Workers enqueue the next
  short recording when the previous result is committed.
- No synthetic clinical note, automatic diagnostic assumptions, speaker
  diarization, patient linking or downstream research categorisation.
  Raw recognition is stored with timestamps and an explicit unverified flag.
- Android provider saved ≠ cloud verified; check remote Drive independently.
- Android WorkManager timing is opportunistic. Overnight charging provides
  better throughput; there is no guarantee of immediate or uninterrupted ASR.
- The preview does not download model weights automatically. This avoids
  unexpected mobile data charges and large unverified downloads.
- For long consultations, chunking with overlap, foreground execution,
  model-quality evaluation, and resumable processing are **future work**,
  not features claimed by this branch.
- A provider folder named Audio cannot host sibling Transcripts via SAF;
  the worker leaves the draft locally if that folder is selected.

## Validation gates

1. CI passes unit tests, Android lint and `assembleDebug`.
2. Test from a synthetic recording with the screen locked; ensure the
   existing audio is still archived when ASR/model decoding fails.
3. Validate offline operation (airplane mode after model import).
4. Observe RAM, battery/thermal impact, Android 16 background restrictions,
   retry semantics, duplicate transcript protection, and cloud read-back.
5. Compare model output with manually transcribed bilingual reference audio:
   WER/CER, clinical term error rate, drugs/doses/CR number exact-match, and
   number of hallucinated clinically actionable facts.
6. Review privacy, license, package supply chain, and signed APK upgrade.
7. Only after independent sign-off: evaluate longer recordings and the
   archival backlog, then structured notes and Google Sheets registry updates.

For production, keep original audio and reviewed clinical notes in
institutionally approved private storage. Never commit recordings, CR numbers,
patient names or transcripts to this public repository.
