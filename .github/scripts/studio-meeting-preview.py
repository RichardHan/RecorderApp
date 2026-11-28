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


def enter_text(label, value):
    tap(label, scroll=True)
    for _ in range(3):
        for _ in range(6):
            field = next((node for node in nodes() if node.get("class") == "android.widget.EditText"
                          and node.get("focused") == "true"), None)
            if field is not None:
                break
            time.sleep(0.25)
        else:
            raise AssertionError(f"Input for {label!r} did not receive focus")

        # Replace the full draft if a delayed Android IME consumed part of a prior attempt.
        adb("shell", "input", "keycombination", "KEYCODE_CTRL_LEFT", "KEYCODE_A")
        adb("shell", "input", "keyevent", "KEYCODE_DEL")
        adb("shell", "input", "text", value.replace(" ", "%s"))
        if any(node.get("text") == value and node.get("focused") == "true" for node in nodes()):
            return
    raise AssertionError(f"The focused input did not contain {value!r}")


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

# Generate a bilingual result through the app's real client and a loopback-only mock.
adb("shell", "am", "force-stop", PACKAGE)
adb("install", "-r", "studio/build/outputs/apk/androidTest/debug/studio-debug-androidTest.apk")
out = adb("shell", "am", "instrument", "-w", "-e", "class",
          PACKAGE + ".LocalServiceTest#meetingToolsSendOnlyTextAndKeepValidatedResultsPerRecording",
          "-e", "previewFixture", "true", PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner")
if b"OK (1 test)" not in out:
    raise AssertionError(out.decode(errors="replace"))

launch()
tap("AI meeting tools")
tap("Bilingual transcript")
tap("繁體中文")
find("已同意計畫。", scroll=True)
find("寄出草稿。", scroll=True)
screenshot("bilingual.png")
adb("shell", "am", "force-stop", PACKAGE)
launch()
tap("AI meeting tools")
tap("Action items")
enter_text("New task", "Review the design")
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

adb("shell", "am", "force-stop", PACKAGE)
launch()
tap("AI meeting tools")
tap("Decisions")
enter_text("Add a decision manually", "Approve the pilot")
adb("shell", "input", "keyevent", "KEYCODE_BACK")
tap("Done", scroll=True)
find("Approve the pilot", scroll=True)
adb("shell", "am", "force-stop", PACKAGE)
launch()
tap("AI meeting tools")
tap("Decisions")
find("Approve the pilot", scroll=True)
screenshot("decisions.png")
