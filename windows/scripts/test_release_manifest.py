import copy
import hashlib
import json
from pathlib import Path
import tempfile
import tomllib
import unittest
import zipfile

from release_manifest import (BASE_URL, ROOT, package_release, release_info,
                              validate_manifest, validate_promotion, verify_release)


class WindowsReleaseTests(unittest.TestCase):
    def setUp(self):
        self.manifest = {"schemaVersion": 1, "platform": "windows", "architecture": "x64",
                         "version": "0.0.1", "tag": "windows-v0.0.1",
                         "fileName": "alogin-windows-v0.0.1-x64.zip",
                         "downloadUrl": BASE_URL + "alogin-windows-v0.0.1-x64.zip",
                         "size": 123, "sha256": "a" * 64, "releaseNotes": "首版说明"}

    def test_channel_rejects_android_tags_paths_and_invalid_metadata(self):
        validate_manifest(self.manifest)
        for key, value in [("schemaVersion", True), ("size", True), ("size", 0),
                           ("sha256", "bad"), ("version", "../0.0.1"),
                           ("version", "0.0.65536"), ("architecture", "arm64"),
                           ("tag", "v0.0.1"), ("fileName", "alogin-v0.0.1.apk"),
                           ("downloadUrl", "https://example.com/update.zip"),
                           ("releaseNotes", "")]:
            with self.subTest(key=key, value=value), self.assertRaises(ValueError):
                validate_manifest({**self.manifest, key: value})

    def test_promotion_allows_exact_reruns_and_prevents_downgrade_or_mutation(self):
        validate_promotion(self.manifest, None)
        validate_promotion(self.manifest, copy.deepcopy(self.manifest))
        with self.assertRaises(ValueError):
            validate_promotion({**self.manifest, "sha256": "b" * 64}, self.manifest)
        newer = {**self.manifest, "version": "0.0.2", "tag": "windows-v0.0.2",
                 "fileName": "alogin-windows-v0.0.2-x64.zip",
                 "downloadUrl": BASE_URL + "alogin-windows-v0.0.2-x64.zip"}
        validate_promotion(newer, self.manifest)
        with self.assertRaises(ValueError):
            validate_promotion(self.manifest, newer)

    def test_version_and_notes_must_agree_before_build(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "Cargo.toml").write_text('[package]\nname="alogin-windows"\nversion="0.0.1"\n', encoding="utf-8")
            lock = root / "Cargo.lock"
            lock.write_text('[[package]]\nname="alogin-windows"\nversion="0.0.1"\n', encoding="utf-8")
            (root / "release-notes").mkdir()
            notes = root / "release-notes/0.0.1.md"
            notes.write_text("首版", encoding="utf-8")
            self.assertEqual(release_info("windows-v0.0.1", root), ("0.0.1", "首版"))
            for tag in ["v0.0.1", "windows-v0.0.2"]:
                with self.assertRaises(ValueError):
                    release_info(tag, root)
            notes.write_text(" ", encoding="utf-8")
            with self.assertRaises(ValueError):
                release_info("windows-v0.0.1", root)
            notes.write_text("首版", encoding="utf-8")
            lock.write_text('[[package]]\nname="alogin-windows"\nversion="0.1.0"\n', encoding="utf-8")
            with self.assertRaises(ValueError):
                release_info("windows-v0.0.1", root)

    def test_package_includes_notices_and_detects_corruption_before_publication(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            executable = directory / "app.exe"
            executable.write_bytes(b"MZsynthetic executable")
            crate = directory / "crate"
            crate.mkdir()
            (crate / "LICENSE-MIT").write_text("Synthetic license", encoding="utf-8")
            metadata = directory / "metadata.json"
            version = tomllib.loads((ROOT / "Cargo.toml").read_text(encoding="utf-8"))["package"]["version"]
            tag = f"windows-v{version}"
            metadata.write_text(json.dumps({"packages": [
                {"name": "alogin-windows", "version": version, "source": None},
                {"name": "sample", "version": "1.0.0", "source": "registry", "license": "MIT",
                 "manifest_path": str(crate / "Cargo.toml")},
            ]}), encoding="utf-8")
            output = directory / "release"
            package_release(tag, executable, metadata, output)
            verify_release(output)
            archive = next(output.glob("*.zip"))
            original_digest = hashlib.sha256(archive.read_bytes()).hexdigest()
            with zipfile.ZipFile(archive) as bundle:
                self.assertEqual(bundle.read("alogin-windows.exe"), executable.read_bytes())
                self.assertEqual(bundle.read("licenses/sample-1.0.0/LICENSE-MIT"), b"Synthetic license")
            package_release(tag, executable, metadata, output)
            self.assertEqual(hashlib.sha256(archive.read_bytes()).hexdigest(), original_digest)
            archive.write_bytes(archive.read_bytes() + b"tampered")
            with self.assertRaises(ValueError):
                verify_release(output)


if __name__ == "__main__":
    unittest.main()
