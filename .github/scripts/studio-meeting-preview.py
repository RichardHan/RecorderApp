"""Exercise the installed APK on the disposable GitHub Actions emulator."""
import os
import re
import subprocess
import time
from pathlib import Path
from xml.etree import ElementTree

PACKAGE = "io.github.richardhan.voicenote"
PREVIEW = Path(os.environ["RUNNER_TEMP"]) / "studio-preview"


def adb(*args, data=None):
    return subprocess.run(["adb", *args], input=data, stdout=subprocess.PIPE, check=True).stdout


def launch():
    adb("shell", "am", "start", "-W", "-n", PACKAGE + "/.StudioActivity")


def nodes():
    adb("shell", "uiautomator", "dump", "/sdcard/studio-screen.xml")
    return list(ElementTree.fromstring(adb("exec-out", "cat", "/sdcard/studio-screen.xml")).iter("node"))


def find(label, scroll=False):
    labels = []
    for attempt in range(6):
        tree = nodes()
        labels = [n.get("text") for n in tree if n.get("text")]
        for node in tree:
            if node.get("text") == label:
                return node
        if scroll and attempt > 0:
            adb("shell", "input", "swipe", "490", "1530", "490", "580", "450")
        time.sleep(0.4)
    raise AssertionError(f"Missing {label!r}. Visible labels: {labels}")


def tap(label, scroll=False):
    node = find(label, scroll)
    left, top, right, bottom = map(int, re.findall(r"\d+", node.get("bounds")))
    adb("shell", "input", "tap", str((left + right) // 2), str((top + bottom) // 2))


def screenshot(name):
    (PREVIEW / name).write_bytes(adb("exec-out", "screencap", "-p"))


launch()
tap("Done")
find("Start recording")
find("AI meeting tools")
screenshot("studio.png")
tap("AI meeting tools")
find("AI settings")
find("Bilingual transcript", scroll=True)

# Seed a public audio fixture and synthetic text for an offline user-level check.
adb("shell", "am", "force-stop", PACKAGE)
adb("shell", "run-as", PACKAGE, "mkdir", "-p", "files/audio")
for name, body in (
    ("Review.wav", (Path(os.environ["RUNNER_TEMP"]) / "studio-model-check/speech.wav").read_bytes()),
    ("Review.txt", b"The group agreed to review the design on Friday."),
):
    adb("exec-in", "run-as", PACKAGE, "tee", "files/audio/" + name, data=body)

launch()
tap("AI meeting tools")
tap("Bilingual transcript")
find("English")
screenshot("bilingual.png")
tap("Action items")
tap("New task", scroll=True)
adb("shell", "input", "text", "Review%sthe%sdesign")
# Hide the Android input method before tapping the checklist action.
adb("shell", "input", "keyevent", "KEYCODE_BACK")
tap("Add to checklist", scroll=True)
find("Review the design", scroll=True)
screenshot("tasks.png")
adb("shell", "am", "force-stop", PACKAGE)
launch()
tap("AI meeting tools")
tap("Action items")
find("Review the design", scroll=True)
