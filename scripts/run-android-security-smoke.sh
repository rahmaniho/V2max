#!/usr/bin/env bash
set -euo pipefail

# Targeted, disposable emulator security and bundled-audio checks only; this
# does not replace the full VPN/TUN or updater integration suites.
if [[ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" != 1 ]]; then
  echo '::error title=Android security smoke::Use a disposable Android emulator, not a physical device.'
  exit 1
fi
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
classes=ir.v2max.app.AndroidSecureBackupTest,ir.v2max.app.AndroidStoredDataRecoveryTest,ir.v2max.app.AndroidConnectionCueTest
expected=10
if [[ "${V2MAX_BACKUP_ONLY:-false}" != true ]]; then
  classes+=,ir.v2max.app.AndroidExternalControlTest,ir.v2max.app.AndroidSubscriptionWorkerTest,ir.v2max.app.AndroidGeoAssetTest
  expected=20
fi
run_instrumentation() {
  local name="$1" test_classes="$2" count="$3"; shift 3
  local status=0
  adb shell am instrument -w -r -e class "$test_classes" "$@" "$runner" \
    | tr -d '\r' | tee "$work/$name.log" || status=$?
  if (( status != 0 )) || ! grep -Fq "OK ($count test" "$work/$name.log"; then
    python3 - "$work/$name.log" <<'PY'
import pathlib, sys
text = pathlib.Path(sys.argv[1]).read_text(errors='replace')[-3400:]
text = text.replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')
print(f'::error title=Android security smoke::{text}')
PY
    exit 1
  fi
}
run_instrumentation security "$classes" "$expected" -e expectNoDeviceCredential true -e expectUnpreparedVpn true
echo "Android API $api: $expected targeted security/backup/audio tests passed (not a release build)."
if [[ "${V2MAX_BACKUP_ONLY:-false}" != true ]]; then
  # The emulator stages its own signed debug APK, opens the real system
  # installer, then cancels; it never installs or downloads a new package.
  adb shell appops set "$pkg" REQUEST_INSTALL_PACKAGES allow
  run_instrumentation installer \
    'ir.v2max.app.AndroidUpdatePermissionTest#installSourcePermissionReflectsAndroidSetting,ir.v2max.app.AndroidInstallFlowTest' 4 \
    -e expectInstallPermission true -e allowInstallSessionTest true
fi
if [[ "${V2MAX_SELF_UPDATE_TEST:-false}" == true ]]; then
  bash scripts/run-android-self-update-smoke.sh
fi
if [[ "${V2MAX_CAPTURE_UI:-false}" == true ]]; then
  bash scripts/capture-android-ui.sh
fi
