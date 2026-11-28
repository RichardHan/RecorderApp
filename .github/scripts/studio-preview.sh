#!/usr/bin/env bash
set -euo pipefail

package=io.github.richardhan.voicenote
mkdir -p "$RUNNER_TEMP/studio-preview"
adb install -r studio/build/outputs/apk/debug/studio-debug.apk
# This disposable Actions emulator starts the walkthrough with a fresh install.
adb shell pm clear "$package"
adb shell wm size 1080x1920
adb shell wm density 320
python3 .github/scripts/studio-meeting-preview.py
