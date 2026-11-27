"""Build a local Mapsforge XYZ tile store from bulk open imagery (no WMTS crawling).
Run with the project GIS environment plus rasterio, mercantile, Pillow and numpy.
python tools/satellite_pack.py --work .build/uae-imagery --osm .build/uae.osm.pbf
"""
import argparse
import hashlib
import json
import subprocess
from pathlib import Path
from tempfile import TemporaryDirectory
from zipfile import ZipFile, ZipInfo
from xml.etree import ElementTree as ET

import mercantile as mt
import numpy as np
import rasterio as rio
import shapely
from affine import Affine
from PIL import Image
from rasterio.enums import ColorInterp, Resampling
from rasterio.vrt import WarpedVRT
from rasterio.windows import Window
from rasterio.warp import transform_bounds
from shapely.geometry import box
from place_index import emirate_areas

SURVEYS = [
    ("Al Wagan", "2021-01-24", "600d0a2701e6c7000542b612", "600d0a2701e6c7000542b613"),
    ("Cedre Villas Strip", "2021-01-13", "5fff0f65c46165000547372b", "5fff0f65c46165000547372c"),
    ("Al Ghail", "2021-03-06", "6202466c7b3a50000743047a", "6202466c7b3a50000743047b"),
]
OAM = "https://oin-hotosm-temp.s3.amazonaws.com/{}/0/{}.tif"
ESA = "https://esa-worldcover-s2.s3.eu-central-1.amazonaws.com/rgbnir/2021/N{}/ESA_WorldCover_10m_2021_v200_N{}E{:03d}_S2RGBNIR.tif"
LUT = np.minimum(255, 255 * (np.arange(65536) / 5400) ** .68).astype("uint8")
NOTICE = """UAE offline imagery, edition 1. Country layer: ESA WorldCover 2021 v200 annual
10 m optical RGB composite. Contains modified Copernicus Sentinel data (2021)
processed by the ESA WorldCover consortium. https://esa-worldcover.org/en/data-access
Local aerial surveys: Eric Horring via OpenAerialMap, dates and original files
in manifest.json. https://openaerialmap.org/
Imagery: Creative Commons Attribution 4.0 International.
https://creativecommons.org/licenses/by/4.0/
Changes: subset, colour stretch (ESA), reprojection, resampling and WebP encoding.
Boundary selection: © OpenStreetMap contributors, https://www.openstreetmap.org/copyright
Survey and satellite dates describe historical scenes. Coverage varies over water.
"""

def fetch_aerial(work):
    sources = []
    for name, date, folder, ident in SURVEYS:
        path = work / (ident + ".tif")
        url = OAM.format(folder, ident)
        if not path.exists():
            with rio.open(url) as src:
                ratio = min(1, abs(src.res[0]) / .23)
                h, w = round(src.height * ratio), round(src.width * ratio)
                rgb = src.read([1, 2, 3], out_shape=(3, h, w), resampling=Resampling.bilinear)
                alpha = src.dataset_mask(out_shape=(h, w))
                alpha[~np.any(rgb, axis=0)] = 0
                tmp = path.with_suffix(".tmp")
                with rio.open(tmp, "w", driver="GTiff", width=w, height=h, count=4,
                              dtype="uint8", crs=src.crs, tiled=True, compress="deflate",
                              transform=src.transform * Affine.scale(src.width/w, src.height/h)) as out:
                    out.write(rgb, [1, 2, 3])
                    out.write(alpha, 4)
                    out.colorinterp = (ColorInterp.red, ColorInterp.green, ColorInterp.blue, ColorInterp.alpha)
                tmp.replace(path)
        sources.append(dict(title=name, date=date, url=url, author="Eric Horring"))
        print(name, path.stat().st_size, flush=True)
    return sources

def country(work, region):
    bounds = region.bounds
    sources = []
    for lat in range(int(bounds[1]), int(bounds[3])+1):
        for lon in range(int(bounds[0]), int(bounds[2])+1):
            if not region.intersects(box(lon, lat, lon+1, lat+1)):
                continue
            url = ESA.format(lat, lat, lon)
            path = work / url.rsplit("/", 1)[1]
            if not path.exists():
                part = path.with_suffix(".part")
                # Some ocean cells do not have a published land product.
                result = subprocess.run(["curl", "-fLsS", "--retry", "3", "-C", "-", "-o", str(part), url, "-w", "%{http_code}"], capture_output=True)
                if result.stdout == b"404":
                    continue
                result.check_returncode()
                part.replace(path)
            sources.append((lat, lon, path))
    west, south = min(i[1] for i in sources), min(i[0] for i in sources)
    east, north = max(i[1]+1 for i in sources), max(i[0]+1 for i in sources)
    n = 12000
    vrt = ET.Element("VRTDataset", rasterXSize=str((east-west)*n), rasterYSize=str((north-south)*n))
    ET.SubElement(vrt, "SRS").text = "EPSG:4326"
    ET.SubElement(vrt, "GeoTransform").text = f"{west},{1/n},0,{north},0,{-1/n}"
    for i in range(1, 4):
        band = ET.SubElement(vrt, "VRTRasterBand", dataType="UInt16", band=str(i))
        ET.SubElement(band, "NoDataValue").text = "0"
        for lat, lon, path in sources:
            s = ET.SubElement(band, "SimpleSource")
            ET.SubElement(s, "SourceFilename", relativeToVRT="1").text = path.name
            ET.SubElement(s, "SourceBand").text = str(i)
            ET.SubElement(s, "SrcRect", xOff="0", yOff="0", xSize=str(n), ySize=str(n))
            ET.SubElement(s, "DstRect", xOff=str((lon-west)*n), yOff=str((north-lat-1)*n), xSize=str(n), ySize=str(n))
    path = work / "country.vrt"
    ET.ElementTree(vrt).write(path)
    return path

def tile_path(root, t):
    return root / str(t.z) / str(t.x) / (str(t.y)+".webp")

def save(root, tile, im):
    a = im.getchannel("A").getextrema()
    if a[1] == 0:
        return False
    path = tile_path(root, tile)
    path.parent.mkdir(parents=True, exist_ok=True)
    if a[0] == 255:
        im = im.convert("RGB")
    im.save(path, "WEBP", quality=83, method=1)
    return True

def render(path, shape, root, maximum, minimum):
    candidates = [t for t in mt.tiles(*shape.bounds, maximum) if shape.intersects(box(*mt.bounds(t)))]
    xmin, ymin = min(t.x for t in candidates), min(t.y for t in candidates)
    xmax, ymax = max(t.x for t in candidates), max(t.y for t in candidates)
    origin = mt.xy_bounds(mt.Tile(xmin,ymin,maximum))
    res = (origin.right-origin.left)/256
    populated = set()
    with rio.open(path) as src, WarpedVRT(src, crs="EPSG:3857", transform=Affine(res,0,origin.left,0,-res,origin.top),
            width=(xmax-xmin+1)*256, height=(ymax-ymin+1)*256, resampling=Resampling.bilinear,
            warp_mem_limit=96) as ds:
        blocks = {}
        for t in candidates:
            blocks.setdefault(((t.x-xmin)//4, (t.y-ymin)//4), []).append(t)
        for index, ((bx, by), tiles) in enumerate(sorted(blocks.items())):
            if index % 150 == 0:
                print(path.name, index, "/", len(blocks), flush=True)
            x, y = bx*4, by*4
            window = Window(x*256, y*256, min(4,xmax-xmin+1-x)*256, min(4,ymax-ymin+1-y)*256)
            data = ds.read(window=window)
            for t in tiles:
                xx, yy = (t.x-xmin-x)*256, (t.y-ymin-y)*256
                pixels = data[:,yy:yy+256,xx:xx+256]
                if maximum == 14:
                    rgb = LUT[pixels[:3]]
                    alpha = (np.any(pixels[:3],axis=0)*255).astype("uint8")
                else:
                    rgb, alpha = pixels[:3], pixels[3]
                im = Image.fromarray(np.dstack((rgb.transpose(1,2,0),alpha)))
                if save(root,t,im):
                    populated.add(t)
    written = set(populated)
    for z in range(maximum-1, minimum-1,-1):
        parents = {mt.parent(t) for t in populated}
        next_level = set()
        for t in parents:
            out = Image.new("RGBA",(512,512))
            for child in mt.children(t):
                if child in populated:
                    with Image.open(tile_path(root,child)) as im:
                        out.paste(im.convert("RGBA"),((child.x%2)*256,(child.y%2)*256))
            if save(root,t,out.resize((256,256),Image.Resampling.LANCZOS)):
                next_level.add(t)
        populated = next_level
        written.update(populated)
    return written


def remove_stale_tiles(root, zooms, written):
    keep = {tile_path(root, tile) for tile in written}
    for zoom in zooms:
        for path in (root / str(zoom)).glob("*/*.webp"):
            if path not in keep:
                path.unlink()


def package_tiles(root, surveys, target):
    tiles = sorted(root.glob("*/*/*.webp"))
    manifest = dict(edition=1, scheme="XYZ", tileCount=len(tiles), country="ESA WorldCover 2021 v200", surveys=surveys)
    target.parent.mkdir(parents=True, exist_ok=True)
    with TemporaryDirectory(dir=target.parent) as staging:
        candidate = Path(staging) / target.name
        with ZipFile(candidate, "w") as out:
            out.writestr(ZipInfo("manifest.json"), json.dumps(manifest, indent=2))
            out.writestr(ZipInfo("NOTICE.txt"), NOTICE.encode())
            for tile in tiles:
                out.writestr(ZipInfo(tile.relative_to(root).as_posix()), tile.read_bytes())
        candidate.replace(target)
    with target.open("rb") as stream:
        print(target, target.stat().st_size, hashlib.file_digest(stream, "sha256").hexdigest(), len(tiles), flush=True)

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--work", type=Path, default=Path(".build/uae-imagery"))
    parser.add_argument("--osm", type=Path, default=Path(".build/uae.osm.pbf"))
    parser.add_argument("--step", choices=["aerial","country","package","all"], default="all")
    args = parser.parse_args()
    work, root = args.work, args.work / "render"
    work.mkdir(parents=True,exist_ok=True)
    survey_manifest = work / "surveys.json"
    country_ready = work / "country.complete"
    if args.step in ("aerial","all"):
        survey_manifest.unlink(missing_ok=True)
        records = fetch_aerial(work)
        written = set()
        for record, (_,_,_,ident) in zip(records, SURVEYS):
            path = work / (ident+".tif")
            with rio.open(path) as src:
                boundary = transform_bounds(src.crs,"EPSG:4326",*src.bounds)
            record["bounds"] = boundary
            written.update(render(path, box(*boundary), root, 19, 15))
        remove_stale_tiles(root, range(15, 20), written)
        survey_manifest.write_text(json.dumps(records))
    if args.step in ("country","all"):
        country_ready.unlink(missing_ok=True)
        region = shapely.union_all(list(emirate_areas(args.osm).values())).buffer(.05).simplify(.001).buffer(.001)
        written = render(country(work,region), region, root, 14, 10)
        remove_stale_tiles(root, range(10, 15), written)
        country_ready.touch()
    if args.step in ("package","all"):
        if not country_ready.is_file() or not survey_manifest.is_file():
            raise RuntimeError("Complete both country and aerial rendering before packaging.")
        package_tiles(root, json.loads(survey_manifest.read_text()), Path("delivery/UAE-satellite.zip"))

if __name__ == "__main__":
    with rio.Env(GDAL_CACHEMAX=256*1024*1024, GDAL_DISABLE_READDIR_ON_OPEN="EMPTY_DIR", GDAL_HTTP_TIMEOUT="60", GDAL_HTTP_MAX_RETRY="3"):
        main()
