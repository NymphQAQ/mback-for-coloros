"""Exercise production foreground fitting against all six original bundled images.

Needs the existing Python/Pillow runtime and JDK; no phone or payment pages.
"""

import io
import json
import math
import subprocess
import tempfile
import zipfile
from functools import lru_cache
from pathlib import Path

from PIL import Image, ImageDraw


@lru_cache(maxsize=1)
def artwork():
    folder = Path("app/src/main/assets/preset-icons")
    names = [item["asset"] for item in json.loads((folder / "sources.json").read_text())]
    originals = []
    with tempfile.TemporaryDirectory() as directory:
        paths = []
        with zipfile.ZipFile("app/build/local/mback-debug.apk") as apk:
            for index, name in enumerate(names):
                data = apk.read("assets/preset-icons/" + name)
                assert data == (folder / name).read_bytes(), "Bundled original changed: " + name
                source = Image.open(io.BytesIO(data)).convert("RGBA").resize((256, 256), Image.Resampling.BILINEAR)
                originals.append(source)
                path = Path(directory) / (str(index) + ".png")
                source.save(path)
                paths.append(str(path))
        subprocess.run(["javac", "-d", directory,
                        "app/src/main/java/dev/mback/oneplus/IconLayout.java",
                        "tests/dev/mback/oneplus/IconLayoutTest.java"], check=True)
        output = subprocess.check_output(["java", "-Djava.awt.headless=true", "-cp", directory,
                                          "dev.mback.oneplus.IconLayoutTest", *paths], text=True)
    print(output.splitlines()[-1], flush=True)
    circles = []
    for source, line in zip(originals, output.splitlines()):
        _, scale, x, y = line.split()
        scale, x, y = map(float, (scale, x, y))
        background = source.getpixel((0, 0))
        fitted = source.transform((256, 256), Image.Transform.AFFINE,
                                  (1 / scale, 0, -x / scale, 0, 1 / scale, -y / scale),
                                  Image.Resampling.BICUBIC, fillcolor=background)
        for angle in range(0, 360, 5):
            for radius in (104, 112, 120):
                point = (round(127.5 + radius * math.cos(math.radians(angle))),
                         round(127.5 + radius * math.sin(math.radians(angle))))
                assert fitted.getpixel(point) == background, "Foreground or white border reached the outer brand ring"
        mask = Image.new("L", (1024, 1024))
        ImageDraw.Draw(mask).ellipse((0, 0, 1023, 1023), fill=255)
        fitted.putalpha(mask.resize((256, 256), Image.Resampling.LANCZOS))
        assert fitted.getpixel((0, 0))[3] == 0
        circles.append(fitted)
    return originals, circles


def main():
    originals, circles = artwork()
    preview = Image.new("RGB", (6 * 160, 2 * 170), "#e8eaee")
    for row, icons in enumerate((originals, circles)):
        for column, icon in enumerate(icons):
            icon = icon.resize((144, 144), Image.Resampling.LANCZOS)
            preview.paste(icon, (column * 160 + 8, row * 170 + 12), icon)
    path = Path("app/build/local/ui/preset-circle-artwork.png")
    path.parent.mkdir(parents=True, exist_ok=True)
    preview.save(path)
    print("Six original glyphs fit completely; brand ring has no white border. Preview: " + str(path), flush=True)


if __name__ == "__main__":
    main()
