"""ColorOS native-window regression for both More entry points; leaves settings intact."""

import json
import re
import time

from activity_shortcuts_test import arc_point, image, wait_tree
from corner_touch_test import wait_menu
from main_ui_test import adb, capture, entries, nodes, preferences, snapshot, tab, tap

PACKAGE = "dev.mback.oneplus"
AFFINITY = PACKAGE + ".shortcuts"


def native_window():
    dump = adb("shell", "dumpsys", "activity", "activities")
    flexible = dump.split("mFlexibleTasks:")[-1].split("DisplayType:")[0]
    matches = re.findall(r"Task\{[^\n]*#(\d+)[^\n]*" + re.escape(AFFINITY) + r"\}[^\n]*", flexible)
    if not matches:
        return None
    assert len(matches) == 1, "Duplicate native More windows"
    task_id = matches[0]
    record = next(line for line in flexible.splitlines() if AFFINITY in line)
    assert "hasCaption=true" in record and "flexibleWindowScenario=true" in record
    scale = float(re.search(r"scale=([\d.]+)", record)[1])
    active = dump.split("Resumed activities in task display areas")[0]
    bounds = re.search(r"\* Task\{[^\n]*#" + task_id + r"\b[^\n]*\}\n\s*mBounds=Rect\((-?\d+), (-?\d+) - (-?\d+), (-?\d+)\)", active)
    assert bounds, "Native task bounds not found"
    return task_id, tuple(map(int, bounds.groups())), scale, active


def wait_native():
    for _ in range(30):
        result = native_window()
        if result:
            return result
        time.sleep(.1)
    raise AssertionError("More did not enter a system-managed small window")


def open_from_corner(prefs, count):
    width, height = image().size
    density = int(re.findall(r"\d+", adb("shell", "wm", "density"))[-1]) / 160
    x, y = arc_point(0, prefs, width, height, density, count + 1)
    start_x, start_y = round(width * .1), height - round(32 * density)
    try:
        adb("shell", "input", "motionevent", "DOWN", str(start_x), str(start_y))
        adb("shell", "input", "motionevent", "MOVE", str(start_x + 100), str(start_y - 100))
        wait_menu(True)
        adb("shell", "input", "motionevent", "MOVE", str(x), str(y))
        adb("shell", "input", "motionevent", "UP", str(x), str(y))
    finally:
        adb("shell", "input", "motionevent", "CANCEL", str(x), str(y))
    wait_menu(False)


def main():
    original = preferences()
    assert original.get("mback_corner_menu_enabled") == "1", "Enable corner shortcuts first"
    saved = original.get("mback_shortcuts_v2")
    count = len(json.loads(saved)) if saved is not None else len(original["mback_corner_menu_apps"].split(","))
    try:
        adb("shell", "am", "force-stop", PACKAGE)
        adb("shell", "am", "start", "-a", "android.settings.SETTINGS")
        open_from_corner(original, count)
        task_id, _, scale, active = wait_native()
        assert 0 < scale < 1, "Window did not receive native scaling"
        assert "mCallingUid=10241" in active, "Corner entry was not started by SystemUI"
        assert not re.search(r"Hist[^\n]*ActivityRecord[^\n]*dev\.mback\.oneplus/\.MainActivity", active)
        assert re.search(r"Task\{[^\n]*com.android.settings[^\n]*visible=true", active), (
            "The underlying application was hidden")
        wait_tree(lambda tree: nodes(tree, "预置功能"))
        capture("native-more-corner.png")
        open_from_corner(original, count)
        assert wait_native()[0] == task_id, "Repeated corner entry created another task"
        print("Native More: corner entry, system caption/scaling, underlying app and task reuse passed", flush=True)

        adb("shell", "am", "force-stop", PACKAGE)
        adb("shell", "am", "start", "-n", PACKAGE + "/.MainActivity")
        tree = wait_tree(lambda tree: nodes(tree, "快捷小窗", "content-desc"))
        tap(tab(tree, "快捷小窗"))
        tree = wait_tree(lambda tree: entries(tree, "管理快捷应用"))
        tap(entries(tree, "管理快捷应用")[0])
        wait_native()
        wait_tree(lambda tree: nodes(tree, "预置功能"))
        capture("native-more-main-entry.png")
        assert preferences() == original
        print("Native More: settings-page entry and preference preservation passed", flush=True)

        before = wait_native()
        _, (left, top, right, bottom), scale, _ = before
        x, y = round(left + (right - left) * scale) - 8, round(top + (bottom - top) * scale) - 8
        adb("shell", "input", "swipe", str(x), str(y), str(x - 80), str(y - 142), "900")
        for _ in range(30):
            resized = wait_native()
            if resized[2] < scale - .02:
                break
            time.sleep(.15)
        else:
            raise AssertionError("System resize handle did not resize More")
        _, (left, top, right, _), scale, _ = resized
        adb("shell", "input", "tap", str(round(left + (right - left) * scale * .5)), str(round(top + 76 * scale)))
        tree = wait_tree(lambda tree: nodes(tree, "关闭") and nodes(tree, "最小化") and nodes(tree, "全屏"))
        capture("native-more-controls.png")
        tap(nodes(tree, "关闭")[0])
        for _ in range(30):
            if native_window() is None:
                break
            time.sleep(.15)
        else:
            raise AssertionError("System Close did not remove More")
        print("Native More: system resize, window menu and Close passed", flush=True)
    finally:
        adb("shell", "input", "motionevent", "CANCEL", "0", "0")
        assert preferences() == original, "Native-window test changed preferences"


if __name__ == "__main__":
    main()
