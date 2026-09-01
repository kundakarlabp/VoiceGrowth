# VoiceGrowth Clinical v0.2

VoiceGrowth Clinical is the server-side processing, longitudinal consult-data and review layer for the VoiceGrowth Android recorder.

## Boundary

- **Android:** capture reliable source audio, archive it to private Google Drive, and later submit a processing copy to this backend.
- **Backend:** preserve a job record, transcribe, diarize, medically normalize, extract a structured consult record with provenance, link patients/admissions and store follow-up outcomes.
- **Dashboard:** restricted operational overview for recent consults and verification.
- **MCP/ChatGPT:** read-only tools for case retrieval, patient timelines, follow-up, learning-topic detection and candidate research cohorts.

The backend does **not** de-identify patient names or CR numbers. It must therefore be deployed only to an access-controlled environment appropriate for identifiable clinical data.

## Processing pipeline

```text
Original audio (.m4a etc.)
  -> durable recording/job row
  -> ffmpeg 16 kHz mono / 15-minute processing chunks
  -> GPT-Transcribe high-accuracy lexical transcript
  -> GPT-4o Transcribe Diarize speaker/timestamp segments
  -> GPT-5.6 Sol conservative medical transcript correction
  -> GPT-5.6 Sol structured extraction with provenance
  -> consult + transcript-segment database records
  -> CR-number patient linkage / admission linkage
  -> verification + follow-up/outcome updates
  -> API / Excel / private dashboard / MCP tools
```

The original Google Drive audio remains the archival source. The backend processing copy should use persistent encrypted storage appropriate for the deployment environment.

## Required environment variables

```text
OPENAI_API_KEY=<secret>
VOICEGROWTH_API_TOKEN=<long random secret shared only with the Android app/backend clients>
DATABASE_URL=<PostgreSQL connection URL; SQLite /data/voicegrowth.db is development fallback>
AUDIO_DIR=/data/audio
```

Optional:

```text
OPENAI_TRANSCRIBE_MODEL=gpt-transcribe
OPENAI_DIARIZE_MODEL=gpt-4o-transcribe-diarize
OPENAI_REASONING_MODEL=gpt-5.6-sol
AUDIO_CHUNK_SECONDS=900
MAX_UPLOAD_MB=250
CORS_ORIGINS=https://your-private-dashboard.example
DASHBOARD_USERNAME=<private username>
DASHBOARD_PASSWORD=<strong separate password>
```

Never commit API keys, dashboard passwords, patient audio or exported clinical datasets to GitHub.

## Data model

Core tables:

- `recordings` — original processing job and transcript state.
- `transcript_segments` — speaker/timestamp evidence.
- `consults` — extracted consult and canonical structured JSON.
- `patients` — CR-number keyed longitudinal patient identity.
- `encounters` — admission episode when date of admission is available.
- `consult_links` — consult-to-patient/admission linkage.
- `outcome_updates` — append-only follow-up/outcome observations.

### Linking policy

CR number is the primary operational patient key. Patient records are **not** merged by fuzzy name matching. An encounter is created only when an admission date is available; this avoids silently combining separate admissions.

## API

All `/v1/*` and `/v2/*` clinical API endpoints require:

```text
Authorization: Bearer <VOICEGROWTH_API_TOKEN>
```

### Processing / consult endpoints

- `POST /v1/recordings` — multipart audio submission; idempotent by `client_recording_id`.
- `GET /v1/recordings/{job_id}` — processing state and resulting consult ID.
- `POST /v1/recordings/{job_id}/retry` — retry a failed persisted job.
- `GET /v1/consults` — recent structured consults.
- `GET /v1/consults/search?q=...` — search name, CR number, department, diagnosis and summary.
- `GET /v1/consults/{id}` — full structured record + source/corrected transcripts.
- `PATCH /v1/consults/{id}/verification` — mark AI-extracted/reviewed/verified/needs-correction.
- `GET /v1/exports/consults.xlsx` — flat Excel review/export view.

### Longitudinal / research endpoints

- `GET /v2/patients/search?q=...`
- `GET /v2/patients/{patient_id}/timeline`
- `POST /v2/consults/{consult_id}/outcomes`
- `GET /v2/consults/{consult_id}/outcomes`
- `GET /v2/followups/pending`
- `GET /v2/learning/topics`
- `GET /v2/research/cohort`

`/v2/research/cohort` defaults to verified consults only. It is a cohort-discovery interface, **not** an ethics-approved research dataset by itself.

## Private dashboard

The backend container serves `clinical_app:app`. When `DASHBOARD_USERNAME` and `DASHBOARD_PASSWORD` are configured, `/dashboard` provides a basic-auth protected operational table with search and counts.

The dashboard contains identifiers. Do not expose it publicly without access controls, TLS and appropriate institutional safeguards.

## MCP / ChatGPT tools

`mcp_server.py` is a **tool-only** MCP server following the current MCP Streamable HTTP model. It currently exposes read-only tools:

- `search_consults`
- `get_consult`
- `get_patient_timeline`
- `pending_followups`
- `learning_topics`
- `research_cohort`

For local development:

```bash
cd backend
MCP_HOST=127.0.0.1 python mcp_server.py
```

The endpoint is normally `http://127.0.0.1:8001/mcp`.

The MCP process intentionally refuses non-local binding unless `MCP_ALLOW_INSECURE_REMOTE=true`. **Do not use that override for patient data.** A remote ChatGPT connection must first add a supported authorization boundary (for example OAuth/mTLS/authenticated proxy) and HTTPS. Tool annotations do not replace authorization.

## Data integrity rules

The extraction prompt enforces:

- no de-identification;
- no invented values/units/dates/doses;
- questions are not automatically facts;
- primary-team diagnosis is distinct from ID working diagnosis and differential diagnoses;
- prior treatment is distinct from ID recommendations;
- material facts carry speaker/timestamp/source-text provenance where available;
- missing data remain explicitly missing;
- outcomes are empty unless actually stated.

`verification_status` defaults to `ai_extracted`. Research analyses should preferentially use records marked `verified`.

Outcome updates are append-only observations so later changes do not erase what was known at an earlier time.

## Local backend run

```bash
cd backend
docker build -t voicegrowth-clinical .
docker run --rm -p 8080:8080 \
  -e OPENAI_API_KEY=... \
  -e VOICEGROWTH_API_TOKEN=... \
  -e DASHBOARD_USERNAME=... \
  -e DASHBOARD_PASSWORD=... \
  -v "$PWD/data:/data" \
  voicegrowth-clinical
```

Then open `http://localhost:8080/health` or the authenticated `/dashboard`.
