from __future__ import annotations

import asyncio
import json
import logging
import os
import shutil
import subprocess
import tempfile
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from fastapi import Depends, FastAPI, File, Form, Header, HTTPException, Query, UploadFile
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse
from openai import OpenAI
from openpyxl import Workbook
from pydantic import BaseModel, Field, ValidationError
from sqlalchemy import DateTime, Float, ForeignKey, Integer, JSON, String, Text, create_engine, func, or_, select
from sqlalchemy.orm import DeclarativeBase, Mapped, Session, mapped_column, relationship, sessionmaker

logging.basicConfig(level=os.getenv("LOG_LEVEL", "INFO"))
log = logging.getLogger("voicegrowth-clinical")

APP_VERSION = "0.1.0"
AUDIO_DIR = Path(os.getenv("AUDIO_DIR", "/data/audio"))
AUDIO_DIR.mkdir(parents=True, exist_ok=True)
API_TOKEN = os.getenv("VOICEGROWTH_API_TOKEN", "").strip()
OPENAI_API_KEY = os.getenv("OPENAI_API_KEY", "").strip()
TRANSCRIBE_MODEL = os.getenv("OPENAI_TRANSCRIBE_MODEL", "gpt-transcribe")
DIARIZE_MODEL = os.getenv("OPENAI_DIARIZE_MODEL", "gpt-4o-transcribe-diarize")
REASONING_MODEL = os.getenv("OPENAI_REASONING_MODEL", "gpt-5.6-sol")
CHUNK_SECONDS = int(os.getenv("AUDIO_CHUNK_SECONDS", "900"))
MAX_UPLOAD_MB = int(os.getenv("MAX_UPLOAD_MB", "250"))

raw_database_url = os.getenv("DATABASE_URL", "sqlite:////data/voicegrowth.db")
if raw_database_url.startswith("postgres://"):
    raw_database_url = raw_database_url.replace("postgres://", "postgresql+psycopg://", 1)
elif raw_database_url.startswith("postgresql://"):
    raw_database_url = raw_database_url.replace("postgresql://", "postgresql+psycopg://", 1)

engine = create_engine(raw_database_url, pool_pre_ping=True)
SessionLocal = sessionmaker(bind=engine, expire_on_commit=False)


class Base(DeclarativeBase):
    pass


class Recording(Base):
    __tablename__ = "recordings"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    job_id: Mapped[str] = mapped_column(String(36), unique=True, index=True)
    client_recording_id: Mapped[str | None] = mapped_column(String(100), unique=True, nullable=True, index=True)
    original_filename: Mapped[str] = mapped_column(String(255))
    local_path: Mapped[str] = mapped_column(Text)
    source: Mapped[str] = mapped_column(String(50), default="manual_discussion")
    recorded_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True, index=True)
    duration_seconds: Mapped[int | None] = mapped_column(Integer, nullable=True)
    drive_audio_file_id: Mapped[str | None] = mapped_column(Text, nullable=True)
    drive_web_view_link: Mapped[str | None] = mapped_column(Text, nullable=True)
    status: Mapped[str] = mapped_column(String(30), default="queued", index=True)
    error_message: Mapped[str | None] = mapped_column(Text, nullable=True)
    source_transcript: Mapped[str | None] = mapped_column(Text, nullable=True)
    corrected_transcript: Mapped[str | None] = mapped_column(Text, nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc))
    processed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)

    consult: Mapped["Consult | None"] = relationship(back_populates="recording", uselist=False, cascade="all, delete-orphan")
    segments: Mapped[list["TranscriptSegment"]] = relationship(back_populates="recording", cascade="all, delete-orphan")


class TranscriptSegment(Base):
    __tablename__ = "transcript_segments"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    recording_id: Mapped[int] = mapped_column(ForeignKey("recordings.id", ondelete="CASCADE"), index=True)
    speaker: Mapped[str] = mapped_column(String(80), default="Unknown")
    start_seconds: Mapped[float] = mapped_column(Float, default=0.0)
    end_seconds: Mapped[float] = mapped_column(Float, default=0.0)
    text: Mapped[str] = mapped_column(Text)
    confidence: Mapped[float | None] = mapped_column(Float, nullable=True)

    recording: Mapped[Recording] = relationship(back_populates="segments")


class Consult(Base):
    __tablename__ = "consults"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    recording_id: Mapped[int] = mapped_column(ForeignKey("recordings.id", ondelete="CASCADE"), unique=True, index=True)
    patient_name: Mapped[str | None] = mapped_column(String(255), nullable=True, index=True)
    cr_number: Mapped[str | None] = mapped_column(String(120), nullable=True, index=True)
    age: Mapped[str | None] = mapped_column(String(80), nullable=True)
    sex: Mapped[str | None] = mapped_column(String(40), nullable=True)
    date_of_admission: Mapped[str | None] = mapped_column(String(80), nullable=True)
    admitting_department: Mapped[str | None] = mapped_column(String(255), nullable=True, index=True)
    ward_unit: Mapped[str | None] = mapped_column(String(255), nullable=True)
    consult_date: Mapped[str | None] = mapped_column(String(80), nullable=True, index=True)
    consult_for: Mapped[str | None] = mapped_column(Text, nullable=True)
    primary_team_diagnosis: Mapped[str | None] = mapped_column(Text, nullable=True)
    id_working_diagnosis: Mapped[str | None] = mapped_column(Text, nullable=True, index=True)
    consult_summary: Mapped[str | None] = mapped_column(Text, nullable=True)
    structured_record: Mapped[dict[str, Any]] = mapped_column(JSON)
    verification_status: Mapped[str] = mapped_column(String(30), default="ai_extracted", index=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc))
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc))

    recording: Mapped[Recording] = relationship(back_populates="consult")


class EvidenceRef(BaseModel):
    speaker: str | None = None
    timestamp: str | None = None
    source_text: str | None = None
    confidence: float | None = Field(default=None, ge=0, le=1)


class Fact(BaseModel):
    value: str | None = None
    status: str = "not_stated"
    evidence: list[EvidenceRef] = Field(default_factory=list)


class Investigation(BaseModel):
    category: str
    name: str
    value: str | None = None
    unit: str | None = None
    date_or_timing: str | None = None
    interpretation: str | None = None
    evidence: list[EvidenceRef] = Field(default_factory=list)


class Medication(BaseModel):
    drug: str
    dose: str | None = None
    route: str | None = None
    frequency: str | None = None
    start_or_timing: str | None = None
    stop_or_timing: str | None = None
    indication: str | None = None
    status: str | None = None
    evidence: list[EvidenceRef] = Field(default_factory=list)


class Recommendation(BaseModel):
    category: str
    action: str
    target: str | None = None
    details: str | None = None
    rationale_stated: str | None = None
    evidence: list[EvidenceRef] = Field(default_factory=list)


class ConsultRecord(BaseModel):
    patient_name: Fact = Field(default_factory=Fact)
    cr_number: Fact = Field(default_factory=Fact)
    age: Fact = Field(default_factory=Fact)
    sex: Fact = Field(default_factory=Fact)
    date_of_admission: Fact = Field(default_factory=Fact)
    admitting_department: Fact = Field(default_factory=Fact)
    ward_unit: Fact = Field(default_factory=Fact)
    consult_date: Fact = Field(default_factory=Fact)
    consult_for: Fact = Field(default_factory=Fact)
    primary_team_diagnosis: Fact = Field(default_factory=Fact)
    id_working_diagnosis: Fact = Field(default_factory=Fact)
    diagnostic_certainty: Fact = Field(default_factory=Fact)
    clinical_syndromes: list[Fact] = Field(default_factory=list)
    differential_diagnoses: list[Fact] = Field(default_factory=list)
    comorbidities: list[Fact] = Field(default_factory=list)
    immunosuppression_and_host_factors: list[Fact] = Field(default_factory=list)
    relevant_history: list[Fact] = Field(default_factory=list)
    investigations: list[Investigation] = Field(default_factory=list)
    microbiology: list[Investigation] = Field(default_factory=list)
    antimicrobials_and_key_medications: list[Medication] = Field(default_factory=list)
    recommendations: list[Recommendation] = Field(default_factory=list)
    pending_items: list[Fact] = Field(default_factory=list)
    follow_up_plan: list[Fact] = Field(default_factory=list)
    outcomes: list[Fact] = Field(default_factory=list)
    consult_summary: str = ""
    uncertainties: list[str] = Field(default_factory=list)
    learning_topics: list[str] = Field(default_factory=list)
    research_tags: list[str] = Field(default_factory=list)


Base.metadata.create_all(engine)

TRANSCRIPTION_CONTEXT = """Clinical consultation at an Indian tertiary-care hospital. The primary speaker is an Infectious Diseases consultant and the second speaker is commonly a General Medicine senior resident. Speech may contain Indian English, Telugu-English or Hindi-English code switching, abbreviations and rapid clinical shorthand. Preserve patient names and CR numbers when spoken; do NOT de-identify. Likely terminology includes CMV, HSV, VZV, EBV, HHV-6, PJP/PCP, BAL, CSF, BioFire, MMF, IVIG, ATG, tacrolimus, valganciclovir, ganciclovir, CRE, CRAB, MRSA, D+/R+, SLED, CRRT, HFNC, DWI, ADC, FLAIR, NCSE, ADEM, PRES, HLH, carbapenem, colistin, cefoperazone-sulbactam, voriconazole, isavuconazole, amphotericin, meropenem and medical doses/numbers. Do not convert an unstated unit into a stated unit."""

CORRECTION_INSTRUCTIONS = """You are correcting a medical transcript, not writing a case summary. Preserve speaker labels and timestamps. Correct obvious ASR errors in drug names, organisms, abbreviations and medical terminology using context, but never invent a value, unit, diagnosis, date, dose or recommendation. Preserve uncertainty. Preserve patient name and CR number exactly when available. Do not de-identify. If a phrase cannot be safely corrected, keep it and mark [unclear]. Return only the corrected transcript."""

EXTRACTION_INSTRUCTIONS = """Extract a longitudinal Infectious Diseases consultation record from the supplied corrected transcript. This is publication-grade source extraction, not autonomous clinical advice.

Rules:
1. Use only information stated or clearly attributable in the transcript. Never invent missing demographics, units, dates, diagnoses, doses, outcomes or recommendations.
2. Do not de-identify: retain patient name and CR number if spoken.
3. Separate the primary team's diagnosis from the ID consultant's working diagnosis and from differential diagnoses.
4. Separate medications already being given from recommendations to start/stop/change therapy.
5. For every clinically material extracted fact, attach the best available speaker/timestamp/source_text evidence. Keep source_text short and verbatim.
6. A question asked during history-taking is not itself a confirmed fact. A differential discussed is not a final diagnosis. An AI inference is not a spoken fact.
7. For missing fields, set Fact.value to null and status to not_stated. Use uncertainty strings for conflicts or ambiguous audio.
8. Capture negative findings when they materially affect reasoning.
9. Capture pending tests, follow-up plans, source-control advice, antimicrobial decisions, monitoring and immunosuppression changes.
10. Outcomes should be empty unless an outcome is actually discussed in this recording.
11. learning_topics and research_tags may categorize the case but must not add new patient facts.
12. consult_summary should be a concise structured narrative of the case as discussed, including why ID was consulted, relevant host factors, syndrome, key evidence, working diagnosis and recommendations."""


def require_token(authorization: str | None = Header(default=None)) -> None:
    if not API_TOKEN:
        raise HTTPException(status_code=503, detail="VOICEGROWTH_API_TOKEN is not configured")
    if authorization != f"Bearer {API_TOKEN}":
        raise HTTPException(status_code=401, detail="Invalid API token")


def openai_client() -> OpenAI:
    if not OPENAI_API_KEY:
        raise RuntimeError("OPENAI_API_KEY is not configured")
    return OpenAI(api_key=OPENAI_API_KEY, timeout=180.0, max_retries=3)


def fact_value(record: ConsultRecord, field: str) -> str | None:
    fact = getattr(record, field)
    return fact.value.strip() if fact.value and fact.value.strip() else None


def normalize_and_chunk(source: Path, workdir: Path) -> list[Path]:
    pattern = workdir / "chunk_%03d.mp3"
    cmd = [
        "ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-i", str(source),
        "-vn", "-ac", "1", "-ar", "16000", "-b:a", "64k",
        "-f", "segment", "-segment_time", str(CHUNK_SECONDS), "-reset_timestamps", "1", str(pattern),
    ]
    subprocess.run(cmd, check=True, timeout=600)
    chunks = sorted(workdir.glob("chunk_*.mp3"))
    if not chunks:
        raise RuntimeError("ffmpeg did not produce transcription chunks")
    return chunks


def as_dict(obj: Any) -> dict[str, Any]:
    if hasattr(obj, "model_dump"):
        return obj.model_dump()
    if isinstance(obj, dict):
        return obj
    return json.loads(json.dumps(obj, default=lambda x: getattr(x, "__dict__", str(x))))


def transcribe_chunks(chunks: list[Path]) -> tuple[str, list[dict[str, Any]]]:
    client = openai_client()
    all_segments: list[dict[str, Any]] = []
    lexical_parts: list[str] = []

    for idx, chunk in enumerate(chunks):
        offset = idx * CHUNK_SECONDS
        with chunk.open("rb") as f:
            lexical = client.audio.transcriptions.create(
                model=TRANSCRIBE_MODEL,
                file=f,
                prompt=TRANSCRIPTION_CONTEXT,
            )
        lexical_text = getattr(lexical, "text", None) or as_dict(lexical).get("text", "")
        lexical_parts.append(lexical_text.strip())

        diarized_segments: list[dict[str, Any]] = []
        try:
            with chunk.open("rb") as f:
                diarized = client.audio.transcriptions.create(
                    model=DIARIZE_MODEL,
                    file=f,
                    response_format="diarized_json",
                )
            payload = as_dict(diarized)
            diarized_segments = payload.get("segments") or payload.get("diarization") or []
        except Exception as exc:
            log.warning("Diarization failed for chunk %s: %s", idx, exc)

        if diarized_segments:
            for seg in diarized_segments:
                start = float(seg.get("start", seg.get("start_time", 0.0))) + offset
                end = float(seg.get("end", seg.get("end_time", start))) + offset
                all_segments.append({
                    "speaker": str(seg.get("speaker", "Unknown")),
                    "start": start,
                    "end": end,
                    "text": str(seg.get("text", "")).strip(),
                    "confidence": seg.get("confidence"),
                })
        else:
            all_segments.append({
                "speaker": "Unknown",
                "start": float(offset),
                "end": float(offset + CHUNK_SECONDS),
                "text": lexical_text.strip(),
                "confidence": None,
            })

    source_lines = []
    for seg in all_segments:
        start = int(seg["start"])
        hh, rem = divmod(start, 3600)
        mm, ss = divmod(rem, 60)
        stamp = f"{hh:02d}:{mm:02d}:{ss:02d}"
        source_lines.append(f"[{stamp}] {seg['speaker']}: {seg['text']}")

    # The diarized text is the provenance-bearing source. Lexical transcript is appended only when
    # diarization failed to capture useful text.
    source_transcript = "\n".join(source_lines).strip()
    if not source_transcript or all(s["speaker"] == "Unknown" for s in all_segments):
        source_transcript = "\n\n".join(p for p in lexical_parts if p).strip()
    return source_transcript, all_segments


def correct_transcript(source_transcript: str) -> str:
    client = openai_client()
    response = client.responses.create(
        model=REASONING_MODEL,
        instructions=CORRECTION_INSTRUCTIONS,
        input=source_transcript,
        store=False,
    )
    return response.output_text.strip()


def extract_consult(corrected_transcript: str) -> ConsultRecord:
    client = openai_client()
    schema = ConsultRecord.model_json_schema()
    response = client.responses.create(
        model=REASONING_MODEL,
        instructions=EXTRACTION_INSTRUCTIONS,
        input=corrected_transcript,
        text={
            "format": {
                "type": "json_schema",
                "name": "voicegrowth_consult_record",
                "schema": schema,
                "strict": False,
            }
        },
        store=False,
    )
    raw = json.loads(response.output_text)
    return ConsultRecord.model_validate(raw)


def process_recording(recording_id: int) -> None:
    with SessionLocal() as db:
        recording = db.get(Recording, recording_id)
        if not recording or recording.status == "completed":
            return
        recording.status = "processing"
        recording.error_message = None
        db.commit()
        source_path = Path(recording.local_path)

    try:
        if not source_path.exists():
            raise FileNotFoundError(f"Audio file missing: {source_path}")
        with tempfile.TemporaryDirectory(prefix="voicegrowth-") as temp_dir:
            chunks = normalize_and_chunk(source_path, Path(temp_dir))
            source_transcript, segments = transcribe_chunks(chunks)
            corrected = correct_transcript(source_transcript)
            consult_record = extract_consult(corrected)

        with SessionLocal() as db:
            recording = db.get(Recording, recording_id)
            if not recording:
                return
            recording.source_transcript = source_transcript
            recording.corrected_transcript = corrected
            recording.status = "completed"
            recording.processed_at = datetime.now(timezone.utc)

            db.query(TranscriptSegment).filter(TranscriptSegment.recording_id == recording.id).delete()
            for seg in segments:
                db.add(TranscriptSegment(
                    recording_id=recording.id,
                    speaker=seg["speaker"],
                    start_seconds=seg["start"],
                    end_seconds=seg["end"],
                    text=seg["text"],
                    confidence=seg.get("confidence"),
                ))

            existing = db.scalar(select(Consult).where(Consult.recording_id == recording.id))
            payload = consult_record.model_dump(mode="json")
            values = dict(
                patient_name=fact_value(consult_record, "patient_name"),
                cr_number=fact_value(consult_record, "cr_number"),
                age=fact_value(consult_record, "age"),
                sex=fact_value(consult_record, "sex"),
                date_of_admission=fact_value(consult_record, "date_of_admission"),
                admitting_department=fact_value(consult_record, "admitting_department"),
                ward_unit=fact_value(consult_record, "ward_unit"),
                consult_date=fact_value(consult_record, "consult_date"),
                consult_for=fact_value(consult_record, "consult_for"),
                primary_team_diagnosis=fact_value(consult_record, "primary_team_diagnosis"),
                id_working_diagnosis=fact_value(consult_record, "id_working_diagnosis"),
                consult_summary=consult_record.consult_summary,
                structured_record=payload,
                verification_status="ai_extracted",
                updated_at=datetime.now(timezone.utc),
            )
            if existing:
                for key, value in values.items():
                    setattr(existing, key, value)
            else:
                db.add(Consult(recording_id=recording.id, **values))
            db.commit()
            log.info("Processed recording %s -> consult", recording.job_id)
    except (ValidationError, Exception) as exc:
        log.exception("Processing failed for recording %s", recording_id)
        with SessionLocal() as db:
            rec = db.get(Recording, recording_id)
            if rec:
                rec.status = "failed"
                rec.error_message = str(exc)[:4000]
                db.commit()


def next_queued_recording() -> int | None:
    with SessionLocal() as db:
        rec = db.scalar(select(Recording).where(Recording.status == "queued").order_by(Recording.created_at).limit(1))
        return rec.id if rec else None


async def worker_loop() -> None:
    while True:
        try:
            recording_id = next_queued_recording()
            if recording_id is None:
                await asyncio.sleep(3)
                continue
            await asyncio.to_thread(process_recording, recording_id)
        except asyncio.CancelledError:
            raise
        except Exception:
            log.exception("Worker loop error")
            await asyncio.sleep(5)


app = FastAPI(title="VoiceGrowth Clinical", version=APP_VERSION)
app.add_middleware(
    CORSMiddleware,
    allow_origins=[x.strip() for x in os.getenv("CORS_ORIGINS", "").split(",") if x.strip()] or [],
    allow_credentials=True,
    allow_methods=["GET", "POST", "PATCH"],
    allow_headers=["Authorization", "Content-Type"],
)
worker_task: asyncio.Task | None = None


@app.on_event("startup")
async def startup() -> None:
    global worker_task
    # Any interrupted job is safe to retry because the source audio remains on disk/volume.
    with SessionLocal() as db:
        db.query(Recording).filter(Recording.status == "processing").update({Recording.status: "queued"})
        db.commit()
    worker_task = asyncio.create_task(worker_loop())


@app.on_event("shutdown")
async def shutdown() -> None:
    if worker_task:
        worker_task.cancel()


@app.get("/health")
def health() -> dict[str, Any]:
    with SessionLocal() as db:
        db.execute(select(1))
    return {
        "ok": True,
        "service": "voicegrowth-clinical",
        "version": APP_VERSION,
        "openai_configured": bool(OPENAI_API_KEY),
        "api_auth_configured": bool(API_TOKEN),
    }


@app.post("/v1/recordings", dependencies=[Depends(require_token)], status_code=202)
async def submit_recording(
    audio: UploadFile = File(...),
    client_recording_id: str | None = Form(default=None),
    source: str = Form(default="manual_discussion"),
    recorded_at: str | None = Form(default=None),
    duration_seconds: int | None = Form(default=None),
    drive_audio_file_id: str | None = Form(default=None),
    drive_web_view_link: str | None = Form(default=None),
) -> dict[str, Any]:
    if client_recording_id:
        with SessionLocal() as db:
            existing = db.scalar(select(Recording).where(Recording.client_recording_id == client_recording_id))
            if existing:
                return {"job_id": existing.job_id, "status": existing.status, "duplicate": True}

    suffix = Path(audio.filename or "recording.m4a").suffix.lower()
    if suffix not in {".m4a", ".mp4", ".mp3", ".wav", ".webm", ".ogg", ".flac", ".mpeg", ".mpga"}:
        raise HTTPException(status_code=415, detail="Unsupported audio format")

    job_id = str(uuid.uuid4())
    destination = AUDIO_DIR / f"{job_id}{suffix}"
    total = 0
    max_bytes = MAX_UPLOAD_MB * 1024 * 1024
    try:
        with destination.open("wb") as output:
            while chunk := await audio.read(1024 * 1024):
                total += len(chunk)
                if total > max_bytes:
                    raise HTTPException(status_code=413, detail=f"Audio exceeds {MAX_UPLOAD_MB} MB")
                output.write(chunk)
    except Exception:
        destination.unlink(missing_ok=True)
        raise

    parsed_recorded_at = None
    if recorded_at:
        try:
            parsed_recorded_at = datetime.fromisoformat(recorded_at.replace("Z", "+00:00"))
        except ValueError:
            destination.unlink(missing_ok=True)
            raise HTTPException(status_code=422, detail="recorded_at must be ISO-8601")

    with SessionLocal() as db:
        rec = Recording(
            job_id=job_id,
            client_recording_id=client_recording_id,
            original_filename=audio.filename or destination.name,
            local_path=str(destination),
            source=source,
            recorded_at=parsed_recorded_at,
            duration_seconds=duration_seconds,
            drive_audio_file_id=drive_audio_file_id,
            drive_web_view_link=drive_web_view_link,
            status="queued",
        )
        db.add(rec)
        db.commit()
    return {"job_id": job_id, "status": "queued", "duplicate": False}


@app.get("/v1/recordings/{job_id}", dependencies=[Depends(require_token)])
def get_recording(job_id: str) -> dict[str, Any]:
    with SessionLocal() as db:
        rec = db.scalar(select(Recording).where(Recording.job_id == job_id))
        if not rec:
            raise HTTPException(status_code=404, detail="Recording not found")
        consult_id = rec.consult.id if rec.consult else None
        return {
            "job_id": rec.job_id,
            "status": rec.status,
            "error": rec.error_message,
            "consult_id": consult_id,
            "processed_at": rec.processed_at,
        }


@app.post("/v1/recordings/{job_id}/retry", dependencies=[Depends(require_token)], status_code=202)
def retry_recording(job_id: str) -> dict[str, str]:
    with SessionLocal() as db:
        rec = db.scalar(select(Recording).where(Recording.job_id == job_id))
        if not rec:
            raise HTTPException(status_code=404, detail="Recording not found")
        if not Path(rec.local_path).exists():
            raise HTTPException(status_code=409, detail="Backend audio copy is unavailable; re-upload from VoiceGrowth/Drive archive")
        rec.status = "queued"
        rec.error_message = None
        db.commit()
        return {"job_id": rec.job_id, "status": "queued"}


def consult_to_dict(c: Consult, include_transcripts: bool = False) -> dict[str, Any]:
    result: dict[str, Any] = {
        "id": c.id,
        "recording_job_id": c.recording.job_id,
        "patient_name": c.patient_name,
        "cr_number": c.cr_number,
        "age": c.age,
        "sex": c.sex,
        "date_of_admission": c.date_of_admission,
        "admitting_department": c.admitting_department,
        "ward_unit": c.ward_unit,
        "consult_date": c.consult_date,
        "consult_for": c.consult_for,
        "primary_team_diagnosis": c.primary_team_diagnosis,
        "id_working_diagnosis": c.id_working_diagnosis,
        "consult_summary": c.consult_summary,
        "verification_status": c.verification_status,
        "structured_record": c.structured_record,
        "created_at": c.created_at,
        "updated_at": c.updated_at,
    }
    if include_transcripts:
        result["source_transcript"] = c.recording.source_transcript
        result["corrected_transcript"] = c.recording.corrected_transcript
    return result


@app.get("/v1/consults", dependencies=[Depends(require_token)])
def list_consults(limit: int = Query(50, ge=1, le=500), offset: int = Query(0, ge=0)) -> list[dict[str, Any]]:
    with SessionLocal() as db:
        rows = db.scalars(select(Consult).order_by(Consult.created_at.desc()).offset(offset).limit(limit)).all()
        return [consult_to_dict(c) for c in rows]


@app.get("/v1/consults/search", dependencies=[Depends(require_token)])
def search_consults(q: str = Query(min_length=1), limit: int = Query(50, ge=1, le=200)) -> list[dict[str, Any]]:
    like = f"%{q}%"
    with SessionLocal() as db:
        rows = db.scalars(
            select(Consult).where(or_(
                Consult.patient_name.ilike(like),
                Consult.cr_number.ilike(like),
                Consult.admitting_department.ilike(like),
                Consult.consult_for.ilike(like),
                Consult.primary_team_diagnosis.ilike(like),
                Consult.id_working_diagnosis.ilike(like),
                Consult.consult_summary.ilike(like),
            )).order_by(Consult.created_at.desc()).limit(limit)
        ).all()
        return [consult_to_dict(c) for c in rows]


@app.get("/v1/consults/{consult_id}", dependencies=[Depends(require_token)])
def get_consult(consult_id: int) -> dict[str, Any]:
    with SessionLocal() as db:
        c = db.get(Consult, consult_id)
        if not c:
            raise HTTPException(status_code=404, detail="Consult not found")
        return consult_to_dict(c, include_transcripts=True)


@app.patch("/v1/consults/{consult_id}/verification", dependencies=[Depends(require_token)])
def set_verification(consult_id: int, status: str = Form(...)) -> dict[str, Any]:
    allowed = {"ai_extracted", "reviewed", "verified", "needs_correction"}
    if status not in allowed:
        raise HTTPException(status_code=422, detail=f"status must be one of {sorted(allowed)}")
    with SessionLocal() as db:
        c = db.get(Consult, consult_id)
        if not c:
            raise HTTPException(status_code=404, detail="Consult not found")
        c.verification_status = status
        c.updated_at = datetime.now(timezone.utc)
        db.commit()
        return {"id": c.id, "verification_status": c.verification_status}


@app.get("/v1/dashboard/summary", dependencies=[Depends(require_token)])
def dashboard_summary() -> dict[str, Any]:
    with SessionLocal() as db:
        total = db.scalar(select(func.count(Consult.id))) or 0
        verified = db.scalar(select(func.count(Consult.id)).where(Consult.verification_status == "verified")) or 0
        pending = db.scalar(select(func.count(Recording.id)).where(Recording.status.in_(["queued", "processing"]))) or 0
        failed = db.scalar(select(func.count(Recording.id)).where(Recording.status == "failed")) or 0
        departments = db.execute(
            select(Consult.admitting_department, func.count(Consult.id))
            .where(Consult.admitting_department.is_not(None))
            .group_by(Consult.admitting_department)
            .order_by(func.count(Consult.id).desc())
            .limit(10)
        ).all()
        return {
            "total_consults": total,
            "verified_consults": verified,
            "processing_queue": pending,
            "failed_jobs": failed,
            "top_departments": [{"department": d, "count": n} for d, n in departments],
        }


@app.get("/v1/exports/consults.xlsx", dependencies=[Depends(require_token)])
def export_consults_xlsx() -> FileResponse:
    wb = Workbook()
    ws = wb.active
    ws.title = "Consults"
    headers = [
        "Consult ID", "CR Number", "Patient Name", "Age", "Sex", "Date of Admission",
        "Department", "Ward/Unit", "Consult Date", "Consult For", "Primary Team Diagnosis",
        "ID Working Diagnosis", "Consult Summary", "Verification Status", "Recording Job ID",
    ]
    ws.append(headers)
    with SessionLocal() as db:
        rows = db.scalars(select(Consult).order_by(Consult.created_at)).all()
        for c in rows:
            ws.append([
                c.id, c.cr_number, c.patient_name, c.age, c.sex, c.date_of_admission,
                c.admitting_department, c.ward_unit, c.consult_date, c.consult_for,
                c.primary_team_diagnosis, c.id_working_diagnosis, c.consult_summary,
                c.verification_status, c.recording.job_id,
            ])
    ws.freeze_panes = "A2"
    ws.auto_filter.ref = ws.dimensions
    fd, path = tempfile.mkstemp(prefix="voicegrowth-consults-", suffix=".xlsx")
    os.close(fd)
    wb.save(path)
    return FileResponse(path, filename="VoiceGrowth_Consults.xlsx", media_type="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
