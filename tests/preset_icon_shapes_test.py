"""Check native rounded and circular preset icons; restore all original settings.

Default checks the live More window. --arc checks both SystemUI bitmap caches
after the module has been reloaded. Neither mode launches payment/transit pages.
"""

import json
import math
import re
import shlex
import sys
import time

from PIL import Image, ImageFilter

from activity_shortcuts_test import arc_point, editor, image
from corner_touch_test import wait_menu
from main_ui_test import adb, bounds, capture, nodes, preferences, screen_point
from preset_shortcuts_test import KEY, PACKAGE, PRESETS, bundled_icons, write_shortcuts
from preset_icon_artwork_test import artwork

SHAPE = "mback_arc_original_icons"


def shape(value):
    adb("shell", "su", "-c", shlex.quote("settings put global " + SHAPE + " " + str(value)))


def brand(pixel, index):
    r, g, b = pixel
    return g > 150 and r < 70 and b < 90 if index in (0, 1, 4) else b > 200 and r < 70 and 40 < g < 170


def white(pixel):
    return min(pixel) >= 248


def close(a, b):
    return max(abs(x - y) for x, y in zip(a, b)) <= 3


def icon_point(tile, fx, fy):
    rect = bounds(next(n for n in tile.iter("node") if n.get("class") == "android.widget.ImageView"))
    return screen_point(tile, round(rect[0] + (rect[2] - rect[0]) * fx),
                        round(rect[1] + (rect[3] - rect[1]) * fy))


def check_more(tree, original):
    bitmap = image()
    for prefix in ("快捷项：", "预置功能："):
        for index, (label, target) in enumerate(PRESETS):
            tile = nodes(tree, prefix + (target if prefix == "快捷项：" else label), "content-desc")[0]
            assert white(bitmap.getpixel(icon_point(tile, .02, .02))), "Square corner remains on " + label
            pixel = bitmap.getpixel(icon_point(tile, .12, .12))
            assert (brand(pixel, index) if original else white(pixel)), "Wrong icon shape on " + label
            assert brand(bitmap.getpixel(icon_point(tile, .5, .08)), index), "Icon lost its full-size background"
            left, top = icon_point(tile, 0, 0)
            right, bottom = icon_point(tile, 1, 1)
            check_glyph(bitmap, (left, top, right, bottom), index, original)
            if not original:
                icon = bounds(next(n for n in tile.iter("node") if n.get("class") == "android.widget.ImageView"))
                badge = bounds(next(n for n in tile.iter("node")
                                    if n.get("content-desc", "").startswith(("添加", "移除"))))
                distance = math.hypot((icon[0] + icon[2] - badge[0] - badge[2]) * .5,
                                      (icon[1] + icon[3] - badge[1] - badge[3]) * .5)
                assert distance > (icon[2] - icon[0] + badge[2] - badge[0]) * .5, "Badge covers the circular icon"
    return bitmap


def check_glyph(bitmap, rect, index, original):
    reference = artwork()[0 if original else 1][index].convert("RGB")
    sample = bitmap.crop(rect).resize((96, 96))
    reference = reference.resize(sample.size)
    expected, actual = set(), set()
    for y in range(96):
        for x in range(96):
            if math.hypot(x - 47.5, y - 47.5) > 38:
                continue
            if min(reference.getpixel((x, y))) > 200:
                expected.add((x, y))
            if min(sample.getpixel((x, y))) > 200:
                actual.add((x, y))
    # Native task scaling can move a thin scan-frame edge by one output pixel.
    def expanded(points):
        mask = Image.new("L", (96, 96))
        for point in points:
            mask.putpixel(point, 255)
        return mask.filter(ImageFilter.MaxFilter(3))
    actual_mask, expected_mask = expanded(actual), expanded(expected)
    recall = sum(bool(actual_mask.getpixel(point)) for point in expected) / len(expected)
    precision = sum(bool(expected_mask.getpixel(point)) for point in actual) / max(1, len(actual))
    assert min(recall, precision) > .97, ("Incomplete or displaced glyph: " + PRESETS[index][0]
                                         + " recall=" + str(recall) + " precision=" + str(precision))
    if not original:
        # Outward-facing ring is clear of neighboring arc icons and add/remove badges.
        for degrees in (-90, -60, -30, 0):
            point = (round(47.5 + 43 * math.cos(math.radians(degrees))),
                     round(47.5 + 43 * math.sin(math.radians(degrees))))
            assert brand(sample.getpixel(point), index), "Circle has a white border: " + PRESETS[index][0]


def more_test():
    shape(1)
    tree = editor()
    check_more(tree, True)
    capture("presets-system-rounded.png")
    for value in (0, 1):
        shape(value)
        # Shape changes must update the already-open window without restarting it.
        point = icon_point(nodes(tree, "快捷项：" + PRESETS[0][1], "content-desc")[0], .12, .12)
        for _ in range(20):
            pixel = image().getpixel(point)
            if brand(pixel, 0) if value else white(pixel):
                break
            time.sleep(.1)
        else:
            raise AssertionError("More window did not observe the shape setting")
        check_more(tree, bool(value))
        if not value:
            capture("presets-circular.png")
    print("More: six rounded/circular pairs, live switching, complete fitted glyphs and no white border passed", flush=True)


def arc_capture(value):
    shape(value)
    adb("shell", "input", "keyevent", "3")
    adb("shell", "am", "start", "-a", "android.settings.SETTINGS")
    time.sleep(.5)
    baseline = image()
    width, height = baseline.size
    density = int(re.findall(r"\d+", adb("shell", "wm", "density"))[-1]) / 160
    start_x, start_y = round(width * .1), height - round(32 * density)
    try:
        adb("shell", "input", "motionevent", "DOWN", str(start_x), str(start_y))
        adb("shell", "input", "motionevent", "MOVE", str(start_x + 100), str(start_y - 100))
        wait_menu(True)
        adb("shell", "input", "motionevent", "MOVE", str(width // 2), str(height - width - 50))
        time.sleep(.4)
        bitmap = image()
        capture("preset-arc-" + ("rounded.png" if value else "circular.png"))
    finally:
        adb("shell", "input", "motionevent", "CANCEL", "0", "0")
    wait_menu(False)
    return baseline, bitmap, width, height, density


def arc_test(original):
    frames = []
    for value in (1, 0, 1):
        baseline, bitmap, width, height, density = arc_capture(value)
        side = int(original.get("mback_arc_icon_size_dp", "44")) * density
        for index in range(-1, len(PRESETS)):
            x, y = arc_point(index + 1, original, width, height, density, len(PRESETS) + 1)
            # The outer upper-right corner stays clear of neighboring arc icons.
            corner = (round(x + side * .48), round(y - side * .48))
            assert close(bitmap.getpixel(corner), baseline.getpixel(corner)), "Arc retained a square corner"
            shoulder = (round(x + side * .38), round(y - side * .38))
            pixel = bitmap.getpixel(shoulder)
            assert (brand(pixel, index) if value else close(pixel, baseline.getpixel(shoulder))), (
                "Arc did not select the correct cached shape")
        frames.append(bitmap)
        for index in range(len(PRESETS)):
            x, y = arc_point(index + 1, original, width, height, density, len(PRESETS) + 1)
            check_glyph(bitmap, (round(x - side * .5), round(y - side * .5),
                                 round(x + side * .5), round(y + side * .5)), index, bool(value))
    for index in range(len(PRESETS)):
        x, y = arc_point(index + 1, original, width, height, density, len(PRESETS) + 1)
        for dx in (-.26, -.13, 0, .13, .26):
            for dy in (-.26, -.13, 0, .13, .26):
                if math.hypot(dx, dy) > .34:
                    continue
                point = (round(x + dx * side), round(y + dy * side))
                assert close(frames[0].getpixel(point), frames[2].getpixel(point)), "Switching back lost the rounded bitmap"
    print("SystemUI: six pairs plus More, both caches, complete glyphs, no white border and repeated switching passed", flush=True)


def main():
    bundled_icons()
    original = preferences()
    try:
        # Isolate artwork checks from gesture behavior and backdrop dimming.
        for key in ('mback_corner_dim_percent', 'mback_corner_keep_open'):
            adb('shell', 'su', '-c', shlex.quote('settings put global ' + key + ' 0'))
        write_shortcuts(json.dumps([{"id": target} for _, target in PRESETS]))
        if "--arc" in sys.argv:
            arc_test(original)
        else:
            more_test()
    finally:
        adb("shell", "input", "motionevent", "CANCEL", "0", "0")
        adb("shell", "am", "force-stop", PACKAGE)
        for key in (KEY, SHAPE, "mback_corner_dim_percent", "mback_corner_keep_open"):
            command = ("settings put global " + key + " " + shlex.quote(original[key]) if key in original
                       else "settings delete global " + key)
            adb("shell", "su", "-c", shlex.quote(command))
        current = preferences()
        changed = [key for key in original.keys() | current.keys() if original.get(key) != current.get(key)]
        assert not changed, "Original preferences were not restored: " + str(changed)


if __name__ == "__main__":
    main()
