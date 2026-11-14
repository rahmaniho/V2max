#!/usr/bin/env bash
set -euo pipefail

# This deliberately replaces the disposable emulator's debug APK with the same
# signed APK. Never run it against a user's phone or a non-debug installation.
if [[ "${V2MAX_SELF_UPDATE_TEST:-false}" != true ]] || [[ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" != 1 ]]; then
  echo '::error title=Self-update smoke::Requires explicit opt-in and a disposable emulator.'
  exit 1
fi
pkg=ir.v2max.app.debug
runner="$pkg.test/androidx.test.runner.AndroidJUnitRunner"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

fail() {
  local reason="$1"
  adb shell uiautomator dump /sdcard/v2max-self-update.xml > /dev/null 2>&1 || true
  adb exec-out cat /sdcard/v2max-self-update.xml > "$work/window.xml" 2>/dev/null || true
  adb logcat -d -v brief 'PackageInstaller:W' 'AndroidRuntime:E' '*:S' > "$work/logcat.txt" || true
  python3 - "$reason" "$work" <<'PY'
import pathlib, sys, xml.etree.ElementTree as ET
reason, work = sys.argv[1], pathlib.Path(sys.argv[2])
try:
    root = ET.parse(work / 'window.xml').getroot()
    visible = ' | '.join(n.get('text', '') for n in root.iter('node') if n.get('text'))[-1200:]
except Exception:
    visible = '(window unavailable)'
logcat = (work / 'logcat.txt').read_text(errors='replace')[-1400:]
text = f'{reason}\nVisible: {visible}\nLogcat:\n{logcat}'
text = text.replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')
print(f'::error title=Self-update smoke::{text}')
PY
  exit 1
}

run_instrumentation() {
  local name="$1" method="$2" flag="$3" status=0
  adb shell am instrument -w -r -e class "ir.v2max.app.AndroidSelfUpdateFixtureTest#$method" \
    -e "$flag" true "$runner" | tr -d '\r' | tee "$work/$name.log" || status=$?
  if (( status != 0 )) || ! grep -Fq 'OK (1 test' "$work/$name.log"; then
    fail "$name instrumentation failed: $(tail -c 900 "$work/$name.log")"
  fi
}

install_time() {
  adb shell dumpsys package "$pkg" | tr -d '\r' | python3 -c \
    'import re,sys; m=re.search(r"lastUpdateTime=([^\n]+)",sys.stdin.read()); print(m.group(1).strip() if m else "")'
}
before="$(install_time)"
[[ -n "$before" ]] || fail 'The initial package update time was unavailable.'
run_instrumentation stage stageForExternalConfirmation stageSelfUpdateFixture

# A normal background process kill does not remove notifications. Avoid force
# stop: Android intentionally removes their PendingIntents on a force stop.
adb shell am kill "$pkg"
sleep 1
pid="$(adb shell pidof "$pkg" 2>/dev/null | tr -d '\r' || true)"
[[ -z "$pid" ]] || fail 'The fixture process did not exit before the notification was opened.'
adb shell cmd statusbar expand-notifications

# Locate and tap only the expected notification or the system installer button;
# do not blindly tap coordinates or accept an unrelated Android dialog.
tap_when_visible() {
  local kind="$1" xy
  for _ in {1..12}; do
    adb shell uiautomator dump /sdcard/v2max-self-update.xml > /dev/null 2>&1 || true
    adb exec-out cat /sdcard/v2max-self-update.xml > "$work/window.xml" 2>/dev/null || true
    xy="$(python3 - "$kind" "$work/window.xml" <<'PY' 2>/dev/null || true
import re, sys, xml.etree.ElementTree as ET
kind, path = sys.argv[1:]
root = ET.parse(path).getroot()
nodes = list(root.iter('node'))
texts = ' '.join(n.get('text', '') for n in nodes).lower()
for node in nodes:
    text = node.get('text', '').strip()
    package = node.get('package', '')
    if kind == 'notification':
        match = package == 'com.android.systemui' and text == 'تأیید نصب V2max'
    else:
        match = (('packageinstaller' in package or 'permissioncontroller' in package)
                 and 'v2max' in texts and text.lower() in ('update', 'install', 'به‌روزرسانی', 'نصب'))
    bounds = re.fullmatch(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', node.get('bounds', ''))
    if match and bounds:
        x1, y1, x2, y2 = map(int, bounds.groups())
        print((x1 + x2) // 2, (y1 + y2) // 2)
        break
PY
)"
    if [[ -n "$xy" ]]; then
      adb shell input tap $xy
      return 0
    fi
    sleep 1
  done
  return 1
}
tap_when_visible notification || fail 'The installer notification was not available after process death.'
tap_when_visible install || fail 'The expected Android update confirmation was not visible.'

changed=false
for _ in {1..60}; do
  after="$(install_time)"
  if [[ -n "$after" && "$after" != "$before" ]]; then changed=true; break; fi
  sleep 1
done
[[ "$changed" == true ]] || fail 'The debug package was not replaced after confirmation.'
run_instrumentation verify verifyAfterReplacement verifySelfUpdateFixture
api="$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
echo "::notice title=Android self-update::API $api replaced the same-signed debug APK after opening its notification with the app process stopped; fixture data and signature were preserved."
