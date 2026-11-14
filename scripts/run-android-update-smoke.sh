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
  adb shell am instrument -w -r -e class "$classes" "$@" "$runner" | tr -d '\r' | tee "$work/$name.log"
  if ! grep -Fq "OK ($expected test" "$work/$name.log"; then
    echo "::error title=Android update smoke::$name did not pass $expected test(s)"
    exit 1
  fi
}

adb shell pm revoke "$pkg" android.permission.POST_NOTIFICATIONS
run_instrumentation denied \
  'ir.v2max.app.AndroidUpdatePermissionTest#updateNotificationRespectsDeniedPermission' 1 \
  -e expectNotificationDenied true

adb shell pm grant "$pkg" android.permission.POST_NOTIFICATIONS
run_instrumentation granted \
  'ir.v2max.app.AndroidVpnSmokeTest#updateNoticeIsPostedAndPendingVersionIsPersisted,ir.v2max.app.AndroidVpnSmokeTest#updaterAcceptsOnlyTheInstalledPackageIdentity' 2

echo 'Android 13+ update permission and signer checks passed.'
