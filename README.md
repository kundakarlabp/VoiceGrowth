# VoiceGrowth - Android Companion App (v1.0.0)

VoiceGrowth is a personal, zero-effort Android companion application designed specifically for medical practitioners, infectious disease specialists, and clinical researchers. 

It provides an automated pipeline:
```
iQOO Automatic Call Recording / Manual Discussion
                    ↓
   VoiceGrowth Local Folder Observer
                    ↓
  On-Device Whisper Transcription (ID Biased)
                    ↓
  Clinical Privacy De-identification Scrubber
                    ↓
  Standardized Markdown (.md) Generator
                    ↓
     Google Drive Automated Upload
   (VoiceGrowth/Transcripts/YYYY/MM-MMM/)
                    ↓
  Gemini / ChatGPT Scheduled Coaching Agent
 (Daily Review • Weekly Synthesis • Monthly Scorecard)
```

---

## Key Features & Architecture

### 1. Zero-Effort OEM Call Recording Capture
- Monitors native iQOO 13 / Funtouch OS call recording directory via Storage Access Framework (`DocumentFile` / `ContentObserver`).
- Automatically detects newly saved calls without requiring root, accessibility hacks, or third-party call recorder subscriptions.

### 2. Bedside & Academic Discussion Recorder
- Includes a dedicated "Record Discussion" module with a foreground audio recording service for bedside rounds, clinical consultations, antimicrobial stewardship meetings, and personal reflections.

### 3. On-Device Medical ASR Engine (Whisper / Sherpa-ONNX)
- Fully offline speech recognition running locally on the phone's Snapdragon NPU/CPU.
- Pre-configured with an **Infectious Diseases Biasing Prompt** containing specialized medical vocabulary (e.g., *Pseudomonas aeruginosa*, *Ceftazidime-avibactam*, *Aztreonam-avibactam*, *Colistin*, *Enterococcus faecium*, *Mucormycosis*, *Cytomegalovirus*, PK/PD parameters, MIC thresholds, and AMR phenotypes).

### 4. Clinical Privacy & De-Identification Mode
- Built-in heuristic and regex scrubber that detects and redacts hospital MRN/UHID numbers, patient introduction names, phone numbers, email addresses, and national ID patterns before any transcript touches the cloud.

### 5. Automated Structured Google Drive Sync
- Organizes transcripts into a clean folder hierarchy:
  `VoiceGrowth/Transcripts/2026/08-Aug/transcript_call_2026-08-15_16-22-00.md`
- Uploads directly via Google Drive REST API (`drive.file` scope) with background retry logic via Android `WorkManager`.

### 6. Queue & Status Monitoring
- Live status tracking: `Pending` → `Transcribing` → `Uploaded to Drive` (or `Failed` / `Skipped <30s`).
- Built-in transcript viewer dialog.

---

## App Settings (User Controls)

1. **Call Recording Folder**: Choose your OEM recording directory once via the SAF folder picker.
2. **Automatic Processing**: `ON` / `OFF` (Transcribe automatically upon call completion).
3. **Wi-Fi Only Sync**: `ON` / `OFF` (Wait for Wi-Fi before Drive upload).
4. **Only Process Recordings >30s**: `ON` / `OFF` (Filters out brief missed calls or voicemails).
5. **Upload Original Audio**: `OFF` by default (Preserves local storage and privacy).
6. **Upload Transcript (.md)**: `ON` by default.
7. **Delete Local Audio After X Days**: Configurable slider (default 7 days).
8. **Transcription Language**: `Auto` / `English` / `Telugu` / `Hindi`.
9. **Google Drive Destination**: `VoiceGrowth/Transcripts`.
10. **Clinical Privacy Mode**: `ON` / `OFF`.

---

## Getting Started & Building the APK

### Prerequisites
- Android Studio Hedgehog (2023.1.1) or newer
- JDK 17
- Android SDK 34 (Target: Android 14 / UpsideDownCake, Min SDK: 26)

### Steps to Build
1. Open Android Studio and select **Open**, then choose the `VoiceGrowthApp` directory.
2. Let Gradle sync dependencies.
3. Configure Google Drive API:
   - Go to [Google Cloud Console](https://console.cloud.google.com/).
   - Create a project (or select existing) and enable **Google Drive API**.
   - Under **Credentials**, create an **OAuth 2.0 Client ID** for Android.
   - Enter your package name: `com.voicegrowth.app` and your SHA-1 signing certificate fingerprint.
4. Connect your iQOO 13 via USB debugging or Wireless debugging.
5. Click **Run 'app'** or build an APK via **Build > Build Bundle(s) / APK(s) > Build APK(s)**.

---

## iQOO 13 Setup Guide
1. Open native **Phone** app > Settings (three dots) > **Call recording settings**.
2. Select **Record all calls automatically**.
3. Open VoiceGrowth > Settings > Tap **Choose Folder** > Select your device's `Recordings/Call` or `Record/Call` directory and tap **Use this folder**.
4. Tap **Connect Google Drive** to authorize your transcript destination folder.
5. You're all set! From now on, simply take calls normally; VoiceGrowth handles transcription and Drive sync in the background.
