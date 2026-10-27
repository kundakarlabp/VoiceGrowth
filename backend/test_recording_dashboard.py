"""Offline test of clinician-authorized transcript import and dashboard review."""
import uuid
from unittest.mock import patch
import unittest

from fastapi import HTTPException
from sqlalchemy import select

import full_app  # noqa: F401 - registers the production routes
from app import ConsultRecord, Recording, SessionLocal
from recording_dashboard import (
    TranscriptImport, TranscriptProvenance, import_reviewed_transcript,
    recording_detail, recording_inventory, recording_review, cloud_audio_inventory, import_extracted_consult,
)


class RecordingDashboardTests(unittest.TestCase):
    def setUp(self):
        self.file_id = "1VG" + uuid.uuid4().hex
        self.payload = TranscriptImport(
            drive_file_id=self.file_id,
            source_filename="synthetic_nonclinical_test.m4a",
            transcript_text="Synthetic test transcript only. No patient information.",
            method="clinician_reviewed",
            source_bytes=1024,
            source_sha256="a" * 64,
        )

    def tearDown(self):
        with SessionLocal() as db:
            rec = db.scalar(select(Recording)
                            .where(Recording.drive_audio_file_id == self.file_id).limit(1))
            if rec:
                provenance = db.get(TranscriptProvenance, rec.id)
                if provenance:
                    db.delete(provenance)
                db.delete(rec)
                db.commit()

    def test_import_is_review_pending_and_idempotent(self):
        created = import_reviewed_transcript(self.payload)
        self.assertFalse(created["duplicate"])
        self.assertFalse(created["source_verified_against_drive"])
        self.assertFalse(created["consult_created"])
        self.assertEqual(created["status"], "transcript_review_pending")
        second = import_reviewed_transcript(self.payload)
        self.assertTrue(second["duplicate"])
        self.assertEqual(second["recording_id"], created["recording_id"])

        detail = recording_detail(created["recording_id"])
        self.assertTrue(detail["has_transcript"])
        self.assertFalse(detail["has_structured_consult"])
        self.assertEqual(detail["source_transcript"], self.payload.transcript_text)
        self.assertFalse(recording_inventory(limit=500, offset=0)["drive_cloud_inventory_verified"])

        with SessionLocal() as db:
            provenance = db.get(TranscriptProvenance, created["recording_id"])
            self.assertEqual(provenance.drive_file_id, self.file_id)
            self.assertEqual(provenance.asserted_source_sha256, "a" * 64)
            self.assertEqual(provenance.asserted_source_bytes, 1024)
            self.assertFalse(provenance.source_verified_against_drive)

    def test_different_transcript_cannot_silently_overwrite(self):
        import_reviewed_transcript(self.payload)
        changed = self.payload.model_copy(update={"transcript_text": "Conflicting transcript"})
        with self.assertRaises(HTTPException) as err:
            import_reviewed_transcript(changed)
        self.assertEqual(err.exception.status_code, 409)

    def test_dashboard_escapes_transcript_markup(self):
        changed = self.payload.model_copy(update={"transcript_text": "<script>not allowed</script>"})
        created = import_reviewed_transcript(changed)
        response = recording_review(created["recording_id"], _="test")
        document = response.body.decode("utf-8")
        self.assertNotIn("<script>not allowed</script>", document)
        self.assertIn("&lt;script&gt;not allowed&lt;/script&gt;", document)
        self.assertEqual(response.headers["cache-control"], "no-store, max-age=0")

    def test_structured_import_requires_transcript_and_is_not_verified(self):
        created = import_reviewed_transcript(self.payload)
        structured = ConsultRecord(consult_summary="Synthetic learning discussion; no clinical claims.")
        result = import_extracted_consult(created["recording_id"], structured)
        self.assertFalse(result["duplicate"])
        self.assertEqual(result["verification_status"], "ai_extracted")
        self.assertFalse(result["source_verified_against_drive"])
        self.assertTrue(recording_detail(created["recording_id"])["has_structured_consult"])
        second = import_extracted_consult(created["recording_id"], structured)
        self.assertTrue(second["duplicate"])
        conflicting = structured.model_copy(update={"consult_summary": "Changed synthetic case"})
        with self.assertRaises(HTTPException) as err:
            import_extracted_consult(created["recording_id"], conflicting)
        self.assertEqual(err.exception.status_code, 409)

    def test_cloud_dashboard_fails_closed_without_credentials(self):
        with patch.dict("os.environ", {
            "GOOGLE_DRIVE_AUDIO_FOLDER_ID": "",
            "GOOGLE_SERVICE_ACCOUNT_JSON": "",
        }):
            with self.assertRaises(HTTPException) as err:
                cloud_audio_inventory()
        self.assertEqual(err.exception.status_code, 503)

    def test_protected_routes_exist(self):
        paths = {route.path for route in full_app.app.routes}
        for path in ("/v2/recordings/inventory",
                     "/v2/recordings/transcripts/import", "/v2/recordings/{recording_id}/consult-import",
                     "/dashboard/recordings", "/dashboard/cloud",
                     "/v2/drive/audio-inventory"):
            self.assertIn(path, paths)

    def test_invalid_drive_id_rejected(self):
        altered = self.payload.model_copy(update={"drive_file_id": "../invalid"})
        with self.assertRaises(HTTPException) as err:
            import_reviewed_transcript(altered)
        self.assertEqual(err.exception.status_code, 422)


if __name__ == "__main__":
    unittest.main()
