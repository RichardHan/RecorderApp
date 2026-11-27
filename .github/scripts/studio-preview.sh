#!/usr/bin/env bash
set -euo pipefail

package=io.github.richardhan.voicenote
preview="$RUNNER_TEMP/studio-preview"
mkdir -p "$preview"
adb install -r studio/build/outputs/apk/debug/studio-debug.apk
adb shell am start -W -n "$package/.StudioActivity"
for attempt in {1..5}; do
  if adb shell uiautomator dump /sdcard/studio-screen.xml >/dev/null; then
    adb exec-out cat /sdcard/studio-screen.xml > "$preview/screen.xml"
    if python3 - "$preview/screen.xml" <<'PY'
import re
import subprocess
import sys
from xml.etree import ElementTree

nodes = ElementTree.parse(sys.argv[1]).iter("node")
for node in nodes:
    if node.get("text") == "Done":
        left, top, right, bottom = map(int, re.findall(r"\d+", node.get("bounds", "")))
        subprocess.run(["adb", "shell", "input", "tap", str((left+right)//2), str((top+bottom)//2)], check=True)
        break
    if node.get("text") == "Start recording":
        break
else:
    sys.exit(1)
PY
    then break; fi
  fi
  sleep 1
done
adb shell uiautomator dump /sdcard/studio-screen.xml
adb exec-out cat /sdcard/studio-screen.xml > "$preview/screen.xml"
python3 - "$preview/screen.xml" <<'PY'
import sys
from xml.etree import ElementTree

labels = {node.get("text") for node in ElementTree.parse(sys.argv[1]).iter("node")}
if "Start recording" not in labels or "Recording studio" not in labels:
    raise SystemExit("Studio recording controls did not appear")
PY
adb exec-out screencap -p > "$preview/studio.png"
