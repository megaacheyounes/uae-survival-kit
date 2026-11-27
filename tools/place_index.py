"""Compile the UAE offline place directory from the same OSM file as the map."""
from collections import defaultdict
from math import cos, radians
import sqlite3

import osmium
import shapely
from shapely.geometry import MultiLineString, Point, Polygon

EMIRATES = {
    "AE-AZ": "Abu Dhabi", "AE-DU": "Dubai", "AE-SH": "Sharjah", "AE-AJ": "Ajman",
    "AE-UQ": "Umm Al Quwain", "AE-RK": "Ras Al Khaimah", "AE-FU": "Fujairah",
}
PLACES = {"city", "town", "village", "hamlet", "suburb", "neighbourhood", "quarter", "district", "locality", "island"}
SPELLINGS = {
    "خورفكان": "Khor Fakkan Khorfakkan Khawr Fakkan", "خور فكان": "Khor Fakkan Khorfakkan",
    "أم القيوين": "Umm Al Quwain Umm Al Qaywayn", "رأس الخيمة": "Ras Al Khaimah Ras Al-Khaimah",
    "حتا": "Hatta",
}
GROCERIES = set("supermarket convenience grocery greengrocer bakery butcher seafood dairy farm deli frozen_food food health_food nuts spices general".split())
REPAIRS = set("hardware doityourself trade tools building_materials electrical plumbing locksmith car_repair car_parts tyres bicycle_repair tool_hire".split())
FOOD = {"restaurant", "fast_food", "cafe", "food_court", "ice_cream"}
NOT_A_SHOP = {"no", "vacant", "closed", "empty", "construction", "abandoned", "disused", "former", "proposed"}
KEYS = ("place", "amenity", "healthcare", "shop", "highway", "railway", "public_transport",
        "natural", "man_made", "drinking_water", "tourism", "craft", "atm")
TITLES = {
    "atm": "ATM", "yes": "Shop", "fuel": "Fuel station", "police": "Police station",
    "fast_food": "Fast food", "doityourself": "DIY supplies", "halt": "Rail halt",
    "water_point": "Water point", "toilets": "Toilets", "taxi": "Taxi stand",
    "clinic": "Clinic", "doctors": "Medical practice", "bus_stop": "Bus stop",
}


def label(value):
    return TITLES.get(value, value.replace("_", " ").capitalize())


def classify(t):
    """An explicitly mapped secondary facility can belong to another category too."""
    if any(t.get(k) == "yes" for k in ("disused", "abandoned", "demolished")):
        return {}
    found = {}
    amenity, shop, medical = (t.get(k) for k in ("amenity", "shop", "healthcare"))
    if t.get("place") in PLACES:
        found[t["place"]] = label(t["place"])
    for key in ("hospital", "pharmacy"):
        if key in (amenity, medical):
            found[key] = label(key)
    if amenity in ("police", "fuel", "fire_station", "toilets"):
        found[amenity] = label(amenity)
    if amenity in ("clinic", "doctors") or medical in ("clinic", "doctor"):
        found["clinic"] = label(amenity if amenity in ("clinic", "doctors") else "clinic")
    if amenity in FOOD:
        found["restaurant"] = label(amenity)
    if shop in GROCERIES or amenity == "marketplace":
        found["grocery"] = label("marketplace" if amenity == "marketplace" else shop)
    elif shop in REPAIRS:
        found["repair"] = label(shop)
    elif shop and shop not in NOT_A_SHOP:
        found["shopping"] = label(shop)
    if t.get("craft") in ("carpenter", "plumber", "electrician", "locksmith", "shoemaker") or amenity == "bicycle_repair_station":
        found["repair"] = label(amenity if amenity == "bicycle_repair_station" else t["craft"])
    if amenity in ("bank", "atm"):
        found["bank"] = label(amenity)
    elif t.get("atm") == "yes":
        found["bank"] = "ATM"
    if amenity in ("post_office", "post_box", "parcel_locker"):
        found["post"] = label(amenity)
    if amenity in ("bus_station", "ferry_terminal", "taxi"):
        found["transport"] = label(amenity)
    elif t.get("highway") == "bus_stop":
        found["transport"] = "Bus stop"
    elif t.get("railway") in ("station", "halt", "tram_stop", "subway_entrance", "platform"):
        found["transport"] = "Rail " + t["railway"] if t["railway"] in ("station", "platform") else label(t["railway"])
    elif t.get("public_transport") in ("platform", "station"):
        found["transport"] = "Transit " + t["public_transport"]
    potable = t.get("drinking_water")
    if potable == "yes" or (amenity == "drinking_water" and potable is None):
        found["drinking_water"] = "Drinking water"
    elif (t.get("man_made") in ("water_well", "water_tap") or t.get("natural") == "spring"
          or amenity in ("water_point", "drinking_water")):
        source = t.get("man_made") or t.get("natural") or "water_point"
        found["water_source"] = label(source) + (" · Marked non-potable" if potable == "no" else " · Potability unknown")
    if t.get("tourism") in ("camp_site", "alpine_hut", "wilderness_hut"):
        found["shelter"] = label(t["tourism"])
    elif amenity == "shelter":
        found["shelter"] = "Transit shelter" if t.get("shelter_type") == "public_transport" else label(t.get("shelter_type", "shelter"))
    return found


def emirate_areas(source):
    # Some offshore ways fall outside the extract. Use complete rings only, retain
    # enclaves and holes, and never guess an emirate from its nearest city.
    roles = {}
    for r in osmium.FileProcessor(source, entities=osmium.osm.RELATION).with_filter(osmium.filter.KeyFilter("ISO3166-2")):
        code = r.tags.get("ISO3166-2")
        if code in EMIRATES:
            roles[code] = [(m.ref, m.role) for m in r.members if m.type == "w" and m.role in ("outer", "inner", "")]
    if set(roles) != set(EMIRATES):
        raise ValueError("Missing emirate boundary relations")
    ids = {ref for members in roles.values() for ref, _ in members}
    ways = {}
    for w in osmium.FileProcessor(source).with_locations("flex_mem").with_filter(osmium.filter.EntityFilter(osmium.osm.WAY)).with_filter(osmium.filter.IdFilter(ids)):
        if all(n.location.valid() for n in w.nodes):
            ways[w.id] = [(n.lon,n.lat) for n in w.nodes]
    regions = {}
    for code, members in roles.items():
        def rings(inner):
            lines = [ways[ref] for ref, role in members if ref in ways and (role == "inner") == inner]
            merged = shapely.line_merge(MultiLineString(lines))
            return [shapely.make_valid(Polygon(r.coords)) for r in shapely.get_parts(merged) if r.is_closed]
        holes = rings(True)
        pieces = [outer.difference(shapely.union_all([h for h in holes if outer.covers(h)])) for outer in rings(False)]
        region = shapely.union_all(pieces)
        if region.is_empty:
            raise ValueError("No boundary for " + code)
        shapely.prepare(region)
        regions[EMIRATES[code]] = region
    return regions


def coordinates(obj, factory):
    if obj.is_node():
        return (obj.location.lon, obj.location.lat) if obj.location.valid() else None
    if obj.is_way():
        if len(obj.nodes) < 2 or not all(n.location.valid() for n in obj.nodes):
            return None
        coords = [(n.lon,n.lat) for n in obj.nodes]
        if obj.is_closed() and len(coords) >= 4:
            point = Polygon(coords).representative_point()
        else:
            point = shapely.LineString(coords).interpolate(0.5, normalized=True)
    elif obj.is_area() and not obj.from_way():
        point = shapely.from_wkb(bytes.fromhex(factory.create_multipolygon(obj))).representative_point()
    else:
        return None
    return (point.x, point.y) if not point.is_empty else None


def create_index(source, path):
    regions = emirate_areas(source)
    factory = osmium.geom.WKBFactory()
    keys = osmium.filter.KeyFilter(*KEYS)
    data = osmium.FileProcessor(source).with_locations("flex_mem").with_areas(keys).with_filter(keys)
    rows = []
    towns = defaultdict(list)
    for obj in data:
        if obj.is_relation() or (obj.is_area() and obj.from_way()):
            continue
        cats = classify(obj.tags)
        if not cats:
            continue
        xy = coordinates(obj, factory)
        if xy is None:
            continue
        lon, lat = xy
        point = Point(lon, lat)
        emirate = next((name for name, poly in regions.items() if poly.covers(point)), None)
        if emirate is None:
            continue
        t = obj.tags
        names = [t.get(k,"") for k in ("name:en", "name", "name:ar", "alt_name", "name:en_alt", "official_name", "brand", "operator")]
        names.extend(SPELLINGS[n] for n in tuple(names) if n in SPELLINGS)
        name = next(filter(None, names), "")
        address = ", ".join(dict.fromkeys(filter(None, [" ".join(filter(None,[t.get("addr:housenumber"),t.get("addr:street")])),
                                                            t.get("addr:suburb"), t.get("addr:city") or t.get("addr:town")])))
        access = {"private":"Private access", "customers":"Customers only", "no":"Access restricted",
                  "permit":"Permit required", "members":"Members only"}.get(t.get("access"), "")
        for kind, detail in cats.items():
            if kind in PLACES and name:
                towns[(emirate, int(lat*20), int(lon*20))].append((name,lat,lon))
            rows.append((name or detail.split(" · ")[0], names[2], kind, lat, lon,
                         " ".join(dict.fromkeys(filter(None,names))), detail, address, emirate, access))

    # Give unnamed stops and facilities a local reference even where no street was tagged.
    def with_locality(row):
        title, ar, kind, lat, lon, aliases, detail, address, emirate, access = row
        if not address and kind not in PLACES:
            x, y = int(lat*20), int(lon*20)
            candidates = (item for i in range(x-1,x+2) for j in range(y-1,y+2) for item in towns[(emirate,i,j)])
            longitude_scale = cos(radians(lat))
            closest = min((((a-lat)**2+((b-lon)*longitude_scale)**2, n) for n,a,b in candidates), default=None)
            if closest and closest[0] < 0.04**2:
                address = "Near " + closest[1]
        aliases = " ".join([aliases, detail, address, emirate])
        return (title, ar, kind, lat, lon, aliases, detail, address, emirate, access)

    with sqlite3.connect(path) as db:
        db.execute("""CREATE TABLE places(name TEXT,arabic TEXT,kind TEXT,lat REAL,lon REAL,aliases TEXT,
                                          detail TEXT,area TEXT,emirate TEXT,access TEXT)""")
        db.executemany("INSERT INTO places VALUES(?,?,?,?,?,?,?,?,?,?)",map(with_locality, dict.fromkeys(rows)))
        db.execute("CREATE INDEX by_category ON places(kind,emirate,lat)")
        db.execute("CREATE VIRTUAL TABLE search USING fts4(name,arabic,kind,aliases,tokenize=unicode61)")
        db.execute("INSERT INTO search(docid,name,arabic,kind,aliases) SELECT rowid,name,arabic,kind,aliases FROM places")
        db.execute("PRAGMA user_version=2")
        by_kind = dict(db.execute("SELECT kind,count(*) FROM places GROUP BY kind"))
    db.close()
    return by_kind
