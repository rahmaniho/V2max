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
# Stop the earlier smoke process and revoke its shell-granted VPN app-op. The
# instrumentation itself asserts that VpnService.prepare still needs consent.
adb shell am force-stop "$pkg"
adb shell appops set "$pkg" ACTIVATE_VPN ignore
status=0
adb shell am instrument -w -r -e enableUiVpnTests true \
  -e class ir.v2max.app.AndroidUiConnectionTest \
  "$pkg.test/androidx.test.runner.AndroidJUnitRunner" | tr -d '\r' | tee "$work/test.log" || status=$?
if (( status != 0 )) || ! grep -Fq 'OK (2 tests)' "$work/test.log"; then
  python3 - "$work/test.log" <<'PY'
import pathlib, sys
text = pathlib.Path(sys.argv[1]).read_text(errors='replace')[-3400:]
text = text.replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')
print(f'::error title=Android UI connection smoke::{text}')
PY
  exit 1
fi
echo "::notice title=Android UI connection smoke::$pkg exercised real VPN consent, selected profiles and local TUN traffic."
