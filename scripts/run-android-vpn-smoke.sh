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
import time

class Fixture(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path in ('/probe', '/tun-check', '/slow-probe'):
            if self.path == '/slow-probe':
                time.sleep(2)  # leave a native post-start probe in flight during cancellation
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
if gradle --no-daemon connectedDebugAndroidTest 2>&1 | tee "$work/android-test.log"; then
  echo 'Android VPN smoke test passed.'
  # Report, rather than conceal, any TUN handover retry after a new interface.
  while IFS= read -r line; do
    [[ -z "$line" ]] && continue
    if [[ "$line" =~ attempts=([2-9]|[1-9][0-9]+) ]]; then
      echo "::warning title=VPN TUN handover::$line"
    else
      echo "::notice title=VPN TUN handover::$line"
    fi
  done < <(adb logcat -d -v raw -s V2maxTunTest:I '*:S' 2>/dev/null | grep 'V2MAX_TUN_READY' || true)
else
  echo '::group::Android VPN diagnostics'
  adb logcat -d -s V2maxVpn:V V2maxCore:V GoLog:V AndroidRuntime:E > "$work/logcat.txt" || true
  adb shell dumpsys connectivity > "$work/connectivity.txt" || true
  cat "$work/xray.log" "$work/http.log" "$work/logcat.txt" || true
  WORK_DIR="$work" python3 - <<'PY'
import glob, os, pathlib, xml.etree.ElementTree as ET

def annotate(title, text):
    text = text[:8000].replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')
    print(f"::error title={title}::{text}")

for path in glob.glob('app/build/outputs/androidTest-results/**/*.xml', recursive=True):
    for case in ET.parse(path).iter('testcase'):
        for failure in case.findall('failure'):
            annotate(f"Android VPN {case.get('name')}", failure.get('message') or failure.text or '')

work = pathlib.Path(os.environ['WORK_DIR'])
for name in ('http.log', 'xray.log', 'logcat.txt'):
    contents = (work / name).read_text(errors='replace').strip()
    annotate(f'Android VPN fixture {name}', contents[-7000:] or '(empty)')
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
  adb shell am force-stop ir.v2max.app.debug
  adb shell am start -W -n ir.v2max.app.debug/ir.v2max.app.MainActivity
  python3 -c 'import time; time.sleep(5)'
  adb exec-out screencap -p > "$work/home.png"
  preview="$work/home.png"
  if command -v convert >/dev/null 2>&1; then
    convert "$work/home.png" -resize 480x -quality 65 "$work/home.jpg"
    preview="$work/home.jpg"
  elif python3 -c 'import PIL' >/dev/null 2>&1; then
    python3 - "$work/home.png" "$work/home.jpg" <<'PY'
from PIL import Image
import sys
with Image.open(sys.argv[1]) as image:
    image = image.convert('RGB')
    image.thumbnail((480, 960))
    image.save(sys.argv[2], format='JPEG', quality=65, optimize=True)
PY
    preview="$work/home.jpg"
  fi
  # Small notices can be read through the GitHub check API if artifact storage
  # is inaccessible from a developer's environment. No real user data is used.
  python3 - "$preview" <<'PY'
import base64, hashlib, pathlib, sys
image = pathlib.Path(sys.argv[1]).read_bytes()
encoded = base64.b64encode(image).decode('ascii')
# GitHub truncates individual annotation messages at 4096 characters.
chunks = [encoded[i:i + 3800] for i in range(0, len(encoded), 3800)]
if len(chunks) > 45:
    print(f'::error title=UI capture::Preview is too large ({len(image)} bytes)')
    raise SystemExit(1)
print(f'::notice title=V2MAX_UI_SHA256::{hashlib.sha256(image).hexdigest()}')
for number, chunk in enumerate(chunks):
    print(f'::notice title=V2MAX_UI_{number:03d}::{chunk}')
PY
fi
