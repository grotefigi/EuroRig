import json
import hashlib
import tempfile
import unittest
import zipfile
from pathlib import Path
from make_catalog import MAX_RELEASE_ASSET_BYTES, catalog, checked_asset_size
from package_region import sha256


class CatalogueTests(unittest.TestCase):
    def package(self, directory, native_version='0.6.3', overrides=None):
        path=Path(directory)/'Romania-2026-10-17.eurorig'
        payloads={'routing.tar':b'valid routing payload','display.sqlite':b'valid display payload'}
        with zipfile.ZipFile(path,'w') as archive:
            manifest={'format':2,'engine':'valhalla','native_version':native_version,'name':'Romania',
                      'routing_source':'OpenStreetMap / Geofabrik','routing_date':'2026-10-17T20:20:51Z',
                      'license':'ODbL-1.0','attribution':'© OpenStreetMap contributors',
                      'sha256':{name:hashlib.sha256(data).hexdigest() for name,data in payloads.items()}}
            manifest.update(overrides or {})
            archive.writestr('manifest.json',json.dumps(manifest))
            for name,data in payloads.items():archive.writestr(name,data)
        return path

    def test_catalog_records_immutable_asset_hash_and_host_url(self):
        with tempfile.TemporaryDirectory() as directory:
            package=self.package(directory)
            result=catalog([('romania',package)],'https://example.invalid/releases/maps-current/')
            self.assertEqual(result['maps'],[{
                'id':'romania','name':'Romania','url':'https://example.invalid/releases/maps-current/'+package.name,
                'bytes':package.stat().st_size,'sha256':sha256(package),'osm_timestamp':'2026-10-17T20:20:51Z',
                'license':'ODbL-1.0','attribution':'© OpenStreetMap contributors','source':'OpenStreetMap / Geofabrik',
            }])
            self.assertFalse(result['europe_complete'])

    def test_catalog_rejects_incompatible_map_and_non_https_host(self):
        with tempfile.TemporaryDirectory() as directory:
            package=self.package(directory,native_version='0.6.2')
            with self.assertRaisesRegex(ValueError,'Incompatible'):
                catalog([('romania',package)])
            with self.assertRaisesRegex(ValueError,'HTTPS'):
                catalog([('romania',package)],'http://example.invalid/maps/')

    def test_release_asset_limit_is_strictly_below_two_gib(self):
        self.assertEqual(checked_asset_size(MAX_RELEASE_ASSET_BYTES-1),MAX_RELEASE_ASSET_BYTES-1)
        for size in (0,MAX_RELEASE_ASSET_BYTES,MAX_RELEASE_ASSET_BYTES+1):
            with self.subTest(size=size),self.assertRaisesRegex(ValueError,'2 GiB'):
                checked_asset_size(size)

    def test_russia_and_duplicate_ids_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            package=self.package(directory)
            with self.assertRaisesRegex(ValueError,'excludes Russia'):
                catalog([('russia',package)])
            with self.assertRaisesRegex(ValueError,'Duplicate'):
                catalog([('romania',package),('romania',package)])

    def test_catalog_rejects_missing_rights_and_mismatched_inner_payload(self):
        with tempfile.TemporaryDirectory() as directory:
            package=self.package(directory,overrides={'license':''})
            with self.assertRaisesRegex(ValueError,'license'):
                catalog([('romania',package)])
            package=self.package(directory,overrides={'sha256':{'routing.tar':'0'*64,'display.sqlite':'0'*64}})
            with self.assertRaisesRegex(ValueError,'routing.tar'):
                catalog([('romania',package)])

    def test_catalog_rejects_corrupted_archive_crc(self):
        with tempfile.TemporaryDirectory() as directory:
            package=self.package(directory)
            data=bytearray(package.read_bytes());offset=data.index(b'valid routing payload');data[offset]^=1;package.write_bytes(data)
            with self.assertRaisesRegex(ValueError,'ZIP CRC'):
                catalog([('romania',package)])


if __name__=='__main__': unittest.main()
