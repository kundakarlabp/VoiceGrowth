"""Read-only VoiceGrowth cloud inventory.

Run on an authorized backend with a dedicated service account already granted
read access to the private VoiceGrowth Audio folder. This NEVER downloads audio,
changes Google Drive data, or invokes transcription/AI models.

    GOOGLE_SERVICE_ACCOUNT_JSON='...' GOOGLE_DRIVE_AUDIO_FOLDER_ID='...' \
      python drive_audit.py

Only aggregate counts and Drive file IDs are emitted by default. Folder paths
can be sensitive in clinical settings; use the detailed report only privately.
"""
from __future__ import annotations

import argparse
import json
import os
from collections import defaultdict, deque
from typing import Any

AUDIO_EXTENSIONS = {".m4a", ".mp4", ".mp3", ".wav", ".ogg", ".opus", ".flac", ".aac", ".amr", ".3gp"}
FOLDER_MIME = "application/vnd.google-apps.folder"


def summarize_audit(items: list[dict[str, Any]]) -> dict[str, Any]:
    """Pure summary of Drive metadata; hashless duplicates are only suspected."""
    folders: dict[tuple[str, str], list[str]] = defaultdict(list)
    by_checksum: dict[tuple[str, str], list[str]] = defaultdict(list)
    name_size: dict[tuple[str, str], list[str]] = defaultdict(list)
    audio = []

    for item in items:
        if item.get("mimeType") == FOLDER_MIME:
            folders[(str(item["parentId"]), str(item["name"]).casefold())].append(str(item["id"]))
            continue
        mime = str(item.get("mimeType") or "")
        name = str(item.get("name") or "")
        if not mime.startswith("audio/") and not any(name.casefold().endswith(ext) for ext in AUDIO_EXTENSIONS):
            continue
        audio.append(item)
        size = str(item.get("size") or "")
        checksum = str(item.get("md5Checksum") or "")
        if checksum and size:
            by_checksum[(checksum, size)].append(str(item["id"]))
        elif size:
            name_size[(name.casefold(), size)].append(str(item["id"]))

    folder_duplicates = [
        {"parent_id": parent, "folder_name": name, "ids": sorted(ids)}
        for (parent, name), ids in folders.items() if len(ids) > 1
    ]
    content_duplicates = [
        {"file_ids": sorted(ids), "checksum_type": "Drive md5Checksum"}
        for ids in by_checksum.values() if len(ids) > 1
    ]
    suspected = [
        {"file_ids": sorted(ids), "reason": "same filename and size; no Drive checksum"}
        for ids in name_size.values() if len(ids) > 1
    ]
    return {
        "audio_files": len(audio),
        "distinct_content_groups_with_checksums": len(by_checksum),
        "duplicate_folder_groups": sorted(folder_duplicates, key=lambda x: (x["parent_id"], x["folder_name"])),
        "matching_checksum_audio_groups": sorted(content_duplicates, key=lambda x: x["file_ids"]),
        "suspected_audio_groups_without_checksum": sorted(suspected, key=lambda x: x["file_ids"]),
        "files_missing_checksum": sum(1 for x in audio if not x.get("md5Checksum")),
        "scope": "Google Drive metadata only; no local-device reconciliation or audio transcription",
    }


def list_children(service: Any, folder_id: str) -> list[dict[str, Any]]:
    items = []
    token = None
    while True:
        response = service.files().list(
            q=f"'{folder_id}' in parents and trashed = false",
            fields="nextPageToken,files(id,name,mimeType,size,md5Checksum,createdTime,parents)",
            pageSize=1000,
            pageToken=token,
        ).execute()
        items.extend(response.get("files", []))
        token = response.get("nextPageToken")
        if not token:
            return items


def audit_drive(service: Any, audio_root_id: str, max_items: int = 20000) -> dict[str, Any]:
    """Walk only the selected Audio root; refuse silently truncated inventories."""
    assert audio_root_id, "A specific canonical Audio folder ID is required"
    queue = deque([audio_root_id])
    visited = set()
    items = []
    while queue:
        folder_id = queue.popleft()
        if folder_id in visited:
            continue
        visited.add(folder_id)
        children = list_children(service, folder_id)
        if len(items) + len(children) > max_items:
            raise RuntimeError("Audit item limit exceeded; no partial audit is reported")
        for child in children:
            child["parentId"] = folder_id
            items.append(child)
            if child.get("mimeType") == FOLDER_MIME:
                queue.append(str(child["id"]))
    summary = summarize_audit(items)
    summary["folders_scanned"] = len(visited)
    summary["items_scanned"] = len(items)
    summary["audio_root_id"] = audio_root_id
    return summary


def main() -> None:
    parser = argparse.ArgumentParser(description="Read-only private VoiceGrowth Google Drive sync audit")
    parser.add_argument("--max-items", type=int, default=20000)
    args = parser.parse_args()
    audio_id = os.getenv("GOOGLE_DRIVE_AUDIO_FOLDER_ID", "").strip()
    secret = os.getenv("GOOGLE_SERVICE_ACCOUNT_JSON", "").strip()
    if not audio_id or not secret:
        parser.error("GOOGLE_DRIVE_AUDIO_FOLDER_ID and GOOGLE_SERVICE_ACCOUNT_JSON are required")
    from google.oauth2 import service_account
    from googleapiclient.discovery import build
    credentials = service_account.Credentials.from_service_account_info(
        json.loads(secret), scopes=["https://www.googleapis.com/auth/drive.readonly"]
    )
    service = build("drive", "v3", credentials=credentials, cache_discovery=False)
    print(json.dumps(audit_drive(service, audio_id, args.max_items), indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
