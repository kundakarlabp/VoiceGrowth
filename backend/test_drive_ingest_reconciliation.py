"""Offline regression tests for already-persisted Drive transcript reconciliation."""
import uuid
import unittest

from sqlalchemy import select

from app import Recording, SessionLocal
from drive_ingest import already_ingested, parse_persisted_transcript_markdown, record_success


class PersistedTranscriptTests(unittest.TestCase):
    def setUp(self):
        self.file_id = "1TEST" + uuid.uuid4().hex

    def tearDown(self):
        with SessionLocal() as db:
            rec = db.scalar(select(Recording).where(Recording.drive_audio_file_id == self.file_id))
            if rec:
                db.delete(rec)
                db.commit()

    def test_matching_document_recovers_source_text(self):
        source = "Synthetic words in English, not a real consultation."
        markdown = (
            "# Transcript\n\n"
            f"- Source Drive file ID: `{self.file_id}`\n\n"
            "## Machine transcript\n\n"
            "> Machine transcription. Verify clinically consequential content.\n\n"
            + source + "\n"
        )
        self.assertEqual(parse_persisted_transcript_markdown(markdown, self.file_id), source)
        with self.assertRaises(ValueError):
            parse_persisted_transcript_markdown(markdown, "1unrelated1234")
        with self.assertRaises(ValueError):
            parse_persisted_transcript_markdown("# no section", self.file_id)

    def test_empty_legacy_record_is_repaired_without_paid_model_call(self):
        with SessionLocal() as db:
            db.add(Recording(
                job_id=str(uuid.uuid4()),
                client_recording_id=f"drive:{self.file_id}",
                original_filename="synthetic.m4a",
                local_path="",
                source="drive_transcription_bridge",
                drive_audio_file_id=self.file_id,
                status="completed",
                source_transcript="",
            ))
            db.commit()
        self.assertFalse(already_ingested(self.file_id))
        item = {"id": self.file_id, "name": "synthetic.m4a"}
        record_success(item, "Recovered words", [], {"id": "test-transcript"})
        self.assertTrue(already_ingested(self.file_id))
        with SessionLocal() as db:
            rec = db.scalar(select(Recording).where(Recording.drive_audio_file_id == self.file_id))
            self.assertEqual(rec.source_transcript, "Recovered words")
            self.assertEqual(rec.status, "completed")


if __name__ == "__main__":
    unittest.main()
