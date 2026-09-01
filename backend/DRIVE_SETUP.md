# Google Drive ingestion setup

VoiceGrowth Android already archives original audio to Google Drive through Android SAF. VoiceGrowth Clinical can independently read that archive using a **Google service account**. This keeps Google OAuth/login code out of the Android app and avoids embedding Google credentials on the phone.

## One-time setup

1. In a Google Cloud project, enable **Google Drive API**.
2. Create a dedicated service account for VoiceGrowth Clinical.
3. Create a JSON credential for that service account and store the JSON only in the backend host's secret manager.
4. In Google Drive, share the parent folder that contains the VoiceGrowth archive with the service account email as **Viewer**. The backend needs read/download access only.
5. Copy the shared folder's Drive file/folder ID.
6. Configure backend secrets:

```text
GOOGLE_DRIVE_ROOT_FOLDER_ID=<shared folder ID>
GOOGLE_SERVICE_ACCOUNT_JSON=<entire service-account JSON as a secret>
```

Optional:

```text
GOOGLE_DRIVE_POLL_SECONDS=300
GOOGLE_DRIVE_MAX_SCAN_ITEMS=5000
GOOGLE_DRIVE_MAX_DEPTH=6
```

Restart the backend. When both required values are present, Drive ingestion starts automatically.

## Behavior

- Recursively scans below the configured root so `VoiceGrowth/Audio/YYYY/MM-MMM` works without additional configuration.
- Accepts common audio extensions and audio MIME types.
- Each Drive file ID is ingested only once.
- The downloaded processing copy is saved under `AUDIO_DIR` and queued through the same durable transcription pipeline.
- The Drive file ID and web-view link are retained in the recording record for provenance.
- For standard VoiceGrowth names such as `VG_20260901_103012_42_manual_discussion.m4a`, the recorded timestamp is parsed from the filename using Asia/Kolkata (+05:30); otherwise Drive creation time is used as the best available recording timestamp.
- The original Google Drive file is never modified or deleted by the backend; the service-account scope is Drive read-only.

## Security

The service-account JSON is a credential. Never paste it into GitHub, Android source, issue comments, screenshots or documentation.

The Drive folder contains identifiable patient audio by design. Share it only with the dedicated backend identity and authorized clinical users, and use deployment/storage controls appropriate for identifiable clinical information.

## Why service-account ingestion

This is intentionally different from the old VoiceGrowth Android Drive REST/OAuth implementation. Android remains a thin recorder using the system SAF provider. The server owns the machine-to-machine read credential, which is easier to rotate, audit and revoke without changing the APK.
