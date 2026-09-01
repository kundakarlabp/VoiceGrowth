from __future__ import annotations

import asyncio
import io
import json
import logging
import os
import re
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any

from google.oauth2 import service_account
from googleapiclient.discovery import build
from googleapiclient.http import MediaIoBaseDownload
from sqlalchemy import select

from app import AUDIO_DIR, Recording, SessionLocal, app

log = logging.getLogger("voicegrowth-drive-ingest")

DRIVE_ROOT_FOLDER_ID = os.getenv("GOOGLE_DRIVE_ROOT_FOLDER_ID", "").strip()
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
        raise RuntimeError("Google Drive ingestion is not configured")
    info = json.loads(SERVICE_ACCOUNT_JSON)
    credentials = service_account.Credentials.from_service_account_info(
        info,
        scopes=["https://www.googleapis.com/auth/drive.readonly"],
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


def list_children(service, folder_id: str) -> list[dict[str, Any]]:
    result: list[dict[str, Any]] = []
    page_token = None
    while True:
        response = service.files().list(
            q=f"'{folder_id}' in parents and trashed = false",
            spaces="drive",
            fields="nextPageToken, files(id,name,mimeType,createdTime,modifiedTime,size,webViewLink)",
            pageSize=1000,
            pageToken=page_token,
            orderBy="createdTime asc",
        ).execute()
        result.extend(response.get("files", []))
        page_token = response.get("nextPageToken")
        if not page_token or len(result) >= DRIVE_MAX_SCAN_ITEMS:
            break
    return result[:DRIVE_MAX_SCAN_ITEMS]


def scan_audio_files(service) -> list[dict[str, Any]]:
    queue: list[tuple[str, int]] = [(DRIVE_ROOT_FOLDER_ID, 0)]
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


def download_drive_file(service, item: dict[str, Any]) -> int | None:
    file_id = str(item["id"])
    if already_ingested(file_id):
        return None

    name = str(item.get("name") or f"drive-{file_id}.m4a")
    suffix = Path(name).suffix.lower()
    if suffix not in AUDIO_EXTENSIONS:
        suffix = ".m4a"
    job_id = str(uuid.uuid4())
    destination = AUDIO_DIR / f"{job_id}{suffix}"

    request = service.files().get_media(fileId=file_id)
    try:
        with destination.open("wb") as handle:
            downloader = MediaIoBaseDownload(handle, request, chunksize=1024 * 1024)
            done = False
            while not done:
                _, done = downloader.next_chunk(num_retries=3)
    except Exception:
        destination.unlink(missing_ok=True)
        raise

    with SessionLocal() as db:
        duplicate = db.scalar(select(Recording.id).where(Recording.drive_audio_file_id == file_id).limit(1))
        if duplicate:
            destination.unlink(missing_ok=True)
            return None
        rec = Recording(
            job_id=job_id,
            client_recording_id=f"drive:{file_id}",
            original_filename=name,
            local_path=str(destination),
            source="drive_ingest",
            recorded_at=infer_recorded_at(name, item.get("createdTime")),
            drive_audio_file_id=file_id,
            drive_web_view_link=item.get("webViewLink"),
            status="queued",
        )
        db.add(rec)
        db.commit()
        db.refresh(rec)
        log.info("Queued Drive audio %s as job %s", name, job_id)
        return rec.id


def ingest_drive_once() -> dict[str, int]:
    if not drive_configured():
        return {"configured": 0, "discovered": 0, "queued": 0}
    service = build_drive_service()
    items = scan_audio_files(service)
    queued = 0
    for item in items:
        try:
            if download_drive_file(service, item) is not None:
                queued += 1
        except Exception as exc:
            log.exception("Failed to ingest Drive file %s: %s", item.get("id"), exc)
    return {"configured": 1, "discovered": len(items), "queued": queued}


async def drive_loop() -> None:
    while True:
        try:
            await asyncio.to_thread(ingest_drive_once)
            await asyncio.sleep(DRIVE_POLL_SECONDS)
        except asyncio.CancelledError:
            raise
        except Exception:
            log.exception("Drive ingestion loop failed")
            await asyncio.sleep(DRIVE_POLL_SECONDS)


_drive_task: asyncio.Task | None = None


@app.on_event("startup")
async def start_drive_ingest() -> None:
    global _drive_task
    if drive_configured():
        log.info("Google Drive ingestion enabled for root folder %s", DRIVE_ROOT_FOLDER_ID)
        _drive_task = asyncio.create_task(drive_loop())
    else:
        log.info("Google Drive ingestion disabled; configure GOOGLE_DRIVE_ROOT_FOLDER_ID and GOOGLE_SERVICE_ACCOUNT_JSON")


@app.on_event("shutdown")
async def stop_drive_ingest() -> None:
    if _drive_task:
        _drive_task.cancel()
