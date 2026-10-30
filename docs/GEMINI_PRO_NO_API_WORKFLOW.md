# VoiceGrowth → Gemini web & Gemini Spark (no API, no new subscription)

## What is implemented in this PR

VoiceGrowth Home has a **user-confirmed** button: "Open Gemini — consultation
extraction". It copies only a *generic* source-grounded clinical extraction
instruction to the phone clipboard and launches https://gemini.google.com/app.
It does **not** attach a recording, log into the user's account, read private
Gemini conversations, receive an answer, upload patient information, write to
the registry or run unattended.

Because Android browsers and Gemini's consumer UI do not publish a reliable
background batch-transcription upload/result API, avoid screen-scraping or
undocumented app intent integration. The existing Share recording button
continues to show the OS chooser; users must decide if a provider is authorized.

## Immediate manual flow (Google AI Pro subscription)

1. Confirm recording/AI processing is authorized and remove identifiers.
2. VoiceGrowth Home → **Open Gemini — consultation extraction** → confirm.
3. In the Gemini site/app paste the generic template; tap **Add files** →
   **Add from Drive**, choose an *approved* recording or transcript.
4. Request faithful Telugu/English transcription, source timestamps,
   structured clinical draft, and an explicit uncertainty list.
5. Review drug names/doses, CR ID mapping, cultures, dates, recommendations
   and missing facts against audio and source records.
6. Transfer only verified and approved *de-identified* output to the existing
   KBP Intelligence Dashboard & Registry, not a second patient database.

Official Gemini Apps limit for paid Google AI Pro: up to 3 hours total
audio per prompt; each supported non-video file up to 100 MB, subject to
upload and usage limits. The actual file must be attached through Gemini's
UI; Drive connectivity may require Keep Activity and the Workspace connector.

## Promising unattended stage: Gemini Spark on *de-identified text*

Google officially offers Gemini Spark tasks and schedules to eligible
Google AI Pro subscribers in India (2026), with Google Drive, Docs and
Sheets integration. The connected Drive agent can read documents and Spark
can propose edits to Google Sheets; editing shared Workspace files may
require review/confirmation.

This **does not prove** it can discover and transcribe new binary M4A files
from a watched Drive folder. Do not run production patient audio through
Spark on that assumption.

Pilot the following in gemini.google.com → Spark (or Tasks), using *synthetic*
or institutionally authorized, de-identified transcripts in a supported
Drive document. Enable Workspace access only when appropriate:

    Once each day, review only new de-identified VoiceGrowth transcript
    documents explicitly marked ReadyForAI in my approved private Drive
    folder. For each distinct source recording, draft a consultation summary
    with source document URL, timestamp references, clinical problems,
    microbiology, antimicrobial medications and doses, ID recommendations,
    acceptance, follow-up, and an uncertainty checklist. Distinguish explicit
    fact from inferred interpretation. Preserve Telugu-English terms.
    Never invent patient identifiers, CR numbers, dates, doses or outcomes.
    Return results as structured JSON and an easy-to-review table.
    Do not mark anything clinically verified. Do not modify my existing
    registry or create a new registry until I inspect and approve the
    results. De-duplicate by source Drive file ID. For any unsupported
    audio file, report "audio input not accessible" and do not infer content.

This is a **pilot prompt, not a running scheduled task**. A successful manual
pilot and verified privacy approval are prerequisites to a daily schedule.

After validation, a possible next step is:
local VoiceGrowth Whisper/Gemma transcription → private Drive transcript
→ Google Apps Script metadata watcher (no inference) → de-identified
Google Docs source → Gemini Spark recurring extraction → user-reviewed,
canonical VG Consults and VG Clinical Events tabs.

The existing Apps Script watcher in
`tools/voicegrowth_transcript_watcher.gs` links transcript metadata; it
does not perform transcription or create Google Docs.

## Why this is not fully zero-click yet

- The stock Gemini consumer website has no supported unattended file-upload
  and return-transcript interface without an API or browser automation.
- Gemini Spark reads Drive docs but audio-folder watch/transcription has not
  been verified; Gmail-event schedules are possible in principle but are
  not validated with self-sent notifications for this workflow.
- Spark may require user approval to modify existing shared Sheets.
- Gemini tasks have usage limits and can pause or require user input.
- VoiceGrowth must produce usable source-grounded text; the short-recording
  pilot currently has its own limitations and does not process all 203
  pending recordings.

## Data protection is mandatory

Gemini Spark requires Keep Activity and access to Personal Intelligence /
connected apps, depending on account. Consumer Gemini Privacy Hub describes
that uploaded content may be processed and used for service improvement with
human review according to settings and product terms. An existing subscription
does **not** establish an institutional data processing agreement for raw
identifiable patient audio.

Never place patient names, direct identifiers, exact CR mappings, rare
identifying details, voice recordings, or other protected data into a
consumer AI account without explicit institutional legal/ethics/privacy
authorization. Even "de-identified" case details may still re-identify a
patient. Keep sensitive identifiers in approved local/institutional systems,
and require physician verification before structured registry updates.

Official sources:
- https://support.google.com/gemini/answer/14903178
- https://support.google.com/gemini/answer/17094507
- https://support.google.com/gemini/answer/17094710
- https://support.google.com/gemini/answer/13594961
- https://developers.google.com/apps-script/guides/triggers/installable
