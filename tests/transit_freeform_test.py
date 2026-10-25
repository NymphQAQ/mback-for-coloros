"""Check native transit windows through Assistant; restore shortcuts, never capture QR screens."""

import json
import re
import shlex
import subprocess
import time

from activity_shortcuts_test import arc_point
from corner_touch_test import wait_menu
from main_ui_test import adb, preferences, snapshot
from preset_shortcuts_test import KEY, PRESETS, write_shortcuts


MODE_KEY = "mback_transit_direct"
ORDER = (4, 5, 0, 1, 2, 3)


def flexible_record(dump, task):
    flexible = dump.split("mFlexibleTasks:")[-1].split("DisplayType:")[0]
    return next((line for line in flexible.splitlines()
                 if re.search(r"Task\{[^\n]*#" + task + r"\b", line)
                 and "flexibleWindowScenario=true" in line), "")


def transit_window(index, prefs, cold, direct=False):
    package = "com.tencent.mm" if index == 4 else "com.eg.android.AlipayGphone"
    expected = (package + "/.plugin.appbrand.ui.AppBrandUI" if index == 4 else
                package + "/com.alipay.android.phone.xriver.bundlex.CSGAPushActivity")
    if cold:
        adb("shell", "am", "force-stop", package)
    adb("shell", "input", "keyevent", "KEYCODE_HOME")
    adb("shell", "am", "start", "-W", "--windowingMode", "1", "-a", "android.settings.SETTINGS")
    width, height = map(int, re.findall(r"(\d+)x(\d+)", adb("shell", "wm", "size"))[-1])
    density = int(re.findall(r"\d+", adb("shell", "wm", "density"))[-1]) / 160
    x, y = arc_point(ORDER.index(index) + 1, prefs, width, height, density, 7)
    time.sleep(1)
    stamp = adb("shell", "date", "+%m-%d_%H:%M:%S.000").strip().replace("_", " ")
    gesture = subprocess.Popen(["rtk", "proxy", "adb", "shell", "input", "swipe",
                                str(round(16 * density)), str(height - round(32 * density)),
                                str(x), str(y), "1600"], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    try:
        wait_menu(True)
    finally:
        gesture.communicate(timeout=10)
    assert gesture.returncode == 0
    wait_menu(False)
    deadline = time.monotonic() + 20
    page = False
    while time.monotonic() < deadline:
        dump = adb("shell", "dumpsys", "activity", "activities")
        records = [line for line in dump.splitlines() if "topResumedActivity=" in line and expected in line]
        if records:
            task = re.search(r" t(\d+)\}", records[0])[1]
            record = flexible_record(dump, task)
            native = "state=1," in record and "maximized=false" in record
            if not page:
                tree = snapshot()
                text = "\n".join(node.get("text", "") + node.get("content-desc", "") for node in tree.iter("node"))
                # Assistant's own launch also alternates between the code title and transit dashboard.
                page = (("乘车码" in text or all(word in text for word in ("公交", "地铁", "出行")))
                        if index == 4 else "出行" in text)
            if native and page:
                windows = adb("shell", "dumpsys", "window", "displays")
                focused = any("mCurrentFocus=" in line and
                              ("plugin.appbrand.ui.AppBrandUI" if index == 4 else "CSGAPushActivity") in line
                              for line in windows.splitlines())
                if not focused:
                    time.sleep(.25)
                    continue
                events = adb("shell", "logcat", "-b", "events", "-d", "-v", "time", "-T", shlex.quote(stamp))
                activity = PRESETS[index][1].split("/", 1)[1]
                bridged = any(activity in line and ("wm_create_activity" in line or "wm_new_intent" in line)
                              for line in events.splitlines())
                assert bridged != direct, "Wrong transit launch mode"
                print(PRESETS[index][0] + (" cold" if cold else " warm") + (": direct native transit window passed" if direct else ": Assistant native transit window passed"), flush=True)
                return
        time.sleep(.25)
    top = [line.strip() for line in dump.splitlines() if "topResumedActivity=" in line]
    raise AssertionError(PRESETS[index][0] + ": page=" + str(page) + "; top=" + str(top)
                         + "; native=" + (record if records else "no matching foreground activity"))


def ordinary_transit(index):
    package = "com.tencent.mm" if index == 4 else "com.eg.android.AlipayGphone"
    expected = (package + "/.plugin.appbrand.ui.AppBrandUI" if index == 4 else
                package + "/com.alipay.android.phone.xriver.bundlex.CSGAPushActivity")
    adb("shell", "am", "force-stop", package)
    adb("shell", "input", "keyevent", "KEYCODE_HOME")
    adb("shell", "su", "-c", shlex.quote("am start -W --windowingMode 1 -n " + PRESETS[index][1]))
    deadline = time.monotonic() + 15
    while time.monotonic() < deadline:
        dump = adb("shell", "dumpsys", "activity", "activities")
        records = [line for line in dump.splitlines() if "topResumedActivity=" in line and expected in line]
        if records:
            task = re.search(r" t(\d+)\}", records[0])[1]
            time.sleep(1.5)
            assert not flexible_record(adb("shell", "dumpsys", "activity", "activities"), task), (
                "An Assistant launch outside MBack was forced into a small window")
            print(PRESETS[index][0] + ": ordinary Assistant launch stays fullscreen", flush=True)
            return
        time.sleep(.25)
    raise AssertionError("Ordinary Assistant transit launch did not reach the expected activity")


def main():
    assert re.search(r"^\s+showing=false$", adb("shell", "dumpsys", "window", "policy"), re.M), "Unlock phone first"
    original = preferences()
    assert original.get("mback_corner_menu_enabled", "1") == "1", "Enable corner shortcuts first"
    assistant = adb("shell", "dumpsys", "package", "com.coloros.assistantscreen")
    state = re.search(r"User 0: [^\n]* enabled=(\d+)", assistant)[1]
    assert state in ("0", "1"), "Assistant must remain enabled"
    try:
        adb("shell", "su", "-c", shlex.quote("settings put global " + MODE_KEY + " 0"))
        write_shortcuts(json.dumps([{"id": PRESETS[index][1]} for index in ORDER]))
        for cold in (True, False):
            for index in (4, 5):
                transit_window(index, original, cold)
        for index in (4, 5):
            ordinary_transit(index)
    finally:
        adb("shell", "input", "motionevent", "CANCEL", "0", "0")
        adb("shell", "input", "keyevent", "KEYCODE_HOME")
        for package in ("com.tencent.mm", "com.eg.android.AlipayGphone"):
            adb("shell", "am", "force-stop", package)
        if KEY in original:
            write_shortcuts(original[KEY])
        else:
            adb("shell", "su", "-c", shlex.quote("settings delete global " + KEY))
        adb("shell", "su", "-c", shlex.quote("settings put global " + MODE_KEY + " " + original[MODE_KEY]
                                                    if MODE_KEY in original else "settings delete global " + MODE_KEY))
        adb("shell", "rm", "-f", "/data/local/tmp/mback-main-test.xml")
        assert preferences() == original, "Original settings were not restored"
        assistant = adb("shell", "dumpsys", "package", "com.coloros.assistantscreen")
        assert re.search(r"User 0: [^\n]* enabled=(\d+)", assistant)[1] == state, "Assistant state changed"


if __name__ == "__main__":
    main()
