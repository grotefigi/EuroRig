import csv
import io
import json
import sqlite3
import subprocess
import sys
import tarfile
import tempfile
import unittest
import zipfile
from contextlib import closing
from pathlib import Path
from unittest.mock import patch

from apply_authority_rules import apply
from authority_intake import FIELDS, intake
from compile_display import compile_display
from compile_map import BLOCKED
from enrich_display import enrich
from package_region import package, sha256
from restriction_kind import ACCESS, BACKWARD, HARD


class AuthorityApplicationTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        source = self.root / 'synthetic.osm'
        source.write_text('''<osm version="0.6" generator="EuroRig original synthetic authority fixture">
          <node id="1" lat="45" lon="27" version="1"/>
          <node id="2" lat="45" lon="27.01" version="1"/>
          <node id="3" lat="45" lon="27.02" version="1"/>
          <node id="4" lat="45" lon="27.03" version="1"/>
          <way id="101" version="1"><nd ref="1"/><nd ref="2"/><tag k="highway" v="primary"/></way>
          <way id="102" version="1"><nd ref="2"/><nd ref="3"/><tag k="highway" v="primary"/><tag k="maxheight" v="3.2"/></way>
          <way id="103" version="1"><nd ref="3"/><nd ref="4"/><tag k="highway" v="primary"/><tag k="access" v="no"/><tag k="hgv:forward" v="yes"/></way>
        </osm>''', encoding='utf-8')
        self.display = self.root / 'original.sqlite'
        compile_display([source], self.display, 'Synthetic authority application')
        enrich([source], self.display)
        self.rows = [dict(zip(FIELDS, ('height-1', 'RO', 'height', '350', 'cm', 'both', '', '2026-01-01', '', '', '')))]
        self.matches = {'height-1': {'osm_way_ids': [101], 'review_ref': 'Original synthetic exact-way fixture'}}
        self.review = {'schema_version': 1, 'status': 'approved', 'source_id': 'original-synthetic-ro',
                       'publisher': 'EuroRig original fixture', 'source_url': 'https://example.org/synthetic',
                       'country': 'RO', 'license': 'Original EuroRig fixture', 'reuse_allowed': True,
                       'map_derivative_approved': True, 'license_review': 'Original synthetic derivative fixture',
                       'map_attribution': 'EuroRig original synthetic fixture',
                       'currentness_review': 'Synthetic validity fixture, no legal claim',
                       'reviewed_on': '2026-10-01', 'review_due': '2026-11-01'}

    def stage(self, name='bundle'):
        source, matches, review = [self.root / (name + suffix) for suffix in ('.csv', '-matches.json', '-review.json')]
        with source.open('w', encoding='utf-8', newline='') as stream:
            writer = csv.DictWriter(stream, fieldnames=FIELDS)
            writer.writeheader(); writer.writerows(self.rows)
        matches.write_text(json.dumps(self.matches), encoding='utf-8')
        self.review.update(source_sha256=sha256(source), matches_sha256=sha256(matches), display_sha256=sha256(self.display))
        review.write_text(json.dumps(self.review), encoding='utf-8')
        bundle = self.root / name
        intake(source, review, matches, self.display, bundle, '2026-10-25')
        return bundle

    def test_applies_numeric_rules_to_a_clone_with_provenance_and_deterministic_receipts(self):
        self.rows.append({**self.rows[0], 'record_id': 'mass-1', 'restriction': 'loaded_mass',
                          'value': '7500', 'unit': 'kg', 'mass_basis': 'loaded'})
        self.matches['mass-1'] = self.matches['height-1']
        bundle = self.stage()
        before = sha256(self.display)
        result = apply(self.display, [bundle], self.root / 'applied', '2026-10-26')
        self.assertEqual(('applied', 2, 0), (result['status'], result['applied_records'], result['application_rejected']))
        with closing(sqlite3.connect(self.root / 'applied/display.sqlite')) as db:
            row = db.execute('SELECT height,weight,kind FROM road_rules WHERE way=101').fetchone()
            self.assertEqual((3.5, 7.5, HARD), row)
            self.assertEqual(2, db.execute('SELECT count(*) FROM authority_rules').fetchone()[0])
            self.assertEqual('ok', db.execute('PRAGMA integrity_check').fetchone()[0])
        self.assertEqual(before, sha256(self.display))
        self.assertFalse(result['native_tiles_modified'])
        self.assertEqual('unknown', result['legal_completeness'])
        repeated = apply(self.display, [bundle], self.root / 'repeat', '2026-10-26')
        self.assertEqual(result, repeated)
        for name, digest in result['files'].items():
            self.assertEqual(digest, sha256(self.root / 'applied' / name))

    def test_never_raises_existing_clearance_or_discards_other_limits(self):
        self.matches['height-1']['osm_way_ids'] = [102]
        self.rows[0]['value'] = '425'
        result = apply(self.display, [self.stage()], self.root / 'applied', '2026-10-26')
        self.assertEqual(1, result['applied_records'])
        with closing(sqlite3.connect(self.root / 'applied/display.sqlite')) as db:
            self.assertEqual(3.2, db.execute('SELECT height FROM road_rules WHERE way=102').fetchone()[0])

    def test_new_directional_limits_keep_their_travel_direction(self):
        for index, (restriction, value, unit, kind) in enumerate((('height', '350', 'cm', HARD), ('hgv_access', 'no', '', ACCESS))):
            self.rows[0].update(restriction=restriction, value=value, unit=unit, direction='backward')
            apply(self.display, [self.stage('direction' + str(index))], self.root / ('direction-applied' + str(index)), '2026-10-26')
            with closing(sqlite3.connect(self.root / ('direction-applied' + str(index)) / 'display.sqlite')) as db:
                row = db.execute('SELECT kind,tags FROM road_rules WHERE way=101').fetchone()
                self.assertEqual(kind | BACKWARD, row[0])
                key = 'maxheight:backward' if restriction == 'height' else 'hgv:backward'
                self.assertIn(key, json.loads(row[1]))

    def test_hgv_ban_closes_an_existing_scoped_grant(self):
        self.rows[0].update(restriction='hgv_access', value='no', unit='')
        self.matches['height-1']['osm_way_ids'] = [103]
        apply(self.display, [self.stage()], self.root / 'applied', '2026-10-26')
        with closing(sqlite3.connect(self.root / 'applied/display.sqlite')) as db:
            flags, raw = db.execute('SELECT flags,tags FROM road_rules WHERE way=103').fetchone()
            self.assertTrue(flags & BLOCKED)
            tags = json.loads(raw)
            self.assertEqual('no', tags['hgv'])
            self.assertEqual('no', tags['hgv:forward'])
            self.assertEqual('no', tags['access'])

    def test_unsupported_access_exceptions_are_queued_without_changing_that_record(self):
        self.rows.append({**self.rows[0], 'record_id': 'access-1', 'restriction': 'hgv_access', 'value': 'delivery', 'unit': ''})
        self.matches['access-1'] = {'osm_way_ids': [103], 'review_ref': 'Synthetic'}
        result = apply(self.display, [self.stage()], self.root / 'applied', '2026-10-26')
        self.assertEqual((1, 1), (result['applied_records'], result['application_rejected']))
        rejection = json.loads((self.root / 'applied/application-rejected.jsonl').read_text())
        self.assertEqual('access_exception_requires_separate_review', rejection['application_rejection'])
        with closing(sqlite3.connect(self.root / 'applied/display.sqlite')) as db:
            tags = json.loads(db.execute('SELECT tags FROM road_rules WHERE way=103').fetchone()[0])
            self.assertEqual('yes', tags['hgv:forward'])
            self.assertNotIn('hgv', tags)

    def test_existing_uncertainty_cannot_mask_a_new_hgv_ban(self):
        self.rows[0].update(restriction='hgv_access', value='no', unit='')
        self.matches['height-1']['osm_way_ids'] = [101, 103]
        for index, key in enumerate(('hgv:conditional', 'access:conditional', 'maxheight:conditional')):
            with closing(sqlite3.connect(self.display)) as db:
                tags = {'access': 'no', 'hgv:forward': 'yes', key: 'yes @ (Mo-Fr)'}
                db.execute('UPDATE road_rules SET tags=? WHERE way=103', (json.dumps(tags),)); db.commit()
            output = self.root / ('uncertain-applied' + str(index))
            result = apply(self.display, [self.stage('uncertain' + str(index))], output, '2026-10-26')
            self.assertEqual((0, 1), (result['applied_records'], result['application_rejected']))
            rejected = json.loads((output / 'application-rejected.jsonl').read_text())
            self.assertEqual('existing_uncertainty_conflicts_with_access_ban', rejected['application_rejection'])
            with closing(sqlite3.connect(output / 'display.sqlite')) as db:
                self.assertIsNone(db.execute('SELECT way FROM road_rules WHERE way=101').fetchone())
                self.assertNotIn('hgv', json.loads(db.execute('SELECT tags FROM road_rules WHERE way=103').fetchone()[0]))

    def test_tampered_acceptance_still_fails_after_a_worker_rehashes_the_manifest(self):
        bundle = self.stage()
        accepted = bundle / 'accepted.jsonl'
        record = json.loads(accepted.read_text())
        record['value_si'] = 9
        accepted.write_text(json.dumps(record) + '\n')
        manifest = json.loads((bundle / 'manifest.json').read_text())
        manifest['files']['accepted.jsonl'] = sha256(accepted)
        (bundle / 'manifest.json').write_text(json.dumps(manifest))
        before = sha256(self.display)
        with self.assertRaisesRegex(ValueError, 'no longer matches'):
            apply(self.display, [bundle], self.root / 'applied', '2026-10-26')
        self.assertFalse((self.root / 'applied').exists())
        self.assertEqual(before, sha256(self.display))

    def test_expired_review_and_changed_map_are_rejected_before_output(self):
        bundle = self.stage()
        with self.assertRaisesRegex(ValueError, 'no longer matches'):
            apply(self.display, [bundle], self.root / 'expired', '2026-11-02')
        with closing(sqlite3.connect(self.display)) as db:
            db.execute("UPDATE metadata SET value='changed' WHERE key='name'"); db.commit()
        with self.assertRaisesRegex(ValueError, 'no longer matches'):
            apply(self.display, [bundle], self.root / 'changed', '2026-10-26')
        self.assertFalse((self.root / 'changed').exists())

    def test_derivative_rights_are_an_additional_application_gate(self):
        self.review['map_derivative_approved'] = False
        with self.assertRaisesRegex(ValueError, 'reuse is not approved'):
            apply(self.display, [self.stage()], self.root / 'applied', '2026-10-26')
        self.assertFalse((self.root / 'applied').exists())

    def test_missing_source_attribution_is_rejected(self):
        self.review.pop('map_attribution')
        with self.assertRaisesRegex(ValueError, 'attribution is required'):
            apply(self.display, [self.stage()], self.root / 'applied', '2026-10-26')

    def test_two_country_sources_are_revalidated_against_the_same_original_map(self):
        first = self.stage('ro')
        self.review.update(source_id='original-synthetic-md', country='MD')
        self.rows[0].update(country='MD', restriction='loaded_mass', value='7.5', unit='t', mass_basis='loaded')
        self.matches['height-1']['osm_way_ids'] = [102]
        second = self.stage('md')
        result = apply(self.display, [first, second], self.root / 'both', '2026-10-26')
        self.assertEqual(2, result['applied_records'])
        self.assertEqual({'RO', 'MD'}, {s['country'] for s in result['sources']})
        with closing(sqlite3.connect(self.root / 'both/display.sqlite')) as db:
            self.assertEqual(3.5, db.execute('SELECT height FROM road_rules WHERE way=101').fetchone()[0])
            self.assertEqual((3.2, 7.5), db.execute('SELECT height,weight FROM road_rules WHERE way=102').fetchone())

    def test_a_multiway_record_does_not_partially_apply_when_one_existing_limit_is_unknown(self):
        with closing(sqlite3.connect(self.display)) as db:
            tags = json.loads(db.execute('SELECT tags FROM road_rules WHERE way=102').fetchone()[0])
            tags['maxheight'] = 'unknown'
            db.execute('UPDATE road_rules SET tags=? WHERE way=102', (json.dumps(tags),)); db.commit()
        self.matches['height-1']['osm_way_ids'] = [101, 102]
        result = apply(self.display, [self.stage()], self.root / 'applied', '2026-10-26')
        self.assertEqual(('quarantined_only', 0, 1), (result['status'], result['applied_records'], result['application_rejected']))
        with closing(sqlite3.connect(self.root / 'applied/display.sqlite')) as db:
            self.assertIsNone(db.execute('SELECT height FROM road_rules WHERE way=101').fetchone())
            self.assertEqual(3.2, db.execute('SELECT height FROM road_rules WHERE way=102').fetchone()[0])
            self.assertEqual([], json.loads(db.execute("SELECT value FROM metadata WHERE key='authority_sources'").fetchone()[0]))

    def test_existing_output_and_duplicate_sources_are_preserved(self):
        bundle = self.stage()
        with self.assertRaisesRegex(ValueError, 'Duplicate source'):
            apply(self.display, [bundle, bundle], self.root / 'duplicate', '2026-10-26')
        apply(self.display, [bundle], self.root / 'applied', '2026-10-26')
        before = sha256(self.root / 'applied/display.sqlite')
        with self.assertRaises(FileExistsError):
            apply(self.display, [bundle], self.root / 'applied', '2026-10-26')
        self.assertEqual(before, sha256(self.root / 'applied/display.sqlite'))

    def test_input_open_failure_removes_only_attempt_created_output(self):
        bundle = self.stage()
        destination = self.root / 'failed'
        original_open = Path.open
        def denied(path, *args, **kwargs):
            if path == self.display and destination.exists():
                raise PermissionError('Injected input-open failure')
            return original_open(path, *args, **kwargs)
        before = sha256(self.display)
        with patch.object(Path, 'open', denied), self.assertRaisesRegex(PermissionError, 'Injected'):
            apply(self.display, [bundle], destination, '2026-10-26')
        self.assertFalse(destination.exists())
        self.assertEqual(before, sha256(self.display))

    def test_report_write_failure_cleans_created_files_and_preserves_input(self):
        bundle = self.stage()
        destination = self.root / 'failed-report'
        original_dump = json.dump
        def broken(value, stream, *args, **kwargs):
            if Path(stream.name).name == 'restriction-coverage.json':
                stream.write('partial report')
                raise OSError('Injected report-write failure')
            return original_dump(value, stream, *args, **kwargs)
        before = sha256(self.display)
        with patch('apply_authority_rules.json.dump', broken), self.assertRaisesRegex(OSError, 'Injected'):
            apply(self.display, [bundle], destination, '2026-10-26')
        self.assertFalse(destination.exists())
        self.assertEqual(before, sha256(self.display))

    def test_cli_output_packages_the_applied_map_and_source_licenses(self):
        bundle = self.stage()
        command = [sys.executable, str(Path(__file__).with_name('apply_authority_rules.py')),
                   '--display', str(self.display), '--bundle', str(bundle), '--out', str(self.root / 'cli'), '--as-of', '2026-10-26']
        cli = subprocess.run(command, capture_output=True, text=True, check=True)
        self.assertEqual(1, json.loads(cli.stdout)['applied_records'])
        tiles = self.root / 'routing.tar'
        with tarfile.open(tiles, 'w') as archive:
            entry = tarfile.TarInfo('2/000/123.gph'); entry.size = 512
            archive.addfile(entry, io.BytesIO(b'x' * entry.size))
        packed = self.root / 'reviewed.eurorig'
        manifest = package(tiles, self.root / 'cli/display.sqlite', packed, 'Synthetic', 'Original fixture', '2026-10-26')
        self.assertEqual('original-synthetic-ro', manifest['restriction_sources'][0]['source_id'])
        self.assertEqual(self.review['license'], manifest['restriction_sources'][0]['license'])
        self.assertIn(self.review['map_attribution'], manifest['attribution'])
        with zipfile.ZipFile(packed) as archive:
            self.assertEqual(sha256(self.root / 'cli/display.sqlite'), manifest['sha256']['display.sqlite'])
            self.assertEqual((self.root / 'cli/display.sqlite').read_bytes(), archive.read('display.sqlite'))


if __name__ == '__main__':
    unittest.main()
