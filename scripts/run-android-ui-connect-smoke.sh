#!/usr/bin/env bash
set -euo pipefail

# Called only while run-android-vpn-smoke.sh's isolated Xray/HTTP fixture is
# alive. The test changes the emulator's VPN and notification permissions.
if [[ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" != 1 ]]; then
  echo '::error title=Android UI connection smoke::Use a disposable Android emulator, not a physical device.'
  exit 1
fi
case "${1:-debug}" in
  debug) pkg=ir.v2max.app.debug ;;
  preview) pkg=ir.v2max.app.preview ;;
  *) echo '::error::Expected debug or preview target'; exit 1 ;;
esac
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
stage='checking emulator'
trap 'code=$?; echo "::error title=Android UI connection smoke::Stage $stage failed at line $LINENO (exit $code)"' ERR
# Stop the earlier smoke process and revoke its shell-granted VPN app-op. The
# instrumentation itself asserts that VpnService.prepare still needs consent.
stage='verify installed packages'
for package in "$pkg" "$pkg.test"; do
  if ! output=$(adb shell pm path "$package" 2>&1) || [[ "$output" != package:* ]]; then
    echo "::error title=Android UI connection smoke::Required emulator package is not installed: $package ($output)"
    exit 1
  fi
done
stage='stop previous app process'
if ! output=$(adb shell am force-stop "$pkg" 2>&1); then
  echo "::error title=Android UI connection smoke::Could not stop the previous app process: $output"
  exit 1
fi
stage='revoke emulator VPN app-op'
if ! output=$(adb shell appops set "$pkg" ACTIVATE_VPN ignore 2>&1); then
  echo "::error title=Android UI connection smoke::Could not revoke the test VPN app-op: $output"
  exit 1
fi
# Revoking a runtime permission from the instrumented app itself may kill that
# process on newer Android versions. Do it before starting instrumentation.
api=$(adb shell getprop ro.build.version.sdk | tr -d '\r')
if [[ ! "$api" =~ ^[0-9]+$ ]]; then
  echo '::error title=Android UI connection smoke::Could not determine the emulator API level.'
  exit 1
fi
reset_permission() {
  local permission=$1 output
  stage="reset emulator permission $permission"
  if ! output=$(adb shell pm revoke "$pkg" "$permission" 2>&1); then
    echo "::error title=Android UI connection smoke::Could not revoke $permission: $output"
    exit 1
  fi
  # Android 30's pm shell lacks clear-permission-flags. Its UI tests run on a
  # fresh installation and still assert that the permission dialog appears.
  if (( api >= 33 )); then
    if ! output=$(adb shell pm clear-permission-flags "$pkg" "$permission" user-set user-fixed 2>&1); then
      echo "::error title=Android UI connection smoke::Could not reset flags for $permission: $output"
      exit 1
    fi
  fi
}
reset_permission android.permission.CAMERA
if (( api >= 33 )); then reset_permission android.permission.POST_NOTIFICATIONS; fi
# Include a crash buffer in diagnostics if the runner dies before JUnit can
# report a stack trace. The emulator contains only disposable fixture data.
adb logcat -b crash -c || true
stage='instrumentation'
status=0
adb shell am instrument -w -r -e enableUiVpnTests true \
  -e class ir.v2max.app.AndroidUiConnectionTest \
  "$pkg.test/androidx.test.runner.AndroidJUnitRunner" | tr -d '\r' | tee "$work/test.log" || status=$?
if (( status != 0 )) || ! grep -Fq 'OK (12 tests)' "$work/test.log"; then
  adb logcat -b crash -d -v threadtime > "$work/crash.log" 2>&1 || true
  python3 - "$work/test.log" "$work/crash.log" <<'PY'
import pathlib, re, sys
text = pathlib.Path(sys.argv[1]).read_text(errors='replace')
def annotate(title, message):
    safe = message[:3000].replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')
    print(f'::error title={title}::{safe}')
for stack in re.findall(r'(?ms)^INSTRUMENTATION_STATUS: stack=(.*?)(?=^INSTRUMENTATION_STATUS(?:_CODE)?:|\Z)', text):
    annotate('Android UI test failure', stack.strip())
crash = pathlib.Path(sys.argv[2]).read_text(errors='replace').strip()
if 'FATAL EXCEPTION' in crash:
    annotate('Android UI process crash', crash[-3000:])
annotate('Android UI connection smoke', text[-3000:])
PY
  exit 1
fi
echo "::notice title=Android UI connection smoke::$pkg exercised startup refresh, partial/unknown preservation, cancellation, subscription confirmation, VPN consent, selected profiles and local TUN traffic."
