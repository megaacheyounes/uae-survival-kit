"""Regenerate the offline page-text index after replacing a bundled PDF. Requires pypdf."""
import json
from pathlib import Path

from pypdf import PdfReader

MANUAL = Path(__file__).resolve().parents[1] / "app/src/main/assets/manual"


def main():
    lines = []
    for path in sorted(MANUAL.glob("*.pdf")):
        reader = PdfReader(path)
        for index, page in enumerate(reader.pages):
            lines.append(json.dumps([path.stem, index, page.extract_text() or ""], ensure_ascii=True))
    (MANUAL / "booklet-pages.jsonl").write_text("\n".join(lines) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
