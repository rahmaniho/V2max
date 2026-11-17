#!/usr/bin/env bash
set -euo pipefail

# Static metadata inspection only. APKs are read as data, never installed or
# executed, and remain in the runner's temporary directory. These digests were
# recorded from the official GitHub release assets; a change fails closed.
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/usr/local/lib/android/sdk}}"
aapt="$(find "$sdk/build-tools" -type f -name aapt | sort -V | tail -1)"
signer="$(find "$sdk/build-tools" -type f -name apksigner | sort -V | tail -1)"
[[ -n "$aapt" && -n "$signer" ]] || { echo 'Android build tools are required' >&2; exit 1; }

inspect() {
  local label="$1" url="$2" expected="$3" apk="$work/$1.apk"
  local required_package="${4:-}" required_version="${5:-}" required_signer="${6:-}"
  curl -fL --retry 3 --connect-timeout 20 --max-time 600 -o "$apk" "$url"
  printf '%s  %s\n' "$expected" "$apk" | sha256sum --check --status
  local badging package min_sdk target_sdk signing cert libs dex
  badging="$("$aapt" dump badging "$apk")"
  package="$(printf '%s\n' "$badging" | grep -m1 '^package: ' || true)"
  min_sdk="$(printf '%s\n' "$badging" | grep -m1 '^sdkVersion:' || true)"
  target_sdk="$(printf '%s\n' "$badging" | grep -m1 '^targetSdkVersion:' || true)"
  signing="$("$signer" verify --print-certs "$apk")"
  cert="$(printf '%s\n' "$signing" | grep -m1 'certificate SHA-256 digest:' || true)"
  [[ -n "$package" && -n "$cert" ]] || { echo "APK metadata or signer missing for $label" >&2; exit 1; }
  libs="$(unzip -Z1 "$apk" | grep -E '^lib/arm64-v8a/[^/]+\.so$' | sed 's#lib/arm64-v8a/##' | paste -sd, - || true)"
  dex="$(unzip -Z1 "$apk" | grep -Ec '^classes([0-9]+)?\.dex$' || true)"
  if [[ -n "$required_package" ]]; then
    [[ "$package" == *"name='$required_package'"* && "$package" == *"versionName='$required_version'"* && -n "$libs" ]] || {
      echo "Unexpected package, version or ARM64 payload for $label" >&2; exit 1;
    }
    # Continuity with a previously inspected v2rayNG signer, not independent
    # verification of the publisher's real-world identity. Reject extra signers.
    local fingerprints
    fingerprints="$(printf '%s\n' "$signing" | grep -i 'certificate SHA-256 digest:' | awk '{print $NF}' | tr '[:upper:]' '[:lower:]' | sort -u)"
    [[ "$fingerprints" == "$required_signer" ]] || { echo "Unexpected signer for $label" >&2; exit 1; }
  fi
  # Notices are available via GitHub's check-run annotations even when raw
  # artifact downloads are unavailable; they contain no APK or user data.
  printf '::notice title=%s APK metadata::%s; %s; %s; dex=%s; native=%s; sha256=%s\n' \
    "$label" "$package" "$min_sdk" "$target_sdk" "$dex" "$libs" "$expected"
  printf '::notice title=%s APK signing::%s\n' "$label" "$cert"
}

inspect MahsaNG \
  'https://github.com/GFW-knocker/MahsaNG/releases/download/v18-%281405-7-10%29/MahsaNG_18_arm64-v8a.apk' \
  'faf3da49739d4e0c37fd0662b175a60985b10c073ee8d6dfc4858acba16228d9'
inspect v2rayNG \
  'https://github.com/2dust/v2rayNG/releases/download/2.4.0/v2rayNG_2.4.0_arm64-v8a.apk' \
  '0020b6725df7877734776598024a5db837977a6c3842dfe29bfd57485adb4688'
# Published stable ARM64 asset, separately pinned from GitHub release metadata.
# This is static inspection on a disposable CI runner, never an installation.
inspect v2rayNG-stable \
  'https://github.com/2dust/v2rayNG/releases/download/2.3.15/v2rayNG_2.3.15_arm64-v8a.apk' \
  'ec53590f042ebb3998169506164ca72eeb84de938d6ed8e888d3b10031f548e3' \
  'com.v2ray.ang' '2.3.15' 'b7dc883e7678d6c618569e48a1c8c267a134a75f8bcb763510c8bffb3b90b077'
inspect V2max-2.3.1 \
  'https://github.com/rahmaniho/V2max/releases/download/v2.3.1/app-arm64-v8a-release.apk' \
  'a67215b306f99ce3d15b270d1421778daccd7e16d2982e2fa4e5905898f497ca'
