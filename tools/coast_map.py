"""Build the small offline coastal backdrop shared by the original UAE map packs.

Input: the pack's UAE OSM extract and Natural Earth 1:10m land (GeoJSON).
OSM coastlines run with land on the left. Substitute the two mainland shore
sections into the surrounding regional outline, then restore offshore islands.
"""
from itertools import count
from pathlib import Path
import json
import subprocess

import osmium
import shapely
from shapely.geometry import MultiLineString, Point, Polygon, box, shape
from shapely.geometry.polygon import orient
from shapely.ops import substring

ROOT = Path(__file__).resolve().parents[1]
WORK = ROOT / ".build"
EXTENT = box(47, 17, 61, 32)


def polygons(geom):
    if geom.geom_type == "Polygon":
        yield geom
    elif hasattr(geom, "geoms"):
        for item in geom.geoms:
            yield from polygons(item)


def arc(ring, start, end):
    if start <= end:
        return list(substring(ring, start, end).coords)
    return list(substring(ring, start, ring.length).coords) + list(substring(ring, 0, end).coords)


def regional_water():
    earth = json.loads((WORK / "ne_10m_land.geojson").read_text(encoding="utf8"))
    land = shapely.union_all([p.intersection(EXTENT) for feature in earth["features"]
                              for p in polygons(shape(feature["geometry"])) if p.intersects(EXTENT)])
    mainland, *other_land = sorted(polygons(land), key=lambda p: -p.area)
    ring = orient(mainland, sign=1).exterior
    coast = []
    source = osmium.FileProcessor(WORK / "uae.osm.pbf").with_locations("flex_mem")
    for way in source.with_filter(osmium.filter.KeyFilter("natural")):
        if way.is_way() and way.tags.get("natural") == "coastline":
            coast.append([(node.lon, node.lat) for node in way.nodes])
    islands, sections = [], []
    for line in shapely.line_merge(MultiLineString(coast), directed=True).geoms:
        if line.is_closed:
            islands.extend(polygons(shapely.make_valid(Polygon(line))))
        else:
            a, b = Point(line.coords[0]), Point(line.coords[-1])
            if max(ring.distance(a), ring.distance(b)) > 0.025:
                raise ValueError("Review changed source coastline endpoints")
            sections.append((ring.project(a), ring.project(b), line))
    if len(sections) != 2:
        raise ValueError("Review the mainland joins before rebuilding this UAE extract")
    sections.sort(key=lambda s: s[0])
    coordinates = []
    for index, (_, end, line) in enumerate(sections):
        coordinates.extend(line.coords)
        coordinates.extend(arc(ring, end, sections[(index + 1) % len(sections)][0]))
    mainland = shapely.make_valid(Polygon(coordinates))
    island_index = shapely.STRtree(islands)
    neighbours = [p for p in other_land if len(island_index.query(p, predicate="dwithin", distance=0.012)) == 0]
    return EXTENT.difference(shapely.union_all([mainland, *islands, *neighbours]))


def write_water(water, output):
    ids = count(1)
    with osmium.SimpleWriter(str(output), overwrite=True) as writer:
        # The guide's emirate centres stay identifiable when street-scale labels are hidden.
        book = json.loads((ROOT / "app/src/main/assets/fieldbook.json").read_text(encoding="utf8"))
        for name, lat, lon, _ in book["emirates"]:
            group = "major_centre" if name in ("Abu Dhabi", "Dubai") else "emirate_centre"
            writer.add_node(osmium.osm.mutable.Node(id=next(ids), location=(lon,lat), tags={"overview":group, "name":name}))
        # Short generalized land borders provide context outside the country pack at low zoom.
        borders = json.loads((WORK / "ne_50m_admin_0_boundary_lines_land.geojson").read_text(encoding="utf8"))
        for feature in borders["features"]:
            geom = shape(feature["geometry"]).intersection(EXTENT)
            parts = geom.geoms if geom.geom_type == "MultiLineString" else [geom]
            for line in parts:
                if line.is_empty or line.geom_type != "LineString":
                    continue
                refs = []
                for xy in line.coords:
                    ref = next(ids)
                    writer.add_node(osmium.osm.mutable.Node(id=ref, location=xy))
                    refs.append(ref)
                writer.add_way(osmium.osm.mutable.Way(id=next(ids), nodes=refs, tags={"overview":"land_border"}))
        # Grid limits preprocessing and clipping costs in the renderer.
        west, south, east, north = (int(x * 2) for x in EXTENT.bounds)
        for col in range(west, east):
            for row in range(south, north):
                cell = box(col/2, row/2, (col+1)/2, (row+1)/2)
                for poly in polygons(water.intersection(cell)):
                    if poly.area < 1e-10:
                        continue
                    rings = [poly.exterior, *poly.interiors]
                    members = []
                    for index, ring in enumerate(rings):
                        refs = []
                        for xy in list(ring.coords)[:-1]:
                            ref = next(ids)
                            writer.add_node(osmium.osm.mutable.Node(id=ref, location=xy))
                            refs.append(ref)
                        wid = next(ids)
                        tags = {"natural": "sea"} if len(rings) == 1 else {}
                        writer.add_way(osmium.osm.mutable.Way(id=wid, nodes=refs + refs[:1], tags=tags))
                        members.append(("w", wid, "outer" if index == 0 else "inner"))
                    if len(rings) > 1:
                        writer.add_relation(osmium.osm.mutable.Relation(id=next(ids), members=members,
                                            tags={"type":"multipolygon", "natural":"sea"}))


if __name__ == "__main__":
    sea = regional_water()
    for lon, lat, expected in [(54.0,25.0,True),(56.50,25.15,True),(55.25,25.16,False),
                               (56.13,24.80,False),(54.37,24.47,False)]:
        assert sea.covers(Point(lon,lat)) == expected
    pbf = WORK / "coast.osm.pbf"
    write_water(sea, pbf)
    java = next(WORK.glob("jdk-17*/bin/java.exe"))
    libs = next((WORK / "osmosis").rglob("osmosis-core-*.jar")).parent
    staged = WORK / "coast.map"
    subprocess.run([java, "-Xmx1500m", "-XX:ActiveProcessorCount=2", "-cp",
                    str(libs/"*") + ";" + str(WORK/"writer.jar"), "org.openstreetmap.osmosis.core.Osmosis",
                    "--rb", "file="+str(pbf), "--mw", "file="+str(staged),
                    "tag-conf-file="+str(ROOT/"tools/coast-tags.xml"), "bbox=17,47,32,61", "threads=2", "zoom-interval-conf=6,0,8,10,9,11,13,12,21",
                    "type=ram"], check=True)
    staged.replace(ROOT/"app/src/main/assets/map/coast.map")
