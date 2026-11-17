#!/usr/bin/env bash
set -euo pipefail

# Invoked by run-android-vpn-smoke.sh while its local VLESS/HTTP fixture is alive.
# Never install or exercise this fixture on a physical device.
if [[ "${V2MAX_PREVIEW_SMOKE:-false}" != true ]] || [[ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" != 1 ]]; then
  echo '::error title=Preview smoke::Requires explicit opt-in and a disposable emulator.'
  exit 1
fi
: "${RUNNER_TEMP:?RUNNER_TEMP is required for the tested APK checksum}"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
trap 'status=$?; echo "::error title=Preview smoke::Command failed at script line $LINENO (exit $status)"' ERR

mapfile -t apks < <(find app/build/outputs/apk/preview -maxdepth 1 -type f -name '*universal*preview.apk')
mapfile -t tests < <(find app/build/outputs/apk/androidTest/preview -maxdepth 1 -type f -name '*androidTest.apk')
[[ "${#apks[@]}" == 1 && "${#tests[@]}" == 1 ]] || {
  echo '::error title=Preview smoke::Expected one universal preview APK and one instrumentation APK.'
  exit 1
}
python3 scripts/verify-preview-apk.py "${apks[0]}"
python3 scripts/verify-connection-audio.py "${apks[0]}"
sha256sum "${apks[0]}" > "$RUNNER_TEMP/v2max-preview-tested.sha256"

# Prove side-by-side installation against the exact previously published APK.
curl -fL --retry 3 -o "$work/official.apk" \
  'https://github.com/rahmaniho/V2max/releases/download/v2.3.1/app-x86_64-release.apk'
printf '%s  %s\n' '5c3e5bb06e64f31d8c310f69ede6b898dde36e81642804cb0da38a17e95f9872' "$work/official.apk" | sha256sum --check
adb shell am force-stop ir.v2max.app.debug || true
adb shell appops set ir.v2max.app.debug ACTIVATE_VPN ignore || true
adb install "$work/official.apk"
adb install "${apks[0]}"
adb install "${tests[0]}"
adb shell pm path ir.v2max.app
adb shell pm path ir.v2max.app.preview
adb shell appops set ir.v2max.app.preview ACTIVATE_VPN allow

status=0
adb shell am instrument -w -r -e class ir.v2max.app.AndroidPreviewSmokeTest \
  ir.v2max.app.preview.test/androidx.test.runner.AndroidJUnitRunner | tr -d '\r' | tee "$work/test.log" || status=$?
if (( status != 0 )) || ! grep -Fq 'OK (1 test' "$work/test.log"; then
  python3 - "$work/test.log" <<'PY'
import pathlib, sys
text = pathlib.Path(sys.argv[1]).read_text(errors='replace')[-3400:]
text = text.replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')
print(f'::error title=Preview smoke::{text}')
PY
  exit 1
fi
# Revoke the shell-granted app-op and exercise the installed preview through
# its real UI and Android's first-time consent dialog, not just SmartConnect.
bash scripts/run-android-ui-connect-smoke.sh preview
sha256sum --check "$RUNNER_TEMP/v2max-preview-tested.sha256"
echo '::notice title=Preview smoke::Non-debuggable preview coexisted with v2.3.1 and passed local TUN and UI consent tests.'
