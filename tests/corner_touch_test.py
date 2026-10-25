"""Connected-device regression: app controls above both bottom corners stay touchable."""

import re
import subprocess
import time

from main_ui_test import adb, bounds, entries, nodes, preferences, snapshot, tap


def wait_menu(opened: bool) -> None:
    deadline = time.monotonic() + 2
    while time.monotonic() < deadline:
        if ("MBack Flyme Shortcut" in adb("shell", "dumpsys", "input")) == opened:
            return
        time.sleep(.05)
    raise AssertionError(f"Menu visibility did not become {opened}")


def check_swipe(x: int, y: int, width: int, height: int, expect_menu: bool,
                duration_ms: int = 2000) -> None:
    process = subprocess.Popen(["adb", "shell", "input", "swipe", str(x), str(y),
                                str(width // 2), str(round(height * .40)), str(duration_ms)],
                               stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, text=True)
    opened = False
    try:
        while process.poll() is None:
            opened |= "MBack Flyme Shortcut" in adb("shell", "dumpsys", "input")
            time.sleep(.05)
    finally:
        _, error = process.communicate(timeout=10)
    assert process.returncode == 0, error
    assert opened == expect_menu, f"Wrong corner menu response for swipe from {x},{y}"
    wait_menu(False)


def check_cancel(x: int, y: int, width: int, height: int) -> None:
    inward = 30 if x < width // 2 else -30
    try:
        adb("shell", "input", "motionevent", "DOWN", str(x), str(y))
        adb("shell", "input", "motionevent", "MOVE", str(x + inward), str(y - 30))
        wait_menu(True)
    finally:
        adb("shell", "input", "motionevent", "CANCEL", str(x + inward), str(y - 30))
    wait_menu(False)
    check_swipe(x, y, width, height, True)


def main() -> None:
    original = preferences()
    assert original.get("mback_corner_menu_enabled") == "1", "Enable corner shortcuts first"
    adb("shell", "am", "force-stop", "dev.mback.oneplus")
    adb("shell", "am", "start", "-n", "dev.mback.oneplus/.MainActivity")
    try:
        tree = snapshot()
        _, _, width, height = bounds(next(tree.iter("node")))
        for _ in range(5):
            entry = entries(tree, "打开返回测试页")
            if entry:
                tap(entry[0])
                break
            adb("shell", "input", "swipe", "30", str(round(height * .78)),
                "30", str(round(height * .30)), "350")
            tree = snapshot()
        else:
            raise AssertionError("Return test page entry unavailable")
        tree = snapshot()
        button = next(n for n in tree.iter("node")
                      if n.get("text", "").startswith("底部点击穿透测试 · 次数 "))
        top = bounds(button)[1]
        navigation = re.search(r"type=navigationBars frame=\[0,(\d+)\]\[\d+,\d+\] visible=true",
                               adb("shell", "dumpsys", "window", "displays"))
        assert navigation, "System navigation inset not found"
        navigation_top = int(navigation[1])
        density = int(re.findall(r"\d+", adb("shell", "wm", "density"))[-1]) / 160
        count = 0
        for name, x in (("left", round(width * .10)), ("right", round(width * .90))):
            for y in (top + 20, navigation_top - 20):
                adb("shell", "input", "tap", str(x), str(y))
                count += 1
                tree = snapshot()
                assert nodes(tree, f"底部点击穿透测试 · 次数 {count}"), (
                    f"{name} bottom-corner app control did not receive tap at {x},{y}")
            check_swipe(x, min(top + 20, height - round(64 * density)), width, height, False)
            check_swipe(x, height - 12, width, height, True)
            for duration in (400, 700, 400):
                check_swipe(x, height - round(32 * density), width, height, True, duration)
            check_cancel(x, height - round(32 * density), width, height)
            tree = snapshot()
            assert nodes(tree, f"底部点击穿透测试 · 次数 {count}"), "Corner gesture clicked the app underneath"
        print("Corner touch passed: app taps, repeated 32dp/bottom diagonals, release/cancel recovery, no app click-through")
    finally:
        adb("shell", "am", "force-stop", "dev.mback.oneplus")
        adb("shell", "am", "start", "-n", "dev.mback.oneplus/.MainActivity")
        assert preferences() == original, "Unexpected preference changes"
        adb("shell", "rm", "-f", "/data/local/tmp/mback-main-test.xml")


if __name__ == "__main__":
    main()
