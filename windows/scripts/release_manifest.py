"""Package Windows releases and protect the independent Windows download channel."""

import argparse
import hashlib
import json
from pathlib import Path
import re
import tomllib
import zipfile

ROOT = Path(__file__).resolve().parents[1]
VERSION = re.compile(r"(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)", re.ASCII)
BASE_URL = "https://aloginupdate.steins.top/windows/"


def version_parts(version):
    match = VERSION.fullmatch(version) if isinstance(version, str) else None
    if not match or any(int(part) > 65535 for part in match.groups()):
        raise ValueError("Expected a stable three-part Windows version (0..65535)")
    return tuple(int(part) for part in match.groups())


def release_info(tag, root=ROOT):
    package = tomllib.loads((root / "Cargo.toml").read_text(encoding="utf-8"))["package"]
    version = package["version"]
    version_parts(version)
    if tag != f"windows-v{version}":
        raise ValueError("Tag must match windows-v<Cargo version>")
    lock = tomllib.loads((root / "Cargo.lock").read_text(encoding="utf-8"))
    own = [p for p in lock["package"] if p["name"] == package["name"]]
    if len(own) != 1 or own[0]["version"] != version:
        raise ValueError("Cargo.lock must match Cargo.toml")
    notes = (root / "release-notes" / f"{version}.md").read_text(encoding="utf-8").strip()
    if not notes or len(notes.encode("utf-16-le")) > 40000:
        raise ValueError("Release notes must contain 1..20000 UTF-16 code units")
    return version, notes


def validate_manifest(manifest):
    if type(manifest.get("schemaVersion")) is not int or manifest["schemaVersion"] != 1:
        raise ValueError("Unsupported schemaVersion")
    version = manifest.get("version")
    version_parts(version)
    name = f"alogin-windows-v{version}-x64.zip"
    if manifest.get("platform") != "windows" or manifest.get("architecture") != "x64":
        raise ValueError("Expected Windows x64 manifest")
    if manifest.get("tag") != f"windows-v{version}" or manifest.get("fileName") != name:
        raise ValueError("Tag and fileName must match the version")
    if manifest.get("downloadUrl") != BASE_URL + name:
        raise ValueError("Download URL must use the fixed Windows channel")
    if type(manifest.get("size")) is not int or manifest["size"] <= 0:
        raise ValueError("Invalid file size")
    digest = manifest.get("sha256")
    if not isinstance(digest, str) or not re.fullmatch(r"[0-9a-f]{64}", digest):
        raise ValueError("Invalid SHA-256")
    notes = manifest.get("releaseNotes")
    if not isinstance(notes, str) or not notes.strip() or len(notes.encode("utf-16-le")) > 40000:
        raise ValueError("Invalid release notes")


def validate_promotion(incoming, current):
    validate_manifest(incoming)
    if current is None:
        return
    validate_manifest(current)
    if version_parts(incoming["version"]) < version_parts(current["version"]):
        raise ValueError("Refusing to replace a newer Windows release")
    if incoming["version"] == current["version"] and incoming != current:
        raise ValueError("A published version cannot be replaced with different content")


def third_party_files(metadata):
    """Include Cargo license declarations and shipped license/notice texts."""
    files = {}
    lines = ["# Third-party dependencies", "", "Generated from Cargo.lock and cargo metadata.",
             "Slint is used under GPL-3.0; other choices remain as declared below.",
             "Source crates are available at https://crates.io/ and upstream links.", ""]
    for package in sorted(metadata["packages"], key=lambda p: (p["name"], p["version"])):
        if package.get("source") is None:
            continue
        identity = f'{package["name"]}-{package["version"]}'
        license_name = package.get("license")
        if not license_name and not package.get("license_file"):
            raise ValueError(f"Missing license information: {identity}")
        lines.append(f'- {identity}: {license_name or "see license file"}; '
                     f'https://crates.io/crates/{package["name"]}/{package["version"]}')
        directory = Path(package["manifest_path"]).parent
        candidates = [p for p in directory.iterdir() if p.is_file() and
                      p.name.lower().startswith(("license", "licence", "copying", "notice", "copyright"))]
        if package.get("license_file"):
            candidates.append(directory / package["license_file"])
        for path in candidates:
            files[f"licenses/{identity}/{path.name}"] = path.read_bytes()
    if not files:
        raise ValueError("No third-party license files found")
    files["THIRD-PARTY-NOTICES.md"] = ("\n".join(lines) + "\n").encode("utf-8")
    return files


def package_release(tag, executable, metadata_path, output, root=ROOT):
    version, notes = release_info(tag, root)
    metadata = json.loads(metadata_path.read_text(encoding="utf-8-sig"))
    own = [p for p in metadata["packages"] if p["name"] == "alogin-windows" and p.get("source") is None]
    if len(own) != 1 or own[0]["version"] != version:
        raise ValueError("Cargo metadata must match this release")
    binary = executable.read_bytes()
    if not binary.startswith(b"MZ"):
        raise ValueError("Expected a Windows executable")
    files = third_party_files(metadata)
    files.update({
        "alogin-windows.exe": binary,
        "README.md": (root / "README.md").read_bytes(),
        "LICENSE": (root.parent / "LICENSE").read_bytes(),
        "release-notes.md": (notes + "\n").encode("utf-8"),
        "SOURCE.txt": (f"Corresponding source and build instructions:\n"
                       f"https://github.com/LeiSteins/alogin/tree/{tag}\n"
                       f"https://github.com/LeiSteins/alogin/archive/refs/tags/{tag}.zip\n"
                       "Dependency sources and exact versions: windows/Cargo.lock\n").encode("utf-8"),
    })
    output.mkdir(parents=True, exist_ok=True)
    name = f"alogin-windows-v{version}-x64.zip"
    archive = output / name
    with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as bundle:
        for path, content in sorted(files.items()):
            entry = zipfile.ZipInfo(path, (2026, 1, 1, 0, 0, 0))
            entry.compress_type = zipfile.ZIP_DEFLATED
            entry.external_attr = 0o100644 << 16
            bundle.writestr(entry, content)
    digest = hashlib.sha256(archive.read_bytes()).hexdigest()
    manifest = {"schemaVersion": 1, "platform": "windows", "architecture": "x64",
                "version": version, "tag": tag, "fileName": name,
                "downloadUrl": BASE_URL + name, "size": archive.stat().st_size,
                "sha256": digest, "releaseNotes": notes}
    validate_manifest(manifest)
    (output / "latest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (output / (name + ".sha256")).write_text(f"{digest}  {name}\n", encoding="ascii")
    (output / "release-notes.md").write_text(notes + "\n", encoding="utf-8")
    verify_release(output)


def verify_release(directory):
    manifest = json.loads((directory / "latest.json").read_text(encoding="utf-8"))
    validate_manifest(manifest)
    archive = directory / manifest["fileName"]
    if archive.stat().st_size != manifest["size"] or hashlib.sha256(archive.read_bytes()).hexdigest() != manifest["sha256"]:
        raise ValueError("Release archive size or SHA-256 mismatch")
    expected = f'{manifest["sha256"]}  {manifest["fileName"]}\n'
    if (directory / (manifest["fileName"] + ".sha256")).read_text(encoding="ascii") != expected:
        raise ValueError("Checksum file mismatch")
    if (directory / "release-notes.md").read_text(encoding="utf-8").strip() != manifest["releaseNotes"]:
        raise ValueError("Release notes mismatch")
    with zipfile.ZipFile(archive) as bundle:
        if bundle.testzip() is not None:
            raise ValueError("Corrupt archive")
        for name in ["alogin-windows.exe", "README.md", "LICENSE", "SOURCE.txt", "THIRD-PARTY-NOTICES.md", "release-notes.md"]:
            if not bundle.read(name):
                raise ValueError(f"Missing package content: {name}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    validate = commands.add_parser("validate")
    validate.add_argument("--tag", required=True)
    package = commands.add_parser("package")
    package.add_argument("--tag", required=True)
    package.add_argument("--exe", type=Path, required=True)
    package.add_argument("--metadata", type=Path, required=True)
    package.add_argument("--output-dir", type=Path, required=True)
    verify = commands.add_parser("verify")
    verify.add_argument("--release-dir", type=Path, required=True)
    promote = commands.add_parser("validate-promotion")
    promote.add_argument("--incoming", type=Path, required=True)
    promote.add_argument("--current", type=Path, required=True)
    args = parser.parse_args()
    if args.command == "validate":
        release_info(args.tag)
    elif args.command == "package":
        package_release(args.tag, args.exe, args.metadata, args.output_dir)
    elif args.command == "verify":
        verify_release(args.release_dir)
    else:
        current = args.current.read_text(encoding="utf-8").strip()
        validate_promotion(json.loads(args.incoming.read_text(encoding="utf-8")), json.loads(current) if current else None)


if __name__ == "__main__":
    main()
