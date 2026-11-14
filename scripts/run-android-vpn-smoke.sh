#!/usr/bin/env bash
set -euo pipefail

# Disposable fixtures only: the test VLESS server is bound on the CI runner and
# its freedom outbound redirects to a local HTTP server. No public VPN is used.
work="$(mktemp -d)"
http_pid=""
xray_pid=""
cleanup() {
  if [[ -n "$xray_pid" ]]; then kill "$xray_pid" 2>/dev/null || true; fi
  if [[ -n "$http_pid" ]]; then kill "$http_pid" 2>/dev/null || true; fi
  rm -rf "$work"
}
trap cleanup EXIT
trap 'status=$?; echo "::error title=Android VPN smoke::Command failed at script line $LINENO (exit $status)"' ERR
# Slow streaming makes it possible to verify real TUN traffic while a second
# no-TUN Xray instance is serving a download, and again after cancellation.
cat > "$work/http-fixture.py" <<'PY'
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import threading
import time

hold_started = threading.Event()
hold_release = threading.Event()

class Fixture(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path in ('/hold-status', '/hold-release'):
            if self.path == '/hold-release':
                hold_release.set()
            body = b'started' if hold_started.is_set() else b'waiting'
            self.send_response(200)
            self.send_header('Content-Length', str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        elif self.path in ('/probe', '/tun-check', '/slow-probe', '/hold-probe'):
            if self.path == '/slow-probe':
                time.sleep(2)  # leave a native post-start probe in flight during cancellation
            if self.path == '/hold-probe':
                hold_started.set()
                hold_release.wait(15)  # the test releases this only after canceling the scan
            body = b'v2max-tun-ok'
            self.send_response(200)
            self.send_header('Content-Length', str(len(body)))
            self.end_headers()
            try:
                self.wfile.write(body)
            except (BrokenPipeError, ConnectionResetError):
                pass  # the caller canceled while the slow response was pending
        elif self.path == '/speed':
            total = 1_200_000
            self.send_response(200)
            self.send_header('Content-Length', str(total))
            self.end_headers()
            try:
                while total:
                    chunk = b'x' * min(32_000, total)
                    self.wfile.write(chunk)
                    self.wfile.flush()
                    total -= len(chunk)
                    time.sleep(0.08)
            except (BrokenPipeError, ConnectionResetError):
                pass  # expected on cancellation
        else:
            self.send_error(404)

ThreadingHTTPServer(('127.0.0.1', 18081), Fixture).serve_forever()
PY
python3 "$work/http-fixture.py" > "$work/http.log" 2>&1 &
http_pid=$!
cat > "$work/xray.json" <<'JSON'
{
  "log": {"loglevel": "warning"},
  "inbounds": [{
    "listen": "0.0.0.0", "port": 18080, "protocol": "vless",
    "settings": {
      "decryption": "none",
      "clients": [
        {"id": "11111111-2222-3333-4444-555555555555"},
        {"id": "22222222-3333-4444-5555-666666666666"}
      ]
    }
  }],
  "outbounds": [{"protocol": "freedom", "settings": {
    "redirect": "127.0.0.1:18081",
    "finalRules": [{"action": "allow", "ip": ["127.0.0.1/32"]}]
  }}]
}
JSON
"$TEST_XRAY_BIN" run -config "$work/xray.json" > "$work/xray.log" 2>&1 &
xray_pid=$!

gradle --no-daemon installDebug installDebugAndroidTest
adb shell appops set ir.v2max.app.debug ACTIVATE_VPN allow
# UiAutomation.executeShellCommand uses Runtime.exec rather than a shell, and
# the Android 11 emulator's toybox does not include wget. Install a small shell
# script that issues HTTP over netcat from a UID outside the VPN application.
cat > "$work/tun-probe.sh" <<'SH'
#!/system/bin/sh
printf 'PROBE_UID=%s\n' "$(id -u)"
printf 'GET /tun-check HTTP/1.1\r\nHost: 198.51.100.1:18081\r\nConnection: close\r\n\r\n' | \
  toybox timeout 20 toybox nc -w 10 -W 10 198.51.100.1 18081 2>&1
status=$?
printf '\nNC_EXIT=%s\n' "$status"
SH
adb push "$work/tun-probe.sh" /data/local/tmp/v2max-tun-probe.sh
# Unlike the synthetic TUN-only address above, this host fixture is reachable
# directly without a VPN. It lets the test detect leakage while a stopped core
# retains the TUN, and confirm normal access resumes when that TUN is closed.
cat > "$work/direct-probe.sh" <<'SH'
#!/system/bin/sh
printf 'PROBE_UID=%s\n' "$(id -u)"
printf 'GET /tun-check HTTP/1.1\r\nHost: 10.0.2.2:18081\r\nConnection: close\r\n\r\n' | \
  toybox timeout 3 toybox nc -w 2 -W 2 10.0.2.2 18081 2>&1
status=$?
printf '\nNC_EXIT=%s\n' "$status"
SH
adb push "$work/direct-probe.sh" /data/local/tmp/v2max-direct-probe.sh
if gradle --no-daemon connectedDebugAndroidTest 2>&1 | tee "$work/android-test.log"; then
  echo 'Android VPN smoke test passed.'
  # Report all handovers together: GitHub limits annotations per step. Any
  # retry is still surfaced as a warning rather than concealed by eventual success.
  mapfile -t handovers < <(adb logcat -d -v raw -s V2maxTunTest:I '*:S' 2>/dev/null | grep 'V2MAX_TUN_READY' || true)
  if ((${#handovers[@]})); then
    handover_summary=""
    handover_level=notice
    for line in "${handovers[@]}"; do
      printf '%s\n' "$line"
      handover_summary+="${line}%0A"
      if [[ "$line" =~ attempts=([2-9]|[1-9][0-9]+)([^0-9]|$) ]]; then handover_level=warning; fi
    done
    echo "::${handover_level} title=VPN TUN handover::$handover_summary"
  fi
else
  echo '::group::Android VPN diagnostics'
  adb logcat -d -s V2maxVpn:V V2maxCore:V GoLog:V AndroidRuntime:E > "$work/logcat.txt" || true
  adb shell dumpsys connectivity > "$work/connectivity.txt" || true
  cat "$work/xray.log" "$work/http.log" "$work/logcat.txt" || true
  WORK_DIR="$work" python3 - <<'PY'
import glob, os, pathlib, xml.etree.ElementTree as ET

def annotate(title, text):
    # GitHub truncates annotation messages at 4096 characters. Keep logs'
    # newest events and reserve room for any encoding/metadata overhead.
    text = text[:3500].replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')
    print(f"::error title={title}::{text}")

for path in glob.glob('app/build/outputs/androidTest-results/**/*.xml', recursive=True):
    for case in ET.parse(path).iter('testcase'):
        for failure in case.findall('failure'):
            annotate(f"Android VPN {case.get('name')}", failure.get('message') or failure.text or '')

work = pathlib.Path(os.environ['WORK_DIR'])
for name in ('http.log', 'xray.log', 'logcat.txt'):
    contents = (work / name).read_text(errors='replace').strip()
    annotate(f'Android VPN fixture {name}', contents[-3500:] or '(empty)')
network = (work / 'connectivity.txt').read_text(errors='replace')
network = '\n'.join(line for line in network.splitlines()
                    if any(word in line.lower() for word in ('vpn', 'tun', 'uid')))[:7000]
annotate('Android VPN network state', network or '(no VPN or UID details)')
PY
  echo '::endgroup::'
  exit 1
fi

# Optional manual visual inspection. The emulator and its fixture data are
# disposable; no screenshot is captured during ordinary CI runs.
if [[ "${V2MAX_CAPTURE_UI:-false}" == true ]]; then
  bash scripts/capture-android-ui.sh
fi
