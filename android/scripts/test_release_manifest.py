import json
from pathlib import Path
import tempfile
import unittest

from release_manifest import generate, validate_manifest, validate_promotion


class ReleaseManifestTest(unittest.TestCase):
    def setUp(self):
        self.manifest = {
            "schemaVersion": 1, "version": "1.2.3", "versionCode": 20,
            "fileName": "alogin-v1.2.3.apk", "releaseNotes": '- 新增功能\n- 修复 "登录" 问题',
        }

    def test_generate_preserves_chinese_notes_and_matches_android_version(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            gradle = root / "build.gradle.kts"
            gradle.write_text('versionName = "1.2.3"\nversionCode = 20\n', encoding="utf-8")
            (root / "1.2.3.md").write_text(self.manifest["releaseNotes"], encoding="utf-8")
            generate("v1.2.3", gradle, root, root / "output")
            actual = json.loads((root / "output/latest.json").read_text(encoding="utf-8"))
            self.assertEqual(self.manifest, actual)
            self.assertEqual(self.manifest["releaseNotes"], (root / "output/release-notes.md").read_text(encoding="utf-8"))
            with self.assertRaises(ValueError):
                generate("v1.2.4", gradle, root, root / "output")
            (root / "1.2.3.md").unlink()
            with self.assertRaises(FileNotFoundError):
                generate("v1.2.3", gradle, root, root / "output")

    def test_invalid_metadata_is_rejected(self):
        for field, value in [
            ("schemaVersion", True), ("versionCode", 0), ("versionCode", "20"),
            ("version", "../1.2.3"), ("version", "1.2.3-01"),
            ("fileName", "../other.apk"), ("releaseNotes", "  "),
            ("releaseNotes", "😀" * 10001),
        ]:
            with self.subTest(field=field, value=str(value)[:40]):
                with self.assertRaises(ValueError):
                    validate_manifest({**self.manifest, field: value})

    def test_first_publish_and_same_release_retry_are_allowed(self):
        validate_promotion(self.manifest, None)
        validate_promotion(self.manifest, self.manifest)

    def test_newer_publish_is_allowed_but_rollback_and_reused_code_fail(self):
        newer = {**self.manifest, "version": "1.2.4", "versionCode": 21, "fileName": "alogin-v1.2.4.apk"}
        validate_promotion(newer, self.manifest)
        with self.assertRaises(ValueError):
            validate_promotion(self.manifest, newer)
        with self.assertRaises(ValueError):
            validate_promotion({**newer, "versionCode": 20}, self.manifest)


if __name__ == "__main__":
    unittest.main()
