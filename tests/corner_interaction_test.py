"""Real-device coverage for ranges, dimming, sticky selection, preview and auto-save."""
import io
import json
import re
import shlex
import statistics
import subprocess
import time

from PIL import Image
from main_ui_test import adb, bounds, capture, entries, nodes, preferences, snapshot, tab, tap, wait_value
from corner_touch_test import check_swipe, wait_menu
from activity_shortcuts_test import arc_point, wait_tree

REGION = ['mback_corner_bottom_length_dp', 'mback_corner_bottom_height_dp',
          'mback_corner_side_length_dp', 'mback_corner_side_width_dp',
          'mback_corner_triangle_width_dp', 'mback_corner_triangle_height_dp']
DIM = 'mback_corner_dim_percent'
KEEP = 'mback_corner_keep_open'
SHORTCUTS = 'mback_shortcuts_v2'
ENABLE = 'mback_corner_menu_enabled'
CHANGED = REGION + [DIM, KEEP, SHORTCUTS, ENABLE]
PACKAGE = 'dev.mback.oneplus'


def put(key, value):
    command = 'settings put global ' + key + ' ' + shlex.quote(str(value))
    adb('shell', 'su', '-c', shlex.quote(command))


def screenshot():
    return Image.open(io.BytesIO(subprocess.check_output(['adb', 'exec-out', 'screencap', '-p']))).convert('RGB')


def gesture(width, height, density):
    adb('shell', 'input', 'swipe', str(round(width * .1)), str(height - round(30 * density)),
        str(width // 2), str(height // 2), '450')


def restore(original):
    adb('shell', 'input', 'keyevent', '4')
    adb('shell', 'input', 'motionevent', 'CANCEL', '0', '0')
    for key in CHANGED:
        command = ('settings put global ' + key + ' ' + shlex.quote(original[key]) if key in original
                   else 'settings delete global ' + key)
        adb('shell', 'su', '-c', shlex.quote(command))
    assert preferences() == original, 'Preferences were not restored'


def main():
    original = preferences()
    assert original.get('mback_corner_menu_enabled') == '1'
    width, height = screenshot().size
    density = int(re.findall(r'\d+', adb('shell', 'wm', 'density'))[-1]) / 160
    try:
        put(KEEP, 0)
        put(SHORTCUTS, json.dumps([{'id': 'com.android.settings'}]))
        adb('shell', 'am', 'force-stop', PACKAGE)
        adb('shell', 'am', 'start', '-a', 'android.settings.SETTINGS')
        for sizes, inside, outside in [
            ([100, 30, 0, 0, 0, 0], (70, 15), (110, 15)),
            ([0, 0, 160, 20, 0, 0], (10, 120), (30, 120)),
            ([0, 0, 0, 0, 120, 120], (45, 45), (80, 80)),
        ]:
            for key, value in zip(REGION, sizes): put(key, value)
            for mirror in (False, True):
                for (inward, up), expected in ((inside, True), (outside, False)):
                    x = round(inward * density)
                    if mirror: x = width - x
                    check_swipe(x, height - round(up * density), width, height, expected, 600)
        print('Ranges: isolated bottom / side / triangle, both corners, inside/outside passed', flush=True)

        for key, value in zip(REGION, [100, 48, 120, 12, 72, 72]): put(key, value)
        put(KEEP, 1)
        pictures = []
        for dim in (0, 50):
            put(DIM, dim)
            gesture(width, height, density)
            wait_menu(True)
            time.sleep(.4)
            pictures.append(screenshot())
            capture(f'corner-sticky-dim-{dim}.png')
            tree = snapshot()
            assert nodes(tree, '更多应用', 'content-desc'), 'Sticky icons missing accessible click targets'
            adb('shell', 'input', 'tap', str(width // 2), str(height // 3))
            wait_menu(False)
        ratios = []
        for x in range(width // 3, width * 2 // 3, 50):
            for y in range(height // 5, height // 3, 50):
                before = sum(pictures[0].getpixel((x, y)))
                after = sum(pictures[1].getpixel((x, y)))
                if before > 250: ratios.append(after / before)
        assert ratios and .44 < statistics.median(ratios) < .56, statistics.median(ratios)
        print('Dimming: measured 50% background brightness, sticky release and blank-close passed', flush=True)

        gesture(width, height, density); wait_menu(True)
        adb('shell', 'input', 'keyevent', '4'); wait_menu(False)
        gesture(width, height, density); wait_menu(True)
        tree = snapshot()
        tap(nodes(tree, '更多应用', 'content-desc')[0]); wait_menu(False)
        wait_tree(lambda t: nodes(t, '预置功能'))
        print('Sticky: Back closes; tapping More opens native window after overlay removal', flush=True)
        adb('shell', 'am', 'force-stop', PACKAGE)
        gesture(width, height, density); wait_menu(True)
        tree = snapshot()
        target = next(n for n in tree.iter('node') if n.get('class') == 'android.widget.Button'
                      and n.get('content-desc') not in ('更多应用', '') and n.get('text') != '关闭')
        tap(target); wait_menu(False)
        dump = adb('shell', 'dumpsys', 'activity', 'activities')
        assert re.search(r'topResumedActivity=.*com.android.settings/', dump), 'Tap did not open Settings'
        print('Sticky: tapping a regular app launches it; no overlay left behind', flush=True)
        adb('shell', 'am', 'force-stop', 'com.android.settings')
        adb('shell', 'am', 'start', '-n', PACKAGE + '/.MainActivity')

        gesture(width, height, density); wait_menu(True)
        put(ENABLE, 0); wait_menu(False)
        put(ENABLE, 1)
        gesture(width, height, density); wait_menu(True)
        adb('shell', 'cmd', 'statusbar', 'expand-notifications'); wait_menu(False)
        adb('shell', 'cmd', 'statusbar', 'collapse')
        time.sleep(.6)
        put(KEEP, 0)
        gesture(width, height, density); wait_menu(False)
        print('Lifecycle: shade dismisses sticky menu; original release-to-close still works', flush=True)

        adb('shell', 'am', 'force-stop', PACKAGE)
        adb('shell', 'am', 'start', '-n', PACKAGE + '/.MainActivity')
        tree = snapshot(); tap(tab(tree, '快捷小窗'))
        tree = snapshot()
        tap(entries(tree, '呼出与交互')[0])
        tree = snapshot()
        for _ in range(5):
            entry = nodes(tree, '呼出范围与预览')
            if entry:
                tap(entry[0]); break
            adb('shell', 'input', 'swipe', '35', str(round(height * .78)), '35', str(round(height * .37)), '300')
            tree = snapshot()
        else: raise AssertionError('Range preview entry missing')
        tree = snapshot()
        assert nodes(tree, '呼出范围预览，彩色区域可斜滑，双击播放呼出动画', 'content-desc')
        names = ['底部长度', '底部高度', '侧边长度', '侧边宽度', '三角区域宽度', '三角区域高度']
        for name in names:
            for _ in range(6):
                sliders = [n for n in tree.iter('node') if n.get('class') == 'android.widget.SeekBar'
                           and n.get('content-desc', '').startswith(name + '，')]
                if sliders: break
                adb('shell', 'input', 'swipe', str(round(width * .065)), str(round(height * .85)), str(round(width * .065)), str(round(height * .60)), '300')
                tree = snapshot()
            assert sliders, name + ' missing'
        tap(nodes(tree, '播放动画')[0]); time.sleep(.4)
        capture('corner-range-preview.png')
        tap(nodes(tree, '左下角')[0]); tap(nodes(tree, '右下角')[0])
        # Bottom preview remains visible while the slider list scrolls.
        slider = next(n for n in tree.iter('node') if n.get('class') == 'android.widget.SeekBar'
                      and n.get('content-desc', '').startswith('三角区域高度，'))
        tap(slider)
        wait_value(REGION[5], '100')
        tap(nodes(snapshot(), '关闭')[0])
        # Saved range survives closing and reopening; there is no Done action.
        tree = snapshot(); tap(nodes(tree, '呼出范围与预览')[0]); tree = snapshot()
        assert not nodes(tree, '完成')
        tap(nodes(tree, '关闭')[0])
        print('Preview: all six dimensions, left/right animation, automatic save and reopen passed', flush=True)
    finally:
        restore(original)
        adb('shell', 'am', 'force-stop', PACKAGE)
        adb('shell', 'am', 'start', '-n', PACKAGE + '/.MainActivity')


if __name__ == '__main__': main()
