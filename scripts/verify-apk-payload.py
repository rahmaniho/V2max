#!/usr/bin/env python3
"""Ensure re-signing changed only APK signatures, not the tested payload."""
import hashlib
import pathlib
import re
import sys
import zipfile

REQUIRED_APKS = {
    "app-arm64-v8a-release.apk",
    "app-armeabi-v7a-release.apk",
    "app-universal-release.apk",
    "app-x86_64-release.apk",
}
SIGNATURE = re.compile(r"^META-INF/[^/]+\.(?:SF|RSA|DSA|EC)$", re.IGNORECASE)


def payload(path):
    result = {}
    seen = set()
    with zipfile.ZipFile(path) as archive:
        for entry in archive.infolist():
            # Duplicate names are ambiguous to ZIP consumers, including Android.
            if entry.filename in seen:
                raise ValueError(f"duplicate ZIP entry in {path.name}: {entry.filename}")
            seen.add(entry.filename)
            if entry.is_dir() or entry.filename.upper() == "META-INF/MANIFEST.MF" or SIGNATURE.fullmatch(entry.filename):
                continue
            digest = hashlib.sha256()
            with archive.open(entry) as content:
                for chunk in iter(lambda: content.read(1024 * 1024), b""):
                    digest.update(chunk)
            result[entry.filename] = (entry.compress_type, entry.file_size, digest.hexdigest())
    return result


def verify(before, after):
    before, after = pathlib.Path(before), pathlib.Path(after)
    for directory in (before, after):
        paths = list(directory.glob("*.apk"))
        actual = {p.name for p in paths}
        if actual != REQUIRED_APKS or any(p.is_symlink() or not p.is_file() for p in paths):
            raise ValueError(f"unexpected APK set in {directory}: {sorted(actual)}")
    for name in sorted(REQUIRED_APKS):
        source, signed = payload(before / name), payload(after / name)
        if source != signed:
            different = sorted(key for key in source.keys() | signed.keys() if source.get(key) != signed.get(key))
            raise ValueError(f"re-signing changed {name}: {different[:10]}")
        print(f"Verified unchanged APK payload: {name} ({len(source)} entries)")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit("Usage: verify-apk-payload.py <tested-dir> <signed-dir>")
    try:
        verify(sys.argv[1], sys.argv[2])
    except (OSError, ValueError, zipfile.BadZipFile) as error:
        sys.exit(f"::error title=release payload::{error}")
