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


if __name__=='__main__': unittest.main()
