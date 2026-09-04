from __future__ import annotations

import asyncio
import hashlib
import io
import json
import logging
import os
import re
import tempfile
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any

from google.oauth2 import service_account
from googleapiclient.discovery import build
from googleapiclient.http import MediaIoBaseDownload, MediaIoBaseUpload
from sqlalchemy import select

from app import (
    AUDIO_DIR,
    DIARIZE_MODEL,
    TRANSCRIBE_MODEL,
    Recording,
    SessionLocal,
    TranscriptSegment,
    app,
    normalize_and_chunk,
    transcribe_chunks,
)

log = logging.getLogger("voicegrowth-drive-transcription")

DRIVE_ROOT_FOLDER_ID = os.getenv("GOOGLE_DRIVE_ROOT_FOLDER_ID", "").strip()
DRIVE_AUDIO_FOLDER_ID = os.getenv("GOOGLE_DRIVE_AUDIO_FOLDER_ID", "").strip()
DRIVE_TRANSCRIPTS_FOLDER_ID = os.getenv("GOOGLE_DRIVE_TRANSCRIPTS_FOLDER_ID", "").strip()
SERVICE_ACCOUNT_JSON = os.getenv("GOOGLE_SERVICE_ACCOUNT_JSON", "").strip()
DRIVE_POLL_SECONDS = max(60, int(os.getenv("GOOGLE_DRIVE_POLL_SECONDS", "300")))
DRIVE_MAX_SCAN_ITEMS = max(100, int(os.getenv("GOOGLE_DRIVE_MAX_SCAN_ITEMS", "5000")))
DRIVE_MAX_DEPTH = max(1, min(10, int(os.getenv("GOOGLE_DRIVE_MAX_DEPTH", "6"))))

FOLDER_MIME = "application/vnd.google-apps.folder"
AUDIO_EXTENSIONS = {".m4a", ".mp4", ".mp3", ".wav", ".webm", ".ogg", ".flac", ".mpeg", ".mpga", ".aac"}
VOICEGROWTH_NAME = re.compile(r"^VG_(\d{8})_(\d{6})_", re.IGNORECASE)
IST = timezone(timedelta(hours=5, minutes=30))


def drive_configured() -> bool:
    return bool(DRIVE_ROOT_FOLDER_ID and SERVICE_ACCOUNT_JSON)


def build_drive_service():
    if not drive_configured():
        raise RuntimeError("Google Drive transcription bridge is not configured")
    info = json.loads(SERVICE_ACCOUNT_JSON)
    credentials = service_account.Credentials.from_service_account_info(
        info,
        scopes=["https://www.googleapis.com/auth/drive"],
    )
    return build("drive", "v3", credentials=credentials, cache_discovery=False)


def parse_drive_time(value: str | None) -> datetime | None:
    if not value:
        return None
    try:
        return datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return None


def infer_recorded_at(name: str, created_time: str | None) -> datetime | None:
    match = VOICEGROWTH_NAME.match(name)
    if match:
        try:
            local = datetime.strptime("".join(match.groups()), "%Y%m%d%H%M%S").replace(tzinfo=IST)
            return local.astimezone(timezone.utc)
        except ValueError:
            pass
    return parse_drive_time(created_time)


def escape_drive_query(value: str) -> str:
    return value.replace("\\", "\\\\").replace("'", "\\'")


def list_children(service, folder_id: str) -> list[dict[str, Any]]:
    result: list[dict[str, Any]] = []
    page_token = None
    while True:
        response = service.files().list(
            q=f"'{folder_id}' in parents and trashed = false",
            spaces="drive",
            fields="nextPageToken, files(id,name,mimeType,createdTime,modifiedTime,size,webViewLink,parents,appProperties)",
            pageSize=1000,
            pageToken=page_token,
            orderBy="createdTime asc",
        ).execute()
        result.extend(response.get("files", []))
        page_token = response.get("nextPageToken")
        if not page_token or len(result) >= DRIVE_MAX_SCAN_ITEMS:
            break
    return result[:DRIVE_MAX_SCAN_ITEMS]


def resolve_unique_child_folder(service, parent_id: str, name: str) -> str:
    escaped = escape_drive_query(name)
    response = service.files().list(
        q=(
            f"'{parent_id}' in parents and trashed = false and "
            f"mimeType = '{FOLDER_MIME}' and name = '{escaped}'"
        ),
        spaces="drive",
        fields="files(id,name,parents)",
        pageSize=10,
    ).execute()
    matches = response.get("files", [])
    if len(matches) != 1:
        raise RuntimeError(
            f"Canonical VoiceGrowth/{name} folder is ambiguous: expected exactly 1 direct child, found {len(matches)}. "
            f"Set GOOGLE_DRIVE_{name.upper()}_FOLDER_ID explicitly."
        )
    return str(matches[0]["id"])


def resolve_audio_folder_id(service) -> str:
    return DRIVE_AUDIO_FOLDER_ID or resolve_unique_child_folder(service, DRIVE_ROOT_FOLDER_ID, "Audio")


def resolve_transcripts_folder_id(service) -> str:
    return DRIVE_TRANSCRIPTS_FOLDER_ID or resolve_unique_child_folder(service, DRIVE_ROOT_FOLDER_ID, "Transcripts")


def scan_audio_files(service, audio_root_id: str | None = None) -> list[dict[str, Any]]:
    root_id = audio_root_id or resolve_audio_folder_id(service)
    queue: list[tuple[str, int]] = [(root_id, 0)]
    discovered: list[dict[str, Any]] = []
    visited: set[str] = set()
    scanned_items = 0

    while queue and scanned_items < DRIVE_MAX_SCAN_ITEMS:
        folder_id, depth = queue.pop(0)
        if folder_id in visited:
            continue
        visited.add(folder_id)
        for item in list_children(service, folder_id):
            scanned_items += 1
            if item.get("mimeType") == FOLDER_MIME:
                if depth < DRIVE_MAX_DEPTH:
                    queue.append((item["id"], depth + 1))
                continue
            suffix = Path(item.get("name", "")).suffix.lower()
            mime = str(item.get("mimeType", ""))
            if suffix in AUDIO_EXTENSIONS or mime.startswith("audio/"):
                discovered.append(item)
            if scanned_items >= DRIVE_MAX_SCAN_ITEMS:
                break
    return discovered


def already_ingested(file_id: str) -> bool:
    with SessionLocal() as db:
        return db.scalar(select(Recording.id).where(Recording.drive_audio_file_id == file_id).limit(1)) is not None


def find_persisted_transcript(service, transcripts_folder_id: str, source_file_id: str) -> dict[str, Any] | None:
    escaped = escape_drive_query(source_file_id)
    response = service.files().list(
        q=(
            f"'{transcripts_folder_id}' in parents and trashed = false and "
            f"appProperties has {{ key='sourceDriveFileId' and value='{escaped}' }}"
        ),
        spaces="drive",
        fields="files(id,name,mimeType,webViewLink,createdTime,modifiedTime,appProperties)",
        pageSize=10,
    ).execute()
    rows = response.get("files", [])
    if len(rows) > 1:
        raise RuntimeError(f"Multiple canonical transcripts found for source Drive ID {source_file_id}")
    return rows[0] if rows else None


def download_verified_audio(service, item: dict[str, Any], destination: Path) -> tuple[int, str]:
    request = service.files().get_media(fileId=str(item["id"]))
    hasher = hashlib.sha256()
    total = 0
    try:
        with destination.open("wb") as handle:
            downloader = MediaIoBaseDownload(handle, request, chunksize=1024 * 1024)
            done = False
            while not done:
                _, done = downloader.next_chunk(num_retries=3)
        with destination.open("rb") as handle:
            while chunk := handle.read(1024 * 1024):
                total += len(chunk)
                hasher.update(chunk)
    except Exception:
        destination.unlink(missing_ok=True)
        raise

    expected = int(item["size"]) if item.get("size") not in (None, "") else None
    if total <= 0:
        destination.unlink(missing_ok=True)
        raise RuntimeError(f"Drive audio {item['id']} downloaded as an empty file")
    if expected is not None and total != expected:
        destination.unlink(missing_ok=True)
        raise RuntimeError(
            f"Drive audio size mismatch for {item['id']}: metadata={expected}, downloaded={total}"
        )
    return total, hasher.hexdigest()


def render_transcript_markdown(
    item: dict[str, Any],
    source_sha256: str,
    source_bytes: int,
    source_transcript: str,
    segments: list[dict[str, Any]],
) -> str:
    recorded_at = infer_recorded_at(str(item.get("name") or ""), item.get("createdTime"))
    transcribed_at = datetime.now(timezone.utc)
    diarization_available = any(str(s.get("speaker", "Unknown")) != "Unknown" for s in segments)
    drive_id = str(item["id"])
    web_link = item.get("webViewLink") or f"https://drive.google.com/file/d/{drive_id}/view"
    return "\n".join([
        f"# {item.get('name', drive_id)} - Transcript",
        "",
        "## Provenance",
        f"- Source Drive file ID: `{drive_id}`",
        f"- Source Drive link: {web_link}",
        f"- Source archive filename: `{item.get('name', '')}`",
        f"- Source bytes: `{source_bytes}`",
        f"- Source SHA-256: `{source_sha256}`",
        f"- Source created time: `{item.get('createdTime', '')}`",
        f"- Recorded at: `{recorded_at.isoformat() if recorded_at else ''}`",
        f"- Transcribed at: `{transcribed_at.isoformat()}`",
        f"- Lexical transcription model: `{TRANSCRIBE_MODEL}`",
        f"- Diarization model: `{DIARIZE_MODEL}`",
        f"- Speaker diarization: `{'available' if diarization_available else 'not available/fallback'}`",
        "",
        "## Machine transcript",
        "",
        "> Machine transcription. Preserve uncertainty. Clinically consequential names, doses, dates and numbers require verification against audio/context.",
        "",
        source_transcript.strip(),
        "",
    ])


def persist_transcript(
    service,
    transcripts_folder_id: str,
    item: dict[str, Any],
    source_sha256: str,
    source_bytes: int,
    source_transcript: str,
    segments: list[dict[str, Any]],
) -> dict[str, Any]:
    source_file_id = str(item["id"])
    existing = find_persisted_transcript(service, transcripts_folder_id, source_file_id)
    if existing:
        return existing

    source_name = str(item.get("name") or source_file_id)
    stem = Path(source_name).stem
    transcript_name = f"{stem} - Transcript.md"
    markdown = render_transcript_markdown(
        item=item,
        source_sha256=source_sha256,
        source_bytes=source_bytes,
        source_transcript=source_transcript,
        segments=segments,
    )
    media = MediaIoBaseUpload(io.BytesIO(markdown.encode("utf-8")), mimetype="text/markdown", resumable=False)
    created = service.files().create(
        body={
            "name": transcript_name,
            "parents": [transcripts_folder_id],
            "mimeType": "text/markdown",
            "appProperties": {
                "voiceGrowthSchema": "2",
                "sourceDriveFileId": source_file_id,
                "sourceSha256": source_sha256,
                "sourceBytes": str(source_bytes),
            },
        },
        media_body=media,
        fields="id,name,mimeType,webViewLink,createdTime,modifiedTime,appProperties",
    ).execute()
    return created


def record_success(
    item: dict[str, Any],
    source_transcript: str,
    segments: list[dict[str, Any]],
    transcript: dict[str, Any],
) -> None:
    file_id = str(item["id"])
    with SessionLocal() as db:
        if db.scalar(select(Recording.id).where(Recording.drive_audio_file_id == file_id).limit(1)):
            return
        rec = Recording(
            job_id=f"drive-{file_id}",
            client_recording_id=f"drive:{file_id}",
            original_filename=str(item.get("name") or file_id),
            local_path="",
            source="drive_transcription_bridge",
            recorded_at=infer_recorded_at(str(item.get("name") or ""), item.get("createdTime")),
            drive_audio_file_id=file_id,
            drive_web_view_link=item.get("webViewLink"),
            status="completed",
            source_transcript=source_transcript,
            corrected_transcript=None,
            processed_at=datetime.now(timezone.utc),
            error_message=f"transcript_drive_file_id={transcript.get('id')}",
        )
        db.add(rec)
        db.flush()
        for seg in segments:
            db.add(TranscriptSegment(
                recording_id=rec.id,
                speaker=str(seg.get("speaker", "Unknown")),
                start_seconds=float(seg.get("start", 0.0)),
                end_seconds=float(seg.get("end", seg.get("start", 0.0))),
                text=str(seg.get("text", "")),
                confidence=seg.get("confidence"),
            ))
        db.commit()


def reconcile_existing_transcript(service, transcripts_folder_id: str, item: dict[str, Any]) -> bool:
    transcript = find_persisted_transcript(service, transcripts_folder_id, str(item["id"]))
    if not transcript:
        return False
    record_success(item, "", [], transcript)
    return True


def process_drive_audio(service, transcripts_folder_id: str, item: dict[str, Any]) -> bool:
    file_id = str(item["id"])
    if already_ingested(file_id):
        return False
    if reconcile_existing_transcript(service, transcripts_folder_id, item):
        log.info("Reconciled existing canonical transcript for Drive audio %s", file_id)
        return False

    suffix = Path(str(item.get("name") or "")).suffix.lower()
    if suffix not in AUDIO_EXTENSIONS:
        suffix = ".m4a"

    temp_audio = AUDIO_DIR / f"drive-{file_id}{suffix}"
    try:
        source_bytes, source_sha256 = download_verified_audio(service, item, temp_audio)
        with tempfile.TemporaryDirectory(prefix=f"voicegrowth-{file_id[:8]}-") as temp_dir:
            chunks = normalize_and_chunk(temp_audio, Path(temp_dir))
            source_transcript, segments = transcribe_chunks(chunks)
        if not source_transcript.strip():
            raise RuntimeError(f"Transcription returned no text for Drive audio {file_id}")
        transcript = persist_transcript(
            service=service,
            transcripts_folder_id=transcripts_folder_id,
            item=item,
            source_sha256=source_sha256,
            source_bytes=source_bytes,
            source_transcript=source_transcript,
            segments=segments,
        )
        record_success(item, source_transcript, segments, transcript)
        log.info("Persisted canonical transcript %s for Drive audio %s", transcript.get("id"), file_id)
        return True
    finally:
        temp_audio.unlink(missing_ok=True)


def ingest_drive_once() -> dict[str, int]:
    if not drive_configured():
        return {"configured": 0, "discovered": 0, "processed": 0, "failed": 0}

    service = build_drive_service()
    audio_folder_id = resolve_audio_folder_id(service)
    transcripts_folder_id = resolve_transcripts_folder_id(service)
    items = scan_audio_files(service, audio_folder_id)
    processed = 0
    failed = 0
    for item in items:
        try:
            if process_drive_audio(service, transcripts_folder_id, item):
                processed += 1
        except Exception as exc:
            failed += 1
            log.exception("Failed to transcribe Drive file %s: %s", item.get("id"), exc)
    return {"configured": 1, "discovered": len(items), "processed": processed, "failed": failed}


async def drive_loop() -> None:
    while True:
        try:
            await asyncio.to_thread(ingest_drive_once)
            await asyncio.sleep(DRIVE_POLL_SECONDS)
        except asyncio.CancelledError:
            raise
        except Exception:
            log.exception("Drive transcription loop failed")
            await asyncio.sleep(DRIVE_POLL_SECONDS)


_drive_task: asyncio.Task | None = None


@app.on_event("startup")
async def start_drive_ingest() -> None:
    global _drive_task
    if drive_configured():
        log.info("Drive-ID-first transcription enabled for VoiceGrowth root %s", DRIVE_ROOT_FOLDER_ID)
        _drive_task = asyncio.create_task(drive_loop())
    else:
        log.info("Drive transcription disabled; configure GOOGLE_DRIVE_ROOT_FOLDER_ID and GOOGLE_SERVICE_ACCOUNT_JSON")


@app.on_event("shutdown")
async def stop_drive_ingest() -> None:
    if _drive_task:
        _drive_task.cancel()
