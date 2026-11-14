#!/usr/bin/env bash
set -euo pipefail

# Targeted, disposable emulator checks only; this is not a release build and
# does not replace the full VPN/TUN or updater integration suites.
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
if ! gradle --no-daemon installDebug installDebugAndroidTest 2>&1 | tee "$work/build.log"; then
  grep -E '^e: |error:|FAILURE:|What went wrong|Execution failed' "$work/build.log" | head -20 | while IFS= read -r line; do
    echo "::error title=Security smoke build::$line"
  done
  exit 1
fi

pkg=ir.v2max.app.debug
runner="$pkg.test/androidx.test.runner.AndroidJUnitRunner"
api="$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
if (( api >= 33 )); then adb shell pm grant "$pkg" android.permission.POST_NOTIFICATIONS; fi
classes=ir.v2max.app.AndroidSecureBackupTest
expected=4
if [[ "${V2MAX_BACKUP_ONLY:-false}" != true ]]; then
  classes+=,ir.v2max.app.AndroidExternalControlTest
  expected=8
fi
status=0
adb shell am instrument -w -r -e class "$classes" -e expectNoDeviceCredential true "$runner" \
  | tr -d '\r' | tee "$work/tests.log" || status=$?
if (( status != 0 )) || ! grep -Fq "OK ($expected test" "$work/tests.log"; then
  python3 - "$work/tests.log" <<'PY'
import pathlib, sys
text = pathlib.Path(sys.argv[1]).read_text(errors='replace')[-3400:]
text = text.replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')
print(f'::error title=Android security smoke::{text}')
PY
  exit 1
fi
echo "Android API $api: $expected targeted security/backup tests passed (not a release build)."
if [[ "${V2MAX_CAPTURE_UI:-false}" == true ]]; then
  bash scripts/capture-android-ui.sh
fi
