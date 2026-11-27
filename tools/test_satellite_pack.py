import json
import sys
import unittest
from contextlib import chdir, redirect_stdout
from io import StringIO
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest.mock import patch
from zipfile import ZipFile

import satellite_pack as pack


class SatellitePackRecoveryTest(unittest.TestCase):
    def test_render_retains_the_tile_and_its_generated_parent(self):
        tile = pack.mt.tile(55.3, 25.1, 14)
        with TemporaryDirectory() as temp, redirect_stdout(StringIO()):
            source = Path(temp) / 'patch.tif'
            root = Path(temp) / 'render'
            transform = pack.rio.transform.from_bounds(*pack.mt.xy_bounds(tile), 32, 32)
            with pack.rio.open(source, 'w', driver='GTiff', width=32, height=32,
                               count=3, dtype='uint16', crs='EPSG:3857', transform=transform) as raster:
                raster.write(pack.np.full((3, 32, 32), 2400, dtype='uint16'))
            area = pack.box(*pack.mt.bounds(tile)).buffer(-0.00001)
            written = pack.render(source, area, root, 14, 13)
            self.assertEqual(written, {tile, pack.mt.parent(tile)})
            self.assertTrue(all(pack.tile_path(root, t).is_file() for t in written))

    def test_failed_archive_write_preserves_previous_pack(self):
        with TemporaryDirectory() as temp, redirect_stdout(StringIO()):
            root = Path(temp) / 'render'
            tile = root / '14/1/2.webp'
            tile.parent.mkdir(parents=True)
            tile.write_bytes(b'first tile')
            output = Path(temp) / 'delivery' / 'UAE-satellite.zip'
            pack.package_tiles(root, [], output)
            previous = output.read_bytes()

            with patch.object(Path, 'read_bytes', side_effect=OSError('simulated tile read failure')):
                with self.assertRaises(OSError):
                    pack.package_tiles(root, [], output)
            self.assertEqual(output.read_bytes(), previous)

            tile.write_bytes(b'replacement tile')
            pack.package_tiles(root, [], output)
            with ZipFile(output) as updated:
                self.assertEqual(updated.read('14/1/2.webp'), b'replacement tile')

    def test_interrupted_render_requires_retry_and_drops_stale_tiles(self):
        with TemporaryDirectory() as temp, chdir(temp), redirect_stdout(StringIO()):
            work = Path('work')
            root = work / 'render'
            old = root / '14/0/0.webp'
            aerial = root / '19/0/0.webp'
            for path in (old, aerial):
                path.parent.mkdir(parents=True)
                path.write_bytes(b'previous render')
            (work / 'country.complete').touch()
            (work / 'surveys.json').write_text('[]')

            def run(step):
                with patch.object(sys, 'argv', ['satellite_pack', '--work', str(work), '--step', step]):
                    pack.main()

            run('package')
            output = Path('delivery/UAE-satellite.zip')
            previous = output.read_bytes()

            def replacement(*args):
                new = pack.mt.Tile(1, 2, 14)
                target = pack.tile_path(root, new)
                target.parent.mkdir(parents=True)
                target.write_bytes(b'new render')
                return {new}

            with (
                patch.object(pack, 'emirate_areas', return_value={'test': pack.box(55, 24, 56, 25)}),
                patch.object(pack, 'country', return_value=work / 'country.vrt'),
            ):
                with patch.object(pack, 'render', side_effect=OSError('interrupted render')):
                    with self.assertRaises(OSError):
                        run('country')
                with self.assertRaises(RuntimeError):
                    run('package')
                self.assertEqual(output.read_bytes(), previous)

                with patch.object(pack, 'render', side_effect=replacement):
                    run('country')
            run('package')
            with ZipFile(output) as updated:
                self.assertNotIn('14/0/0.webp', updated.namelist())
                self.assertEqual(updated.read('14/1/2.webp'), b'new render')
                self.assertEqual(updated.read('19/0/0.webp'), b'previous render')
                self.assertEqual(json.loads(updated.read('manifest.json'))['tileCount'], 2)

            with patch.object(pack, 'fetch_aerial', side_effect=OSError('interrupted aerial survey')):
                with self.assertRaises(OSError):
                    run('aerial')
            with self.assertRaises(RuntimeError):
                run('package')


if __name__ == '__main__':
    unittest.main()
