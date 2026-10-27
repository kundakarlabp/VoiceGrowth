"""Deterministic, network-free tests for the read-only cloud audit."""
import unittest

from drive_audit import AUDIO_EXTENSIONS, FOLDER_MIME, summarize_audit


class CloudAuditTests(unittest.TestCase):
    def test_duplicate_folders_and_matching_checksum_audio(self):
        items = [
            {"id": "f1", "name": "10-Oct", "mimeType": FOLDER_MIME, "parentId": "2026"},
            {"id": "f2", "name": "10-Oct", "mimeType": FOLDER_MIME, "parentId": "2026"},
            {"id": "a1", "name": "VG_1.m4a", "mimeType": "audio/mp4", "size": "100",
             "md5Checksum": "same", "parentId": "f1"},
            {"id": "a2", "name": "VG_1.m4a", "mimeType": "audio/mp4", "size": "100",
             "md5Checksum": "same", "parentId": "f2"},
            {"id": "b", "name": "VG_2.m4a", "mimeType": "audio/mp4", "size": "50",
             "md5Checksum": "other", "parentId": "f2"},
        ]
        result = summarize_audit(items)
        self.assertEqual(result["audio_files"], 3)
        self.assertEqual(result["duplicate_folder_groups"][0]["ids"], ["f1", "f2"])
        self.assertEqual(result["matching_checksum_audio_groups"][0]["file_ids"], ["a1", "a2"])
        self.assertEqual(result["files_missing_checksum"], 0)

    def test_no_checksum_is_suspected_not_confirmed(self):
        items = [
            {"id": "a1", "name": "call.m4a", "mimeType": "audio/mp4", "size": "100"},
            {"id": "a2", "name": "call.m4a", "mimeType": "audio/mp4", "size": "100"},
        ]
        result = summarize_audit(items)
        self.assertEqual(len(result["suspected_audio_groups_without_checksum"]), 1)
        self.assertEqual(result["matching_checksum_audio_groups"], [])

    def test_same_name_different_content_is_not_duplicate(self):
        items = [
            {"id": "a1", "name": "rec.m4a", "mimeType": "audio/mp4",
             "size": "100", "md5Checksum": "a"},
            {"id": "a2", "name": "rec.m4a", "mimeType": "audio/mp4",
             "size": "100", "md5Checksum": "b"},
            {"id": "text", "name": "report.txt", "mimeType": "text/plain", "size": "100"},
        ]
        result = summarize_audit(items)
        self.assertEqual(result["audio_files"], 2)
        self.assertEqual(result["matching_checksum_audio_groups"], [])
        self.assertEqual(result["suspected_audio_groups_without_checksum"], [])

    def test_audio_exts_cover_android_imports(self):
        self.assertTrue({".m4a", ".amr", ".3gp", ".mp3"}.issubset(AUDIO_EXTENSIONS))


if __name__ == "__main__":
    unittest.main()
