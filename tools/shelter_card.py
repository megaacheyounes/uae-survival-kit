"""Write localized print-ready HTML cards from the kit's Android strings."""
from html import escape
from pathlib import Path
from xml.etree import ElementTree

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "app/src/main/res"


def column(folder, language, direction):
    rows = ElementTree.parse(RES / folder / "kit.xml").getroot()
    strings = {row.attrib["name"]: escape(row.text) for row in rows}
    blocks = []
    for topic in ("inside", "stay", "outside", "updates"):
        key = "kit_" + topic
        blocks.append(f"<h2>{strings[key]}</h2><p>{strings[key + '_text']}</p>")
    return f'<section lang="{language}" dir="{direction}">' + "".join(blocks) + "</section>"


if __name__ == "__main__":
    style = """@page{size:A4;margin:20mm}body{font:16pt/1.45 Arial,sans-serif;color:#18251f;margin:0}
h1{font-size:30pt;border-bottom:3px solid #365b45;padding-bottom:10px}
h2{font-size:19pt;margin:18px 0 4px}p{margin:0}
footer{margin-top:24px;border-top:1px solid;padding-top:8px;font-size:10pt}"""
    for folder, lang, direction in (("values","en","ltr"), ("values-ar","ar","rtl")):
        title = ElementTree.parse(RES / folder / "kit.xml").find("string[@name='kit_shelter']").text
        target = ROOT / ".build" / f"shelter-card-{lang}.html"
        target.parent.mkdir(exist_ok=True)
        target.write_text(f'<!doctype html><html lang="{lang}" dir="{direction}"><meta charset=utf-8>'
                          f'<style>{style}</style><h1>{escape(title)}</h1>' + column(folder, lang, direction)
                          + '<footer>CDC · cdc.gov/radiation-emergencies</footer></html>', encoding="utf8")
