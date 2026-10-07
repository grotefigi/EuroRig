import io
import json
import tarfile
import tempfile
import unittest
import zipfile
from pathlib import Path
from package_region import package, sha256


class RegionTests(unittest.TestCase):
    def test_manifest_matches_streamed_payloads(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with tarfile.open(root/'tiles.tar', 'w') as archive:
                tile = tarfile.TarInfo('2/000/123.gph'); tile.size=512
                archive.addfile(tile, io.BytesIO(b'x'*512))
            (root/'map.europack').write_bytes(b'ERG1test')
            manifest=package(root/'tiles.tar',root/'map.europack',root/'map.eurorig','Test','OSM','unknown')
            with zipfile.ZipFile(root/'map.eurorig') as archive:
                self.assertEqual(set(archive.namelist()),{'manifest.json','routing.tar','display.europack'})
                self.assertEqual(json.loads(archive.read('manifest.json')),manifest)
            self.assertEqual(manifest['sha256']['routing.tar'],sha256(root/'tiles.tar'))

    def test_non_valhalla_archive_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            with tarfile.open(root/'tiles.tar','w'): pass
            (root/'map.europack').write_bytes(b'ERG1test')
            with self.assertRaisesRegex(ValueError,'no Valhalla'):
                package(root/'tiles.tar',root/'map.europack',root/'map.eurorig','Test','OSM','unknown')


    def _tiles(self, root):
        with tarfile.open(root / 'tiles.tar', 'w') as archive:
            tile = tarfile.TarInfo('2/000/123.gph'); tile.size = 512
            archive.addfile(tile, io.BytesIO(b'x' * 512))
        (root / 'map.europack').write_bytes(b'ERG1test')

    def _package(self, root, bbox):
        self._tiles(root)
        return package(root / 'tiles.tar', root / 'map.europack', root / 'map.eurorig',
                       'Test', 'OSM', 'unknown', bbox)

    def test_coverage_published_only_when_valid(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            bbox = [16.108446, 42.229789, 30.278960, 48.589212]
            manifest = self._package(root, bbox)
            self.assertEqual(manifest['coverage'], {'tiles': 1, 'bbox': bbox})
            with zipfile.ZipFile(root / 'map.eurorig') as archive:
                self.assertEqual(json.loads(archive.read('manifest.json'))['coverage'], manifest['coverage'])

    def test_coverage_absent_is_allowed(self):
        with tempfile.TemporaryDirectory() as directory:
            self.assertNotIn('coverage', self._package(Path(directory), None))

    def test_malformed_coverage_refused_without_output(self):
        cases = {
            'reversed longitudes': [30.278960, 42.229789, 16.108446, 48.589212],
            'reversed latitudes': [16.108446, 48.589212, 30.278960, 42.229789],
            'degenerate box': [16.108446, 42.229789, 16.108446, 42.229789],
            'longitude out of range': [-200.0, 42.229789, 30.278960, 48.589212],
            'latitude out of range': [16.108446, 42.229789, 30.278960, 95.0],
            'not numbers': ['west', 'south', 'east', 'north'],
            'wrong arity': [16.108446, 42.229789, 30.278960],
            'infinity': [16.108446, 42.229789, float('inf'), 48.589212],
            'not finite': [16.108446, 42.229789, float('nan'), 48.589212],
        }
        for label, bbox in cases.items():
            with self.subTest(label), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                with self.assertRaises(ValueError):
                    self._package(root, bbox)
                self.assertFalse((root / 'map.eurorig').exists(),
                                 'A refused coverage box must not leave a package behind')


if __name__=='__main__': unittest.main()
