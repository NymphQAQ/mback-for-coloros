"""Switch both transit launch modes and check native windows through the real corner menu.

Requires the installed, reloaded module and an unlocked phone. Restores settings
and Assistant's enabled state; never takes screenshots of payment/transit pages.
"""

import json
import os
import re
import shlex
import subprocess
import tempfile
from pathlib import Path

from main_ui_test import adb, entries, preferences, snapshot, tab, tap, wait_value
from transit_freeform_test import MODE_KEY, ORDER, transit_window
from preset_shortcuts_test import KEY, PACKAGE, PRESETS, write_shortcuts


ASSISTANT = "com.coloros.assistantscreen"
CHECKS = PACKAGE + ".transitchecks"


def run(*args):
    return subprocess.check_output(["rtk", "proxy", *map(str, args)], text=True,
                                   stderr=subprocess.STDOUT, timeout=180)


def root(command):
    return adb("shell", "su", "-c", shlex.quote(command))


def preset_checks(mode):
    sdk = Path(os.environ.get("ANDROID_HOME", str(Path.home() / "Library/Android/sdk")))
    tools, android = sdk / "build-tools/36.0.0", sdk / "platforms/android-35/android.jar"
    with tempfile.TemporaryDirectory() as directory:
        folder = Path(directory)
        manifest = folder / "AndroidManifest.xml"
        manifest.write_text('<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="' + CHECKS + '">'
                            '<application android:label="MBack transit checks"/>'
                            '<instrumentation android:name="dev.mback.oneplus.TransitShortcutsInstrumentation" '
                            'android:targetPackage="dev.mback.oneplus"/></manifest>')
        classes, dex, apk = folder / "classes", folder / "dex", folder / "checks.apk"
        classes.mkdir()
        dex.mkdir()
        run("javac", "-source", "8", "-target", "8", "-cp", str(android) + ":app/build/local/classes",
            "-d", classes, "tests/android/TransitShortcutsInstrumentation.java")
        run(tools / "d8", "--min-api", "29", "--lib", android, "--output", dex, *classes.rglob("*.class"))
        run(tools / "aapt2", "link", "-o", apk, "--manifest", manifest, "-I", android,
            "--min-sdk-version", "29", "--target-sdk-version", "35")
        run("zip", "-q", "-j", apk, dex / "classes.dex")
        run(tools / "apksigner", "sign", "--ks", Path.home() / ".android/debug.keystore",
            "--ks-key-alias", "androiddebugkey", "--ks-pass", "pass:android", "--key-pass", "pass:android", apk)
        run("adb", "push", apk, "/data/local/tmp/mback-transit-checks.apk")
        root("pm install -r /data/local/tmp/mback-transit-checks.apk")
        result = adb("shell", "am", "instrument", "-w", "-r", "-e", "mode", mode, CHECKS + "/dev.mback.oneplus.TransitShortcutsInstrumentation")
        assert "Transit preset checks passed" in result and "INSTRUMENTATION_CODE: -1" in result, result
        print(mode + ": routing, availability, legacy IDs, unchanged non-transit routes and argument rejection passed", flush=True)
    print("Direct launch requires the system permission and SystemUI token; arbitrary parameters are rejected", flush=True)


def restore_assistant(state):
    command = {"0": "default-state", "1": "enable", "2": "disable", "3": "disable-user", "4": "disable-until-used"}[state]
    root("pm " + command + " --user 0 " + ASSISTANT)


def mode_settings():
    for package in ("com.tencent.mm", "com.eg.android.AlipayGphone"):
        adb("shell", "am", "force-stop", package)
    root("settings delete global " + MODE_KEY)
    adb("shell", "am", "force-stop", PACKAGE)
    adb("shell", "am", "start", "-W", "--windowingMode", "1", "-n", PACKAGE + "/.MainActivity")
    tree = snapshot()
    tap(tab(tree, "快捷小窗"))
    tree = snapshot()
    assert "负一屏启动（默认）" in entries(tree, "乘车码启动方式")[0].get("content-desc", "")
    tap(entries(tree, "乘车码启动方式")[0])
    tree = snapshot()
    choices = [n for n in tree.iter("node") if n.get("class") == "android.widget.RadioButton"]
    assert len(choices) == 2 and choices[0].get("checked") == "true", "Assistant is not the fresh-install default"
    assert "短暂全屏" in choices[0].get("text", "") and "更新后可能需要适配" in choices[1].get("text", "")
    assert adb("shell", "settings", "get", "global", MODE_KEY).strip() == "null", "Opening settings wrote a default"
    tap(choices[1])
    wait_value(MODE_KEY, "1")
    adb("shell", "input", "keyevent", "4")
    tree = snapshot()
    assert "直接小窗启动" in entries(tree, "乘车码启动方式")[0].get("content-desc", ""), "Home summary did not update"
    adb("shell", "am", "force-stop", PACKAGE)
    adb("shell", "am", "start", "-W", "--windowingMode", "1", "-n", PACKAGE + "/.MainActivity")
    tree = snapshot()
    tap(tab(tree, "快捷小窗"))
    tree = snapshot()
    tap(entries(tree, "乘车码启动方式")[0])
    tree = snapshot()
    choices = [n for n in tree.iter("node") if n.get("class") == "android.widget.RadioButton"]
    assert choices[1].get("checked") == "true", "Direct mode did not survive an app restart"
    tap(choices[0])
    wait_value(MODE_KEY, "0")
    root("settings delete global " + MODE_KEY)
    adb("shell", "input", "keyevent", "4")
    print("Mode UI passed: default, explanation, autosave, home summary and persistence", flush=True)


def main():
    policy = adb("shell", "dumpsys", "window", "policy")
    assert re.search(r"^\s+showing=false$", policy, re.M), "Unlock the phone before testing"
    original = preferences()
    state = re.search(r"User 0: [^\n]* enabled=(\d+)", adb("shell", "dumpsys", "package", ASSISTANT))[1]
    assert original.get("mback_corner_menu_enabled", "1") == "1", "Enable corner shortcuts first"
    assert state in ("0", "1"), "Enable Assistant before running both modes"
    try:
        mode_settings()
        preset_checks("assistant")
        root("pm disable-user --user 0 " + ASSISTANT)
        preset_checks("unavailable")
        root("settings put global " + MODE_KEY + " 1")
        preset_checks("direct")
        write_shortcuts(json.dumps([{"id": PRESETS[index][1]} for index in ORDER]))
        for cold in (True, False):
            for index in (4, 5):
                transit_window(index, original, cold, direct=True)
        restore_assistant(state)
        root("settings put global " + MODE_KEY + " 0")
        for cold in (True, False):
            for index in (4, 5):
                transit_window(index, original, cold)
    finally:
        adb("shell", "input", "motionevent", "CANCEL", "0", "0")
        adb("shell", "input", "keyevent", "KEYCODE_HOME")
        adb("shell", "am", "force-stop", PACKAGE)
        for package in ("com.tencent.mm", "com.eg.android.AlipayGphone"):
            adb("shell", "am", "force-stop", package)
        if KEY in original:
            write_shortcuts(original[KEY])
        else:
            root("settings delete global " + KEY)
        root("settings put global " + MODE_KEY + " " + original[MODE_KEY]
             if MODE_KEY in original else "settings delete global " + MODE_KEY)
        restore_assistant(state)
        if "package:" + CHECKS in adb("shell", "pm", "list", "packages", CHECKS).splitlines():
            root("pm uninstall " + CHECKS)
        adb("shell", "rm", "-f", "/data/local/tmp/mback-transit-checks.apk", "/data/local/tmp/mback-main-test.xml")
        assert preferences() == original, "Original settings were not restored"
    print("Both modes passed; original settings and Assistant state restored", flush=True)


if __name__ == "__main__":
    main()
