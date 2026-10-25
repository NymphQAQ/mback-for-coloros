"""Check ordinary apps/activities in More and SystemUI against native renderer evidence.

Run icon_rendering_test.py first. Restores preferences; launches only Settings/More.
"""

import json
import math
import shlex
import time
from pathlib import Path

from PIL import Image, ImageChops, ImageStat

from activity_shortcuts_test import arc_point, editor, field_text, image, pick_app
from main_ui_test import adb, bounds, capture, nodes, preferences, screen_point, snapshot
from preset_icon_shapes_test import SHAPE, arc_capture, icon_point, shape
from preset_shortcuts_test import KEY, PACKAGE, write_shortcuts

APPS = ["com.android.settings", "com.tencent.mm", "com.eg.android.AlipayGphone",
        "com.coloros.calculator", "com.coloros.calendar", "tv.danmaku.bili"]


def compare(bitmap, rect, pkg, circular):
    reference = Image.open(Path("app/build/local/ui/icon-render-checks")
                           / (pkg + ("-circle.png" if circular else "-original.png"))).convert("RGBA")
    background = Image.new("RGBA", reference.size, "white")
    reference = Image.alpha_composite(background, reference).convert("RGB").resize((96, 96))
    actual = bitmap.crop(rect).resize((96, 96))
    total = pixels = 0
    for y in range(96):
        for x in range(96):
            if math.hypot(x - 47.5, y - 47.5) > 44:
                continue
            expected = reference.getpixel((x, y))
            # Native window scale introduces at most one pixel of sampling offset.
            error = min(sum(abs(a - b) for a, b in zip(expected, actual.getpixel((xx, yy)))) / 3
                        for xx in range(max(0, x - 1), min(96, x + 2))
                        for yy in range(max(0, y - 1), min(96, y + 2)))
            total += error
            pixels += 1
    assert total / pixels < 6, pkg + " lost artwork or retained a different shape: " + str(total / pixels)


def rect_for(tile):
    return (*icon_point(tile, 0, 0), *icon_point(tile, 1, 1))


def more():
    shape(1)
    tree = editor()
    for value in (1, 0, 1, 0):
        shape(value)
        time.sleep(.4)
        tree = snapshot()
        bitmap = image()
        for pkg in APPS:
            tile = nodes(tree, "快捷项：" + pkg, "content-desc")[0]
            compare(bitmap, rect_for(tile), pkg, not value)
    field = next(n for n in tree.iter("node") if n.get("class") == "android.widget.EditText")
    field_text(field, "com.android.settings")
    tree = snapshot()
    tile = nodes(tree, "选择应用：com.android.settings", "content-desc")[0]
    compare(image(), rect_for(tile), APPS[0], True)
    pick_app("com.android.settings")
    tree = snapshot()
    row = nodes(tree, "活动：" + APPS[0], "content-desc")[0]
    icon = next(n for n in row.iter("node") if n.get("class") == "android.widget.ImageView")
    left, top, right, bottom = bounds(icon)
    p1 = screen_point(icon, left, top)
    p2 = screen_point(icon, right, bottom)
    compare(image(), (*p1, *p2), APPS[0], True)
    capture("all-icons-activity-list.png")
    print("More: six ordinary apps, desktop tiles, activity list and live shape switching passed", flush=True)


def arc(original):
    frames = []
    for value in (1, 0, 1):
        baseline, bitmap, width, height, density = arc_capture(value)
        side = int(original.get("mback_arc_icon_size_dp", "44")) * density
        for i, pkg in enumerate(APPS):
            x, y = arc_point(i + 1, original, width, height, density, len(APPS) + 1)
            rect = (round(x - side * .5), round(y - side * .5), round(x + side * .5), round(y + side * .5))
            if not value:
                # ColorOS supplies themed icons to SystemUI (including different calendar text).
                # Check the requested outline/safe ring here, rather than More's raw-icon colors.
                corner = (round(x + side * .38), round(y - side * .38))
                assert max(abs(a - b) for a, b in zip(bitmap.getpixel(corner), baseline.getpixel(corner))) <= 4
                sample = bitmap.crop(rect).resize((96, 96))
                for angle in range(0, 360, 10):
                    for radius in (40, 43, 45):
                        px = round(47.5 + radius * math.cos(math.radians(angle)))
                        py = round(47.5 + radius * math.sin(math.radians(angle)))
                        pixel = sample.getpixel((px, py))
                        assert (min(pixel) >= 220 if pkg == "com.coloros.calendar" else min(pixel) < 180), (
                            "Artwork reached the outer brand ring or an artificial white border appeared: " + pkg)
            elif frames:
                difference = ImageChops.difference(bitmap.crop(rect), frames[0].crop(rect))
                assert max(ImageStat.Stat(difference).mean) < 1, "Switching back changed the original icon: " + pkg
        frames.append(bitmap)
    print("SystemUI: ordinary app pairs use complete fitted icons and both caches switch correctly", flush=True)


def main():
    original = preferences()
    try:
        # Isolate artwork checks from gesture behavior and backdrop dimming.
        for key in ('mback_corner_dim_percent', 'mback_corner_keep_open'):
            adb('shell', 'su', '-c', shlex.quote('settings put global ' + key + ' 0'))
        write_shortcuts(json.dumps([{"id": pkg} for pkg in APPS]))
        more()
        arc(original)
    finally:
        adb("shell", "input", "motionevent", "CANCEL", "0", "0")
        adb("shell", "am", "force-stop", PACKAGE)
        for key in (KEY, SHAPE, "mback_corner_dim_percent", "mback_corner_keep_open"):
            command = ("settings put global " + key + " " + shlex.quote(original[key]) if key in original
                       else "settings delete global " + key)
            adb("shell", "su", "-c", shlex.quote(command))
        assert preferences() == original, "Original settings changed"


if __name__ == "__main__":
    main()
