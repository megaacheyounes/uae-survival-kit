"""Generate the bilingual kit's two print layouts from its Android string resources."""
from html import escape
from pathlib import Path
from xml.etree import ElementTree

ROOT = Path(__file__).resolve().parents[1]
STYLE = """@page { size: A4; margin: 17mm 19mm }
body { margin: 0; color: #172c2a; font: 14pt/1.42 Arial, sans-serif }
h1 { font-size: 29pt; margin: 0 0 8mm; border-bottom: 3pt solid #285751 }
h2 { font-size: 17pt; margin: 5mm 0 1mm }
p { margin: 0 }
aside { border: 1pt solid #416760; padding: 1mm 5mm 4mm; margin-top: 7mm }
aside p { margin-top: 3mm }
footer { margin-top: 5mm; font-size: 9pt }"""

android = "{http://schemas.android.com/apk/res/android}"
drawing = ElementTree.parse(ROOT / "app/src/main/res/drawable/water_containers.xml")
paths = []
for path in drawing.getroot():
    color = path.get(android + "fillColor")
    fill = "none" if color == "#00000000" else color
    paths.append(f'<path d="{path.get(android + "pathData")}" fill="{fill}" '
                 f'stroke="{path.get(android + "strokeColor", "none")}" stroke-width="2.4"/>')
jugs = '<svg width="70" height="34" viewBox="0 0 90 44">' + "".join(paths) + "</svg>"

for lang, folder, direction in (("en", "values", "ltr"), ("ar", "values-ar", "rtl")):
    strings = {node.attrib["name"]: escape(node.text) for node in
               ElementTree.parse(ROOT / "app/src/main/res" / folder / "water.xml").getroot()}
    sections = []
    for topic in ("safe", "storage", "refill", "return"):
        key = "water_" + topic
        sections.append(f"<h2>{strings[key]}</h2><p>{strings[key + '_body']}</p>")
        if topic == "storage":
            sections.append(f'<div style="margin-top:2mm">{jugs}<small style="display:block">{strings["water_example"]}</small></div>')
    card = (f'<!doctype html><html lang="{lang}" dir="{direction}"><meta charset="utf-8">'
            f'<style>{STYLE}</style><title>{strings["water_title"]}</title><h1>{strings["water_title"]}</h1>'
            + "".join(sections)
            + f'<aside><h2>{strings["water_handover"]}</h2><p>{strings["water_paper_line"]}</p>'
              f'<p>{strings["water_paper_source"]}</p></aside><footer>UAE Field Guide · 1.24 · CDC · cdc.gov/water-emergency</footer></html>')
    out = ROOT / ".build/water124" / f"water-card-{lang}.html"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(card, encoding="utf-8")
