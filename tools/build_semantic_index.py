"""Build the Android English passage index from the pinned MIT-licensed Model2Vec model."""
import argparse
import hashlib
import json
import re
import struct
import unicodedata
from pathlib import Path

import numpy as np

ASSETS = Path(__file__).resolve().parents[1] / "app/src/main/assets"
REVISION = "389b9f64be5aa4ae7a6bc6fe95ef20ce485ae5da"


def passages():
    examples = json.loads((ASSETS / "manual/search-phrases.json").read_text(encoding="utf-8"))
    for gid, title, _, steps, _ in json.loads((ASSETS / "fieldbook.json").read_text(encoding="utf-8"))["guides"]:
        for i, step in enumerate(steps):
            body = step["body"] if isinstance(step, dict) else step
            heading = body.split(". ", 1)[0]
            yield f"{gid}/{i}", title, heading, body, examples[gid][heading]
    for guide in json.loads((ASSETS / "manual/lessons.json").read_text(encoding="utf-8")):
        for i, step in enumerate(guide["steps"]):
            heading = step["heading"]
            yield f"{guide['id']}/{i}", guide["title"], heading, step["body"], examples[guide["id"]][heading]


def pieces(text, vocab):
    clean = unicodedata.normalize("NFD", text.lower())
    clean = "".join(c for c in clean if unicodedata.category(c) != "Mn")
    for word in re.findall(r"[^\W_]+|[^\w\s]|_", clean):
        if len(word) > 100:
            continue
        i, parts = 0, []
        while i < len(word):
            for end in range(len(word), i, -1):
                token = ("" if i == 0 else "##") + word[i:end]
                if token in vocab:
                    parts.append(vocab[token])
                    i = end
                    break
            else:
                parts = []
                break
        yield from parts


def write_string(out, text):
    data = text.encode("utf-8")
    out.write(struct.pack(">H", len(data)) + data)


def build(folder, target):
    raw = (folder / "model.safetensors").read_bytes()
    tokens = (folder / "tokenizer.json").read_bytes()
    assert hashlib.sha256(raw).hexdigest() == "f95ffde02ad06f63ae38eb9d400038cd5ccaf8411ec3cb650c6025113f96cbb8"
    assert hashlib.sha256(tokens).hexdigest() == "e67e803f624fb4d67dea1c730d06e1067e1b14d830e2c2202569e3ef0f70bb50"
    n = struct.unpack_from("<Q", raw)[0]
    info = json.loads(raw[8:8+n])["embeddings"]
    assert info["dtype"] == "F32" and info["data_offsets"][0] == 0
    matrix = np.frombuffer(raw, dtype="<f4", offset=8+n).reshape(info["shape"])
    vocab = json.loads(tokens)["model"]["vocab"]
    scales = np.maximum(np.abs(matrix).max(axis=1) / 127, 1e-12)
    quantized = np.rint(matrix / scales[:, None]).astype(np.int8)
    weights = quantized * scales[:, None]
    rows = list(passages())
    target.parent.mkdir(parents=True, exist_ok=True)
    with target.open("wb") as out:
        out.write(b"FGS1" + struct.pack(">III", len(vocab), matrix.shape[1], len(rows)))
        for word, index in sorted(vocab.items(), key=lambda pair: pair[1]):
            write_string(out, word)
            out.write(struct.pack(">f", scales[index]))
            out.write(quantized[index].tobytes())
        for key, title, heading, body, phrases in rows:
            write_string(out, key)
            descriptions = [title + ". " + heading + ". " + body, heading + ". " + " ".join(phrases), *phrases]
            out.write(struct.pack(">H", len(descriptions)))
            for desc in descriptions:
                vector = weights[list(pieces(desc, vocab))].sum(axis=0)
                vector /= max(np.linalg.norm(vector), 1e-12)
                out.write(vector.astype(">f4").tobytes())
    print(len(rows), "passages;", target.stat().st_size, "bytes")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("model", type=Path, help="Local minishlab/potion-base-2M at " + REVISION)
    parser.add_argument("--benchmark-descriptions", type=Path, help="Also export the step descriptions used by AskBench")
    args = parser.parse_args()
    build(args.model, ASSETS / "search/semantic.bin")
    if args.benchmark_descriptions:
        descriptions = {}
        for key, _, heading, body, phrases in passages():
            excerpt = body.removeprefix(heading + ". ")[:190].replace("<", " ").replace(">", " ")
            descriptions[key] = heading + " | " + " / ".join(phrases[:2]) + " | " + excerpt
        args.benchmark_descriptions.write_text(json.dumps(descriptions), encoding="utf-8")
