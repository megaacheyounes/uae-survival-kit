"""Build display and searchable places from a single offline UAE OSM extract."""
import hashlib
import json
import shutil
import subprocess
import zipfile
from pathlib import Path

import osmium
from place_index import create_index

ROOT = Path(__file__).resolve().parents[1]
WORK = ROOT / ".build"
PBF = WORK / "uae.osm.pbf"
SOURCE = "https://download.openstreetmap.fr/extracts/asia/united_arab_emirates-latest.osm.pbf"
def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def main():
    out = WORK / "map-output"
    out.mkdir(exist_ok=True)
    java = next(WORK.glob("jdk-17*/bin/java.exe"))
    osmlib = next((WORK / "osmosis").rglob("osmosis-core-*.jar")).parent
    pbf_hash = digest(PBF)
    mapped = out / "uae.map"
    places = out / "places.sqlite"
    # A single rendered map can only belong to one extract at a time.
    source_stamp = out / "rendered-source.sha256"
    if not (mapped.is_file() and source_stamp.is_file() and source_stamp.read_text() == pbf_hash):
        source_stamp.unlink(missing_ok=True)
        subprocess.run([str(java),"-Xmx5g","-XX:ActiveProcessorCount=2","-cp",str(osmlib / "*")+";"+str(WORK / "writer.jar"),"org.openstreetmap.osmosis.core.Osmosis","--rb",f"file={PBF}","--mw",f"file={mapped}","bbox=22.60,51.50,26.20,56.55","preferred-languages=en,ar","threads=2","type=ram"], check=True)
        source_stamp.write_text(pbf_hash)
    staged = out / "places.build.sqlite"
    staged.unlink(missing_ok=True)
    by_kind = create_index(PBF, staged)
    staged.replace(places)
    header=osmium.io.Reader(str(PBF))
    try:
        date=header.header().get("osmosis_replication_timestamp")[:10]
    finally:
        header.close()
    if not date:
        import re
        date = re.search(r"\d{4}-\d{2}-\d{2}", (WORK / "osm-state.txt").read_text()).group()
    manifest={"format":1,"region":"AE","date":date,"version":"2-"+date,"directory_schema":2,"counts":by_kind,"source":SOURCE,"source_sha256":pbf_hash,"attribution":"© OpenStreetMap contributors · ODbL 1.0","files":{p.name:{"bytes":p.stat().st_size,"sha256":digest(p)} for p in (mapped,places)}}
    target=ROOT/"delivery"/"UAE-map.zip"
    target.parent.mkdir(exist_ok=True)
    with zipfile.ZipFile(target.with_suffix(".tmp"),"w",zipfile.ZIP_DEFLATED,compresslevel=2) as z:
        def entry(name):
            info = zipfile.ZipInfo(name, tuple(map(int,date.split("-"))) + (0,0,0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            return info
        z.writestr(entry("manifest.json"),json.dumps(manifest,ensure_ascii=False))
        for p in (mapped, places):
            with z.open(entry(p.name),"w") as dest, p.open("rb") as src:
                shutil.copyfileobj(src,dest)
    target.with_suffix(".tmp").replace(target)
    print(json.dumps(manifest,indent=2,ensure_ascii=True))


if __name__ == "__main__":
    main()
