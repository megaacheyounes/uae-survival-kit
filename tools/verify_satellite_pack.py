"""Check unvisited areas, dataset coverage, decoded imagery and archive integrity."""
import argparse
import hashlib
import io
import json
from pathlib import Path
from zipfile import ZipFile

import mercantile
from PIL import Image, ImageStat
from verify_delivery import CITIES


def verify(path):
    resources = Path("app/src/main/java/ae/fieldguide/Resources.kt").read_text(encoding="utf8")
    with path.open("rb") as src:
        digest = hashlib.file_digest(src, "sha256").hexdigest()
    assert f'{path.stat().st_size}L, "{digest}"' in resources, "App must pin the finished pack"
    with ZipFile(path) as archive:
        assert archive.testzip() is None, "ZIP checksum error"
        manifest = json.loads(archive.read("manifest.json"))
        names = set(archive.namelist())
        assert len(names) == manifest["tileCount"] + 2 == len(archive.infolist())

        def lookup(lat, lon, first, last):
            for zoom in range(first, last + 1):
                t = mercantile.tile(lon, lat, zoom)
                name = f"{t.z}/{t.x}/{t.y}.webp"
                with Image.open(io.BytesIO(archive.read(name))) as im:
                    assert im.size == (256, 256) and im.format == "WEBP"
                    b = mercantile.xy_bounds(t)
                    x, y = mercantile.xy(lon, lat)
                    sample = (int(256*(x-b.left)/(b.right-b.left)), int(256*(b.top-y)/(b.top-b.bottom)))
                    assert im.convert("RGBA").getpixel(sample)[3] > 0, name
                    assert max(ImageStat.Stat(im.convert("RGB")).stddev) > 2, name
            return f"zooms {first}–{last}"

        for city, lat, lon in CITIES + [("Sir Bani Yas",24.327,52.607), ("Liwa",23.141,53.776)]:
            print(city, lookup(lat, lon, 10, 14))
        for survey in manifest["surveys"]:
            west,south,east,north = survey["bounds"]
            print(survey["title"], lookup((north+south)/2,(east+west)/2,15,19))
        assert len(manifest["surveys"]) == 3
        print(f'{manifest["tileCount"]} tiles, SHA-256 {digest}')


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("path", type=Path, nargs="?", default=Path("delivery/UAE-satellite.zip"))
    verify(parser.parse_args().path)
