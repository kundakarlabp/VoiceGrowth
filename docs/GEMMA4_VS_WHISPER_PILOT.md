# Gemma 4 versus Whisper: isolated, no-API Android benchmark

## Status

Experimental build flavor `gemmaBenchmark`, separate from the production app
(`com.voicegrowth.app`) and prior Whisper preview
(`com.voicegrowth.app.preview`). The benchmark app's ID suffix is
`.gemma`. It never writes to Google Drive or the KBP clinical registry,
does not run on a timer, and does not invoke the Gemini consumer app or any paid
API. The complete 211-entry backlog remains untouched.

## Google documentation and compatibility

- Gemma 4 E2B and E4B accept audio but **max 30 seconds per clip**.
  Source: https://ai.google.dev/gemma/docs/capabilities/audio
- Google LiteRT-LM Android `0.16.1` works with Kotlin 2.2, whereas
  `0.17+` uses Kotlin metadata too new for VoiceGrowth Kotlin 2.2.
  Source: https://github.com/google-ai-edge/LiteRT-LM/blob/main/docs/api/kotlin/getting_started.md
- E2B model file is approximately **2.58 GB** and E4B around **3.65 GB**
  for common LiteRT mobile variants. The downloaded *.litertlm*
  **must include the audio encoder**. Some optimized variants are
  text-only, so confirm the specific model artifact's modality metadata.
  Sources:
  https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm
  https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm
- Test model **CPU mode** initially to avoid device-specific GPU driver
  crashes; memory, speed and audio compatibility are unverified on your phone.

## How to test

1. Open the benchmark GitHub Actions workflow, ensure all checks pass, and
   install the `gemmaBenchmark` APK **alongside** the existing app. Never
   uninstall the recording app or replace its signing certificate.
2. Generate a **synthetic** two-speaker, English–Telugu clinical-themed
   sample **under 25 seconds** (no patient data). Save as AAC/M4A.
3. Import a multilingual Whisper GGML `.bin` and an audio-capable
   LiteRT-LM `.litertlm` E2B/E4B model into the app's private storage.
   Each import occurs once and may require several gigabytes of free space.
4. Select the same short M4A file. For every run the app uses Android's
   MediaCodec to decode and generates a bounded mono 16k WAV.
5. Run Whisper, then E2B, then E4B **sequentially**, keeping the benchmark
   app in the foreground. Do not run concurrent models.
6. Paste a human-verified reference transcript for descriptive **WER**.
   Compare medical entities separately: drug name, dose, microorganism,
   susceptibility, CR placeholder, language switching, deletions and
   hallucinations.

## Interpretation

WER is just lexical accuracy, not clinical safety. Benchmarking is **not**
enough to approve clinical automation. Use 20–30 synthetic/consented and
deidentified sample recordings, calculate aggregate error and latency, then
review thermal behavior, Android 16 memory, and whether the audio path is
supported by the specific LiteRT-LM version/model. This build is intentionally
limited to 25 seconds because Google documents 30 seconds per Gemma audio clip.
Longer segmentation, overlap and diarization are unimplemented.

Model engine errors are surfaced in the UI instead of inventing a transcript.
This build never uploads the reference, model result or audio. Original
VoiceGrowth Drive sync, production signing and consultation registry remain
unchanged. No claim of superior Gemma performance is possible until the pilot
produces actual side-by-side measurements.
