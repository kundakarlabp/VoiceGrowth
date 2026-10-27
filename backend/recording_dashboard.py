"""Authenticated recording/transcript review routes.

This is a backend database view, NOT proof of the remote Drive inventory.
Audio transcription and clinical fact extraction are separate, opt-in stages.
No OpenAI calls, cloud writes, recording deletions or transcription happen here.
"""
from __future__ import annotations

import html
import re
import uuid
from datetime import datetime, timezone
from typing import Literal

from fastapi import Depends, HTTPException, Query
from fastapi.responses import HTMLResponse
from pydantic import BaseModel, Field
from sqlalchemy.orm import Mapped, mapped_column
from sqlalchemy import Boolean, DateTime, ForeignKey, Integer, String, select

from app import Base, Recording, SessionLocal, app, engine, require_token
from clinical_app import require_dashboard

DRIVE_ID_PATTERN = re.compile(r"^[A-Za-z0-9_-]{10,200}$")

class TranscriptProvenance(Base):
    """Persist caller assertions separately from independently verified source data."""
    __tablename__ = "transcript_provenance"

    recording_id: Mapped[int] = mapped_column(
        ForeignKey("recordings.id", ondelete="CASCADE"), primary_key=True
    )
    drive_file_id: Mapped[str] = mapped_column(String(200))
    asserted_source_sha256: Mapped[str | None] = mapped_column(String(64), nullable=True)
    asserted_source_bytes: Mapped[int | None] = mapped_column(Integer, nullable=True)
    method: Mapped[str] = mapped_column(String(50))
    source_verified_against_drive: Mapped[bool] = mapped_column(Boolean, default=False)
    imported_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=lambda: datetime.now(timezone.utc)
    )


Base.metadata.create_all(engine, tables=[TranscriptProvenance.__table__])



class TranscriptImport(BaseModel):
    drive_file_id: str = Field(min_length=10, max_length=200)
    source_filename: str = Field(min_length=1, max_length=255)
    transcript_text: str = Field(min_length=1, max_length=500000)
    method: Literal["clinician_reviewed", "chatgpt_user_reviewed"]
    recorded_at: datetime | None = None
    source_bytes: int | None = Field(default=None, gt=0)
    source_sha256: str | None = Field(default=None, pattern=r"^[a-fA-F0-9]{64}$")


def recording_dict(row: Recording, include_transcript: bool = False) -> dict:
    result = {
        "id": row.id,
        "drive_file_id": row.drive_audio_file_id,
        "archive_filename": row.original_filename,
        "recorded_at": row.recorded_at.isoformat() if row.recorded_at else None,
        "status": row.status,
        "has_transcript": bool(row.source_transcript and row.source_transcript.strip()),
        "has_structured_consult": row.consult is not None,
        "source_method": row.source,
        "processed_at": row.processed_at.isoformat() if row.processed_at else None,
    }
    if include_transcript:
        result["source_transcript"] = row.source_transcript
        result["corrected_transcript"] = row.corrected_transcript
        result["consult_id"] = row.consult.id if row.consult else None
    return result


@app.get("/v2/recordings/inventory", dependencies=[Depends(require_token)])
def recording_inventory(
    limit: int = Query(200, ge=1, le=500),
    offset: int = Query(0, ge=0),
) -> dict:
    """Locally persisted backend evidence, never advertised as cloud reconciliation."""
    with SessionLocal() as db:
        records = db.scalars(select(Recording).order_by(Recording.id.desc())
                             .offset(offset).limit(limit)).all()
        return {
            "source": "backend_database_only",
            "drive_cloud_inventory_verified": False,
            "recordings": [recording_dict(x) for x in records],
        }


@app.get("/v2/recordings/detail/{recording_id}", dependencies=[Depends(require_token)])
def recording_detail(recording_id: int) -> dict:
    with SessionLocal() as db:
        row = db.get(Recording, recording_id)
        if row is None:
            raise HTTPException(status_code=404, detail="Recording not found")
        return recording_dict(row, include_transcript=True)


@app.post("/v2/recordings/transcripts/import", dependencies=[Depends(require_token)], status_code=201)
def import_reviewed_transcript(payload: TranscriptImport) -> dict:
    """Explicit authenticated manual handoff from a reviewed transcript.

    Source metadata is CALLER-ASSERTED, NOT independently checked against Drive.
    Never mark as clinical consult or auto-run a paid extraction/model pipeline.
    """
    file_id = payload.drive_file_id.strip()
    if not DRIVE_ID_PATTERN.fullmatch(file_id):
        raise HTTPException(status_code=422, detail="Invalid Drive file ID")
    source = payload.transcript_text.strip()
    if not source:
        raise HTTPException(status_code=422, detail="Empty transcript")
    with SessionLocal() as db:
        row = db.scalar(select(Recording)
                        .where(Recording.drive_audio_file_id == file_id).limit(1))
        if row is not None:
            if row.source_transcript and row.source_transcript.strip():
                if row.source_transcript.strip() != source:
                    raise HTTPException(
                        status_code=409,
                        detail="A different transcript already exists for this Drive ID; explicit review is required"
                    )
                return {
                    "recording_id": row.id, "duplicate": True,
                    "status": row.status, "source_verified_against_drive": False
                }
            row.source_transcript = source
            row.status = "transcript_review_pending" if row.consult is None else row.status
            row.processed_at = datetime.now(timezone.utc)
        else:
            row = Recording(
                job_id=str(uuid.uuid5(uuid.NAMESPACE_URL, f"voicegrowth-drive:{file_id}")),
                client_recording_id=f"drive:{file_id}",
                original_filename=payload.source_filename,
                local_path="",
                source=payload.method,
                recorded_at=payload.recorded_at,
                drive_audio_file_id=file_id,
                status="transcript_review_pending",
                source_transcript=source,
                processed_at=datetime.now(timezone.utc),
            )
            db.add(row)
        db.flush()
        provenance = db.get(TranscriptProvenance, row.id)
        if provenance is None:
            db.add(TranscriptProvenance(
                recording_id=row.id,
                drive_file_id=file_id,
                asserted_source_sha256=payload.source_sha256.lower() if payload.source_sha256 else None,
                asserted_source_bytes=payload.source_bytes,
                method=payload.method,
                source_verified_against_drive=False,
            ))
        db.commit()
        db.refresh(row)
        return {
            "recording_id": row.id,
            "duplicate": False,
            "status": row.status,
            "source_verified_against_drive": False,
            "consult_created": row.consult is not None,
        }


def _html_response(document: str) -> HTMLResponse:
    return HTMLResponse(document, headers={
        "Cache-Control": "no-store, max-age=0",
        "Pragma": "no-cache",
        "Referrer-Policy": "no-referrer",
        "X-Content-Type-Options": "nosniff",
    })


def _page(title: str, body: str) -> HTMLResponse:
    return _html_response(f"""<!doctype html><html lang="en"><head>
<meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>{html.escape(title)}</title>
<style>
body{{font-family:system-ui,sans-serif;max-width:1180px;margin:24px auto;padding:0 16px;color:#18202c}}
nav a{{margin-right:18px}}table{{border-collapse:collapse;width:100%}}
td,th{{border-bottom:1px solid #ddd;padding:10px 6px;text-align:left;vertical-align:top;font-size:13px}}
th{{background:#f4f4f4}}.wrap{{overflow-x:auto}}input,button{{padding:9px}}
pre{{white-space:pre-wrap;overflow-wrap:anywhere;background:#f6f6f6;padding:16px;border:1px solid #ddd}}
small{{color:#606a77}}.stats{{display:flex;gap:24px;flex-wrap:wrap;margin:12px 0}}
</style></head><body><nav><a href="/dashboard">Clinical dashboard</a>
<a href="/dashboard/recordings">Recordings and transcripts</a></nav>
<h1>{html.escape(title)}</h1>{body}</body></html>""")


@app.get("/dashboard/recordings", response_class=HTMLResponse)
def recording_dashboard(_: str = Depends(require_dashboard), q: str = "") -> HTMLResponse:
    """Protected index, including unstructured transcripts awaiting discussion."""
    search = q.strip()[:120]
    with SessionLocal() as db:
        query = select(Recording).order_by(Recording.id.desc()).limit(200)
        if search:
            query = query.where(Recording.original_filename.ilike(f"%{search}%"))
        records = db.scalars(query).all()
        counts = {
            "backend_recordings": len(records),
            "transcripts_available": sum(bool(x.source_transcript and x.source_transcript.strip()) for x in records),
            "structured_consults": sum(x.consult is not None for x in records),
            "awaiting_transcript": sum(not x.source_transcript for x in records),
        }
        rows = "".join(
            "<tr>"
            f'<td><a href="/dashboard/recordings/{r.id}">{r.id}</a></td>'
            f"<td>{html.escape(r.original_filename)}</td>"
            f"<td>{html.escape(r.recorded_at.isoformat() if r.recorded_at else 'Unknown')}</td>"
            f"<td>{html.escape(r.status)}</td>"
            f"<td>{'Yes' if r.source_transcript and r.source_transcript.strip() else 'No'}</td>"
            f"<td>{'Yes' if r.consult else 'No'}</td>"
            "</tr>" for r in records
        )
    stats = " ".join(f"<span><b>{value}</b> {html.escape(key.replace('_',' '))}</span>"
                     for key, value in counts.items())
    return _page("Recordings and transcripts", f"""
<p><small>Backend database evidence only. A saved Drive recording is not
proof of transcription, and a transcript is not a verified clinical consult.</small></p>
<div class="stats">{stats}</div>
<form method="get"><input name="q" value="{html.escape(search, quote=True)}"
placeholder="Search archive filename"><button>Search</button></form>
<div class="wrap"><table><thead><tr><th>ID</th><th>File</th><th>Recorded</th>
<th>Backend status</th><th>Transcript present</th><th>Consult extracted</th>
</tr></thead><tbody>{rows}</tbody></table></div>
<p><small>Shows at most 200 recent backend records. No automatic transcription charges.</small></p>""")


@app.get("/dashboard/recordings/{recording_id}", response_class=HTMLResponse)
def recording_review(recording_id: int, _: str = Depends(require_dashboard)) -> HTMLResponse:
    with SessionLocal() as db:
        rec = db.get(Recording, recording_id)
        if rec is None:
            raise HTTPException(status_code=404, detail="Recording not found")
        body = f"""
<p><b>Archive:</b> {html.escape(rec.original_filename)}<br>
<b>Source:</b> {html.escape(rec.source)}<br>
<b>Status:</b> {html.escape(rec.status)}<br>
<b>Drive file ID:</b> {html.escape(rec.drive_audio_file_id or 'Not available')}<br>
<b>Structured consultation:</b> {'Available' if rec.consult else 'Not yet created'}</p>
<p><small>Machine-generated and manually imported transcripts require clinician review
against the source audio. Do not regard this page as clinical verification.</small></p>
<h2>Source transcript</h2>
<pre>{html.escape(rec.source_transcript or 'No transcript has been imported or generated.')}</pre>
"""
        if rec.corrected_transcript:
            body += f"<h2>Corrected transcript</h2><pre>{html.escape(rec.corrected_transcript)}</pre>"
    return _page("Recording review", body)
