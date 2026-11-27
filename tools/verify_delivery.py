"""Check archive payloads and geographic coverage independently of the build."""
import argparse
import hashlib
import io
import json
import sqlite3
import struct
import tempfile
import zipfile
from xml.etree import ElementTree
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CITIES = [
    ("Abu Dhabi", 24.46, 54.37), ("Dubai", 25.23, 55.31),
    ("Sharjah", 25.36, 55.39), ("Ajman", 25.41, 55.45),
    ("Umm Al Quwain", 25.56, 55.55), ("Ras Al Khaimah", 25.79, 55.95),
    ("Fujairah", 25.13, 56.33), ("Hatta", 24.80, 56.13),
    ("Khor Fakkan", 25.34, 56.35),
]
ALIASES = {"Ras Al Khaimah":["ras al khaimah","ras al-khaimah","رأس الخيمة"],"Umm Al Quwain":["umm al quwain","umm al-quwain","أم القيوين"],"Khor Fakkan":["khor fakkan","khorfakkan","khawr fakkan","خورفكان"],"Fujairah":["fujairah","الفجيرة"]}


def sha(stream):
    return hashlib.file_digest(stream, "sha256").hexdigest()


def verify(map_only=False, apk_path=None):
    folder = ROOT / "delivery"
    with zipfile.ZipFile(folder/"UAE-map.zip") as pack, tempfile.TemporaryDirectory() as tmp:
        manifest = json.loads(pack.read("manifest.json"))
        assert manifest["region"] == "AE" and manifest["date"]
        for name, info in manifest["files"].items():
            with pack.open(name) as stream:
                assert sha(stream) == info["sha256"]
            assert pack.getinfo(name).file_size == info["bytes"]
        assert pack.read("uae.map")[:20] == b"mapsforge binary OSM"
        path = pack.extract("places.sqlite", tmp)
        with sqlite3.connect(path) as db:
            assert db.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
            assert db.execute("SELECT count(*) FROM search WHERE search MATCH 'دبي'").fetchone()[0]
            assert db.execute("PRAGMA user_version").fetchone()[0] == manifest["directory_schema"] == 2
            counts = dict(db.execute("SELECT kind, count(*) FROM places GROUP BY kind"))
            assert manifest["counts"] == counts
            required = {"grocery", "restaurant", "transport", "shopping", "bank", "drinking_water",
                        "water_source", "toilets", "post", "repair", "shelter", "fire_station",
                        "hospital", "pharmacy", "police", "fuel", "clinic"}
            assert all(counts.get(kind, 0) > 0 for kind in required)
            assert {row[0] for row in db.execute("SELECT DISTINCT emirate FROM places")} == {name for name, _, _ in CITIES[:7]}
            assert db.execute("SELECT 1 FROM places WHERE kind='drinking_water' AND detail!='Drinking water'").fetchone() is None
            assert db.execute("SELECT 1 FROM places WHERE kind='water_source' AND detail LIKE '%non-potable%'").fetchone()
            assert db.execute("SELECT 1 FROM places WHERE name='Hatta Hospital' AND emirate='Dubai'").fetchone()
            assert db.execute("SELECT 1 FROM places WHERE name='Khor Fakkan' AND kind IN ('town','city') AND emirate='Sharjah'").fetchone()
            for city, lat, lon in CITIES:
                nearby = db.execute("""SELECT name,arabic,kind,aliases FROM places
                    WHERE abs(lat-?1)<0.07 AND abs(lon-?2)<0.07
                    ORDER BY (lat-?1)*(lat-?1) + 0.83*(lon-?2)*(lon-?2)""",(lat,lon)).fetchall()
                names = ALIASES.get(city,[city.lower()])
                locations=[row for row in nearby if row[2] in ('city','town','village') and any(n in (row[0].lower(),row[1].lower()) for n in names)]
                services=[row for row in nearby if row[2] in ('hospital','police','pharmacy','fuel')]
                assert locations and services, f"Missing locality or service: {city}"
                print(city, "|", locations[0][0], "|", services[0][0])
            print("Indexed places:", db.execute("SELECT count(*) FROM places").fetchone()[0])
        db.close()
    if map_only:
        return
    with (folder/"qwen2.5-1.5b-instruct-q4_k_m.gguf").open("rb") as model:
        assert sha(model) == "6a1a2eb6d15622bf3c96857206351ba97e1af16c30d7a74ee38970e434e9407e"
    with zipfile.ZipFile(apk_path or folder/"UAE-Field-Guide.apk") as apk:
        with apk.open("assets/UAE-map.zip") as included, (folder/"UAE-map.zip").open("rb") as transfer:
            assert sha(included) == sha(transfer)
        assert "lib/arm64-v8a/libfieldguide.so" in apk.namelist()
        for language in ("en", "ar"):
            card = f"water-card-{language}.pdf"
            data = apk.read("assets/" + card)
            assert data.startswith(b"%PDF-") and data == (ROOT / "app/src/main/assets" / card).read_bytes(), card
        for name in ("libllama.so", "libggml.so", "libggml-base.so", "libggml-cpu-android_armv8.0_1.so"):
            assert "lib/arm64-v8a/" + name in apk.namelist(), name
        assert "assets/map/satellite-2025.zip" not in apk.namelist()
        assert apk.read("assets/licenses/UAE-Survival-Kit.txt") == (ROOT / "LICENSE").read_bytes()
        theme = ElementTree.fromstring(apk.read("assets/map/field.xml"))
        night_theme = ElementTree.fromstring(apk.read("assets/map/field-night.xml"))
        assert theme.attrib["map-background"] != night_theme.attrib["map-background"]
        def feature_rules(root):
            palette = {"map-background", "map-background-outside", "fill", "stroke"}
            return [(node.tag, {key: value for key, value in node.attrib.items() if key not in palette})
                    for node in root.iter()]
        assert feature_rules(theme) == feature_rules(night_theme), "Night map lost a feature or symbol"
        with apk.open("assets/map/coast.map") as coast:
            assert coast.read(20) == b"mapsforge binary OSM"
        for symbol in theme.iter("{http://mapsforge.org/renderTheme}symbol"):
            ElementTree.fromstring(apk.read("assets/map/" + symbol.attrib["src"].removeprefix("assets:")))
        assert len(json.loads(apk.read("assets/fieldbook.json"))["emirates"]) == 7
        manual = json.loads(apk.read("assets/manual/lessons.json"))
        photos = {p["file"] for p in json.loads(apk.read("assets/manual/photos.json"))}
        illustrations = {step["image"] for lesson in manual for step in lesson["steps"] if "image" in step}
        for asset in illustrations:
            path = "assets/manual/" + asset
            if asset in photos:
                with apk.open(path) as photo:
                    assert photo.read(2) == b"\xff\xd8", asset
            else:
                bounds = ElementTree.fromstring(apk.read(path + ".svg")).attrib["viewBox"].split()
                assert len(bounds) == 4 and all(float(v) > 0 for v in bounds[2:]), asset
        water_steps = next(lesson["steps"] for lesson in manual if lesson["id"] == "findwater")
        water_citations = [step["id"] for step in water_steps if "id" in step]
        assert len(water_citations) == len(set(water_citations))
        assert {"known-supply", "water-symbol", "dubai-refill"} <= set(water_citations)
        quick = json.loads(apk.read("assets/fieldbook.json"))["guides"]
        headings = {lesson["id"]: {step["heading"] for step in lesson["steps"]} for lesson in manual}
        scenarios = json.loads(apk.read("assets/manual/practice.json"))
        assert scenarios and len({s["id"] for s in scenarios}) == len(scenarios)
        for scenario in scenarios:
            assert scenario["tasks"] and all(scenario["tasks"].values())
            questions = scenario["questions"]
            assert questions and len({q["id"] for q in questions}) == len(questions)
            for q in questions:
                assert q["heading"] in headings[q["guide"]], q["id"]
                assert len(set(q["choices"])) == len(q["choices"]) >= 2
                assert 0 <= q["answer"] < len(q["choices"])
        for guide_id, _, _, steps, _ in quick:
            headings[guide_id] = {
                (step["body"] if isinstance(step, dict) else step).split(". ")[0] for step in steps
            }
        phrases = json.loads(apk.read("assets/manual/search-phrases.json"))
        # Check that every searchable step can still open in this APK's reader.
        index = io.BytesIO(apk.read("assets/search/semantic.bin"))
        assert index.read(4) == b"FGS1"
        words, dims, count = struct.unpack(">III", index.read(12))
        assert words == 29528 and dims == 64
        def read_key():
            return index.read(int.from_bytes(index.read(2), "big")).decode()
        for _ in range(words):
            read_key()
            index.seek(4 + dims, 1)
        keys = set()
        for _ in range(count):
            keys.add(read_key())
            variants = int.from_bytes(index.read(2), "big")
            assert variants > 0 and len(index.read(variants * dims * 4)) == variants * dims * 4
        assert len(keys) == count and not index.read(1)
        assert keys == {f"{gid}/{i}" for gid, steps in headings.items() for i in range(len(steps))}
        assert set(headings) == set(phrases)
        for guide_id, expected in headings.items():
            assert expected == set(phrases[guide_id]), guide_id
            assert all(phrases[guide_id].values())
        pages = {}
        for raw in apk.read("assets/manual/booklet-pages.jsonl").splitlines():
            booklet, page, content = json.loads(raw)
            assert "assets/manual/" + booklet + ".pdf" in apk.namelist()
            assert page == pages.get(booklet, 0) and content.strip()
            pages[booklet] = page + 1
        assert len(pages) == 3
        assert apk.testzip() is None


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--map-only", action="store_true")
    parser.add_argument("--apk", type=Path)
    args = parser.parse_args()
    verify(args.map_only, args.apk)
