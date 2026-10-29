# No-API Google Sheets transcript-status watcher

This Apps Script is optional; it does **not transcribe** audio. It links
only **existing draft transcript file metadata** from the private
VoiceGrowth/Transcripts folder to the existing registry `VG Inbox` tab.
It uses Google's built-in Apps Script account permissions, not a paid AI API.

1. Visit [script.google.com](https://script.google.com/) under the Google
   account with private Drive/Sheet access; create a standalone script.
2. Paste `tools/voicegrowth_transcript_watcher.gs`.
3. In Project Settings → Script Properties add:
   - `VOICEGROWTH_TRANSCRIPTS_ID`: exact ID of the canonical private
     **Transcripts** Drive folder (do not guess among duplicate folders).
   - `VOICEGROWTH_REGISTRY_SHEET_ID`: ID of your KBP Intelligence
     Dashboard & Registry Google Sheet.
4. Run `setupVoiceGrowthTranscriptWatcher` and authorize Google Drive
   metadata, Google Sheets and trigger permissions. It creates one
   15-minute time-based trigger.
5. Create a synthetic short audio/transcript through the Android pilot,
   verify the actual private Drive `Transcripts/YYYY/MM-MMM` folder,
   then inspect only the **H, Q, R, S** columns on a uniquely matched
   `VG Inbox` row. Audit manual clinical verification separately.

Safety constraints: no text contents read, no patient info copied into
Apps Script logs, and no changes to consultation, diagnosis or review
status. Exact filename and unique row match are required; ambiguous
duplicates are skipped. No record is ever automatically called verified.

`VOICEGROWTH_TRANSCRIPTS_ID` is deliberately a manually verified
configuration value, because this user's Drive contains more than one
similarly named VoiceGrowth/Transcripts folder. Google Drive's time-driven
triggers are best effort and subject to Apps Script quotas.
