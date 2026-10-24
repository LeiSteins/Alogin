"""Generate the app update manifest and prevent older deployments replacing latest."""

import argparse
import json
from pathlib import Path
import re
import shutil


VERSION_PATTERN = re.compile(
    r"(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)"
    r"(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?"
    r"(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?",
    re.ASCII,
)


def validate_manifest(manifest):
    if type(manifest.get("schemaVersion")) is not int or manifest["schemaVersion"] != 1:
        raise ValueError("Unsupported manifest schemaVersion")
    version = manifest.get("version")
    match = VERSION_PATTERN.fullmatch(version) if isinstance(version, str) else None
    if not match or any(int(part) > 2_147_483_647 for part in match.groups()[:3]):
        raise ValueError("Invalid semantic version")
    prerelease = match.group(4)
    if prerelease and any(
        part.isdigit() and len(part) > 1 and part.startswith("0")
        for part in prerelease.split(".")
    ):
        raise ValueError("Prerelease numbers cannot have leading zeros")
    code = manifest.get("versionCode")
    if type(code) is not int or not 0 < code <= 2_100_000_000:
        raise ValueError("Invalid Android versionCode")
    if manifest.get("fileName") != f"alogin-v{version}.apk":
        raise ValueError("APK name must match the release version")
    notes = manifest.get("releaseNotes")
    if not isinstance(notes, str) or not notes.strip() or len(notes.encode("utf-16-le")) > 40_000:
        raise ValueError("Release notes must contain 1 to 20000 UTF-16 code units")


def generate(tag, gradle_path, notes_dir, output_dir):
    gradle = gradle_path.read_text(encoding="utf-8")
    versions = re.findall(r'^\s*versionName\s*=\s*"([^"]+)"\s*$', gradle, re.MULTILINE)
    codes = re.findall(r"^\s*versionCode\s*=\s*(\d+)\s*$", gradle, re.MULTILINE)
    if len(versions) != 1 or len(codes) != 1 or tag != f"v{versions[0]}":
        raise ValueError("Tag must match the single Android versionName and versionCode")
    version = versions[0]
    # Validate the version before using it as a file path.
    manifest = {
        "schemaVersion": 1,
        "version": version,
        "versionCode": int(codes[0]),
        "fileName": f"alogin-v{version}.apk",
        "releaseNotes": "pending",
    }
    validate_manifest(manifest)
    notes_path = notes_dir / f"{version}.md"
    manifest["releaseNotes"] = notes_path.read_text(encoding="utf-8").strip()
    validate_manifest(manifest)
    output_dir.mkdir(parents=True, exist_ok=True)
    (output_dir / "latest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    shutil.copyfile(notes_path, output_dir / "release-notes.md")


def validate_promotion(incoming, current):
    validate_manifest(incoming)
    if current is None:
        return
    validate_manifest(current)
    if incoming["versionCode"] < current["versionCode"]:
        raise ValueError("Refusing to replace latest.json with an older versionCode")
    if incoming["versionCode"] == current["versionCode"] and incoming["version"] != current["version"]:
        raise ValueError("Different releases must have different versionCode values")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    build = commands.add_parser("generate")
    build.add_argument("--tag", required=True)
    build.add_argument("--gradle", type=Path, default=Path("app/build.gradle.kts"))
    build.add_argument("--notes-dir", type=Path, default=Path("release-notes"))
    build.add_argument("--output-dir", type=Path, required=True)
    promote = commands.add_parser("validate-promotion")
    promote.add_argument("--incoming", type=Path, required=True)
    promote.add_argument("--current", type=Path, required=True)
    args = parser.parse_args()
    if args.command == "generate":
        generate(args.tag, args.gradle, args.notes_dir, args.output_dir)
    else:
        incoming = json.loads(args.incoming.read_text(encoding="utf-8"))
        current_text = args.current.read_text(encoding="utf-8").strip()
        validate_promotion(incoming, json.loads(current_text) if current_text else None)


if __name__ == "__main__":
    main()
