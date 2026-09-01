# VoiceGrowth Clinical v0.1

VoiceGrowth Clinical is the server-side processing and longitudinal consult-data layer for the VoiceGrowth Android recorder.

## Boundary

- **Android:** capture reliable source audio, archive it to private Google Drive, and submit a processing copy to this backend.
- **Backend:** preserve a job record, transcribe, diarize, medically normalize, extract a structured consult record with provenance, and expose search/export APIs.
- **ChatGPT/dashboard:** consume the structured data for case review, evidence review, learning, research discovery and longitudinal analysis.

The backend does **not** de-identify patient names or CR numbers. It must therefore be deployed only to an access-controlled environment appropriate for identifiable clinical data.

## Processing pipeline

```text
Original audio (.m4a etc.)
  -> durable recording/job row
  -> ffmpeg 16 kHz mono / 15-minute processing chunks
  -> GPT-Transcribe high-accuracy lexical transcript
  -> GPT-4o Transcribe Diarize speaker/timestamp segments
  -> GPT-5.6 Sol conservative medical transcript correction
  -> GPT-5.6 Sol structured extraction
  -> consult + transcript-segment database records
  -> API / Excel / future dashboard / ChatGPT MCP
```

The original Google Drive audio remains the archival source. The backend processing copy should be stored on a persistent Railway volume mounted at `/data` for reliable retries.

## Required environment variables

```text
OPENAI_API_KEY=<secret>
VOICEGROWTH_API_TOKEN=<long random secret shared only with the Android app>
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
```

Never commit API keys or patient audio to GitHub.

## API

All `/v1/*` endpoints require:

```text
Authorization: Bearer <VOICEGROWTH_API_TOKEN>
```

Main endpoints:

- `POST /v1/recordings` — multipart audio submission; idempotent by `client_recording_id`.
- `GET /v1/recordings/{job_id}` — processing state and resulting consult ID.
- `POST /v1/recordings/{job_id}/retry` — retry a failed persisted job.
- `GET /v1/consults` — recent structured consults.
- `GET /v1/consults/search?q=...` — search name, CR number, department, diagnosis and summary.
- `GET /v1/consults/{id}` — full structured record + source/corrected transcripts.
- `PATCH /v1/consults/{id}/verification` — mark AI-extracted/reviewed/verified/needs-correction.
- `GET /v1/dashboard/summary` — basic dashboard counters.
- `GET /v1/exports/consults.xlsx` — publication/review-oriented Excel view.

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

`verification_status` defaults to `ai_extracted`. Research analyses should preferentially use records marked `verified` once a human-review workflow is available.

## Local run

```bash
docker build -t voicegrowth-clinical .
docker run --rm -p 8080:8080 \
  -e OPENAI_API_KEY=... \
  -e VOICEGROWTH_API_TOKEN=... \
  -v "$PWD/data:/data" \
  voicegrowth-clinical
```

Then open `http://localhost:8080/health`.
