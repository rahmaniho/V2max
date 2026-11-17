#!/usr/bin/env python3
"""Verify stable release asset order, URLs and digests against the signed APKs."""
import hashlib
import json
import pathlib
import sys

UNIVERSAL_APK = "app-universal-release.apk"
LEGACY_UNIVERSAL_APK = "00-V2max-universal.apk"
REQUIRED_APKS = {
    "app-arm64-v8a-release.apk", "app-armeabi-v7a-release.apk",
    UNIVERSAL_APK, "app-x86_64-release.apk",
}
# Keep the established filenames for links and future releases. The additional
# alias must be an exact copy of the signed canonical universal APK.
PUBLISHED_APKS = {name: name for name in REQUIRED_APKS} | {LEGACY_UNIVERSAL_APK: UNIVERSAL_APK}


def digest(path):
    sha = hashlib.sha256()
    with path.open("rb") as content:
        for chunk in iter(lambda: content.read(1024 * 1024), b""):
            sha.update(chunk)
    return "sha256:" + sha.hexdigest()


def verify(release, directory, tag, draft=True):
    if not isinstance(release, dict):
        raise ValueError("Expected a release object")
    if release.get("tag_name") != tag or release.get("draft") is not draft or release.get("prerelease") is not False:
        raise ValueError("Release is not the expected stable draft or publication")
    directory = pathlib.Path(directory)
    paths = list(directory.glob("*.apk"))
    if {p.name for p in paths} != REQUIRED_APKS or any(p.is_symlink() or not p.is_file() for p in paths):
        raise ValueError("Unexpected local release APK set")
    assets = release.get("assets")
    if not isinstance(assets, list) or len(assets) != len(PUBLISHED_APKS):
        raise ValueError("Draft does not contain the four APKs and universal compatibility alias")
    # The v2.3.1 updater chooses the first APK in this API array without
    # considering ABI. Refuse publication unless that file is our verified
    # universal alias. All entries are APKs by the exact-set check below.
    if not isinstance(assets[0], dict) or assets[0].get("name") != LEGACY_UNIVERSAL_APK:
        raise ValueError("Universal compatibility APK must be first for the v2.3.1 updater")
    by_name = {a.get("name"): a for a in assets if isinstance(a, dict)}
    if set(by_name) != set(PUBLISHED_APKS):
        raise ValueError("Draft has missing, duplicate, or unexpected assets")
    for published_name, local_name in PUBLISHED_APKS.items():
        path = directory / local_name
        asset = by_name[published_name]
        expected_url = f"https://github.com/rahmaniho/V2max/releases/download/{tag}/{published_name}"
        # GitHub uses an untagged-* URL for drafts even if the real tag exists.
        # Once published, v2.3.1 consumes this URL directly, so require the
        # official release URL rather than trusting an arbitrary redirect.
        if (asset.get("state") != "uploaded" or asset.get("size") != path.stat().st_size
                or asset.get("digest") != digest(path)
                or (not draft and asset.get("browser_download_url") != expected_url)):
            raise ValueError(f"Release asset is incomplete or differs: {published_name}")
    state = "draft" if draft else "published release"
    print(f"Verified asset order and uploaded digests for {state} {tag}.")


if __name__ == "__main__":
    if len(sys.argv) not in (4, 5) or (len(sys.argv) == 5 and sys.argv[4] not in ("draft", "published")):
        sys.exit("Usage: verify-release-draft.py <release.json> <signed-dir> <tag> [draft|published]")
    try:
        verify(json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8")), sys.argv[2], sys.argv[3],
               draft=len(sys.argv) == 4 or sys.argv[4] == "draft")
    except (OSError, ValueError, json.JSONDecodeError) as error:
        sys.exit(f"::error title=release draft::{error}")
