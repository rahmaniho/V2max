#!/usr/bin/env python3
"""Verify the isolated preview APK without using any production signing secret."""
import importlib.util
import os
import pathlib
import re
import shlex
import subprocess
import sys

PREVIEW_PACKAGE = "ir.v2max.app.preview"
PREVIEW_LABEL = "V2max آزمایشی"
REQUIRED_ABIS = {"arm64-v8a", "armeabi-v7a", "x86_64"}
# Public certificate from v2.3.1. A preview must not use the production signer.
OFFICIAL_CERT = "ed6a7075f48f21c91b17fa02ff3bcbdace6cc2bb0053c794ec93af473e94691b"


def verify_badging(badging, version, code):
    first = badging.splitlines()[0] if badging else ""
    package = re.match(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", first)
    if not package or package.groups() != (PREVIEW_PACKAGE, str(code), f"{version}-preview"):
        raise ValueError("Preview APK has the wrong package or version")
    if re.search(r"(?m)^application-debuggable(?:\s|$)", badging):
        raise ValueError("Preview APK must not be debuggable")
    if f"application-label:'{PREVIEW_LABEL}'" not in badging:
        raise ValueError("Preview APK lacks its distinct test-only label")
    abi_line = next((line for line in badging.splitlines() if line.startswith("native-code:")), "")
    abis = set(shlex.split(abi_line.removeprefix("native-code:"))) if abi_line else set()
    if not REQUIRED_ABIS.issubset(abis):
        raise ValueError("Preview APK is not universal for the supported ABIs")


def verify_signer(output):
    # Android build-tools versions use both "Signer #1" and "V2 Signer"
    # prefixes; some report the same certificate once per signature scheme.
    matches = re.findall(r"(?im)^.*?certificate SHA-256 digest:\s*([0-9a-f:]+)\s*$", output)
    digests = {match.replace(":", "").lower() for match in matches}
    signer_counts = re.findall(r"(?im)^Number of signers:\s*(\d+)\s*$", output)
    if len(digests) != 1 or any(count != "1" for count in signer_counts):
        raise ValueError("Preview APK must have exactly one distinct readable signer")
    digest = next(iter(digests))
    if not re.fullmatch(r"[0-9a-f]{64}", digest) or digest == OFFICIAL_CERT:
        raise ValueError("Preview signer is invalid or matches the official signer")
    return digest


def build_tools():
    sdk = pathlib.Path(os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") or "/usr/local/lib/android/sdk")
    candidates = list((sdk / "build-tools").glob("*/aapt"))
    if not candidates:
        raise ValueError("Android build tools not found")
    def version_key(path):
        return tuple(int(part) if part.isdigit() else -1 for part in path.parent.name.split("."))
    aapt = max(candidates, key=version_key)
    signer = aapt.parent / "apksigner"
    if not signer.is_file():
        raise ValueError("Android apksigner not found")
    return aapt, signer


def main():
    if len(sys.argv) != 2:
        raise ValueError("Usage: verify-preview-apk.py <preview-universal.apk>")
    apk = pathlib.Path(sys.argv[1])
    if apk.is_symlink() or not apk.is_file() or apk.stat().st_size < 1_000_000:
        raise ValueError("Preview APK is missing or unexpectedly small")
    spec = importlib.util.spec_from_file_location("release_metadata", pathlib.Path(__file__).with_name("verify-release-metadata.py"))
    metadata = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(metadata)
    version, code = metadata.gradle_version("app/build.gradle.kts")
    aapt, signer = build_tools()
    badging = subprocess.check_output([str(aapt), "dump", "badging", str(apk)], text=True)
    verify_badging(badging, version, code)
    signing = subprocess.check_output([str(signer), "verify", "--verbose", "--print-certs", str(apk)], text=True)
    fingerprint = verify_signer(signing)
    print(f"Verified isolated preview: {PREVIEW_PACKAGE}, {version}-preview, code {code}, non-debuggable")
    print(f"Preview public signer SHA-256: {fingerprint}")
    if os.environ.get("GITHUB_OUTPUT"):
        with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as output:
            output.write(f"version={version}\ncode={code}\ncertificate_sha256={fingerprint}\n")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        sys.exit(f"::error title=preview APK::{error}")
