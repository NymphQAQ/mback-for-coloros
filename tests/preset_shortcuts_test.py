"""ColorOS preset regression. Restores preferences; never captures payment screens.

Use --launch after reloading the installed SystemUI module to also check all six
real activities. Requires an unlocked phone, WeChat, Alipay and Python + Pillow.
"""

import json
import hashlib
import re
import shlex
import sys
import tempfile
import time
import zipfile
from pathlib import Path

from PIL import Image

from activity_shortcuts_test import (FIXTURE, KEY, ORANGE, PACKAGE, TEST, arc_point, click,
                                     drag_to, editor, field_text, image, is_orange, pick_app,
                                     tile_center, wait_saved, wait_tree)
from corner_touch_test import wait_menu
from main_ui_test import adb, bounds, capture, nodes, preferences, screen_point, snapshot, tap

PRESETS = [
    ("微信扫一扫", "com.tencent.mm/com.tencent.mm.plugin.scanner.ui.BaseScanUI"),
    ("微信付款码", "com.tencent.mm/com.tencent.mm.plugin.offline.ui.WalletOfflineEntranceUI"),
    ("支付宝扫一扫", "com.eg.android.AlipayGphone/com.alipay.mobile.scan.as.main.MainCaptureActivity"),
    ("支付宝付款码", "com.eg.android.AlipayGphone/com.alipay.mobile.quinox.SchemeLauncherActivity"),
    ("微信乘车码", "com.coloros.assistantscreen/com.oplus.assistantscreen.flamingo.trafficcode.WxTrafficCodeActivity"),
    ("支付宝乘车码", "com.coloros.assistantscreen/com.oplus.assistantscreen.flamingo.trafficcode.AliTrafficCodeActivity"),
]


def bundled_icons():
    expected = {
        "wechat-scan.png": "2f393d2a60d7e17a60875c942ce85e003732caa4bb00e9823733b6f6d7a37dc3",
        "wechat-payment.png": "1b2bdffa707a0cf32a99f3547c80900e557b815f1d4fef83c6baca79e3383753",
        "alipay-scan.png": "7dedc7184820fee6fc0c932026f336498734f729ef33bb8c36403a302e0e741f",
        "alipay-payment.webp": "d3518b992134b5b8d8a0af00c028ce5dcf3c4d3bb5705eeb9dbeb2aa297ecdda",
        "wechat-transit.png": "2b7f3993ad03513456dbdf812f8e24ef73f5f53aef4ccff41504c646d9c6022c",
        "alipay-transit.webp": "2d73a538abb250ab5a20dbb8dbff0fa1210e0cd69e976a357b4c4e2925aece74",
    }
    with zipfile.ZipFile("app/build/local/mback-debug.apk") as apk:
        for name, digest in expected.items():
            assert hashlib.sha256(apk.read("assets/preset-icons/" + name)).hexdigest() == digest, name
    print("Six bundled icons exactly match the selected original files", flush=True)


def write_shortcuts(value):
    adb("shell", "am", "force-stop", PACKAGE)
    adb("shell", "su", "-c", shlex.quote("settings put global " + KEY + " " + shlex.quote(value)))


def ui_test():
    tree = editor()
    assert bounds(nodes(tree, "已添加  ·  0/6")[0])[1] < bounds(nodes(tree, "预置功能")[0])[1]
    assert bounds(nodes(tree, "预置功能")[0])[1] < bounds(nodes(tree, "桌面应用")[0])[1]
    tiles = [nodes(tree, "预置功能：" + name, "content-desc")[0] for name, _ in PRESETS]
    assert len({bounds(tile)[1] for tile in tiles}) == 2, "Six presets must fit into two rows"
    assert all(bounds(tiles[i])[0] < bounds(tiles[i + 1])[0] for i in (0, 1, 2, 4))
    assert bounds(tiles[4])[1] > bounds(tiles[0])[1]
    saved = []
    for name, target in PRESETS:
        click("添加预置功能：" + name, "content-desc")
        saved.append({"id": target})
        wait_saved(saved)
    tree = editor()
    assert all(nodes(tree, "快捷项：" + target, "content-desc") for _, target in PRESETS)
    assert all(nodes(tree, "移除预置功能：" + name, "content-desc") for name, _ in PRESETS)
    # Both sections share the default artwork and activity identities.
    bitmap = image()
    for i, (_, target) in enumerate(PRESETS):
        x, y = tile_center(tree, target)
        # A point near the top center remains inside the rounded brand background.
        pixels = [bitmap.getpixel((x + dx, y + dy)) for dx in range(-20, 21, 4) for dy in range(-20, 21, 4)]
        assert any((g > 150 and r < 70 and b < 90) if i in (0, 1, 4) else (b > 200 and r < 70 and 40 < g < 170)
                   for r, g, b in pixels), "Missing preset brand artwork"
        tile = nodes(tree, "快捷项：" + target, "content-desc")[0]
        rect = bounds(next(n for n in tile.iter("node") if n.get("class") == "android.widget.ImageView"))
        corner = screen_point(tile, round(rect[0] + (rect[2] - rect[0]) * .80),
                              round(rect[1] + (rect[3] - rect[1]) * .80))
        r, g, b = bitmap.getpixel(corner)
        assert (g > 150 and r < 70 and b < 90) if i in (0, 1, 4) else (b > 200 and r < 70 and 40 < g < 170), (
            "Preset image did not fill its icon bounds")
    pick_app("com.tencent.mm")
    tree = snapshot()
    field_text(nodes(tree, "搜索活动", "content-desc")[0], "BaseScanUI")
    assert nodes(snapshot(), "移除快捷项：" + PRESETS[0][1], "content-desc"), "Activity picker did not recognize preset"
    click("返回")
    pick_app(PACKAGE)
    click("添加快捷项：" + TEST, "content-desc")
    wait_saved(saved)
    assert nodes(snapshot(), "添加快捷项：" + TEST, "content-desc"), "Ordinary activity exceeded six-slot limit"
    click("返回")
    tree = snapshot()
    field_text(next(n for n in tree.iter("node") if n.get("class") == "android.widget.EditText"), "")
    click("移除预置功能：" + PRESETS[0][0], "content-desc")
    saved.pop(0)
    wait_saved(saved)
    pick_app(PACKAGE)
    extra = TEST
    click("添加快捷项：" + extra, "content-desc")
    saved.append({"id": extra})
    wait_saved(saved)
    click("返回")
    tree = snapshot()
    field_text(next(n for n in tree.iter("node") if n.get("class") == "android.widget.EditText"), "")
    click("添加预置功能：" + PRESETS[0][0], "content-desc")
    wait_saved(saved)
    assert nodes(snapshot(), "添加预置功能：" + PRESETS[0][0], "content-desc"), "Preset exceeded six-slot limit"
    click("移除快捷项：" + extra, "content-desc")
    saved.pop()
    wait_saved(saved)
    click("添加预置功能：" + PRESETS[0][0], "content-desc")
    saved.append({"id": PRESETS[0][1]})
    wait_saved(saved)
    moved = saved.pop()
    saved.insert(0, moved)
    drag_to(PRESETS[0][1], PRESETS[1][1], saved)
    capture("more-presets.png")
    print("Presets: placement, order, brand icons, existing activity IDs, add/remove, limit, autosave and drag passed", flush=True)

    click("修改图标：" + PRESETS[0][1], "content-desc")
    click("选择图片")
    with tempfile.TemporaryDirectory() as temporary:
        fixture = Path(temporary) / FIXTURE
        Image.new("RGB", (128, 128), ORANGE).save(fixture)
        adb("push", str(fixture), "/sdcard/Download/" + FIXTURE)
    adb("shell", "am", "broadcast", "-a", "android.intent.action.MEDIA_SCANNER_SCAN_FILE",
        "-d", "file:///sdcard/Download/" + FIXTURE)
    click("搜索编辑框", "content-desc")
    tree = wait_tree(lambda t: any(n.get("class") == "android.widget.EditText" for n in t.iter("node")))
    field_text(next(n for n in tree.iter("node") if n.get("class") == "android.widget.EditText"), FIXTURE.removesuffix(".png"))
    click(FIXTURE)
    for _ in range(30):
        saved = json.loads(adb("shell", "settings", "get", "global", KEY))
        if saved[0].get("icon"):
            break
        time.sleep(.1)
    assert re.fullmatch("[0-9a-f]{64}", saved[0].get("icon", ""))
    tree = editor()
    assert is_orange(image().getpixel(tile_center(tree, PRESETS[0][1]))), "Preset custom icon did not persist"
    click("修改图标：" + PRESETS[0][1], "content-desc")
    click("恢复预置图标")
    del saved[0]["icon"]
    wait_saved(saved)
    print("Presets: custom image persistence and restore-to-preset passed", flush=True)


def launch_test(original):
    saved = [{"id": target} for _, target in PRESETS]
    write_shortcuts(json.dumps(saved))
    width, height = image().size
    density = int(re.findall(r"\d+", adb("shell", "wm", "density"))[-1]) / 160
    for index, (name, target) in enumerate(PRESETS):
        adb("shell", "input", "keyevent", "3")
        adb("shell", "am", "start", "-a", "android.settings.SETTINGS")
        time.sleep(.6)
        x, y = arc_point(index + 1, original, width, height, density, len(saved) + 1)
        start_x, start_y = round(width * .1), height - round(32 * density)
        try:
            adb("shell", "input", "motionevent", "DOWN", str(start_x), str(start_y))
            adb("shell", "input", "motionevent", "MOVE", str(start_x + 100), str(start_y - 100))
            wait_menu(True)
            adb("shell", "input", "motionevent", "MOVE", str(x), str(y))
            time.sleep(.3)
            bitmap = image()
            samples = [bitmap.getpixel((x + dx, y + dy)) for dx in (-30, -15, 0, 15, 30)
                       for dy in (-30, -15, 0, 15, 30)]
            assert any((g > 150 and r < 70 and b < 90) if index in (0, 1, 4)
                       else (b > 200 and r < 70 and 40 < g < 170) for r, g, b in samples), (
                name + " did not render its bundled icon in SystemUI")
            adb("shell", "input", "motionevent", "UP", str(x), str(y))
        finally:
            adb("shell", "input", "motionevent", "CANCEL", str(x), str(y))
        wait_menu(False)
        expected = ("com.alipay.mobile.onsitepay.merge.OnsitepayActivity" if index == 3 else
                    "com.tencent.mm.framework.app.UIPageFragmentActivity" if index == 1 else
                    "com.tencent.mm.plugin.appbrand.ui.AppBrandUI" if index == 4 else
                    "com.alipay.android.phone.xriver.bundlex.CSGAPushActivity" if index == 5 else target.split("/")[1])
        for _ in range(30):
            activities = adb("shell", "dumpsys", "activity", "activities")
            focused = re.search(r"mFocusedApp=ActivityRecord\{[^\n]*", activities)
            component = re.search(r"u\d+ ([^/ ]+)/([^ ]+) t", focused[0]) if focused else None
            activity = (component[1] + component[2] if component[2].startswith(".") else component[2]) if component else ""
            if activity == expected or index == 4 and activity.startswith(expected):
                break
            time.sleep(.2)
        else:
            raise AssertionError(name + " did not reach its expected activity")
        if index >= 4:
            keyword = "乘车码" if index == 4 else "出行"
            for _ in range(8):
                tree = snapshot()
                if any(keyword in n.get("text", "") + n.get("content-desc", "") for n in tree.iter("node")):
                    break
                time.sleep(.3)
            else:
                raise AssertionError(name + " did not show the transit page seen in Assistant")
        print(name + ": expected activity reached through the SystemUI arc", flush=True)
        adb("shell", "input", "keyevent", "4")


def main():
    bundled_icons()
    original = preferences()
    try:
        if "--launch" in sys.argv:
            launch_test(original)
        else:
            write_shortcuts("[]")
            ui_test()
    finally:
        adb("shell", "input", "motionevent", "CANCEL", "0", "0")
        adb("shell", "input", "keyevent", "3")
        adb("shell", "am", "force-stop", PACKAGE)
        if KEY in original:
            write_shortcuts(original[KEY])
        else:
            adb("shell", "su", "-c", shlex.quote("settings delete global " + KEY))
        adb("shell", "rm", "-f", "/sdcard/Download/" + FIXTURE)
        assert preferences() == original, "Original preferences were not restored"


if __name__ == "__main__":
    main()
