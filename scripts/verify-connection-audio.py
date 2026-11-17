#!/usr/bin/env python3
"""Ensure each built APK contains the exact, uncompressed connection recording."""
import hashlib
import pathlib
import sys
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
RECORDING = ROOT / "app/src/main/res/raw/vpn_connected.mp3"


def verify_apk(apk, recording):
    apk = pathlib.Path(apk)
    expected = hashlib.sha256(recording).digest()
    matches = []
    seen = set()
    with zipfile.ZipFile(apk) as archive:
        for entry in archive.infolist():
            if entry.filename in seen:
                raise ValueError(f"duplicate APK entry: {entry.filename}")
            seen.add(entry.filename)
            # R8 may rename resources, so identify the asset by size and bytes,
            # not by the original resource filename.
            if entry.is_dir() or not entry.filename.startswith("res/") or entry.file_size != len(recording):
                continue
            if hashlib.sha256(archive.read(entry)).digest() == expected:
                if entry.compress_type != zipfile.ZIP_STORED:
                    raise ValueError(f"connection recording is compressed in {apk.name}")
                matches.append(entry.filename)
    if len(matches) != 1:
        raise ValueError(f"expected exactly one connection recording in {apk.name}, found {len(matches)}")
    print(f"Verified uncompressed connection recording in {apk.name}")


def main():
    if len(sys.argv) < 2:
        raise ValueError("Usage: verify-connection-audio.py <apk> [apk ...]")
    recording = RECORDING.read_bytes()
    if len(recording) < 1_000 or not recording.startswith(b"ID3"):
        raise ValueError("bundled connection recording is missing or invalid")
    for path in sys.argv[1:]:
        verify_apk(path, recording)


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, zipfile.BadZipFile) as error:
        sys.exit(f"::error title=Connection audio APK::{error}")
