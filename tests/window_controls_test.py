"""Native ColorOS window checks; restore preferences and never take screenshots."""

import json
import re
import shlex
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

from main_ui_test import adb, bounds, entries, nodes, preferences, screen_point, tab, tap

PACKAGE = "dev.mback.oneplus"
PREVIEW = PACKAGE + "/.WindowPreviewActivity"
AFFINITY = PACKAGE + ".windowpreview"
PREFIX = "mback_window_"
KEYS = tuple(PREFIX + part for part in (
    "position_enabled", "x_percent", "y_percent", "outside_action", "block_outside"))
XML = "/data/local/tmp/mback-window-test.xml"


def root(command: str) -> str:
    return adb("shell", "su", "-c", shlex.quote(command))


def set_value(key: str, value: int | str | None) -> None:
    assert key in KEYS
    root("settings " + ("delete" if value is None else "put") + " global "
         + key + ("" if value is None else " " + shlex.quote(str(value))))


def tree() -> ET.Element:
    adb("shell", "uiautomator", "dump", XML)
    return ET.fromstring(adb("shell", "cat", XML))


def click(label: str, attribute: str = "text") -> None:
    for attempt in range(5):
        matches = nodes(tree(), label, attribute)
        if matches:
            tap(matches[0])
            return
        if attempt == 0:
            preview = native()
            if preview and "state=1," in preview[3]:
                _, (left, top, right, bottom), scale, _ = preview
                adb("shell", "input", "tap", str(round(left + (right - left) * scale / 2)),
                    str(round(top + (bottom - top) * scale / 5)))
    raise AssertionError("Missing preview control: " + label)


def native() -> tuple[str, tuple[int, ...], float, str] | None:
    dump = adb("shell", "dumpsys", "activity", "activities")
    flexible = dump.split("mFlexibleTasks:")[-1].split("DisplayType:")[0]
    records = [line for line in flexible.splitlines() if AFFINITY in line]
    if not records:
        return None
    assert len(records) == 1, "Duplicate preview tasks"
    record = records[0]
    task = re.search(r"Task\{[^\n]*#(\d+)", record)[1]
    frame = re.search(r"\* Task\{[^\n]*#" + task + r"\b[^\n]*\}\n\s*"
                      r"mBounds=Rect\((-?\d+), (-?\d+) - (-?\d+), (-?\d+)\)", dump)
    assert frame, "Native task bounds unavailable"
    scale = float(re.search(r"scale=([\d.]+)", record)[1])
    return task, tuple(map(int, frame.groups())), scale, record


def open_preview() -> tuple[str, tuple[int, ...], float, str]:
    adb("shell", "am", "start", "-W", "--windowingMode", "100", "-n", PREVIEW)
    for _ in range(30):
        result = native()
        if result and "state=1," in result[3] and "maximized=false" in result[3]:
            time.sleep(.8)
            return result
        time.sleep(.1)
    raise AssertionError("Preview did not enter a native standard window")


def outside_point() -> tuple[int, int]:
    result = native()
    assert result
    width, height = map(int, re.findall(r"(\d+)x(\d+)", adb("shell", "wm", "size"))[-1])
    _, (left, top, right, bottom), scale, _ = result
    for x, y in ((width // 4, height * 3 // 4), (width * 3 // 4, height // 4),
                 (width // 2, height // 10), (width // 2, height * 9 // 10)):
        if not (left - 80 <= x <= left + (right - left) * scale + 80
                and top - 80 <= y <= top + (bottom - top) * scale + 80):
            return x, y
    raise AssertionError("No safe outside test point")


def ui_check() -> None:
    adb("shell", "am", "start", "-W", "--windowingMode", "1", "-n", PACKAGE + "/.MainActivity")
    tap(tab(tree(), "快捷小窗"))
    for _ in range(4):
        current = tree()
        matches = entries(current, "系统小窗设置")
        if matches:
            tap(matches[0])
            break
        width, height = map(int, re.findall(r"(\d+)x(\d+)", adb("shell", "wm", "size"))[-1])
        adb("shell", "input", "swipe", str(width // 2), str(height * 3 // 4),
            str(width // 2), str(height // 3), "300")
    else:
        raise AssertionError("Global window settings entry unavailable")
    current = tree()
    assert nodes(current, "系统小窗设置")
    assert nodes(current, "打开位置预览小窗")
    for label in ("横向位置，", "纵向位置，"):
        slider = next(node for node in current.iter("node")
                      if node.get("class") == "android.widget.SeekBar"
                      and node.get("content-desc", "").startswith(label))
        left, top, right, bottom = bounds(slider)
        adb("shell", "input", "tap", str(left + (right - left) // 3), str((top + bottom) // 2))
        time.sleep(.5)
    saved = preferences()
    assert KEYS[1] in saved and KEYS[2] in saved, "Slider changes were not auto-saved"
    adb("shell", "input", "swipe", "600", "2200", "600", "950", "350")
    current = tree()
    choices = [node for node in current.iter("node") if node.get("class") == "android.widget.RadioButton"]
    assert len(choices) == 4, "Outside-tap modes unavailable"
    for index in (1, 2, 3, 0):
        tap(choices[index])
        time.sleep(.5)
        assert preferences()[KEYS[3]] == str(index), "Mode did not auto-save"
    current = tree()
    matches = nodes(current, "拦截窗外点击")
    if not matches:
        adb("shell", "input", "swipe", "600", "2200", "600", "950", "350")
        current = tree()
    switch = next(node for node in current.iter("node")
                  if node.get("class") == "android.widget.Switch"
                  and node.get("content-desc", "").startswith("拦截窗外点击"))
    before = switch.get("checked") == "true"
    tap(switch)
    time.sleep(.5)
    assert preferences()[KEYS[4]] == str(int(not before)), "Block toggle did not auto-save"
    print("Window settings: grouped entry, real preview button, sliders and four auto-save modes passed", flush=True)


def ready_check() -> None:
    boot = adb("shell", "settings", "get", "global", "boot_count").strip()
    ready = adb("shell", "settings", "get", "global", PREFIX + "hook_ready").strip()
    assert ready == "44:" + boot, "Restart with Android framework scope before runtime checks"


def runtime_check() -> None:
    set_value(KEYS[4], 0)
    set_value(KEYS[3], 0)
    set_value(KEYS[0], 1)
    root("am start -W --windowingMode 1 -n " + PACKAGE + "/.TestActivity")
    positions = []
    sizes = []
    for x, y in ((0, 0), (50, 50), (100, 100)):
        set_value(KEYS[1], x)
        set_value(KEYS[2], y)
        result = open_preview()
        positions.append(result[1][:2])
        sizes.append((result[1][2] - result[1][0], result[1][3] - result[1][1], result[2]))
        click("关闭预览")
        time.sleep(.6)
    assert positions[0][0] < positions[1][0] < positions[2][0], positions
    assert positions[0][1] < positions[1][1] < positions[2][1], positions
    assert all(size == sizes[0] for size in sizes), sizes
    for axis in (0, 1):
        assert abs(positions[1][axis] - (positions[0][axis] + positions[2][axis]) / 2) <= 2, positions
    print("Global native policy: left/top, center, right/bottom and scale preservation passed", positions, flush=True)

    set_value(KEYS[1], 100)
    set_value(KEYS[2], 0)
    result = open_preview()
    task = result[0]
    x, y = outside_point()
    adb("shell", "input", "tap", str(x), str(y))
    time.sleep(.6)
    assert native() and native()[0] == task, "Disabled mode dismissed the window"
    set_value(KEYS[3], 1)
    adb("shell", "input", "swipe", str(x), str(y), str(x), str(y - 150), "250")
    time.sleep(.6)
    assert native() and native()[0] == task, "A swipe dismissed the window"
    click("页面计数 0")
    assert nodes(tree(), "页面计数 1"), "Inside tap failed"
    adb("shell", "input", "tap", str(x), str(y))
    time.sleep(1)
    minimized = native()
    assert not minimized or "state=1," not in minimized[3], "Outside tap did not hide the window"
    dump = adb("shell", "dumpsys", "activity", "activities")
    assert re.search(r"Task\{[^\n]*#" + task + r"\b[^\n]*" + re.escape(AFFINITY), dump), "Hide removed the task"
    set_value(KEYS[1], 0)
    set_value(KEYS[2], 100)
    restored = open_preview()
    assert restored[0] == task and nodes(tree(), "页面计数 1"), "Hide did not preserve the task/page"
    assert abs(restored[1][0] - positions[0][0]) <= 2 and abs(restored[1][1] - positions[2][1]) <= 2, (
        "Warm restore ignored the new default position", restored[1], positions)
    print("Outside mode: off, swipe exclusion, inside tap, minimize, preserved-page restore and updated warm placement passed", flush=True)
    x, y = outside_point()
    adb("shell", "input", "tap", str(x), str(y))
    time.sleep(1)
    set_value(KEYS[1], 50)
    set_value(KEYS[2], 50)
    windows = adb("shell", "dumpsys", "window", "windows")
    entry = next(part for part in re.split(r"(?=  Window #)", windows)
                 if "OplusOSZoomFloatHandleView#" + task in part.splitlines()[0])
    region = re.search(r"touchable region=SkRegion\(\((-?\d+),(-?\d+),(-?\d+),(-?\d+)\)\)", entry)
    assert region, "System floating entry has no touch region"
    left, top, right, bottom = map(int, region.groups())
    width = int(re.findall(r"(\d+)x\d+", adb("shell", "wm", "size"))[-1])
    adb("shell", "input", "tap", str((max(0, left) + min(width, right)) // 2), str((top + bottom) // 2))
    time.sleep(1.5)
    floating = native()
    assert floating and floating[0] == task and nodes(tree(), "页面计数 1"), "Floating entry lost the page"
    assert all(abs(floating[1][axis] - positions[1][axis]) <= 2 for axis in (0, 1)), (
        "Floating entry ignored the new default position", floating[1], positions)
    print("System floating-entry tap: updated default position, same task and retained page passed", flush=True)
    set_value(KEYS[3], 2)
    process = adb("shell", "pidof", PACKAGE).strip()
    x, y = outside_point()
    adb("shell", "input", "tap", str(x), str(y))
    time.sleep(1)
    assert native() is None, "Finish mode left a native window"
    dump = adb("shell", "dumpsys", "activity", "activities")
    assert not re.search(r"Task\{[^\n]*#" + task + r"\b", dump), "Finish mode retained the task"
    assert adb("shell", "pidof", PACKAGE).strip() == process, "Finish mode killed the app process"
    assert open_preview()[0] != task, "Finish mode reused a removed task"
    assert nodes(tree(), "页面计数 0"), "Finished task retained its old Activity"
    click("关闭预览")
    print("Outside finish: task removal and a fresh preview page passed", flush=True)


def block_check() -> None:
    set_value(KEYS[3], 0)
    set_value(KEYS[4], 0)
    adb("shell", "am", "force-stop", PACKAGE)
    root("am start -W --windowingMode 1 -n " + PACKAGE + "/.TestActivity")
    current = tree()
    button = nodes(current, "底部点击穿透测试 · 次数 0")[0]
    left, top, right, bottom = bounds(button)
    density = int(re.findall(r"\d+", adb("shell", "wm", "density"))[-1]) / 160
    width, height = map(int, re.findall(r"(\d+)x(\d+)", adb("shell", "wm", "size"))[-1])
    x, y = (left + right) // 2, max(top + 16, round(height - 45 * density))
    assert top < y < bottom and y < height - 32 * density, "Fixture must be outside gesture edge"
    # Shell `input tap` bypasses InputReader's filter; send through the actual touch device.
    devices = root("getevent -lp")
    panels = [part for part in re.split(r"add device \d+: ", devices)
              if 'name:     "touchpanel"' in part]
    assert len(panels) == 1 and "INPUT_PROP_DIRECT" in panels[0], "Unknown touch device"
    panel = panels[0]
    device = panel.splitlines()[0]
    assert re.fullmatch(r"/dev/input/event\d+", device)
    x_max, y_max = (int(re.search(axis + r"[^\n]*min 0, max (\d+)", panel)[1])
                    for axis in ("ABS_MT_POSITION_X", "ABS_MT_POSITION_Y"))
    assert "mCurrentRotation=ROTATION_0" in adb("shell", "dumpsys", "window", "displays"), "Use portrait for physical touch checks"

    def touch(end_y: int | None = None, duration: float = .08,
              point: tuple[int, int] | None = None, multi: bool = False) -> None:
        def send(kind: int, code: int, value: int) -> str:
            return f"sendevent {device} {kind} {code} {value}"

        start_x, start_y = point or (x, y)
        sequence = [send(3, 47, 0), send(3, 57, 4711),
                    send(3, 53, round(start_x * x_max / (width - 1))),
                    send(3, 54, round(start_y * y_max / (height - 1))),
                    send(3, 48, 8), send(3, 49, 8),
                    send(1, 330, 1), send(1, 325, 1), send(0, 0, 0),
                    f"sleep {duration}"]
        if end_y is not None:
            sequence += [send(3, 54, round(end_y * y_max / (height - 1))), send(0, 0, 0), "sleep .05"]
        if multi:
            sequence += [send(3, 47, 1), send(3, 57, 4712),
                         send(3, 53, round((start_x + 30) * x_max / (width - 1))),
                         send(3, 54, round(start_y * y_max / (height - 1))),
                         send(3, 48, 8), send(3, 49, 8), send(0, 0, 0), "sleep .06",
                         send(3, 57, -1), send(0, 0, 0), send(3, 47, 0), "sleep .05"]
        sequence += [send(3, 57, -1), send(1, 330, 0), send(1, 325, 0), send(0, 0, 0)]
        root("\n".join(sequence))
    set_value(KEYS[0], 1)
    set_value(KEYS[1], 100)
    set_value(KEYS[2], 0)

    def assert_underneath(count: int, downs: int) -> None:
        current = tree()
        actual = [(node.get("text"), node.get("content-desc")) for node in current.iter("node")
                  if "底部" in node.get("text", "")]
        assert nodes(current, "底部点击穿透测试 · 次数 " + str(count)), ("Underlying click count changed", actual)
        assert nodes(current, "底部触摸按下次数 " + str(downs), "content-desc"), "Underlying DOWN was delivered"

    def outside_tap() -> None:
        touch()
        time.sleep(1)

    # Outside behavior OFF takes precedence even when blocking is enabled.
    set_value(KEYS[4], 1)
    task = open_preview()[0]
    outside_tap()
    assert native() and native()[0] == task and "state=1," in native()[3]
    assert_underneath(1, 1)
    set_value(KEYS[4], 0)
    set_value(KEYS[3], 1)
    open_preview()
    outside_tap()
    assert not native() or "state=1," not in native()[3]
    assert_underneath(2, 2)

    set_value(KEYS[4], 1)
    open_preview()
    counter = nodes(tree(), "页面计数 0")[0]
    left, top, right, bottom = bounds(counter)
    touch(point=screen_point(counter, (left + right) // 2, (top + bottom) // 2))
    assert nodes(tree(), "页面计数 1"), "Blocking broke window input"
    outside_tap()
    assert not native() or "state=1," not in native()[3], "Blocked tap did not minimize"
    assert_underneath(2, 2)

    open_preview()
    assert nodes(tree(), "页面计数 1"), "Blocked minimize lost the page"
    for end_y, duration in ((y - 200, .1), (None, .8)):
        touch(end_y, duration)
        time.sleep(.6)
        assert native() and "state=1," in native()[3], "Drag/long press dismissed the window"
    touch(multi=True)
    time.sleep(.6)
    assert native() and "state=1," in native()[3], "Multi-touch dismissed the window"
    # Stay above the navigation bar, inside the reserved 32dp screen-edge strip.
    touch(point=(x, round(height - 28 * density)))
    time.sleep(.6)
    assert native() and "state=1," in native()[3], "Reserved edge dismissed the window"
    set_value(KEYS[3], 2)
    outside_tap()
    assert native() is None, "Blocked tap did not finish the task"
    assert_underneath(3, 3)
    # No expanded window must mean no interception, without changing the switch.
    outside_tap()
    assert_underneath(4, 4)

    open_preview()
    set_value(KEYS[4], 0)
    outside_tap()
    assert native() is None
    assert_underneath(5, 5)
    print("Physical-touch interception: OFF precedence, pass-through toggle, blocked DOWN/UP, hide/finish, retained page, inside taps, drag/long-press/multi-touch exclusion, reserved edge and no-window recovery passed", flush=True)


def main() -> None:
    assert re.search(r"^\s+showing=false$", adb("shell", "dumpsys", "window", "policy"), re.M), "Unlock phone first"
    original = preferences()
    folder = Path("artifacts/window-controls-20261026")
    folder.mkdir(parents=True, exist_ok=True)
    (folder / "before-test-settings.json").write_text(json.dumps({key: original.get(key) for key in KEYS}), encoding="utf-8")
    try:
        if "--block-only" in sys.argv:
            ready_check()
            block_check()
        else:
            ui_check()
            if "--ui-only" not in sys.argv:
                ready_check()
                runtime_check()
                block_check()
    finally:
        set_value(KEYS[3], 0)
        # This package owns the preview; never stop payment apps or unrelated tasks.
        adb("shell", "am", "force-stop", PACKAGE)
        for key in KEYS:
            set_value(key, original.get(key))
        adb("shell", "rm", "-f", XML)
        restored = preferences()
        assert all(restored.get(key) == original.get(key) for key in KEYS), "Preferences were not restored"
        assert {k: v for k, v in restored.items() if k not in KEYS} == {
            k: v for k, v in original.items() if k not in KEYS}, "Unrelated preferences changed"


if __name__ == "__main__":
    main()
