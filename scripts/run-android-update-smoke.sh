#!/usr/bin/env bash
set -euo pipefail

# A disposable Android 13+ emulator verifies both denied and granted runtime
# notification permission, plus archive identity/signature checks.
gradle --no-daemon installDebug installDebugAndroidTest
pkg=ir.v2max.app.debug
runner="$pkg.test/androidx.test.runner.AndroidJUnitRunner"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

run_instrumentation() {
  local name="$1" classes="$2" expected="$3"; shift 3
  local status=0
  adb shell am instrument -w -r -e class "$classes" "$@" "$runner" | tr -d '\r' | tee "$work/$name.log" || status=$?
  if (( status != 0 )) || ! grep -Fq "OK ($expected test" "$work/$name.log"; then
    python3 - "$work/$name.log" <<'PY'
import pathlib, sys
text = pathlib.Path(sys.argv[1]).read_text(errors='replace')[-3400:]
text = text.replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')
print(f'::error title=Android update smoke::{text}')
PY
    exit 1
  fi
}

adb shell pm revoke "$pkg" android.permission.POST_NOTIFICATIONS
run_instrumentation denied \
  'ir.v2max.app.AndroidUpdatePermissionTest#updateNotificationRespectsDeniedPermission,ir.v2max.app.AndroidInstallFlowTest#pendingConfirmationSurvivesLaunchFailureAndOldSessionResults' 2 \
  -e expectNotificationDenied true

adb shell pm grant "$pkg" android.permission.POST_NOTIFICATIONS
adb shell appops set "$pkg" REQUEST_INSTALL_PACKAGES ignore
run_instrumentation install-permission-denied \
  'ir.v2max.app.AndroidUpdatePermissionTest#installSourcePermissionReflectsAndroidSetting' 1 \
  -e expectInstallPermission false
adb shell appops set "$pkg" REQUEST_INSTALL_PACKAGES allow
run_instrumentation install-permission-granted \
  'ir.v2max.app.AndroidUpdatePermissionTest#installSourcePermissionReflectsAndroidSetting' 1 \
  -e expectInstallPermission true
run_instrumentation installer 'ir.v2max.app.AndroidInstallFlowTest' 3 \
  -e allowInstallSessionTest true
run_instrumentation granted \
  'ir.v2max.app.AndroidVpnSmokeTest#updateNoticeIsPostedAndPendingVersionIsPersisted,ir.v2max.app.AndroidVpnSmokeTest#updaterAcceptsOnlyTheInstalledPackageIdentity' 2

# Exercise the actual Android crypto provider and exported entry points on the
# latest target SDK as well as on the API 30 VPN emulator. No VPN fixture is needed.
run_instrumentation backup 'ir.v2max.app.AndroidSecureBackupTest' 4
run_instrumentation external 'ir.v2max.app.AndroidExternalControlTest' 4 \
  -e expectNoDeviceCredential true

echo 'Android 13+ update, backup, app-lock and external-control checks passed.'
