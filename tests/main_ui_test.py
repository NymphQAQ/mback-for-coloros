"""Connected-device regression for compact homes and grouped settings; restores preferences."""

import re
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path


def adb(*args: str) -> str:
    return subprocess.check_output(["adb", *args], text=True, timeout=20)


def snapshot() -> ET.Element:
    adb("shell", "uiautomator", "dump", "/data/local/tmp/mback-main-test.xml")
    return ET.fromstring(adb("shell", "cat", "/data/local/tmp/mback-main-test.xml"))


def nodes(tree: ET.Element, label: str, attribute: str = "text") -> list[ET.Element]:
    return [n for n in tree.iter("node") if n.get(attribute) == label]


def entries(tree: ET.Element, label: str) -> list[ET.Element]:
    return [n for n in tree.iter("node") if n.get("clickable") == "true"
            and n.get("content-desc", "").startswith(label + "，")]


def bounds(node: ET.Element) -> list[int]:
    return list(map(int, re.findall(r"\d+", node.get("bounds", ""))))


def screen_point(node: ET.Element, x: int, y: int) -> tuple[int, int]:
    # ColorOS reports accessibility bounds before its native task-surface scale.
    dump = adb("shell", "dumpsys", "activity", "activities")
    focus = re.search(r"mFocusedApp=ActivityRecord\{[^\n]* u\d+ ([^/ ]+)/[^ ]+ t(\d+)\}", dump)
    if not focus or node.get("package") != focus[1]:
        return x, y
    flexible = dump.split("mFlexibleTasks:")[-1].split("DisplayType:")[0]
    record = re.search(r"Task\{[^\n]*#" + focus[2] + r"\b[^\n]*\}[^\n]*scale=([\d.]+)", flexible)
    frame = re.search(r"\* Task\{[^\n]*#" + focus[2] + r"\b[^\n]*\}\n\s*mBounds=Rect\((-?\d+), (-?\d+) -", dump)
    if not record or not frame:
        return x, y
    scale = float(record[1])
    left, top = map(int, frame.groups())
    return round(left + (x - left) * scale), round(top + (y - top) * scale)


def tap(node: ET.Element, vertical_fraction: float = 0.5) -> None:
    left, top, right, bottom = bounds(node)
    x, y = screen_point(node, (left + right) // 2, round(top + (bottom - top) * vertical_fraction))
    adb("shell", "input", "tap", str(x), str(y))


def tab(tree: ET.Element, name: str) -> ET.Element:
    return next(n for n in nodes(tree, name, "content-desc")
                if n.get("class") == "android.widget.Button")


def preferences() -> dict[str, str]:
    # Shade visibility is live SystemUI state, not a preference to restore.
    return dict(line.split("=", 1) for line in adb("shell", "settings", "list", "global").splitlines()
                if line.startswith("mback_") and not line.startswith("mback_notification_shade_open="))


def wait_value(key: str, value: str) -> None:
    for _ in range(30):
        if adb("shell", "settings", "get", "global", key).strip() == value:
            return
        time.sleep(0.1)
    raise AssertionError(f"{key} did not save {value}")


def capture(name: str) -> None:
    folder = Path("app/build/local/ui")
    folder.mkdir(exist_ok=True)
    (folder / name).write_bytes(subprocess.check_output(
        ["adb", "exec-out", "screencap", "-p"], timeout=15))


def main() -> None:
    original = preferences()
    key = "mback_scale_percent"
    adb("shell", "am", "force-stop", "dev.mback.oneplus")
    adb("shell", "am", "start", "-n", "dev.mback.oneplus/.MainActivity")
    try:
        tree = snapshot()
        assert tab(tree, "MBack").get("selected") == "true"
        assert tab(tree, "快捷小窗").get("selected") == "false"
        _, _, _, height = bounds(next(tree.iter("node")))
        system_navigation = re.search(
            r"type=navigationBars frame=\[0,(\d+)\]\[\d+,\d+\] visible=true",
            adb("shell", "dumpsys", "window", "displays"))
        assert system_navigation, "Visible system navigation bar not found"
        navigation_top = int(system_navigation[1])
        navigation = [bounds(tab(tree, name)) for name in ("MBack", "快捷小窗")]
        assert all(abs(rect[3] - navigation_top) <= 1 for rect in navigation), (
            "Extra gap between the tabs and the system navigation bar")
        for name in ("快捷小窗", "MBack"):
            tap(tab(tree, name), 0.75)
            tree = snapshot()
            assert tab(tree, name).get("selected") == "true", "Tab label tap was intercepted"
        enabled_key = "mback_xposed_enabled"
        initial = original.get(enabled_key, "0")
        changed = "0" if initial == "1" else "1"
        toggle = next(n for n in tree.iter("node") if n.get("class") == "android.widget.Switch"
                      and n.get("content-desc", "").startswith("轻触返回，"))
        tap(toggle)
        wait_value(enabled_key, changed)
        tree = snapshot()
        assert nodes(tree, "已开启" if changed == "1" else "未开启"), "Status badge is stale"
        toggle = next(n for n in tree.iter("node") if n.get("class") == "android.widget.Switch"
                      and n.get("content-desc", "").startswith("轻触返回，"))
        tap(toggle)
        wait_value(enabled_key, initial)
        time.sleep(2.5)
        capture("mback-home.png")
        tree = snapshot()
        assert not any(n.get("class") == "android.widget.SeekBar" for n in tree.iter("node")), (
            "MBack home still contains expanded tuning controls")
        tap(entries(tree, "动作反馈")[0])
        tree = snapshot()
        assert not nodes(tree, "MBack", "content-desc"), "Home navigation overlaps the detail page"
        slider = next(n for n in tree.iter("node")
                      if n.get("class") == "android.widget.SeekBar"
                      and n.get("content-desc", "").startswith("缩放比例，"))
        tap(slider)
        wait_value(key, "100")
        adb("shell", "input", "keyevent", "4")
        tree = snapshot()
        assert "缩放 100%" in entries(tree, "动作反馈")[0].get("content-desc", ""), "Home summary is stale"
        tap(entries(tree, "触觉反馈")[0])
        tree = snapshot()
        assert nodes(tree, "试听当前震动"), "Vibration preview was lost"
        tap(nodes(tree, "返回主页", "content-desc")[0])
        tree = snapshot()
        tap(tab(tree, "快捷小窗"))
        tree = snapshot()
        assert tab(tree, "快捷小窗").get("selected") == "true"
        assert not any(n.get("class") == "android.widget.SeekBar" for n in tree.iter("node")), (
            "Shortcut home still contains expanded tuning controls")
        labels = ("管理快捷应用", "外圈设置", "内圈设置", "呼出与交互", "圆弧布局", "图标与触感")
        assert all(entries(tree, label) for label in labels), "Grouped home entries unavailable"
        capture("shortcuts-home.png")
        assert [bounds(tab(tree, n)) for n in ("MBack", "快捷小窗")] == navigation
        for label in labels[1:]:
            tap(entries(tree, label)[0])
            tree = snapshot()
            assert nodes(tree, label), label + " title missing"
            assert any(n.get("class") == "android.widget.SeekBar" for n in tree.iter("node")), (
                label + " controls missing")
            adb("shell", "input", "keyevent", "4")
            tree = snapshot()
            assert tab(tree, "快捷小窗").get("selected") == "true", "Back left the selected home"
        tap(tab(tree, "MBack"))
        tree = snapshot()
        assert "缩放 100%" in entries(tree, "动作反馈")[0].get("content-desc", ""), (
            "Saved scale not restored after switching tabs")
        tap(tab(tree, "快捷小窗"))
        tree = snapshot()
        tap(entries(tree, "管理快捷应用")[0])
        for _ in range(20):
            activities = adb("shell", "dumpsys", "activity", "activities")
            if re.search(r"topResumedActivity=.*dev.mback.oneplus/.MoreAppsActivity", activities):
                break
            time.sleep(0.1)
        else:
            raise AssertionError("App management did not open")
        adb("shell", "input", "keyevent", "4")
        tree = snapshot()
        assert tab(tree, "快捷小窗").get("selected") == "true", "Wrong page after returning"
        adb("shell", "am", "force-stop", "dev.mback.oneplus")
        adb("shell", "am", "start", "-n", "dev.mback.oneplus/.MainActivity")
        tree = snapshot()
        assert "缩放 100%" in entries(tree, "动作反馈")[0].get("content-desc", ""), "Saved scale lost on app restart"
        print("Main UI passed: compact homes, flush navigation, grouped controls, Back, summaries, saving, native app management")
    finally:
        adb("shell", "am", "force-stop", "dev.mback.oneplus")
        for changed_key in (key, "mback_xposed_enabled"):
            command = ("settings put global " + changed_key + " " + original[changed_key]
                       if changed_key in original else "settings delete global " + changed_key)
            adb("shell", "su", "-c", command)
        adb("shell", "am", "start", "-n", "dev.mback.oneplus/.MainActivity")
        assert preferences() == original, "Unexpected preference changes"
        adb("shell", "rm", "-f", "/data/local/tmp/mback-main-test.xml")


if __name__ == "__main__":
    main()
