"""Execute the production ZIP/restore implementation on the host JVM."""

from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
with tempfile.TemporaryDirectory(prefix="mback-backup-tests-") as classes:
    subprocess.run([
        "rtk", "proxy", "javac", "-source", "8", "-target", "8", "-d", classes,
        str(ROOT / "app/src/main/java/dev/mback/oneplus/SettingsArchive.java"),
        str(ROOT / "tests/dev/mback/oneplus/SettingsArchiveTest.java"),
    ], check=True)
    subprocess.run([
        "rtk", "proxy", "java", "-ea", "-Xmx256m", "-cp", classes,
        "dev.mback.oneplus.SettingsArchiveTest",
    ], check=True)
