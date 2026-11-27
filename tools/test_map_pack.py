import subprocess
import tempfile
import unittest
import zipfile
from contextlib import ExitStack, redirect_stdout
from io import StringIO
from pathlib import Path
from unittest.mock import patch

import map_pack


class MapPackCacheTest(unittest.TestCase):
    def test_switch_extracts_and_retry_an_interrupted_render(self):
        with tempfile.TemporaryDirectory() as tmp, ExitStack() as mocks:
            root = Path(tmp)
            work = root / ".build"
            for relative in ("jdk-17-test/bin/java.exe", "osmosis/osmosis-core-test.jar"):
                tool = work / relative
                tool.parent.mkdir(parents=True)
                tool.touch()
            pbf = work / "uae.osm.pbf"
            for key, path in (("ROOT", root), ("WORK", work), ("PBF", pbf)):
                mocks.enter_context(patch.object(map_pack, key, path))
            header = mocks.enter_context(patch.object(map_pack.osmium.io, "Reader"))
            header.return_value.header.return_value.get.return_value = "2026-11-17"

            def render(args, check):
                mapped = Path(args[args.index("--mw") + 1].removeprefix("file="))
                mapped.write_bytes(pbf.read_bytes())
                if pbf.read_bytes() == b"interrupted":
                    raise subprocess.CalledProcessError(1, args)

            def index(source, target):
                target.write_bytes(source.read_bytes())
                return {}

            writer = mocks.enter_context(patch.object(map_pack.subprocess, "run", side_effect=render))
            mocks.enter_context(patch.object(map_pack, "create_index", side_effect=index))
            mocks.enter_context(redirect_stdout(StringIO()))
            for data, renders in [(b"A", 1), (b"A", 1), (b"B", 2), (b"A", 3),
                                  (b"interrupted", 4), (b"A", 5)]:
                pbf.write_bytes(data)
                if data == b"interrupted":
                    with self.assertRaises(subprocess.CalledProcessError):
                        map_pack.main()
                else:
                    map_pack.main()
                    with zipfile.ZipFile(root / "delivery/UAE-map.zip") as pack:
                        self.assertEqual(pack.read("uae.map"), data)
                        self.assertEqual(pack.read("places.sqlite"), data)
                self.assertEqual(writer.call_count, renders)


if __name__ == "__main__":
    unittest.main()
