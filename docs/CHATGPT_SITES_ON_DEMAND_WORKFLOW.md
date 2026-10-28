# VoiceGrowth — ChatGPT Sites and clinician-invoked review (v3 direction)

**Active architecture:** Android VoiceGrowth recorder → private Google Drive audio → **user-initiated ChatGPT conversation/Work review** → private Google Sheets clinical-academic registry → **workspace-private ChatGPT Site** as a dashboard if Sites and connected-data access are enabled.

**Not part of the active system:** Railway, unattended transcriptions, periodic backend scans, Android LLM, paid OpenAI API jobs, public audio shares, and duplicate patient registries. The historical `backend/` implementation is optional legacy code, disabled by default and **not required** to use this workflow.

## Existing private registry

The user already has a native Google Sheet, **KBP Intelligence Dashboard & Registry**, which is the single source of truth for *curated deidentified* clinical and academic data. Do not copy its Google file ID, private Drive audio IDs, clinical narratives, secrets or patient information into this public GitHub repository.

Five VoiceGrowth tabs have been added:

| Tab | Grain / responsibility |
| --- | --- |
| **VG Summary** | Live formulas for unique audio, reviewed transcriptions, pending sources, verified consults, research and teaching counts |
| **VG Inbox** | One entry per **distinct recording**, keyed to a Google Drive file ID, with transcription, review, content classification and duplicate metadata |
| **VG Consults** | One deidentified consultation per row, keyed by `ConsultID`; multiple consultations may come from a single audio |
| **VG Clinical Events** | One dated antimicrobial/lab/microbiology/imaging/follow-up/outcome event per row, linked by `ConsultID` |
| **VG Data Dictionary** | Data definitions, verification statuses, missing-data rules and ethics requirements |

Existing `ClinicalCases`, `ResearchLeads`, `Evidence`, `TeachingAssets`, `Projects`, `Career` and `ActionsDecisions` tabs remain in use. **VG Consults is the canonical structured consult record; ClinicalCases contains a shorter deidentified teaching abstraction only when needed.** Do not count these as separate patient events.

Fourteen distinct recordings have been entered in `VG Inbox`: 11 dated October 26, 2026; two dated October 27; and one dated October 22. All have `TranscriptionStatus=Not started`, `ContentClass=Unclassified`, `ClinicalReviewStatus=Pending`. A known duplicate Drive copy of one October 26 recording is linked in its original row rather than counted as another recording. These are **audio files**, not confirmed clinical consultations.

## Clinician-invoked review SOP (one conversation at a time)

1. **Select and verify source**: Inspect `VG Inbox` for `Not started`. Confirm the actual private Drive file ID and size, compare with any local sync manifest, and identify duplicate metadata. Never treat a provider `content://` URI as a cloud Drive ID.
2. **Privacy and authorization**: Ensure that recording, cloud storage, transcription, secondary clinical use and any research use are authorized under applicable institutional policy/consent. Private Drive is not by itself equivalent to patient consent.
3. **Transcribe only when audio is actually available to an authorized transcriber.** Listening/transcription capability must be verified in the current ChatGPT session or Work task. If unavailable, keep `Not started`; do not invent words or use unrelated historic transcripts. Preserve the source language in a verbatim draft with timestamps, speaker uncertainty and `[inaudible mm:ss]`. Produce a separate English translation only if needed. Flag ambiguous doses, organisms, specimen dates, CR identifiers and clinical judgments for audio replay.
4. **Clinician verifies source**: Recheck critical facts against the actual audio/clinical documentation. Mark transcript `Reviewed` only after a human-reviewed correction. Store any identifiable full transcript only in an authorized restricted location; do **not** insert patient names/CR numbers into the deidentified Google registry or Sites.
5. **Classify content**: `Clinical consult`, `Teaching`, `Research`, `Other`, `Unusable`. Do not presume every manual discussion is a consultation.
6. **Extract consults**: One deidentified row per distinct consultation in `VG Consults`, using stable `ConsultID` and `RecordingID`. Preserve negative, unknown and not-discussed states distinctly. Never fill unstated outcomes or infer treatment appropriateness.
7. **Write longitudinal facts**: Multiple drugs, cultures, investigations, recommendations, acceptance, follow-ups and outcomes go into `VG Clinical Events`. Every critical clinical fact needs evidence time/Drive identity or an explicit missing-evidence flag. Follow-up is a separate later event.
8. **Human review before publication**: `ClinicalVerification=Verified` requires clinician verification; publication/research use also needs data-governance, appropriate ethics approval/waiver, reproducible coding rules, missingness assessment, denominator definition and consent handling. `ai_extracted` alone is never research-ready.
9. **Derive downstream work**: Populate existing `ResearchLeads`, `TeachingAssets`, `Questions`, `ActionsDecisions`, `Projects` and `Career` from verified deidentified facts. Do not duplicate source records.
10. **Update `VG Inbox`** with classification, source review and linked consultation count. Keep an audit trail of changes. Never mark processed solely because Drive stored an M4A.

## ChatGPT Sites: private dashboard design brief

Open ChatGPT **Work → Sites** (when available) and create a workspace-private Site titled **VoiceGrowth Clinical & Academic OS**. Do not publish or invite anyone by default. Use the connected private **KBP Intelligence Dashboard & Registry** native Google Sheet as canonical data when Site/plugin access to Google Drive is available and specifically consented to. Do not add an independent writeable case database that silently diverges from that source; if read/write connector support is unavailable, use manual **deidentified** snapshot import and label its refresh timestamp.

**Pages:**

- **Overview**: Unique audio / awaiting transcription / reviewed / clinical consults / follow-ups / verified cases. Separate operational data readiness from patient outcomes.
- **Voice Inbox**: sortable list by date/status/type, duplicate warning, link opening the private source in Drive, consent/review indicators. No embedded public audio player.
- **Consultations**: filters for syndrome, service, transplant/ICU status, organism, antibiotics, dates, verification; detail view with deidentified narrative and source provenance.
- **Clinical timeline**: microbiology, antimicrobials, investigations, follow-ups and outcomes from `VG Clinical Events`. Report unknown vs absent clearly.
- **Research studio**: case-series/research questions, PICO, methods, novelty, local sample feasibility, ethics status, missingness, case counts with reproducible denominators, proposal deadlines, publication candidate list. Separate *hypotheses* from *observed data*.
- **Teaching**: validated ID pearls, guideline updates, journal club questions, resident micro-teaching, case-based quizzes (deidentified and educationally reviewed).
- **Career and grants**: link to `Career`, `Projects`, opportunities and application deadlines; distinguish verified open opportunities from ideas; do not send applications automatically.
- **Quality control**: source count, duplicates, untranscribed, inaudible sections, unverified findings, unsourced treatment details, missing follow-ups, ethics/IRB gating, last manual refresh.

**Interaction model:** An explicit `Review selected audio in ChatGPT` action should open a new ChatGPT/Work review task for that source (only if supported by Sites and the current connected app). A Sites button must never fake transcription, schedule paid calls, or mark a consultation as verified. No background pollers. Use the Sites database only as a cache of deidentified material if required; private Google Sheets is the durable record.

**Security:** workspace-private, authenticated access only; no anonymous publishing or indexable URLs. No patient names, CR numbers, unique health identifiers, phone numbers, exact addresses or raw clinical transcripts in shared/public Site tables, telemetry, browser URLs or export files. Protect raw audio in Drive. Minimize link exposure. Use clinician sign-off for case extraction and separate ethics approval for research publication.

## Daily on-demand ChatGPT review prompt

> Review the current VoiceGrowth `VG Inbox` and existing clinical-academic registry. Identify new distinct audio files, duplicates and not-started items. If an authorized audio transcription capability is available for the selected private file, produce a faithful timed source transcript with uncertain words flagged, then a separate English translation if necessary. Do not claim transcription without actually processing the audio. Extract **only** clinically supported deidentified consultation facts and dated events, ask for my verification of critical diagnoses/drugs/cultures before labeling them verified, and update the corresponding registry tabs. From verified cases, suggest up to three high-value learning points, research hypotheses and teaching assets; relate relevant grant/fellowship opportunities to ongoing projects only after independently verifying application terms and deadlines. Give a concise progress table, outstanding clinical follow-ups, and data-quality/ethics warnings. Do not schedule, deploy or submit anything without a specific request.

## Research-ready extraction constraints

- No use of unvalidated speech-to-text as ground-truth.
- Store verified source provenance and codebook, retain encounter/case unit-of-analysis separately from recording unit-of-analysis.
- Distinguish cross-sectional case series from longitudinal event rows; dedup patients without exposing identity.
- Specify inclusion criteria, observation window, denominator, repeat encounters, missingness and censoring before calculating rates.
- Check local ethics/governance and data-use approvals before any proposal or submission. Deidentified dashboard entries are a research *staging* resource, not an approved dataset.
- Keep all data only in authorized private Drive, ChatGPT and institutional systems.

## Deployment boundary

This repository cannot directly publish a ChatGPT Site. Sites is a product interface entered through ChatGPT Work, not a GitHub Actions deploy target. On-demand ChatGPT review is initiated by the user in ChatGPT/Work; there is no unattended reviewer running in the GitHub repo. Native Google Drive metadata reads alone **do not transcribe audio**. A successful APK build likewise does not prove real-device Drive propagation.
