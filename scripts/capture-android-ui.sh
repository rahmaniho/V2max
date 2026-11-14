#!/usr/bin/env bash
set -euo pipefail

# Optional capture on a disposable emulator with fixture data only. Never run
# against a user's device: screenshots and diagnostics become CI annotations.
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
pkg=ir.v2max.app.debug
component="$pkg/ir.v2max.app.MainActivity"
adb shell am force-stop "$pkg"
adb logcat -c
adb shell am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER \
  -n "$component" > "$work/start.txt" 2>&1 || true

# am start may return success even when the activity promptly crashes. Verify
# that this activity actually remains resumed instead of capturing the launcher.
resumed=false
for _ in {1..20}; do
  adb shell dumpsys activity activities > "$work/activities.txt" 2>&1 || true
  if grep -E 'mResumedActivity|topResumedActivity' "$work/activities.txt" | grep -Fq "$component"; then
    sleep 2
    adb shell dumpsys activity activities > "$work/activities.txt" 2>&1 || true
    if grep -E 'mResumedActivity|topResumedActivity' "$work/activities.txt" | grep -Fq "$component"; then
      resumed=true
      break
    fi
  fi
  sleep 1
done
if [[ "$resumed" != true ]]; then
  adb logcat -d -v brief 'AndroidRuntime:E' 'ActivityTaskManager:W' 'ActivityManager:W' '*:S' > "$work/logcat.txt" || true
  python3 - "$work" <<'PY'
import pathlib, sys
work = pathlib.Path(sys.argv[1])
start = (work / 'start.txt').read_text(errors='replace')[-800:]
activities = (work / 'activities.txt').read_text(errors='replace')
focus = '\n'.join(line for line in activities.splitlines()
                  if 'ResumedActivity' in line or 'topResumedActivity' in line)[-600:]
logcat = (work / 'logcat.txt').read_text(errors='replace')[-1800:]
text = f'{start}\nResumed activity: {focus}\nLogcat:\n{logcat}'
text = text.replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')
print(f'::error title=Android UI launch::{text}')
PY
  exit 1
fi

adb exec-out screencap -p > "$work/home.png"
# The JDK is already installed in CI. The adaptive JPEG fits the notice cap.
java -Djava.awt.headless=true scripts/UiPreview.java "$work/home.png" "$work/home.jpg"
python3 - "$work/home.jpg" <<'PY'
import base64, hashlib, pathlib, sys
image = pathlib.Path(sys.argv[1]).read_bytes()
encoded = base64.b64encode(image).decode('ascii')
# GitHub truncates individual annotation messages at 4096 characters.
chunks = [encoded[i:i + 3800] for i in range(0, len(encoded), 3800)]
if len(chunks) > 8:
    print(f'::error title=UI capture::Preview exceeds the annotation budget ({len(image)} bytes)')
    raise SystemExit(1)
print(f'::notice title=V2MAX_UI_SHA256::{hashlib.sha256(image).hexdigest()}')
for number, chunk in enumerate(chunks):
    print(f'::notice title=V2MAX_UI_{number:03d}::{chunk}')
PY
