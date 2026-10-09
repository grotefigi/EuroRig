"""Focused test for the storage audit: what it measures, what it merely estimates, and what it refuses.

The audit is read-only on its inputs, reports savings it never applies, and must never present an
estimate or an unproven duplicate as a measurement. What needs proving:
  1. text sizes are real UTF-8 bytes (LENGTH of the BLOB), not character counts, and numeric columns are
     reported as estimates from the declared type - never as measured storage
  2. two similar text totals are called a CANDIDATE, and the identity of the text is actually checked:
     a copy holding normalized text is proven, a copy holding raw labels is reported as differing
  3. the staging peak is named as a new-map scope with what it excludes, and the in-place upgrade peak
     counts the old map as well
  4. the audit opens its input read-only and leaves it byte-identical
  5. the experiment works on a copy, removes the duplicate tables, and answers the same normalized-prefix
     and diacritic queries as the original
"""
import hashlib
import io
import json
import sqlite3
import sys
import tarfile
import tempfile
import unittest
from contextlib import closing
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import audit_map_storage as audit  # noqa: E402
from package_region import package  # noqa: E402

PLACES = [
    (1, 'București', 44.4268, 26.1025, 'city'),
    (2, 'Cluj-Napoca', 46.7712, 23.6236, 'city'),
    (3, 'Sibiu', 45.7970, 24.1520, 'city'),
    (4, 'Târgu Mureș', 46.5456, 24.5625, 'town'),
    (5, 'Otopeni', 44.5722, 26.1022, 'town'),
    (6, 'Bucureștii Noi', 44.5000, 26.0300, 'suburb'),
]


def build_display(path, index_normalized=True):
    """A miniature display database shaped like the real one: self-content FTS4 over places.label."""
    with closing(sqlite3.connect(path)) as db:
        db.executescript('''
            PRAGMA user_version=1;
            CREATE TABLE places(id INTEGER PRIMARY KEY,label TEXT NOT NULL,lat REAL,lon REAL,kind TEXT);
            CREATE TABLE roads(id INTEGER PRIMARY KEY,name TEXT NOT NULL,kind TEXT NOT NULL,level INTEGER NOT NULL,
                large INTEGER NOT NULL,south REAL,west REAL,north REAL,east REAL,shape TEXT NOT NULL);
            CREATE TABLE cells(lat INTEGER,lon INTEGER,road INTEGER,PRIMARY KEY(lat,lon,road)) WITHOUT ROWID;
            CREATE TABLE road_rules(way INTEGER PRIMARY KEY,height REAL,width REAL,length REAL,weight REAL,
                axle REAL,flags INTEGER,tags TEXT);
            CREATE INDEX place_area ON places(kind,lat);
            CREATE VIRTUAL TABLE search USING fts4(text,tokenize=unicode61);
        ''')
        for row in PLACES:
            db.execute('INSERT INTO places VALUES(?,?,?,?,?)', row)
            text = audit.normalize(row[1]) if index_normalized else row[1]
            db.execute('INSERT INTO search(rowid,text) VALUES(?,?)', (row[0], text))
        db.execute("INSERT INTO roads VALUES(1,'Strada Mare','residential',4,0,44.4,26.0,44.5,26.1,'_sqytA_k}nr@?_yF')")
        db.execute('INSERT INTO cells VALUES(444268,261025,1)')
        db.execute('INSERT INTO road_rules VALUES(1,3.5,2.5,12.0,20.0,8.0,0,\'{"maxheight":"3.5"}\')')
        db.commit()
    return path


class MeasurementTests(unittest.TestCase):
    def setUp(self):
        self._dir = tempfile.TemporaryDirectory(ignore_cleanup_errors=True)
        self.root = Path(self._dir.name)
        self.db = build_display(self.root / 'display.sqlite')

    def tearDown(self):
        self._dir.cleanup()

    def test_text_sizes_are_utf8_bytes_not_character_counts(self):
        info = audit.audit_display(self.db)
        profile = info['values']['places']
        label = [c for c in profile['text_columns'] if c['name'] == 'label'][0]
        self.assertEqual(label['bytes'], sum(len(p[1].encode('utf-8')) for p in PLACES))
        # 'București' is 10 characters and 11 UTF-8 bytes: a character count would be wrong here.
        self.assertNotEqual(len(PLACES[0][1]), len(PLACES[0][1].encode('utf-8')))
        self.assertEqual(profile['text_bytes_total'], label['bytes'] + sum(
            c['bytes'] for c in profile['text_columns'] if c['name'] != 'label'))

    def test_numeric_columns_are_estimates_never_measured_bytes(self):
        info = audit.audit_display(self.db)
        profile = info['values']['places']
        self.assertEqual([c['name'] for c in profile['text_columns']], ['label', 'kind'])
        numeric = {c['name']: c for c in profile['numeric_columns']}
        self.assertEqual(set(numeric), {'id', 'lat', 'lon'})
        self.assertTrue(numeric['id']['rowid_alias'], 'an INTEGER PRIMARY KEY costs nothing in the row')
        self.assertEqual(numeric['id']['estimated_bytes'], 0)
        self.assertEqual(numeric['lat']['estimated_bytes'], len(PLACES) * 8)
        for column in profile['numeric_columns']:
            self.assertNotIn('percent_of_text', column)
            self.assertIn('estimate_basis', column)
        self.assertIn('never a measurement', profile['note'])

    def test_display_measurements_come_from_the_database(self):
        info = audit.audit_display(self.db)
        self.assertEqual(info['row_counts']['places'], len(PLACES))
        self.assertEqual(info['bytes'], self.db.stat().st_size)
        names = {o['name'] for o in info['dbstat']['objects']}
        self.assertIn('search_content', names)
        self.assertIn('search_segments', names)
        self.assertGreater(info['fts_bytes'], 0)
        self.assertGreater(info['fts_percent'], 0)

    def test_quick_skips_the_slow_measurements(self):
        info = audit.audit_display(self.db, quick=True)
        self.assertNotIn('dbstat', info)
        self.assertNotIn('values', info)
        self.assertIn('row_counts', info)

    def test_audit_opens_the_input_read_only(self):
        before = (self.db.stat().st_size, hashlib.sha256(self.db.read_bytes()).hexdigest())
        files_before = sorted(p.name for p in self.root.iterdir())
        audit.audit_display(self.db)
        after = (self.db.stat().st_size, hashlib.sha256(self.db.read_bytes()).hexdigest())
        self.assertEqual(before, after, 'an audit must not modify its input')
        self.assertEqual(files_before, sorted(p.name for p in self.root.iterdir()),
                         'an audit must not create files beside its input')

    def test_similar_text_totals_are_a_candidate_and_identity_is_proven(self):
        info = audit.audit_display(self.db)
        search = info['search_text']
        self.assertEqual(search['fts_private_text']['measure'], 'measured_utf8_bytes')
        self.assertEqual(search['candidate_duplicate_bytes'],
                         min(search['fts_private_text']['bytes'], search['places_label_text']['bytes']))
        self.assertIn('candidate', search['measure'])
        self.assertIn('not\nproof'.replace('\n', ' '), search['candidate_duplicate_note'].replace('\n', ' '))
        check = search['identity_check']
        self.assertEqual(check['verdict'], 'proven_same_text')
        self.assertEqual((check['matched'], check['mismatched']), (len(PLACES), 0))
        self.assertTrue(check['full'])

    def test_an_index_of_raw_labels_is_reported_as_differing(self):
        raw = build_display(self.root / 'raw.sqlite', index_normalized=False)
        info = audit.audit_display(raw)
        check = info['search_text']['identity_check']
        self.assertEqual(check['verdict'], 'texts_differ')
        self.assertGreater(check['mismatched'], 0)
        self.assertTrue(check['examples'])

    def test_sampled_identity_check_says_so(self):
        info = audit.audit_display(self.db, identity_sample=2)
        check = info['search_text']['identity_check']
        self.assertFalse(check['full'])
        self.assertGreater(check['step'], 1)
        self.assertEqual(check['verdict'], 'partially_checked_same_text')

    def test_savings_candidates_state_kind_and_are_never_applied(self):
        info = audit.audit_display(self.db)
        candidates = {c['candidate']: c for c in audit.savings_candidates(info)}
        search_copy = candidates['FTS4 without a private text copy']
        objects = {o['name']: o['bytes'] for o in info['dbstat']['objects']}
        self.assertEqual(search_copy['bytes'], objects['search_content'])
        self.assertEqual(search_copy['kind'], 'measured_physical_bytes')
        self.assertEqual(search_copy['status'], 'not_applied')
        self.assertIn('proven_same_text', search_copy['identity_evidence'])
        numeric = candidates['places lat/lon stored as fixed-point integers']
        self.assertEqual(numeric['kind'], 'estimated_bytes')
        self.assertIn('confirm_by', numeric)
        self.assertEqual(numeric['bytes'], len(PLACES) * 2 * 4)
        self.assertTrue(all(c['contract_cost'] for c in candidates.values()),
                        'every candidate must state what it would cost in behaviour')

    def test_package_measurement_names_its_scope(self):
        tiles = self.root / 'tiles.tar'
        with tarfile.open(tiles, 'w') as archive:
            for name, payload in (('1/048/082.gph', b'a' * 512), ('2/000/761/203.gph', b'b' * 512)):
                info = tarfile.TarInfo(name)
                info.size = len(payload)
                archive.addfile(info, io.BytesIO(payload))
        display = build_display(self.root / 'second.sqlite')
        package(tiles, display, self.root / 'map.eurorig', 'Test', 'OSM', 'unknown')
        result = audit.audit_package(self.root / 'map.eurorig')
        payloads = [e for e in result['entries'] if e['name'] != 'manifest.json']
        self.assertEqual(result['transport_bytes'], self.root.joinpath('map.eurorig').stat().st_size)
        self.assertEqual(result['installed_bytes'], sum(e['stored'] for e in payloads))
        self.assertEqual(result['new_map_staging_bytes'],
                         result['transport_bytes'] + result['installed_bytes'])
        self.assertIn('free space', result['new_map_staging_scope'])
        self.assertNotIn('upgrade_peak_bytes', result)
        self.assertTrue(any('active map' in item for item in result['not_counted']))
        self.assertTrue(any('reserve' in item for item in result['not_counted']))

    def test_upgrade_peak_counts_the_installed_map(self):
        tiles = self.root / 'tiles.tar'
        with tarfile.open(tiles, 'w') as archive:
            # 512 B: a smaller payload is a truncated graph tile and the packager refuses it.
            info = tarfile.TarInfo('1/048/082.gph')
            info.size = 512
            archive.addfile(info, io.BytesIO(b'a' * 512))
        display = build_display(self.root / 'second.sqlite')
        package(tiles, display, self.root / 'old.eurorig', 'Old', 'OSM', 'unknown')
        package(tiles, display, self.root / 'new.eurorig', 'New', 'OSM', 'unknown')
        old = audit.audit_package(self.root / 'old.eurorig')
        result = audit.audit_package(self.root / 'new.eurorig', upgrade_from=self.root / 'old.eurorig')
        self.assertEqual(result['upgrade_peak_bytes'],
                         old['installed_bytes'] + result['transport_bytes'] + result['installed_bytes'])
        self.assertIn('old map still installed', result['upgrade_peak_scope'])
        self.assertGreater(result['upgrade_peak_bytes'], result['new_map_staging_bytes'])


class ExperimentTests(unittest.TestCase):
    def setUp(self):
        self._dir = tempfile.TemporaryDirectory(ignore_cleanup_errors=True)
        self.root = Path(self._dir.name)
        self.db = build_display(self.root / 'display.sqlite')
        self.workspace = self.root / 'workspace'
        self.fingerprint = hashlib.sha256(self.db.read_bytes()).hexdigest()

    def tearDown(self):
        self._dir.cleanup()

    def test_variants_keep_normalized_prefix_and_diacritic_search(self):
        results = audit.experiment(self.db, self.workspace, None,
                                   queries=['București', 'Bucuresti', 'Bucure', 'Cluj', 'Clu',
                                            'Târgu Mureș', 'Targu Mures', 'Sibiu', 'Otopeni', 'missing'])
        for name, info in results['variants'].items():
            with self.subTest(name):
                self.assertEqual(info['mismatches'], [], f'{name} changed query results')
                self.assertEqual(info['queries_matching'], len(results['queries']))
                self.assertEqual(info['duplicate_tables_removed'], ['search_content', 'search_docsize'],
                                 'the private text copy and the docsize table are what the variants remove')
                self.assertEqual(info['saving_bytes'],
                                 results['source_bytes'] - (self.workspace / f'display.{name}.sqlite').stat().st_size)

    def test_experiment_leaves_its_input_untouched(self):
        audit.experiment(self.db, self.workspace, None, queries=['Bucuresti'])
        self.assertEqual(hashlib.sha256(self.db.read_bytes()).hexdigest(), self.fingerprint,
                         'the experiment must only ever work on copies')
        self.assertTrue((self.workspace / 'display.contentless.sqlite').exists())
        self.assertTrue((self.workspace / 'display.external.sqlite').exists())

    def test_experiment_writes_its_receipt_where_asked(self):
        out = self.root / 'receipt.json'
        audit.experiment(self.db, self.workspace, out, queries=['Sibiu'])
        receipt = json.loads(out.read_text(encoding='utf-8'))
        self.assertEqual(receipt['source'], str(self.db))
        self.assertIn('parity', receipt)
        self.assertIn('exact comparison', receipt['measure'])

    def test_experiment_without_a_workspace_is_refused(self):
        with self.assertRaises(SystemExit):
            audit.main(['--experiment', '--display', str(self.db)])

    def test_experiment_without_a_display_is_refused(self):
        with self.assertRaises(SystemExit):
            audit.main(['--experiment', '--workspace', str(self.workspace)])


class AppContractTests(unittest.TestCase):
    """The composed-country check the app runs, and what each index format does to it.

    DisplayDatabase.checkDuplicateEvidence attaches the peer country and runs the JOIN below; a returned
    row means conflicting search evidence and aborts the composition. It fires only if the query can see
    the indexed text at all, so the invariant asserted here is deliberately version-robust: a format that
    CANNOT report a genuinely conflicting peer is not app-compatible, whether it fails the query or
    silently reports nothing. The host SQLite here is 3.53.1; the device's is older, so the Android
    result must be measured on API 26 as well - this test pins the host behaviour and the requirement.
    """
    QUERY = ('SELECT 1 FROM main.search a JOIN peer.search b ON a.rowid=b.rowid '
             'WHERE NOT(a.text IS b.text) LIMIT 1')
    FORMATS = (('self-content', 'text,tokenize=unicode61'),
               ('contentless', "text,tokenize=unicode61,content='',matchinfo=fts3"),
               ('external', 'text,content=places,tokenize=unicode61,matchinfo=fts3'))

    def _build(self, path, declaration, conflict):
        with closing(sqlite3.connect(path)) as db:
            db.execute('CREATE TABLE places(id INTEGER PRIMARY KEY,label TEXT)')
            for pid, label in ((row[0], row[1]) for row in PLACES):
                db.execute('INSERT INTO places VALUES(?,?)', (pid, label))
            db.execute(f'CREATE VIRTUAL TABLE search USING fts4({declaration})')
            for pid, label in ((row[0], row[1]) for row in PLACES):
                text = 'deliberately different' if conflict and pid == PLACES[1][0] else audit.normalize(label)
                db.execute('INSERT INTO search(docid,text) VALUES(?,?)', (pid, text))
            db.commit()
        return path

    def _reports_conflict(self, first, peer):
        """True only when the app's own JOIN returns the conflicting row."""
        db = sqlite3.connect(first)
        db.execute('ATTACH DATABASE ? AS peer', (str(peer),))
        try:
            return bool(db.execute(self.QUERY).fetchall()), 'query ran'
        except sqlite3.Error as error:
            return False, f'query failed: {error}'
        finally:
            try:
                db.execute('DETACH DATABASE peer')
            except sqlite3.Error:
                pass
            db.close()

    def test_self_content_reports_a_conflicting_peer(self):
        root = Path(tempfile.mkdtemp(dir=self._dir.name))
        declaration = self.FORMATS[0][1]
        first = self._build(root / 'a.sqlite', declaration, conflict=False)
        clean = self._build(root / 'clean.sqlite', declaration, conflict=False)
        conflicting = self._build(root / 'conflict.sqlite', declaration, conflict=True)
        self.assertFalse(self._reports_conflict(first, clean)[0], 'identical countries must not conflict')
        reported, why = self._reports_conflict(first, conflicting)
        self.assertTrue(reported, f'the check must see conflicting search evidence today ({why})')

    def test_proposed_index_formats_cannot_report_a_conflicting_peer(self):
        for name, declaration in self.FORMATS[1:]:
            with self.subTest(name):
                root = Path(tempfile.mkdtemp(dir=self._dir.name))
                first = self._build(root / 'a.sqlite', declaration, conflict=False)
                conflicting = self._build(root / 'conflict.sqlite', declaration, conflict=True)
                reported, why = self._reports_conflict(first, conflicting)
                self.assertFalse(reported,
                                 f'{name} silently loses composed-country evidence validation ({why})')

    def setUp(self):
        self._dir = tempfile.TemporaryDirectory(ignore_cleanup_errors=True)

    def tearDown(self):
        self._dir.cleanup()


if __name__ == '__main__':
    unittest.main()
