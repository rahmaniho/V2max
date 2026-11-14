#!/usr/bin/env python3
"""Check a preview draft or published release and its uploaded APK digest."""
import hashlib
import json
import pathlib
import re
import sys

ASSET_NAME = "V2max-PREVIEW-universal.apk"
TAG = re.compile(r"v(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)-preview\.[1-9]\d*\.[1-9]\d*")


def digest(path):
    sha = hashlib.sha256()
    with path.open("rb") as content:
        for chunk in iter(lambda: content.read(1024 * 1024), b""):
            sha.update(chunk)
    return "sha256:" + sha.hexdigest()


def verify(release, apk, tag, draft):
    apk = pathlib.Path(apk)
    if not TAG.fullmatch(tag):
        raise ValueError("Invalid isolated preview tag")
    if release.get("tag_name") != tag or release.get("draft") is not draft or release.get("prerelease") is not True:
        raise ValueError("Release is not the expected preview draft/publication")
    if apk.name != ASSET_NAME or apk.is_symlink() or not apk.is_file():
        raise ValueError("Unexpected local preview asset")
    assets = release.get("assets")
    if not isinstance(assets, list) or len(assets) != 1 or not isinstance(assets[0], dict):
        raise ValueError("Preview must contain exactly one APK")
    asset = assets[0]
    if (asset.get("name") != ASSET_NAME or asset.get("state") != "uploaded" or
            asset.get("size") != apk.stat().st_size or asset.get("digest") != digest(apk)):
        raise ValueError("Uploaded preview asset is incomplete or differs from the tested APK")
    print(f"Verified {'draft' if draft else 'published'} prerelease {tag} and its APK digest.")


def main():
    if len(sys.argv) != 5 or sys.argv[4] not in ("draft", "published"):
        raise ValueError("Usage: verify-preview-release.py <release.json> <preview.apk> <tag> <draft|published>")
    verify(json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8")), sys.argv[2], sys.argv[3], sys.argv[4] == "draft")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, json.JSONDecodeError) as error:
        sys.exit(f"::error title=preview release::{error}")
