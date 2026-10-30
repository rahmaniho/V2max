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
printf 'v2max-tun-ok' > "$work/probe"
printf 'v2max-tun-ok' > "$work/tun-check"
python3 -m http.server 18081 --bind 127.0.0.1 --directory "$work" > "$work/http.log" 2>&1 &
http_pid=$!
cat > "$work/xray.json" <<'JSON'
{
  "log": {"loglevel": "warning"},
  "inbounds": [{
    "listen": "0.0.0.0", "port": 18080, "protocol": "vless",
    "settings": {
      "decryption": "none",
      "clients": [{"id": "11111111-2222-3333-4444-555555555555"}]
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
if gradle --no-daemon connectedDebugAndroidTest 2>&1 | tee "$work/android-test.log"; then
  echo 'Android VPN smoke test passed.'
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
