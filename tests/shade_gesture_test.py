"""Run with adb connected to an unlocked ColorOS phone, both MBack features on."""

import re
import subprocess
import time
import xml.etree.ElementTree as ET


def adb(*args: str) -> str:
    return subprocess.check_output(["adb", *args], text=True, timeout=20)


def wait_shade(opened: bool) -> None:
    deadline = time.monotonic() + 5
    while time.monotonic() < deadline:
        if adb("shell", "settings", "get", "global",
               "mback_notification_shade_open").strip() == str(int(opened)):
            return
        time.sleep(0.1)
    raise AssertionError(f"Shade open flag did not become {opened}")


def navigation_window(dump: str) -> str:
    return next(line for line in dump.splitlines()
                if "name=" in line and "NavigationBar_displayId_0," in line)


def vibrations() -> set[str]:
    return {line.strip() for line in adb("shell", "dumpsys", "vibrator_manager").splitlines()
            if "| com.android.systemui " in line and "| start:" in line}


def check_panel(control_center: bool) -> None:
    wait_shade(True)
    time.sleep(0.4)
    dump = adb("shell", "dumpsys", "input")
    assert "touchableRegion=<empty>" in navigation_window(dump), (
        "MBack still captures the bottom navigation strip while the panel is open")
    assert not re.search(r"name=.*MBack Corner Shield", dump), "Corner shields remain"
    adb("shell", "uiautomator", "dump", "/data/local/tmp/mback-shade-test.xml")
    tree = ET.fromstring(adb("shell", "cat", "/data/local/tmp/mback-shade-test.xml"))
    ids = {node.get("resource-id") for node in tree.iter("node")}
    # shortcut: ColorOS separate-panel resource ID, update if the ROM renames it.
    assert ("com.android.systemui:id/oplus_qs_media_panel" in ids) == control_center, (
        "Horizontal swipe did not switch notification/control-center pages")


def main() -> None:
    for key in ("mback_xposed_enabled", "mback_corner_menu_enabled"):
        assert adb("shell", "settings", "get", "global", key).strip() == "1", key
    assert not re.search(r"^\s+showing=true$",
                         adb("shell", "dumpsys", "window", "policy"), re.M), "Unlock first"
    adb("shell", "cmd", "statusbar", "collapse")
    wait_shade(False)
    time.sleep(0.5)
    window = navigation_window(adb("shell", "dumpsys", "input"))
    frame = re.search(r"frame=\[0,(\d+)\]\[(\d+),(\d+)\]", window)
    assert frame, "Expected portrait navigation at the bottom"
    top, width, bottom = map(int, frame.groups())
    assert top > width, "Expected portrait display"
    y = top + (bottom - top) // 6
    try:
        adb("shell", "cmd", "statusbar", "expand-notifications")
        check_panel(False)
        for control_center, start, end in ((True, 0.7, 0.15), (False, 0.3, 0.85)):
            adb("shell", "input", "swipe", str(round(width * start)), str(y),
                str(round(width * end)), str(y), "350")
            check_panel(control_center)
        for panel in ("expand-notifications", "expand-settings"):
            adb("shell", "cmd", "statusbar", panel)
            check_panel(panel == "expand-settings")
            adb("shell", "input", "swipe", str(width // 2), str(bottom - 12),
                str(width // 2), str(bottom // 2), "350")
            wait_shade(False)
            time.sleep(0.5)
            dump = adb("shell", "dumpsys", "input")
            assert "touchableRegion=<empty>" not in navigation_window(dump), (
                "Tap-back region did not recover after collapse")
            for side in ("Left", "Right"):
                assert re.search(r"name=.*MBack Corner Shield " + side, dump), side
            adb("shell", "cmd", "statusbar", panel)
            wait_shade(True)
            time.sleep(0.4)
            before = vibrations()
            adb("shell", "input", "tap", str(width // 2), str(bottom - 12))
            wait_shade(False)
            time.sleep(0.2)
            level = adb("shell", "settings", "get", "global", "mback_vibration_level").strip()
            if level != "0":
                assert vibrations() - before, "Pill tap did not generate vibration"
        print("Shade gestures passed: left/right switching, bottom up swipes, recovery, pill tap/haptics")
    finally:
        adb("shell", "cmd", "statusbar", "collapse")
        adb("shell", "rm", "-f", "/data/local/tmp/mback-shade-test.xml")


if __name__ == "__main__":
    main()
