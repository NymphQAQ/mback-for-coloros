"""Run native Canvas checks against actual installed icons, without opening apps.

Uses only the installed Android SDK/JDK and a temporary stock instrumentation APK.
"""

import os
import subprocess
import tarfile
import tempfile
from pathlib import Path


def run(*args):
    return subprocess.check_output(args, text=True, stderr=subprocess.STDOUT, timeout=180)


def main():
    sdk = Path(os.environ.get("ANDROID_HOME", str(Path.home() / "Library/Android/sdk")))
    build = sdk / "build-tools/36.0.0"
    android = sdk / "platforms/android-35/android.jar"
    package = "dev.mback.oneplus.iconchecks"
    with tempfile.TemporaryDirectory() as directory:
        folder = Path(directory)
        manifest = folder / "AndroidManifest.xml"
        manifest.write_text('<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="' + package + '">'
                            '<application android:label="MBack icon checks"/>'
                            '<instrumentation android:name="dev.mback.oneplus.IconRenderingInstrumentation" '
                            'android:targetPackage="dev.mback.oneplus"/></manifest>')
        classes = folder / "classes"
        dex = folder / "dex"
        classes.mkdir()
        dex.mkdir()
        run("javac", "-source", "8", "-target", "8", "-cp", str(android) + ":app/build/local/classes",
            "-d", str(classes), "tests/android/IconRenderingInstrumentation.java")
        run(str(build / "d8"), "--min-api", "29", "--lib", str(android), "--output", str(dex),
            *map(str, classes.rglob("*.class")))
        apk = folder / "checks.apk"
        run(str(build / "aapt2"), "link", "-o", str(apk), "--manifest", str(manifest), "-I", str(android),
            "--min-sdk-version", "29", "--target-sdk-version", "35")
        subprocess.run(["zip", "-q", "-j", str(apk), str(dex / "classes.dex")], check=True)
        run(str(build / "apksigner"), "sign", "--ks", str(Path.home() / ".android/debug.keystore"),
            "--ks-key-alias", "androiddebugkey", "--ks-pass", "pass:android", "--key-pass", "pass:android", str(apk))
        try:
            run("adb", "install", "-r", str(apk))
            result = run("adb", "shell", "am", "instrument", "-w", "-r", package + "/dev.mback.oneplus.IconRenderingInstrumentation")
            print(result, flush=True)
            assert "INSTRUMENTATION_CODE: -1" in result and "Native icon rendering passed:" in result
            evidence = Path("app/build/local/ui")
            evidence.mkdir(parents=True, exist_ok=True)
            archive = evidence / "icon-render-checks.tar"
            archive.write_bytes(subprocess.check_output(["adb", "exec-out", "su", "-c",
                                "tar -C /data/user/0/dev.mback.oneplus/cache -cf - icon-render-checks"]))
            with tarfile.open(archive) as data:
                data.extractall(evidence, filter="data")
        finally:
            subprocess.run(["adb", "uninstall", package], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=30)
            run("adb", "shell", "su", "-c", "rm -rf /data/user/0/dev.mback.oneplus/cache/icon-render-checks")


if __name__ == "__main__":
    main()
