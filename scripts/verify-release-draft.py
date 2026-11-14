#!/usr/bin/env python3
"""Fail before publishing unless the draft contains exactly the verified APKs."""
import hashlib
import json
import pathlib
import sys

REQUIRED_APKS = {
    "app-arm64-v8a-release.apk", "app-armeabi-v7a-release.apk",
    "app-universal-release.apk", "app-x86_64-release.apk",
}


def digest(path):
    sha = hashlib.sha256()
    with path.open("rb") as content:
        for chunk in iter(lambda: content.read(1024 * 1024), b""):
            sha.update(chunk)
    return "sha256:" + sha.hexdigest()


def verify(release, directory, tag):
    if release.get("tag_name") != tag or release.get("draft") is not True or release.get("prerelease") is not False:
        raise ValueError("Release is not the expected stable draft")
    paths = list(pathlib.Path(directory).glob("*.apk"))
    if {p.name for p in paths} != REQUIRED_APKS or any(p.is_symlink() or not p.is_file() for p in paths):
        raise ValueError("Unexpected local release APK set")
    assets = release.get("assets")
    if not isinstance(assets, list) or len(assets) != len(REQUIRED_APKS):
        raise ValueError("Draft does not contain exactly four assets")
    by_name = {a.get("name"): a for a in assets if isinstance(a, dict)}
    if set(by_name) != REQUIRED_APKS:
        raise ValueError("Draft has missing, duplicate, or unexpected assets")
    for path in paths:
        asset = by_name[path.name]
        if asset.get("state") != "uploaded" or asset.get("size") != path.stat().st_size or asset.get("digest") != digest(path):
            raise ValueError(f"Draft asset is incomplete or differs: {path.name}")
    print(f"Verified all uploaded asset digests for draft {tag}.")


if __name__ == "__main__":
    if len(sys.argv) != 4:
        sys.exit("Usage: verify-release-draft.py <draft.json> <signed-dir> <tag>")
    try:
        verify(json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8")), sys.argv[2], sys.argv[3])
    except (OSError, ValueError, json.JSONDecodeError) as error:
        sys.exit(f"::error title=release draft::{error}")
