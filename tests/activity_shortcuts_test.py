"""ColorOS device regression for activity selection and icons; restores saved shortcuts.

Run with Python + Pillow, an unlocked phone, enabled corner menu, and the built APK.
The image-picker steps target ColorOS File Manager; no existing images are selected.
"""

import io
import json
import re
import shlex
import subprocess
import tempfile
import time
from pathlib import Path

from PIL import Image, ImageCms

from corner_touch_test import wait_menu
from main_ui_test import adb, bounds, capture, nodes, preferences, screen_point, snapshot, tap

PACKAGE = "dev.mback.oneplus"
KEY = "mback_shortcuts_v2"
TEST = PACKAGE + "/" + PACKAGE + ".TestActivity"
WIFI = "com.android.settings/com.android.settings.Settings$WifiSettingsActivity"
FIXTURE = "mback-activity-icon-test.png"
ORANGE = (241, 97, 42)


def wait_tree(predicate):
    for _ in range(8):
        tree = snapshot()
        if predicate(tree):
            return tree
    capture("activity-control-failure.png")
    raise AssertionError("Expected control did not appear")


def click(label, attribute="text"):
    tree = wait_tree(lambda t: nodes(t, label, attribute))
    tap(nodes(tree, label, attribute)[0])


def field_text(field, value):
    tap(field)
    adb("shell", "input", "keyevent", "123", *(["67"] * 80))
    if value:
        adb("shell", "input", "text", value)
    # These fields open the IME on tap; dismiss it before touching dialog actions.
    adb("shell", "input", "keyevent", "4")


def editor(restart=True):
    if restart:
        adb("shell", "am", "force-stop", PACKAGE)
    adb("shell", "am", "start", "-W", "--windowingMode", "100", "-n", PACKAGE + "/.MoreAppsActivity")
    return wait_tree(lambda t: any(nodes(t, label) for label in ("预置功能", "桌面应用", "搜索结果")))


def pick_app(package):
    tree = snapshot()
    field = next(n for n in tree.iter("node") if n.get("class") == "android.widget.EditText")
    field_text(field, package)
    click("选择应用：" + package, "content-desc")
    return wait_tree(lambda t: nodes(t, "活动：" + package, "content-desc"))


def image():
    bitmap = Image.open(io.BytesIO(subprocess.check_output(["adb", "exec-out", "screencap", "-p"])))
    if "icc_profile" in bitmap.info:
        bitmap = ImageCms.profileToProfile(bitmap, ImageCms.ImageCmsProfile(io.BytesIO(bitmap.info["icc_profile"])),
                                           ImageCms.createProfile("sRGB"), outputMode="RGB")
    return bitmap.convert("RGB")


def is_orange(pixel):
    return all(abs(actual - expected) <= 2 for actual, expected in zip(pixel, ORANGE))


def activity_icon(tree, target):
    row = nodes(tree, "活动：" + target, "content-desc")[0]
    rect = bounds(next(n for n in row.iter("node") if n.get("class") == "android.widget.ImageView"))
    return screen_point(row, (rect[0] + rect[2]) // 2, (rect[1] + rect[3]) // 2)


def wait_custom_icon():
    for _ in range(8):
        tree = snapshot()
        if nodes(tree, "修改图标：" + TEST, "content-desc"):
            if is_orange(image().getpixel(activity_icon(tree, TEST))):
                return tree
    capture("activity-icon-failure.png")
    raise AssertionError("Imported activity icon did not appear")


def wait_saved(expected):
    for _ in range(30):
        current = adb("shell", "settings", "get", "global", KEY).strip()
        if current != "null":
            saved = json.loads(current)
            if saved == expected:
                return
        time.sleep(.1)
    raise AssertionError("Shortcut configuration was not saved exactly")


def tile_center(tree, target):
    tile = nodes(tree, "快捷项：" + target, "content-desc")[0]
    rect = bounds(next(n for n in tile.iter("node") if n.get("class") == "android.widget.ImageView"))
    return screen_point(tile, (rect[0] + rect[2]) // 2, (rect[1] + rect[3]) // 2)


def drag_to(source, destination, expected, empty_end=False):
    tree = snapshot()
    x, y = tile_center(tree, source)
    end_x, end_y = tile_center(tree, destination)
    if empty_end:
        fourth = [n for n in tree.iter("node") if n.get("content-desc", "").startswith("快捷项：")][3]
        end_x, _ = tile_center(tree, fourth.get("content-desc").removeprefix("快捷项："))
    adb("shell", "input", "draganddrop", str(x), str(y), str(end_x), str(end_y), "650")
    wait_saved(expected)
    tree = snapshot()
    displayed = [n.get("content-desc").removeprefix("快捷项：") for n in tree.iter("node")
                 if n.get("content-desc", "").startswith("快捷项：")]
    assert displayed == [item["id"] for item in expected], "Grid order does not match the saved order"


def arc_point(position, prefs, width, height, density, count=7):
    # Exercise the real geometry, including the user's existing margins and icon size.
    source = """package dev.mback.oneplus;
public final class Probe {
 public static void main(String[] a) {
  int p=Integer.parseInt(a[0]), w=Integer.parseInt(a[1]), h=Integer.parseInt(a[2]);
  float d=Float.parseFloat(a[3]);
  int r=Integer.parseInt(a[4]), i=Integer.parseInt(a[5]), s=Integer.parseInt(a[6]), b=Integer.parseInt(a[7]);
  int n=Integer.parseInt(a[8]);
  System.out.println(Math.round(FlymeArcGeometry.x(1,p,w,d,r,i,n,s))+" "+
   Math.round(FlymeArcGeometry.y(p,w,h,d,r,i,n,s,b)));
 }
}"""
    with tempfile.TemporaryDirectory() as temporary:
        file = Path(temporary) / "Probe.java"
        file.write_text(source)
        classes = "app/build/local/classes"
        subprocess.run(["javac", "-cp", classes, "-d", temporary, str(file)], check=True)
        values = [position, width, height, density, prefs.get("mback_arc_radius_percent", "72"),
                  prefs.get("mback_arc_icon_size_dp", "44"), prefs.get("mback_arc_side_margin_dp", "12"),
                  prefs.get("mback_arc_bottom_margin_dp", "24"), count]
        return tuple(map(int, subprocess.check_output(
            ["java", "-cp", temporary + ":" + classes, "dev.mback.oneplus.Probe", *map(str, values)],
            text=True).split()))


def main():
    original = preferences()
    assert original.get("mback_corner_menu_enabled") == "1", "Enable corner shortcuts first"
    imported = None
    try:
        tree = editor()
        assert not nodes(tree, "完成"), "Manual save action is still present"
        assert preferences() == original, "Opening the editor unexpectedly changed settings"
        saved = (json.loads(original[KEY]) if KEY in original else
                 [{"id": n.get("content-desc").removeprefix("快捷项：")} for n in tree.iter("node")
                  if n.get("content-desc", "").startswith("快捷项：")])
        for target in [item["id"] for item in saved]:
            click("移除快捷项：" + target, "content-desc")
            saved = [item for item in saved if item["id"] != target]
            wait_saved(saved)
        tree = pick_app(PACKAGE)
        targets = [PACKAGE] + [PACKAGE + "/" + PACKAGE + "." + name
                               for name in ("MainActivity", "TestActivity", "MoreAppsActivity")]
        assert nodes(tree, "共 3 个活动 · 已添加 0/6"), "Not all manifest activities were listed"
        # Activities inherit the application icon, including non-exported TestActivity.
        before = image()
        assert before.getpixel(activity_icon(tree, PACKAGE)) == before.getpixel(activity_icon(tree, TEST))
        for target in targets:
            # Tap several entries without waiting between saves to check queued writes.
            tap(nodes(tree, "添加快捷项：" + target, "content-desc")[0])
        wait_saved([{"id": target} for target in targets])
        print("Complete activity list, default icons and rapid automatic saves passed", flush=True)
        click("修改图标：" + TEST, "content-desc")
        click("选择图片")
        with tempfile.TemporaryDirectory() as temporary:
            fixture = Path(temporary) / FIXTURE
            Image.new("RGB", (128, 128), ORANGE).save(fixture)
            adb("push", str(fixture), "/sdcard/Download/" + FIXTURE)
        adb("shell", "am", "broadcast", "-a", "android.intent.action.MEDIA_SCANNER_SCAN_FILE",
            "-d", "file:///sdcard/Download/" + FIXTURE)
        click("搜索编辑框", "content-desc")
        tree = wait_tree(lambda t: any(n.get("class") == "android.widget.EditText"
                                      for n in t.iter("node")))
        field_text(next(n for n in tree.iter("node") if n.get("class") == "android.widget.EditText"),
                   FIXTURE.removesuffix(".png"))
        click(FIXTURE)
        wait_custom_icon()
        saved = json.loads(adb("shell", "settings", "get", "global", KEY))
        for _ in range(30):
            if "icon" in saved[2]:
                break
            time.sleep(.1)
            saved = json.loads(adb("shell", "settings", "get", "global", KEY))
        imported = saved[2]["icon"]
        assert re.fullmatch("[0-9a-f]{64}", imported)
        print("Custom image import passed", flush=True)
        capture("activity-picker.png")
        click("返回")
        pick_app("com.android.settings")
        click("添加快捷项：com.android.settings", "content-desc")
        saved.append({"id": "com.android.settings"})
        wait_saved(saved)
        tree = snapshot()
        field_text(nodes(tree, "搜索活动", "content-desc")[0], "WifiSettingsActivity")
        click("添加快捷项：" + WIFI, "content-desc")
        saved.append({"id": WIFI})
        wait_saved(saved)
        # The seventh item is rejected, including after editing an activity filter.
        tree = snapshot()
        field_text(nodes(tree, "搜索活动", "content-desc")[0], "BluetoothSettingsActivity")
        extra = "com.android.settings/com.android.settings.Settings$BluetoothSettingsActivity"
        click("添加快捷项：" + extra, "content-desc")
        assert nodes(snapshot(), "添加快捷项：" + extra, "content-desc")
        click("返回")
        targets += ["com.android.settings", WIFI]
        wait_saved(saved)
        # Move an activity across rows, then to the front; keep its icon attached.
        moved = saved.pop(2)
        saved.append(moved)
        drag_to(TEST, WIFI, saved, empty_end=True)
        saved.insert(0, saved.pop())
        drag_to(TEST, targets[0], saved)
        targets = [item["id"] for item in saved]
        tree = snapshot()
        x, y = tile_center(tree, TEST)
        title = bounds(nodes(tree, "更多应用")[0])
        end_x, end_y = screen_point(nodes(tree, "更多应用")[0],
                                   (title[0] + title[2]) // 2, (title[1] + title[3]) // 2)
        adb("shell", "input", "draganddrop", str(x), str(y),
            str(end_x), str(end_y), "650")
        wait_saved(saved)
        # Closing and reopening needs no final confirmation.
        adb("shell", "input", "keyevent", "4")
        tree = editor(restart=False)
        assert not nodes(tree, "完成")
        assert all(nodes(tree, "快捷项：" + target, "content-desc") for target in targets)
        print("Mixed targets, limit, cross-row/front ordering, cancelled drag and close/reopen passed", flush=True)
        tree = editor()
        assert all(nodes(tree, "快捷项：" + target, "content-desc") for target in targets)
        pick_app(PACKAGE)
        tree = wait_tree(lambda t: nodes(t, "修改图标：" + TEST, "content-desc"))
        assert is_orange(image().getpixel(activity_icon(tree, TEST))), "Icon lost on editor restart"
        adb("shell", "am", "force-stop", PACKAGE)
        adb("shell", "am", "start", "-n", PACKAGE + "/.MainActivity")
        width, height = image().size
        density = int(re.findall(r"\d+", adb("shell", "wm", "density"))[-1]) / 160
        x, y = arc_point(targets.index(TEST) + 1, original, width, height, density)
        start_x, start_y = round(width * .1), height - round(32 * density)
        try:
            adb("shell", "input", "motionevent", "DOWN", str(start_x), str(start_y))
            adb("shell", "input", "motionevent", "MOVE", str(start_x + 100), str(start_y - 100))
            wait_menu(True)
            adb("shell", "input", "motionevent", "MOVE", str(x), str(y))
            time.sleep(.35)
            assert is_orange(image().getpixel((x, y))), "SystemUI did not read the custom icon"
            capture("activity-arc.png")
            adb("shell", "input", "motionevent", "UP", str(x), str(y))
        finally:
            adb("shell", "input", "motionevent", "CANCEL", str(x), str(y))
        wait_menu(False)
        wait_tree(lambda t: nodes(t, "底部点击穿透测试 · 次数 0"))
        activities = adb("shell", "dumpsys", "activity", "activities")
        assert "dev.mback.oneplus/.TestActivity" in activities and "mCallingUid=10241" in activities
        editor()
        click("修改图标：" + TEST, "content-desc")
        click("恢复应用图标")
        capture("activity-default-icon.png")
        del next(item for item in saved if item["id"] == TEST)["icon"]
        wait_saved(saved)
        tree = editor()
        pick_app(PACKAGE)
        tree = wait_tree(lambda t: nodes(t, "修改图标：" + TEST, "content-desc"))
        restored = image()
        assert restored.getpixel(activity_icon(tree, PACKAGE)) == restored.getpixel(activity_icon(tree, TEST))
        denied = subprocess.run(["adb", "shell", "content", "read", "--uri",
                                 "content://dev.mback.oneplus.shortcuts/icons/" + imported],
                                text=True, capture_output=True, timeout=20)
        assert "Only MBack and SystemUI" in denied.stdout + denied.stderr, (
            "Icon provider accepted an untrusted caller")
        print("Activity shortcuts passed: initial configuration, complete activity list, mixed targets, "
              "limit, literal $, drag ordering, automatic saves, default/custom/restored icons, "
              "persistence, SystemUI rendering and exact private launch")
    finally:
        adb("shell", "input", "motionevent", "CANCEL", "0", "0")
        adb("shell", "am", "force-stop", PACKAGE)
        command = ("settings put global " + KEY + " " + shlex.quote(original[KEY]) if KEY in original
                   else "settings delete global " + KEY)
        adb("shell", "su", "-c", shlex.quote(command))
        adb("shell", "rm", "-f", "/sdcard/Download/" + FIXTURE)
        adb("shell", "am", "start", "-n", PACKAGE + "/.MainActivity")
        assert preferences() == original, "Original preferences were not restored"


if __name__ == "__main__":
    main()
