import hashlib
import gzip
import io
import json
import sqlite3
import tarfile
import tempfile
import unittest
from unittest.mock import patch
import zipfile
from contextlib import closing
from pathlib import Path

import check_qa_package
from package_region import (MANIFEST_LIMIT, canonical_tile_name, compress_tiles, package, read_generation, sha256)

# The shapes real coherent builds use: level 0 and 1 with two groups, level 2 with three.
TILE_A = '0/003/019.gph'
TILE_B = '2/000/761/203.gph'
TILE_C = '1/048/082.gph'


def tile_bytes(payload=b'x'):
    return payload * 512


def working_directory():
    """A temp dir that tolerates the read-only SQLite handles assertions may still hold on Windows.

    The library itself never leaves a handle open (it uses contextlib.closing); this is only so a test
    that has just read an index cannot fail for a reason unrelated to what it asserts.
    """
    return tempfile.TemporaryDirectory(ignore_cleanup_errors=True)


def display(root):
    """A minimal indexed display database: user_version 1, SQLite magic. Connection fully closed."""
    path = root / 'map.sqlite'
    with closing(sqlite3.connect(path)) as db:
        db.execute('PRAGMA user_version=1')
        db.execute('CREATE TABLE roads(id INTEGER PRIMARY KEY)')
        db.commit()
    return path


def index_rows(payload, root, name='index.sqlite'):
    """Open an extracted tiles.sqlite read-only and return (version, schema, meta, rows, columns)."""
    path = root / name
    path.write_bytes(payload)
    with closing(sqlite3.connect(f'file:{path.as_posix()}?mode=ro', uri=True)) as db:
        version = db.execute('PRAGMA user_version').fetchone()[0]
        schema = dict(db.execute('SELECT name, sql FROM sqlite_master WHERE type="table"'))
        meta = dict(db.execute('SELECT key, value FROM metadata'))
        rows = db.execute('SELECT path, sha256, size FROM tiles ORDER BY path').fetchall()
        columns = [row[1] for row in db.execute('PRAGMA table_info(tiles)')]
        meta_columns = [row[1] for row in db.execute('PRAGMA table_info(metadata)')]
    return version, schema, meta, rows, columns, meta_columns


def tiles_tar(root, entries, symlink=None):
    """entries: list of (name, payload). Duplicate names are allowed so the rejection can be tested."""
    path = root / 'tiles.tar'
    with tarfile.open(path, 'w') as archive:
        for name, payload in entries:
            info = tarfile.TarInfo(name)
            info.size = len(payload)
            archive.addfile(info, io.BytesIO(payload))
        if symlink is not None:
            link = tarfile.TarInfo(symlink)
            link.type = tarfile.SYMTYPE
            link.linkname = 'elsewhere.gph'
            archive.addfile(link)
    return path


def generation(root, name='coherent-generation.json', tiles=None, generation_id='50f27278e441170d'):
    path = root / name
    payload = {'generation_id': generation_id, 'tiles': tiles if tiles is not None else {}}
    path.write_text(json.dumps(payload), encoding='utf-8')
    return path


def claims_for(entries, extra=None):
    tiles = {name: hashlib.sha256(payload).hexdigest() for name, payload in entries}
    tiles.update(extra or {})
    return tiles


class RegionTests(unittest.TestCase):
    def test_experimental_compression_preserves_canonical_and_stored_identity(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            entries=[(TILE_A,tile_bytes(b'a')),(TILE_B,tile_bytes(b'b'))]
            source=tiles_tar(root,entries)
            original=sha256(source)
            coherent=generation(root,tiles=claims_for(entries))
            target=root/'compressed.eurorig'
            manifest=package(source,display(root),target,'Test','Original fixture','unknown',
                             generation=coherent,country='RO',experimental_compressed=True)
            with zipfile.ZipFile(target) as archive:
                self.assertEqual(manifest['sha256']['routing.tar'],hashlib.sha256(archive.read('routing.tar')).hexdigest())
                index=root/'index.sqlite';index.write_bytes(archive.read('tiles.sqlite'))
                with closing(sqlite3.connect(index)) as db:
                    self.assertEqual(db.execute('PRAGMA user_version').fetchone()[0],2)
                    rows={p:(h,n,ch,cn) for p,h,n,ch,cn in db.execute('SELECT * FROM tiles')}
                with tarfile.open(fileobj=io.BytesIO(archive.read('routing.tar'))) as routing:
                    self.assertEqual(set(routing.getnames()),{name+'.gz' for name,_ in entries})
                    for name,payload in entries:
                        packed=routing.extractfile(name+'.gz').read()
                        self.assertEqual(gzip.decompress(packed),payload)
                        self.assertEqual(rows[name],(hashlib.sha256(payload).hexdigest(),len(payload),hashlib.sha256(packed).hexdigest(),len(packed)))
            self.assertEqual(sha256(source),original)
            self.assertFalse(list(root.glob('.*.part')))

    def test_compression_refuses_changed_input_claims(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);source=tiles_tar(root,[(TILE_A,tile_bytes())])
            with self.assertRaisesRegex(ValueError,'changed during compression'):
                compress_tiles(source,root/'out.tar',[(TILE_A,'f'*64,512)])

    def test_experimental_compression_requires_generation_and_preserves_output_on_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);source=tiles_tar(root,[(TILE_A,tile_bytes())]);map_file=display(root)
            target=root/'map.eurorig';target.write_bytes(b'previous verified package')
            with self.assertRaisesRegex(ValueError,'requires --generation'):
                package(source,map_file,target,'Test','Original fixture','unknown',experimental_compressed=True)
            coherent=generation(root,tiles=claims_for([(TILE_A,tile_bytes())]))
            with patch('package_region.zipfile.ZipFile.write',side_effect=OSError('injected write failure')):
                with self.assertRaisesRegex(OSError,'injected'):
                    package(source,map_file,target,'Test','Original fixture','unknown',generation=coherent,country='RO',experimental_compressed=True)
            self.assertEqual(target.read_bytes(),b'previous verified package')
            self.assertFalse(list(root.glob('.*.part')))

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


class CanonicalPathTests(unittest.TestCase):
    """The path shape real builds produce - the shape a wrong regex silently refuses."""

    def test_real_shapes_accepted(self):
        for name in ('0/003/019.gph', '1/048/082.gph', '2/000/761/203.gph', './2/000/761/203.gph'):
            with self.subTest(name):
                self.assertEqual(canonical_tile_name(name), name[2:] if name.startswith('./') else name)

    def test_unexpected_shapes_rejected(self):
        for name in ('3/000/761/203.gph', '9/048/082.gph', '12345/048/082.gph', '2/00/761/203.gph',
                     '2/000/76/203.gph', '2/000/761/2034.gph', '2/000/761/203.GPH', 'tiles/2/000/761/203.gph',
                     '2/000/761/203.gph.exe', '2/' + '111/' * 20 + '111.gph'):
            with self.subTest(name):
                with self.assertRaises(ValueError):
                    canonical_tile_name(name)

    def test_path_length_cap(self):
        long_name = '2/' + '111/' * 19 + '111.gph'          # 4*19 + 8 = 84 characters
        self.assertGreater(len(long_name), 64)
        with self.assertRaisesRegex(ValueError, 'longer than 64'):
            canonical_tile_name(long_name)


class FormatThreeTests(unittest.TestCase):
    """Format 3: per-tile evidence, coherent generation identity, and total rejection on any doubt."""

    def _stage(self, entries=None, extra_claims=None, display_kind='sqlite', symlink=None):
        entries = entries if entries is not None else [(TILE_A, tile_bytes()), (TILE_B, tile_bytes(b'y'))]
        self.entries = entries
        self._dir = working_directory()
        root = Path(self._dir.name)
        self.root = root
        self.tar = tiles_tar(root, entries, symlink=symlink)
        self.display = display(root) if display_kind == 'sqlite' else self._europack(root)
        self.generation = generation(root, tiles=claims_for(entries, extra_claims))
        return root

    def _europack(self, root):
        path = root / 'map.europack'
        path.write_bytes(b'ERG1test')
        return path

    def _build(self, **overrides):
        arguments = dict(name='Romania', source='Geofabrik', date='2026-10-04',
                         generation=self.generation, country='RO')
        arguments.update(overrides)
        return package(self.tar, self.display, self.root / 'out.eurorig', **arguments)

    def tearDown(self):
        if hasattr(self, '_dir'):
            self._dir.cleanup()

    def test_level_two_three_group_package_is_actually_built(self):
        """The regression: a real level-2 path must package, not be refused by the path pattern."""
        root = self._stage(entries=[(TILE_A, tile_bytes()), (TILE_B, tile_bytes(b'y')),
                                    (TILE_C, tile_bytes(b'z'))])
        manifest = self._build()
        self.assertEqual(manifest['format'], 3)
        with zipfile.ZipFile(root / 'out.eurorig') as archive:
            raw = archive.read('tiles.sqlite')
        version, schema, meta, rows, columns, meta_columns = index_rows(raw, root)
        self.assertEqual([row[0] for row in rows], sorted([TILE_A, TILE_B, TILE_C]))
        self.assertEqual(meta['tiles'], '3')
        self.assertIn('2/000/761/203.gph', [row[0] for row in rows])

    def test_package_and_gate_agree_on_the_real_level_two_shape(self):
        root = self._stage(entries=[(TILE_B, tile_bytes())])
        self._build()
        receipt = root / 'gate.json'
        rc = check_qa_package.main([str(root / 'out.eurorig'), '--generation', str(self.generation),
                                    '--out', str(receipt)])
        self.assertEqual(rc, 0, json.loads(receipt.read_text(encoding='utf-8')).get('refused'))

    def test_entries_index_schema_and_preserved_generation(self):
        root = self._stage()
        manifest = self._build()
        self.assertEqual(manifest['format'], 3)
        self.assertEqual(manifest['country'], 'RO')
        self.assertEqual(manifest['generation_id'], '50f27278e441170d')
        with zipfile.ZipFile(root / 'out.eurorig') as archive:
            self.assertEqual(set(archive.namelist()),
                             {'manifest.json', 'routing.tar', 'display.sqlite', 'tiles.sqlite'})
            raw = archive.read('tiles.sqlite')
        index = root / 'index.sqlite'
        index.write_bytes(raw)
        self.assertEqual(manifest['sha256']['tiles.sqlite'], sha256(index))
        version, schema, meta, rows, columns, meta_columns = index_rows(raw, root)
        self.assertEqual(version, 1)
        self.assertIn('WITHOUT ROWID', schema['tiles'])
        self.assertIn('key TEXT PRIMARY KEY', schema['metadata'])
        self.assertEqual({row[0]: row[1] for row in rows},
                         {name: hashlib.sha256(payload).hexdigest() for name, payload in self.entries})
        self.assertTrue(all(row[2] == 512 for row in rows))
        self.assertEqual((meta['generation_id'], meta['country'], meta['tiles']),
                         ('50f27278e441170d', 'RO', '2'))
        self.assertEqual(columns, ['path', 'sha256', 'size'])
        self.assertEqual(meta_columns, ['key', 'value'])

    def test_staging_file_is_unpredictable_and_never_touches_a_lookalike(self):
        root = self._stage()
        lookalike = root / 'out.eurorig.tiles.part'
        lookalike.write_bytes(b'caller owned bytes')
        self._build()
        self.assertEqual(lookalike.read_bytes(), b'caller owned bytes',
                         'a deterministic staging name must not be used or removed')
        self.assertEqual(sorted(p.name for p in root.glob('*.part')), ['out.eurorig.tiles.part'])

    def test_output_must_not_overwrite_the_generation_input(self):
        self._stage()
        with self.assertRaisesRegex(ValueError, 'must not overwrite an input'):
            package(self.tar, self.display, self.generation, name='X', source='OSM', date='unknown',
                    generation=self.generation, country='RO')

    def test_generation_and_country_must_come_together(self):
        self._stage()
        with self.assertRaisesRegex(ValueError, 'must be given together'):
            self._build(country=None)
        with self.assertRaisesRegex(ValueError, 'must be given together'):
            self._build(generation=None)
        self.assertFalse((self.root / 'out.eurorig').exists())

    def test_country_must_be_iso2_upper(self):
        self._stage()
        for bad in ('romania', 'R', 'rO', 'ROU', '', 'R0'):
            with self.subTest(bad):
                with self.assertRaisesRegex(ValueError, 'ISO2'):
                    self._build(country=bad)

    def test_tile_absent_from_generation_is_refused(self):
        self._stage()
        path = generation(self.root, tiles={TILE_A: hashlib.sha256(tile_bytes()).hexdigest()})
        with self.assertRaisesRegex(ValueError, 'not present in the generation manifest'):
            self._build(generation=path)
        self.assertFalse((self.root / 'out.eurorig').exists())

    def test_hash_mismatch_is_refused(self):
        self._stage()
        wrong = claims_for(self.entries)
        wrong[TILE_B] = 'b' * 64
        path = generation(self.root, name='wrong.json', tiles=wrong)
        with self.assertRaisesRegex(ValueError, 'does not match the generation manifest hash'):
            self._build(generation=path)
        self.assertFalse((self.root / 'out.eurorig').exists())

    def test_duplicate_tile_names_are_refused(self):
        self._stage(entries=[(TILE_A, tile_bytes()), (TILE_A, tile_bytes())])
        with self.assertRaisesRegex(ValueError, 'Duplicate tile'):
            self._build()
        self.assertFalse((self.root / 'out.eurorig').exists())

    def test_non_regular_tile_entries_are_refused(self):
        self._stage(entries=[(TILE_A, tile_bytes())], symlink=TILE_B)
        with self.assertRaisesRegex(ValueError, 'not a regular file'):
            self._build()
        self.assertFalse((self.root / 'out.eurorig').exists())

    def test_unsafe_or_non_canonical_names_are_refused(self):
        for name in ('../2/000/761/203.gph', '/2/000/761/203.gph', '2\\000\\761\\203.gph',
                     'C:/2/000/761/203.gph', '3/000/761/203.gph', './2/000/761/20.gph'):
            with self.subTest(name), working_directory() as directory:
                root = Path(directory)
                tar = tiles_tar(root, [(name, tile_bytes())])
                with self.assertRaises(ValueError):
                    package(tar, display(root), root / 'out.eurorig', 'X', 'OSM', 'unknown',
                            generation=generation(root, tiles={name: hashlib.sha256(tile_bytes()).hexdigest()}),
                            country='RO')
                self.assertFalse((root / 'out.eurorig').exists())

    def test_truncated_tile_is_refused(self):
        self._stage(entries=[(TILE_A, b'x' * 100)])
        with self.assertRaisesRegex(ValueError, 'Truncated graph tile'):
            self._build()

    def test_format_three_requires_a_sqlite_display(self):
        self._stage(display_kind='europack')
        with self.assertRaisesRegex(ValueError, 'Format 3 requires an indexed SQLite display'):
            self._build()

    def test_manifest_over_the_app_limit_is_refused(self):
        self._stage()
        with self.assertRaisesRegex(ValueError, 'exceeds the 65536-byte limit'):
            self._build(name='R' * (MANIFEST_LIMIT + 1000))
        self.assertFalse((self.root / 'out.eurorig').exists())

    def test_generation_manifest_itself_is_validated(self):
        with working_directory() as directory:
            root = Path(directory)
            cases = {
                'no generation_id': ({'tiles': {TILE_A: 'a' * 64}}, ValueError, 'generation_id'),
                'bad generation_id': ({'generation_id': 'not-hex!', 'tiles': {TILE_A: 'a' * 64}}, ValueError,
                                      '16-hex'),
                'uppercase generation_id': ({'generation_id': '50F27278E441170D',
                                             'tiles': {TILE_A: 'a' * 64}}, ValueError, '16-hex'),
                'no tile hashes': ({'generation_id': '50f27278e441170d', 'tiles': {}}, ValueError,
                                   'no tile hashes'),
                'short digest': ({'generation_id': '50f27278e441170d', 'tiles': {TILE_A: 'abc'}}, ValueError,
                                 '64-hex'),
                'non-hex digest': ({'generation_id': '50f27278e441170d', 'tiles': {TILE_A: 'z' * 64}},
                                   ValueError, '64-hex'),
                'uppercase digest': ({'generation_id': '50f27278e441170d', 'tiles': {TILE_A: 'A' * 64}},
                                     ValueError, '64-hex'),
                'unsafe name': ({'generation_id': '50f27278e441170d', 'tiles': {'../a.gph': 'a' * 64}},
                                ValueError, 'Unsafe tile name'),
                'wrong level': ({'generation_id': '50f27278e441170d', 'tiles': {'3/000/761/203.gph': 'a' * 64}},
                                ValueError, 'canonical'),
                'not an object': ([], ValueError, 'JSON object'),
            }
            for label, (payload, error, expected) in cases.items():
                with self.subTest(label):
                    path = root / f'{abs(hash(label))}.json'
                    path.write_text(json.dumps(payload), encoding='utf-8')
                    with self.assertRaisesRegex(error, expected):
                        read_generation(path)

    def test_duplicate_canonical_claims_are_refused(self):
        """Two spellings of one tile must not silently collapse into whichever came last."""
        with working_directory() as directory:
            root = Path(directory)
            digest = 'a' * 64
            path = generation(root, tiles={TILE_B: digest, f'./{TILE_B}': digest})
            with self.assertRaisesRegex(ValueError, 'more than once'):
                read_generation(path)
            conflicting = generation(root, name='conflict.json', tiles={TILE_B: digest, f'./{TILE_B}': 'b' * 64})
            with self.assertRaisesRegex(ValueError, 'more than once'):
                read_generation(conflicting)

    def test_only_tar_present_rows_are_indexed(self):
        """The coherent manifest covers other countries' tiles; only this package's are indexed."""
        self._stage(extra_claims={'1/048/083.gph': 'c' * 64, '2/000/761/204.gph': 'd' * 64})
        manifest = self._build()
        self.assertNotIn('coverage', manifest, 'no bbox was given, so no coverage claim is published')
        with zipfile.ZipFile(self.root / 'out.eurorig') as archive:
            raw = archive.read('tiles.sqlite')
        version, schema, meta, rows, columns, meta_columns = index_rows(raw, self.root)
        self.assertEqual([row[0] for row in rows], sorted([TILE_A, TILE_B]))
        self.assertEqual(meta['tiles'], '2')

    def test_legacy_formats_unchanged_without_the_new_flags(self):
        with working_directory() as directory:
            root = Path(directory)
            tar = tiles_tar(root, [(TILE_B, tile_bytes())])
            sqlite_display = display(root)
            manifest = package(tar, sqlite_display, root / 'two.eurorig', 'X', 'OSM', 'unknown')
            self.assertEqual(manifest['format'], 2)
            self.assertNotIn('country', manifest)
            self.assertNotIn('generation_id', manifest)
            with zipfile.ZipFile(root / 'two.eurorig') as archive:
                self.assertEqual(set(archive.namelist()), {'manifest.json', 'routing.tar', 'display.sqlite'})


class GateMutationTests(unittest.TestCase):
    """Mutate a valid package directly and require the gate to refuse it.

    Built through the packager and then altered by hand, so a packager bug and a gate bug cannot cancel
    each other out: the gate is handed bytes the packager would never have produced.
    """

    def setUp(self):
        self._dir = working_directory()
        self.root = Path(self._dir.name)
        self.entries = [(TILE_A, tile_bytes()), (TILE_B, tile_bytes(b'y'))]
        self.tar = tiles_tar(self.root, self.entries)
        self.display = display(self.root)
        self.generation = generation(self.root, tiles=claims_for(self.entries))
        package(self.tar, self.display, self.root / 'good.eurorig', 'Romania', 'Geofabrik',
                '2026-10-04', generation=self.generation, country='RO')
        self.mutated = 0

    def tearDown(self):
        self._dir.cleanup()

    def parts(self, source=None):
        with zipfile.ZipFile(source or self.root / 'good.eurorig') as original:
            return {name: original.read(name) for name in original.namelist()}

    def repack(self, items, extra=()):
        self.mutated += 1
        target = self.root / f'mutated-{self.mutated}.eurorig'
        with zipfile.ZipFile(target, 'w', compression=zipfile.ZIP_DEFLATED) as out:
            for name, payload in items.items():
                out.writestr(name, payload)
            for name, payload in extra:
                out.writestr(name, payload)
        return target

    def mutate_index(self, mutate, patch_manifest=None):
        """Rewrite the index, keep the manifest's tiles.sqlite hash honest, then apply any manifest patch."""
        items = self.parts()
        path = self.root / f'index-{self.mutated}.sqlite'
        path.write_bytes(items['tiles.sqlite'])
        with closing(sqlite3.connect(path)) as db:
            mutate(db)
            db.commit()
        items['tiles.sqlite'] = path.read_bytes()
        manifest = json.loads(items['manifest.json'].decode('utf-8'))
        manifest['sha256']['tiles.sqlite'] = hashlib.sha256(items['tiles.sqlite']).hexdigest()
        if patch_manifest:
            manifest.update(patch_manifest)
        items['manifest.json'] = json.dumps(manifest).encode('utf-8')
        return self.repack(items)

    def gate(self, target, extra_args=()):
        receipt = self.root / f'receipt-{self.mutated}.json'
        code = check_qa_package.main([str(target), '--out', str(receipt), *extra_args])
        return code, json.loads(receipt.read_text(encoding='utf-8'))

    def test_control_valid_package_passes(self):
        code, receipt = self.gate(self.root / 'good.eurorig', ('--generation', str(self.generation)))
        self.assertEqual(code, 0, receipt.get('refused'))
        self.assertEqual(receipt['verdict'], 'PASS')

    def test_control_valid_package_passes_without_generation_flag(self):
        code, receipt = self.gate(self.root / 'good.eurorig')
        self.assertEqual(code, 0, receipt.get('refused'))
        self.assertEqual(receipt['verdict'], 'PASS')

    def test_failed_packaging_preserves_existing_output(self):
        target = self.root / 'good.eurorig'
        before = target.read_bytes()
        with patch('package_region.zipfile.ZipFile.write', side_effect=OSError('disk write failed')):
            with self.assertRaisesRegex(OSError, 'disk write failed'):
                package(self.tar, self.display, target, 'Romania', 'Geofabrik', '2026-10-04',
                        generation=self.generation, country='RO')
        self.assertEqual(target.read_bytes(), before)
        self.assertEqual(list(self.root.glob('.region-package-*.part')), [])
        self.assertEqual(list(self.root.glob('.tiles-index-*.part')), [])

    def test_extra_index_objects_are_refused(self):
        for statement in ('CREATE TABLE extra(value TEXT)',
                          'CREATE VIEW extra AS SELECT path FROM tiles',
                          'CREATE INDEX extra ON tiles(size)'):
            with self.subTest(statement=statement):
                target = self.mutate_index(lambda db: db.execute(statement))
                code, receipt = self.gate(target)
                self.assertEqual((code, receipt['verdict']), (1, 'FAIL'))
                self.assertIn('unexpected schema objects', receipt['refused'])

    def test_boolean_manifest_format_is_refused(self):
        items = self.parts()
        manifest = json.loads(items['manifest.json'])
        manifest['format'] = True
        items['manifest.json'] = json.dumps(manifest).encode()
        code, receipt = self.gate(self.repack(items))
        self.assertEqual((code, receipt['verdict']), (1, 'FAIL'))
        self.assertIn('manifest format', receipt['refused'])

    def test_index_metadata_country_disagreeing_with_manifest_is_refused(self):
        target = self.mutate_index(lambda db: db.execute("UPDATE metadata SET value='HU' WHERE key='country'"))
        code, receipt = self.gate(target)
        self.assertEqual((code, receipt['verdict']), (1, 'FAIL'))
        self.assertIn('country', receipt['refused'])

    def test_index_metadata_generation_disagreeing_with_manifest_is_refused_without_the_flag(self):
        target = self.mutate_index(
            lambda db: db.execute("UPDATE metadata SET value='0000000000000000' WHERE key='generation_id'"))
        code, receipt = self.gate(target)          # deliberately no --generation
        self.assertEqual((code, receipt['verdict']), (1, 'FAIL'))
        self.assertIn('generation_id', receipt['refused'])

    def test_index_row_hash_altered_is_refused(self):
        target = self.mutate_index(lambda db: db.execute('UPDATE tiles SET sha256=? WHERE path=?',
                                                        ('c' * 64, TILE_B)))
        code, receipt = self.gate(target)
        self.assertEqual((code, receipt['verdict']), (1, 'FAIL'))
        self.assertIn('disagrees with the tar', receipt['refused'])

    def test_index_row_deleted_is_refused(self):
        target = self.mutate_index(lambda db: db.execute('DELETE FROM tiles WHERE path=?', (TILE_B,)))
        code, receipt = self.gate(target)
        self.assertEqual((code, receipt['verdict']), (1, 'FAIL'))
        self.assertIn('index has', receipt['refused'])

    def test_index_extra_row_is_refused(self):
        target = self.mutate_index(lambda db: db.execute('INSERT INTO tiles VALUES (?,?,?)',
                                                        ('2/000/761/204.gph', 'd' * 64, 512)))
        code, receipt = self.gate(target)
        self.assertEqual((code, receipt['verdict']), (1, 'FAIL'))
        # An extra row makes the counts disagree, and the count check fires before the set comparison.
        self.assertIn('tile rows', receipt['refused'])

    def test_index_tile_count_metadata_lie_is_refused(self):
        target = self.mutate_index(lambda db: db.execute("UPDATE metadata SET value='3' WHERE key='tiles'"))
        code, receipt = self.gate(target)
        self.assertEqual((code, receipt['verdict']), (1, 'FAIL'))
        self.assertIn('tiles', receipt['refused'])

    def test_index_user_version_is_refused(self):
        target = self.mutate_index(lambda db: db.execute('PRAGMA user_version=3'))
        code, receipt = self.gate(target)
        self.assertEqual((code, receipt['verdict']), (1, 'FAIL'))
        self.assertIn('user_version', receipt['refused'])

    def test_index_schema_change_is_refused(self):
        def reshape(db):
            db.execute('ALTER TABLE tiles RENAME TO tiles_old')
            db.execute('CREATE TABLE tiles(path TEXT PRIMARY KEY, sha256 TEXT, size INTEGER)')
        target = self.mutate_index(reshape)
        code, receipt = self.gate(target)
        self.assertEqual((code, receipt['verdict']), (1, 'FAIL'))
        self.assertIn('WITHOUT ROWID', receipt['refused'])

    def test_index_sha256_column_not_hex_is_refused(self):
        target = self.mutate_index(lambda db: db.execute('UPDATE tiles SET sha256=? WHERE path=?',
                                                        ('Z' * 64, TILE_A)))
        code, receipt = self.gate(target)
        self.assertEqual((code, receipt['verdict']), (1, 'FAIL'))

    def test_manifest_index_hash_altered_is_refused(self):
        items = self.parts()
        manifest = json.loads(items['manifest.json'].decode('utf-8'))
        manifest['sha256']['tiles.sqlite'] = 'e' * 64
        items['manifest.json'] = json.dumps(manifest).encode('utf-8')
        code, receipt = self.gate(self.repack(items))
        self.assertEqual((code, receipt['verdict']), (1, 'FAIL'))
        self.assertIn('payload hash mismatch', receipt['refused'])

    def test_duplicate_zip_entry_is_refused(self):
        items = self.parts()
        target = self.repack(items, extra=[('routing.tar', items['routing.tar'])])
        code, receipt = self.gate(target)
        self.assertEqual((code, receipt['verdict']), (1, 'FAIL'))
        self.assertIn('more than once', receipt['refused'])

    def test_directory_entry_is_refused(self):
        items = self.parts()
        target = self.repack(items, extra=[('tiles/', b'')])
        code, receipt = self.gate(target)
        self.assertEqual((code, receipt['verdict']), (1, 'FAIL'))
        self.assertIn('directory entries', receipt['refused'])

    def test_manifest_json_of_the_wrong_type_is_a_fail_receipt_not_a_crash(self):
        items = self.parts()
        items['manifest.json'] = b'[]'
        code, receipt = self.gate(self.repack(items))
        self.assertEqual((code, receipt['verdict']), (1, 'FAIL'))
        self.assertIn('expected an object', receipt['refused'])

    def test_malformed_manifest_json_is_a_fail_receipt(self):
        items = self.parts()
        items['manifest.json'] = b'{"format": 3,'
        code, receipt = self.gate(self.repack(items))
        self.assertEqual((code, receipt['verdict']), (1, 'FAIL'))

    def test_truncated_tar_is_a_fail_receipt(self):
        items = self.parts()
        items['routing.tar'] = items['routing.tar'][:600]
        code, receipt = self.gate(self.repack(items))
        self.assertEqual((code, receipt['verdict']), (1, 'FAIL'))

    def test_garbage_index_is_a_fail_receipt(self):
        items = self.parts()
        items['tiles.sqlite'] = b'this is not a database'
        manifest = json.loads(items['manifest.json'].decode('utf-8'))
        manifest['sha256']['tiles.sqlite'] = hashlib.sha256(items['tiles.sqlite']).hexdigest()
        items['manifest.json'] = json.dumps(manifest).encode('utf-8')
        code, receipt = self.gate(self.repack(items))
        self.assertEqual((code, receipt['verdict']), (1, 'FAIL'))

    def test_engine_and_native_version_are_checked(self):
        for patch in ({'engine': 'graphhopper'}, {'native_version': '0.7.0'}):
            with self.subTest(patch):
                items = self.parts()
                manifest = json.loads(items['manifest.json'].decode('utf-8'))
                manifest.update(patch)
                items['manifest.json'] = json.dumps(manifest).encode('utf-8')
                code, receipt = self.gate(self.repack(items))
                self.assertEqual((code, receipt['verdict']), (1, 'FAIL'))

    def test_country_and_generation_id_syntax_are_checked(self):
        for patch in ({'country': 'ro'}, {'country': 'ROMANIA'}, {'generation_id': 'short'},
                      {'generation_id': '50F27278E441170D'}):
            with self.subTest(patch):
                items = self.parts()
                manifest = json.loads(items['manifest.json'].decode('utf-8'))
                manifest.update(patch)
                items['manifest.json'] = json.dumps(manifest).encode('utf-8')
                code, receipt = self.gate(self.repack(items))
                self.assertEqual((code, receipt['verdict']), (1, 'FAIL'))

    def test_generation_flag_disagreement_is_refused(self):
        other = generation(self.root, name='other.json',
                           tiles=claims_for(self.entries), generation_id='1111111111111111')
        code, receipt = self.gate(self.root / 'good.eurorig', ('--generation', str(other)))
        self.assertEqual((code, receipt['verdict']), (1, 'FAIL'))
        self.assertIn('does not match the coherent manifest', receipt['refused'])

    def test_generation_flag_claim_mismatch_is_refused(self):
        wrong = {TILE_B: 'f' * 64}
        other = generation(self.root, name='claims.json', tiles={**claims_for(self.entries), **wrong})
        code, receipt = self.gate(self.root / 'good.eurorig', ('--generation', str(other)))
        self.assertEqual((code, receipt['verdict']), (1, 'FAIL'))
        self.assertIn('do not match the coherent claim', receipt['refused'])


class CompressedGateTests(unittest.TestCase):
    parts=GateMutationTests.parts
    repack=GateMutationTests.repack
    mutate_index=GateMutationTests.mutate_index
    gate=GateMutationTests.gate
    tearDown=GateMutationTests.tearDown

    def setUp(self):
        GateMutationTests.setUp(self)
        package(self.tar,self.display,self.root/'good.eurorig','Romania','Original fixture','unknown',
                generation=self.generation,country='RO',experimental_compressed=True)

    def test_compressed_country_passes_with_and_without_coherent_manifest(self):
        for arguments in ((),('--generation',str(self.generation))):
            code,receipt=self.gate(self.root/'good.eurorig',arguments)
            self.assertEqual(code,0,receipt.get('refused'))
            self.assertEqual(receipt['checks']['tile_index']['version'],2)

    def test_compressed_claim_and_generation_controls(self):
        for sql in ("UPDATE tiles SET compressed_sha256=NULL", "UPDATE tiles SET compressed_size=NULL",
                    "UPDATE tiles SET compressed_sha256='"+'f'*64+"'", "UPDATE tiles SET compressed_size=compressed_size+1",
                    "UPDATE tiles SET size=size-1", "UPDATE tiles SET sha256='"+'f'*64+"'",
                    "UPDATE metadata SET value='HU' WHERE key='country'",
                    "UPDATE metadata SET value='0000000000000002' WHERE key='generation_id'"):
            with self.subTest(sql=sql):
                code,receipt=self.gate(self.mutate_index(lambda db:db.execute(sql)))
                self.assertEqual((code,receipt['verdict']),(1,'FAIL'))

    def replace_compressed_payload(self,payload,symlink=False):
        items=self.parts();output=io.BytesIO()
        with tarfile.open(fileobj=io.BytesIO(items['routing.tar'])) as old,tarfile.open(fileobj=output,mode='w') as new:
            for member in old:
                data=payload if member.name==TILE_A+'.gz' else old.extractfile(member).read()
                info=tarfile.TarInfo(member.name)
                if symlink and member.name==TILE_A+'.gz':
                    info.type=tarfile.SYMTYPE;info.linkname='outside.gph.gz';new.addfile(info)
                else:
                    info.size=len(data);new.addfile(info,io.BytesIO(data))
        items['routing.tar']=output.getvalue();path=self.root/'modified-index.sqlite';path.write_bytes(items['tiles.sqlite'])
        with closing(sqlite3.connect(path)) as db:
            db.execute('UPDATE tiles SET compressed_sha256=?,compressed_size=? WHERE path=?',
                       (hashlib.sha256(payload).hexdigest(),len(payload),TILE_A));db.commit()
        items['tiles.sqlite']=path.read_bytes();manifest=json.loads(items['manifest.json'])
        for name in ('routing.tar','tiles.sqlite'):manifest['sha256'][name]=hashlib.sha256(items[name]).hexdigest()
        items['manifest.json']=json.dumps(manifest).encode()
        return self.repack(items)

    def test_rehashed_invalid_gzip_and_decoded_identity_controls(self):
        original=gzip.compress(tile_bytes(),mtime=0)
        for payload,reason in ((original[:-2],'complete gzip'),(b'not gzip at all.....','complete gzip'),
                               (gzip.compress(tile_bytes(b'z'),mtime=0),'disagrees with the tar'),
                               (gzip.compress(tile_bytes()+b'x',mtime=0),'exceeds its declared decoded size')):
            with self.subTest(reason=reason):
                code,receipt=self.gate(self.replace_compressed_payload(payload))
                self.assertEqual((code,receipt['verdict']),(1,'FAIL'))
                self.assertIn(reason,receipt['refused'])

    def test_compressed_symlink_is_refused(self):
        code,receipt=self.gate(self.replace_compressed_payload(gzip.compress(tile_bytes(),mtime=0),symlink=True))
        self.assertEqual((code,receipt['verdict']),(1,'FAIL'))
        self.assertIn('not a regular file',receipt['refused'])

    def test_version2_canonical_only_index_remains_readable(self):
        legacy=self.root/'legacy.eurorig'
        package(self.tar,self.display,legacy,'Romania','Original fixture','unknown',generation=self.generation,country='RO')
        items=self.parts(legacy);path=self.root/'raw-v2.sqlite';path.write_bytes(items['tiles.sqlite'])
        with closing(sqlite3.connect(path)) as db:
            db.execute('ALTER TABLE tiles ADD COLUMN compressed_sha256 TEXT')
            db.execute('ALTER TABLE tiles ADD COLUMN compressed_size INTEGER')
            db.execute('PRAGMA user_version=2');db.commit()
        items['tiles.sqlite']=path.read_bytes();manifest=json.loads(items['manifest.json'])
        manifest['sha256']['tiles.sqlite']=hashlib.sha256(items['tiles.sqlite']).hexdigest()
        items['manifest.json']=json.dumps(manifest).encode()
        code,receipt=self.gate(self.repack(items),('--generation',str(self.generation)))
        self.assertEqual(code,0,receipt.get('refused'))


if __name__ == '__main__':
    unittest.main()
