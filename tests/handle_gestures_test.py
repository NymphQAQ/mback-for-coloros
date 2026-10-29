"""PLK110 regression: native bar hit area, resize and repeated hold-to-move.

Runs only MBack's preview task. Pass the intended ADB serial explicitly.
"""
import argparse
import json
import re
import shlex
import subprocess
import time

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--serial", required=True)
parser.add_argument("--adb", default="adb")
args = parser.parse_args()


def adb(*parts):
    return subprocess.check_output([args.adb, "-s", args.serial, *parts], text=True, timeout=30)


def setting(namespace, key, value=None, write=False):
    if not write:
        return adb("shell", "settings", "get", namespace, key).strip()
    command = ["settings", "delete" if value == "null" else "put", namespace, key]
    if value != "null":
        command.append(value)
    adb("shell", "su", "-c", shlex.quote(shlex.join(command)))


def state():
    dump = adb("shell", "dumpsys", "activity", "activities")
    records = [line for line in dump.split("mFlexibleTasks:")[-1].split("DisplayType:")[0].splitlines()
               if "dev.mback.oneplus.windowpreview" in line]
    assert len(records) == 1 and "state=1," in records[0], "Own preview is not a normal native window"
    record = records[0]
    task = re.search(r"#(\d+)", record)[1]
    frame = re.search(r"\* Task\{[^\n]*#" + task + r"\b[^\n]*\}\n\s*"
                      r"mBounds=Rect\((-?\d+), (-?\d+) - (-?\d+), (-?\d+)\)", dump)
    assert frame, "Preview task bounds unavailable"
    return tuple(map(int, frame.groups())), float(re.search(r"scale=([\d.]+)", record)[1])


def gesture(command, offset, dx, dy, duration):
    before = state()
    (left, top, right, bottom), scale = before
    x = round(left + (right - left) * scale / 2)
    y = round(top + (bottom - top) * scale + offset)
    adb("shell", "input", command, str(x), str(y), str(x + dx), str(y + dy), str(duration))
    time.sleep(.9)
    return before, state()


assert adb("shell", "getprop", "ro.product.model").strip() == "PLK110", "Wrong phone"
boot = setting("global", "boot_count")
assert setting("global", "mback_window_hook_ready") == "44:" + boot, "Restart to load revision 44 first"
keys = [("secure", "long_press_timeout"), ("global", "mback_window_handle_hold_move"),
        ("global", "mback_window_handle_double_tap"), ("global", "mback_window_handle_vibration"),
        ("global", "mback_window_block_outside"), ("global", "mback_window_outside_action")]
saved = {(ns, key): setting(ns, key) for ns, key in keys}
results = []
try:
    # input draganddrop waits the system timeout; it must exceed MBack's 500ms hold.
    for (ns, key), value in zip(keys, ["650", "1", "0", "8", "1", "3"]):
        setting(ns, key, value, True)
    adb("shell", "am", "start", "-W", "--windowingMode", "100", "-n",
        "dev.mback.oneplus/.WindowPreviewActivity")
    time.sleep(1.2)
    for offset in (100, -40, -20, 8):
        dy = -150 if state()[1] > .72 else 150
        before, after = gesture("swipe", offset, 0, dy, 350)
        assert abs(after[1] - before[1]) > .02, ("Resize missed", offset, before, after)
        results.append({"gesture": "resize", "offset": offset, "before": before, "after": after})
        for repeat in range(2):
            before, after = gesture("draganddrop", offset, -65 if repeat else 65, 110, 1200)
            assert abs(after[1] - before[1]) < .002, ("Hold resized instead of moving", offset, before, after)
            assert max(abs(after[0][i] - before[0][i]) for i in (0, 1)) > 25, (
                "Hold move missed", offset, before, after)
            results.append({"gesture": "hold", "offset": offset, "before": before, "after": after})
    print(json.dumps(results, indent=2))
    print("Passed: four bar positions (including the new lower region), native resizing, repeated holds without changing scale")
finally:
    for (ns, key), value in saved.items():
        setting(ns, key, value, True)
