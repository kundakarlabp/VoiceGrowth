# VoiceGrowth Android Capture — v2.2.0

## Mandatory upgrade/data-retention rule

**Never advise uninstall/reinstall to fix an incompatible VoiceGrowth APK.**
Existing installations may contain original clinical recordings, Room records,
Drive sync queues, and model files. GitHub Actions debug previews may use
ephemeral signing certificates and therefore **cannot be assumed to update
older previews**. In-place updates require matching application ID and APK
signing identity, higher versionCode, and validated non-destructive
migrations. Production signed releases must pass the baseline certificate
and versionCode checks in `.github/workflows/release-apk.yml`.

See [Android upgrade and app-data preservation policy](docs/ANDROID_UPGRADE_DATA_PRESERVATION.md).
Google AI Edge Gallery's downloaded Gemma model is in Gallery's **separate**
storage; removing VoiceGrowth alone does not delete it.



## Active workflow (October 2026): ChatGPT on demand, no external backend

**Source of truth:** VoiceGrowth Android captures originals → user's private Google
Drive stores them → clinician explicitly asks ChatGPT/Work to transcribe/review
a selected recording → deidentified **KBP Intelligence Dashboard & Registry**
Google Sheet stores curated consultations and longitudinal clinical events →
a workspace-private **ChatGPT Site** displays the approved clinical-academic
dashboard when the user creates it in Work.

No Railway, no scheduled Drive polling, no background ASR/LLM, no paid API
service or second automatic dashboard is required or desired for this workflow.
The legacy `backend/` code remains for reference but is **not part of the
active path** and its automatic paid processing flags default to false.
Do not deploy it solely to make ChatGPT access Drive.

The private registry now includes `VG Summary`, `VG Inbox`, `VG Consults`,
`VG Clinical Events` and `VG Data Dictionary`. Fourteen distinct Drive audio objects from October 22, 26 and 27 are indexed in the Inbox (11 on October 26, two on October 27, and one on October 22), with transcription correctly marked **Not started**. Do not classify them as real clinical consultations
until their audio has been transcribed and reviewed.

**Implementation specification and daily manual review protocol:**
[ChatGPT Sites + on-demand VoiceGrowth](docs/CHATGPT_SITES_ON_DEMAND_WORKFLOW.md).

A successful Drive archive is **not** a transcript. This ChatGPT
conversation's Google Drive connector can list/read private Drive metadata
and download recordings, but a separate supported speech-to-text path must
actually process the audio before any transcript or consultation facts are
entered. Creating or deploying a ChatGPT Site requires ChatGPT Work/Sites;
a GitHub commit alone cannot launch the Site. All source audio and sensitive
identifiers stay private; a Sites dashboard must remain workspace-private.


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
- A recording becomes `UPLOADED` (shown as **Provider saved**) only after a deterministic filename is written and SHA-256 read back through the Android DocumentsProvider. Provider-local `content://` URIs are not Google Drive API file IDs.
- This read-back **does not prove Google's remote cloud has synchronized**; confirm from another device. Retrying uses a stable filename to prevent duplicate objects.

## Cloud verification, duplicate folders and transcription boundary

**The Android "Provider saved" status is not a Google Drive cloud-confirmation.**
VoiceGrowth verifies a byte-for-byte SHA-256 read-back from the selected
Android DocumentsProvider, keeps original audio, and never fabricates a Drive
REST file ID from an Android `content://` URI.

Existing remote files are compared with the source hash before a retry; the
app reuses identical bytes and refuses to overwrite different audio. A
temporary provider cache and deterministic folder choice reduce duplicate
directory creation, but Android SAF cannot guarantee an atomic create or
cloud-level idempotency. Do not assume this prevents every provider race.

The authorized backend can inspect remote Drive metadata without model costs:

```sh
cd backend
# Service account must already have read permission on this private Audio root.
export GOOGLE_DRIVE_AUDIO_FOLDER_ID=<canonical Audio folder ID>
export GOOGLE_SERVICE_ACCOUNT_JSON=<secret stored outside the repository>
python drive_audit.py
```

This read-only audit reports repeated sibling folder names and files with
matching Drive MD5 checksums; it does **not** delete/merge files or confirm
that every local phone recording was uploaded. For complete end-to-end proof,
compare this remote inventory against the app's locally queued recording IDs.

### Compare the phone queue with ChatGPT-accessible Drive

Tap **Share sync manifest** on the VoiceGrowth home screen and share its
`voicegrowth_sync_manifest.json` file directly into an authorized ChatGPT
conversation. It includes stable remote archive filenames, recording timestamps,
source sizes and Android provider-save statuses. It excludes audio bytes,
patient names, access tokens and provider-local URI strings. Imported filenames
are reduced to safe stable archive names.

ChatGPT can then compare this local manifest against Google Drive's canonical
Audio folder and identify missing/duplicate remote copies. The manifest
alone does not certify cloud upload: Drive must be checked independently.
Do not make the private Drive folder publicly shareable for this purpose.

Automatic paid backend transcription is **off by default**, even if Drive and
OpenAI credentials are present. The legacy bridge requires explicit opt-in
(`VOICEGROWTH_AUTO_TRANSCRIBE=true`) and will incur API costs if used.
No AI processing is initiated by the Android app. Clinician-authorized,
on-demand transcription and review require a separately verified pathway.
Do not expose clinical audio publicly to enable transcription.

## End-to-end review: Drive → transcript → clinical discussion

Backend additions register through `full_app:app`:

| Screen / API | Purpose |
| --- | --- |
| `/dashboard/cloud` | Protected live **read-only** Drive audio inventory; distinguishes duplicate copies, backend transcripts and structured consults |
| `/dashboard/recordings` | Protected list of stored recordings, transcript availability and consult extraction |
| `/dashboard/recordings/{id}` | Readable source transcript and corrected transcript where available, with clinical verification warnings |
| `GET /v2/drive/audio-inventory` | Bearer-authenticated JSON Drive metadata cross-check |
| `GET /v2/recordings/inventory` | Bearer-authenticated backend-only recording/transcript status |
| `POST /v2/recordings/transcripts/import` | User-authorized imported ChatGPT/clinician transcript with exact Drive file ID; provenance is **caller asserted**, not automatically verified |
| `POST /v2/recordings/{id}/consult-import` | Explicit structured `ConsultRecord` JSON import after a transcript is available; initially `ai_extracted`, not clinically verified |

The live cloud inventory requires the private service account in a running
backend plus explicit `GOOGLE_DRIVE_AUDIO_FOLDER_ID`; it reads only Google
metadata and makes **no audio downloads or GPT requests**. A missing backend
entry does not prove the audio is absent from Drive or a transcript is absent
from other Google folders.

A transcript imported through an authenticated API is **not automatically
considered verified** against the original audio or transformed into
structured patient facts. Conflicting imports return HTTP 409, preserving
existing text for clinical review. Clinical extraction is an independent
explicit step with a separately tracked review status.

Example: after a user has **already** reviewed the audio/transcript and
obtained an authorized backend API token, the body for transcript import is:

```json
{
  "drive_file_id": "<actual private Drive file ID>",
  "source_filename": "VG_YYYYMMDD_HHMMSS_ID_manual_discussion.m4a",
  "transcript_text": "<verified transcription text>",
  "method": "clinician_reviewed"
}
```

No patient identifiers, audio, tokens, or transcripts should be committed
to this public repository or GitHub Issues.

The existing `drive_ingest.py` can perform **paid** server-side OpenAI
transcription only in a private, configured deployment with explicit
`VOICEGROWTH_AUTO_TRANSCRIBE=true`; `app.py` legacy upload processing
also requires `VOICEGROWTH_AUTO_PROCESS_UPLOADS=true`.
Both default to **false**. A ChatGPT subscription does not implicitly give
the backend API credentials or free unattended speech-to-text processing.
No deployment, transcription or transcript import has occurred merely by
merging this code.

## Local retention

Automatic local deletion is **disabled**, including for older installations with the previous preference enabled. The provider only offers local read-back; until independent cloud verification exists, deleting source audio risks permanent loss. Old recordings remain preserved.

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

## Safe preview versus production upgrade

GitHub Actions debug artifacts for this branch use application ID
`com.voicegrowth.app.preview`, displayed as **VoiceGrowth Preview**. They install
beside an existing `com.voicegrowth.app` without replacing its databases or
changing existing app preferences. Configure the target folder again in Preview,
using a **nonclinical** test recording first.

The production release remains `com.voicegrowth.app`. Upgrading it while
retaining data requires an APK signed with the exact **existing production
signing certificate**. The `Signed Android Release` workflow supports this
only when its four release signing secrets are correctly configured.
Do not uninstall the existing app to make a debug APK fit: local recordings
can otherwise be lost.

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
7. Confirm **Waiting sync → Provider saved**, then independently verify Google cloud upload.
8. Confirm the original `.m4a` exists under `VoiceGrowth/Audio/YYYY/MM-MMM` and plays clearly.
9. Repeat once with background noise typical of the ward and once with the phone placed between both speakers.

Analysis is deliberately **on demand** in the existing ChatGPT / ID Professor workflow, not through a paid API or scheduled service. Clinical audio must be handled under NIMS consent and privacy rules.

## Interruption recovery

The app checks `manual_recordings` on launch for playable, unregistered M4A files left by interrupted microphone services. Recoverable audio is queued for archiving. Unplayable files are retained rather than silently deleted. Device tests must confirm this behavior.

## Call recording limits

VoiceGrowth does not record the telephone downlink, bypass Android permissions, or silently start a microphone. Use supported OEM/Google Phone recording **only where enabled and authorized**. Select the user-accessible OEM recordings folder once for periodic import, or use Android's Share action for recordings in restricted provider storage. No phone-call recording is initiated by this app.
