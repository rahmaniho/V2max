#!/usr/bin/env bash
# Re-sign the tested release-variant APKs without executing Gradle or network code
# with the production key. Never pass passwords as command-line arguments.
set -euo pipefail

if [[ $# != 2 ]]; then
  echo 'Usage: resign-release-apks.sh <tested-dir> <signed-dir>' >&2
  exit 1
fi
input="$1"
output="$2"
: "${V2MAX_RELEASE_KEYSTORE:?Missing keystore path}"
: "${V2MAX_RELEASE_STORE_PASSWORD:?Missing store password}"
: "${V2MAX_RELEASE_KEY_ALIAS:?Missing key alias}"
: "${V2MAX_RELEASE_KEY_PASSWORD:?Missing key password}"
[[ -f "$V2MAX_RELEASE_KEYSTORE" ]] || { echo '::error::Signing keystore not found'; exit 1; }
[[ -d "$input" ]] || { echo '::error::Tested APK directory not found'; exit 1; }
mkdir -p "$output"
[[ "$(realpath "$input")" != "$(realpath "$output")" ]] || { echo '::error::Input and output must differ'; exit 1; }
[[ -z "$(find "$output" -maxdepth 1 -name '*.apk' -print -quit)" ]] || { echo '::error::Signed output directory must be empty'; exit 1; }

sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/usr/local/lib/android/sdk}}"
apksigner="$(find "$sdk/build-tools" -type f -name apksigner | sort -V | tail -1)"
[[ -n "$apksigner" ]] || { echo '::error::Android apksigner was not found'; exit 1; }
zipalign="$(dirname "$apksigner")/zipalign"
[[ -x "$zipalign" ]] || { echo '::error::Android zipalign was not found'; exit 1; }

apks=(
  app-arm64-v8a-release.apk
  app-armeabi-v7a-release.apk
  app-universal-release.apk
  app-x86_64-release.apk
)
# Verify the exact input set before invoking the signer; no extra APK can be published.
python3 - "$input" <<'PY'
import pathlib, sys
required = {'app-arm64-v8a-release.apk', 'app-armeabi-v7a-release.apk',
            'app-universal-release.apk', 'app-x86_64-release.apk'}
paths = list(pathlib.Path(sys.argv[1]).glob('*.apk'))
if {p.name for p in paths} != required or any(p.is_symlink() or not p.is_file() for p in paths):
    sys.exit('::error::Unexpected tested APK set')
PY
for name in "${apks[@]}"; do
  "$apksigner" sign --ks "$V2MAX_RELEASE_KEYSTORE" --ks-pass env:V2MAX_RELEASE_STORE_PASSWORD \
    --ks-key-alias "$V2MAX_RELEASE_KEY_ALIAS" --key-pass env:V2MAX_RELEASE_KEY_PASSWORD \
    --v4-signing-enabled false --out "$output/$name" "$input/$name"
  "$apksigner" verify --verbose "$output/$name"
  "$zipalign" -c 4 "$output/$name"
done
python3 scripts/verify-apk-payload.py "$input" "$output"
