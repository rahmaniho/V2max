#!/usr/bin/env python3
"""Check package/version identity across release APKs and a previous release."""
import os
import pathlib
import re
import shlex
import subprocess
import sys

REQUIRED_APKS = {
    "app-arm64-v8a-release.apk", "app-armeabi-v7a-release.apk",
    "app-universal-release.apk", "app-x86_64-release.apk",
}
PACKAGE = "ir.v2max.app"
VERSION = re.compile(r"(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)")
REQUIRED_ABIS = {
    "app-arm64-v8a-release.apk": {"arm64-v8a"},
    "app-armeabi-v7a-release.apk": {"armeabi-v7a"},
    "app-universal-release.apk": {"arm64-v8a", "armeabi-v7a", "x86_64"},
    "app-x86_64-release.apk": {"x86_64"},
}


def gradle_version(path):
    source = pathlib.Path(path).read_text(encoding="utf-8")
    names = re.findall(r'^\s*versionName\s*=\s*"([^"]+)"\s*$', source, re.MULTILINE)
    codes = re.findall(r'^\s*val\s+appVersionCode\s*=\s*(\d+)\s*$', source, re.MULTILINE)
    if len(names) != 1 or len(codes) != 1 or not VERSION.fullmatch(names[0]):
        raise ValueError("release versionName/versionCode is missing or ambiguous")
    code = int(codes[0])
    if not 22 < code <= 2_100_000_000:
        raise ValueError("release versionCode must exceed v2.3.1 and fit Android's limit")
    return names[0], code


def read_badging(aapt, apk):
    text = subprocess.check_output([str(aapt), "dump", "badging", str(apk)], text=True)
    first = text.splitlines()[0] if text else ""
    match = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", first)
    if not match:
        raise ValueError(f"Could not read Android package identity: {apk.name}")
    return match.group(1), int(match.group(2)), match.group(3)


def native_abis(badging):
    lines = [line for line in badging.splitlines() if line.startswith("native-code:")]
    if len(lines) != 1:
        raise ValueError("APK has missing or ambiguous native-code badging")
    abis = set(shlex.split(lines[0].removeprefix("native-code:")))
    if not abis:
        raise ValueError("APK has no native architectures")
    return abis


def verify_abis(directory, read):
    directory = pathlib.Path(directory)
    for name, required in REQUIRED_ABIS.items():
        if not required.issubset(read(directory / name)):
            raise ValueError(f"APK is missing required native architectures: {name}")
    print("Verified native architectures in the universal and split APKs")


def verify(directory, version, code, read, previous=None):
    directory = pathlib.Path(directory)
    paths = list(directory.glob("*.apk"))
    if {p.name for p in paths} != REQUIRED_APKS or any(p.is_symlink() or not p.is_file() for p in paths):
        raise ValueError("Unexpected release APK set")
    for path in paths:
        if read(path) != (PACKAGE, code, version):
            raise ValueError(f"Package/version mismatch: {path.name}")
    if previous is not None:
        previous_package, previous_code, previous_version = read(pathlib.Path(previous))
        if previous_package != PACKAGE or not VERSION.fullmatch(previous_version):
            raise ValueError("Previous release has an unexpected package or version")
        if code <= previous_code or tuple(map(int, version.split('.'))) <= tuple(map(int, previous_version.split('.'))):
            raise ValueError("Release must increase both versionCode and versionName over the latest published APK")
    print(f"Verified {len(paths)} APK identities: {PACKAGE}, version {version}, code {code}")


def main():
    if len(sys.argv) not in (2, 3):
        sys.exit("Usage: verify-release-metadata.py <signed-dir> [previous-release.apk]")
    sdk = pathlib.Path(os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") or "/usr/local/lib/android/sdk")
    candidates = list((sdk / "build-tools").glob("*/aapt"))
    if not candidates:
        raise ValueError("Android aapt not found")
    def version_key(path):
        return tuple(int(part) if part.isdigit() else -1 for part in path.parent.name.split('.'))
    aapt = max(candidates, key=version_key)
    version, code = gradle_version("app/build.gradle.kts")
    verify(sys.argv[1], version, code, lambda path: read_badging(aapt, path), sys.argv[2] if len(sys.argv) == 3 else None)
    verify_abis(sys.argv[1], lambda path: native_abis(subprocess.check_output(
        [str(aapt), "dump", "badging", str(path)], text=True)))
    if os.environ.get("GITHUB_OUTPUT"):
        with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as output:
            output.write(f"version={version}\ncode={code}\n")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        sys.exit(f"::error title=release metadata::{error}")
