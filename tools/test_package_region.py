import io
import json
import tarfile
import tempfile
import unittest
import zipfile
from pathlib import Path
from package_region import normalise, package, sha256


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

    def test_existing_package_or_normalization_file_is_never_overwritten(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            destination=root/'map.eurorig'
            temporary=root/'map.eurorig.normalising'
            for existing in (destination,temporary):
                existing.write_bytes(b'caller-owned file')
                with self.assertRaises(FileExistsError):
                    package(root/'absent.tar',root/'absent.sqlite',destination,'Test','OSM','unknown')
                self.assertEqual(b'caller-owned file',existing.read_bytes())
                existing.unlink()
            with zipfile.ZipFile(destination,'x') as archive:
                archive.writestr('fixture','original')
            before=destination.read_bytes()
            temporary.write_bytes(b'caller-owned normalization file')
            with self.assertRaises(FileExistsError):normalise(destination)
            self.assertEqual(before,destination.read_bytes())
            self.assertEqual(b'caller-owned normalization file',temporary.read_bytes())


if __name__=='__main__': unittest.main()
