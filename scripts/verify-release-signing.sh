#!/usr/bin/env bash
set -euo pipefail

# Do not publish an APK that cannot update the already released application.
# The known v2.3.1 ARM64 APK is used only as public certificate data; its hash
# is pinned so a replaced release asset cannot silently change the signer.
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/usr/local/lib/android/sdk}}"
apksigner="$(find "$sdk/build-tools" -type f -name apksigner | sort -V | tail -1)"
[[ -n "$apksigner" ]] || { echo '::error::Android apksigner was not found'; exit 1; }
curl -fL --retry 3 --connect-timeout 20 --max-time 600 \
  -o "$work/reference.apk" \
  'https://github.com/rahmaniho/V2max/releases/download/v2.3.1/app-arm64-v8a-release.apk'
printf '%s  %s\n' 'a67215b306f99ce3d15b270d1421778daccd7e16d2982e2fa4e5905898f497ca' "$work/reference.apk" | sha256sum --check

signers() {
  "$apksigner" verify --print-certs "$1" |
    sed -nE 's/^.*certificate SHA-256 digest: ([0-9a-fA-F]+)$/\1/p' |
    tr '[:upper:]' '[:lower:]' | sort -u
}
reference="$(signers "$work/reference.apk")"
[[ -n "$reference" ]] || { echo '::error::Could not read the reference APK signer'; exit 1; }
shopt -s nullglob
apks=(app/build/outputs/apk/release/*.apk)
(( ${#apks[@]} > 0 )) || { echo '::error::No release APKs were produced'; exit 1; }
for apk in "${apks[@]}"; do
  actual="$(signers "$apk")"
  if [[ -z "$actual" || "$actual" != "$reference" ]]; then
    echo "::error title=release signing::Signer mismatch for $apk; refusing to publish an incompatible update"
    exit 1
  fi
done
echo "Verified ${#apks[@]} APK(s) against the v2.3.1 release signer."
