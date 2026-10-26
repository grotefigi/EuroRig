import hashlib
import json
import sqlite3
import tempfile
import unittest
from contextlib import closing
from pathlib import Path

from audit_restriction_coverage import audit_database, write_report
from compile_display import compile_display
from enrich_display import enrich


class CoverageAuditTests(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory()
        self.addCleanup(self.folder.cleanup)
        self.path = Path(self.folder.name) / 'display.sqlite'
        with closing(sqlite3.connect(self.path)) as db:
            db.executescript('''
                CREATE TABLE metadata(key TEXT PRIMARY KEY,value TEXT);
                CREATE TABLE roads(id INTEGER PRIMARY KEY,kind TEXT,south REAL,west REAL,north REAL,east REAL);
                CREATE TABLE road_rules(way INTEGER PRIMARY KEY,height REAL,width REAL,length REAL,weight REAL,axle REAL,kind INTEGER,tags TEXT);
                CREATE TABLE node_rules(node INTEGER PRIMARY KEY);
                INSERT INTO metadata VALUES('sources','[{"file":"synthetic.osm","osm_timestamp":"unknown"}]');
                INSERT INTO roads VALUES(1,'residential',45,27,45.01,27.01),(2,'primary',45,27,45.01,27.01),
                    (3,'motorway',45,27,45.01,27.01),(4,'secondary',45,27,45.01,27.01),(5,'unclassified',45,27,45.01,27.01);
                INSERT INTO road_rules VALUES(1,0,0,0,3.5,0,1,'{"maxweight":"3.5"}'),
                    (2,0,0,0,0,0,6,'{"hgv":"no","maxweight:conditional":"3.5 @ (22:00-06:00)"}'),
                    (3,0,0,0,0,0,33,'{"oneway":"yes"}'),
                    (4,0,0,0,0,0,0,'{"hazmat":"no"}'),
                    (99,2,0,0,0,0,1,'{"maxheight":"2"}');
                INSERT INTO node_rules VALUES(8);
            ''')
            db.commit()

    def change(self, sql):
        with closing(sqlite3.connect(self.path)) as db:
            db.execute(sql)
            db.commit()

    def test_counts_keep_nodes_oneways_and_missing_way_records_distinct(self):
        before = hashlib.sha256(self.path.read_bytes()).hexdigest()
        report = audit_database(self.path)
        self.assertEqual({'roads': 5, 'way_rule_records': 4, 'roads_without_way_rule_record': 1,
                          'numeric_limit_records': 1, 'hgv_tag_records': 1, 'adr_tag_records': 1,
                          'conditional_tag_records': 1, 'uncertain_records': 1,
                          'adr_records_without_classification': 1}, report['totals'])
        self.assertEqual(1, report['node_rule_records'])
        self.assertEqual(1, report['orphan_way_rule_records'])
        self.assertEqual('unknown', report['legal_completeness'])
        self.assertEqual(before, hashlib.sha256(self.path.read_bytes()).hexdigest())
        self.assertEqual(1, report['by_road_class']['motorway']['way_rule_records'])
        self.assertEqual(0, report['by_road_class']['motorway']['numeric_limit_records'])

    def test_spatial_buckets_use_negative_centres_without_claiming_admin_boundaries(self):
        self.change('UPDATE roads SET south=-0.2,north=-0.1,west=-0.2,east=-0.1 WHERE id=5')
        spatial = audit_database(self.path)['spatial']
        self.assertIn('not jurisdiction', spatial['method'])
        cell = next(c for c in spatial['cells'] if c['south'] < 0)
        self.assertEqual(-0.25, cell['west'])
        self.assertEqual(1, cell['roads_without_way_rule_record'])

    def test_invalid_bucket_sizes_are_rejected(self):
        for value in (0, -1, 11, float('nan'), float('inf')):
            with self.subTest(value=value), self.assertRaises(ValueError):
                audit_database(self.path, value)

    def test_missing_enrichment_is_not_reported_as_zero_restrictions(self):
        self.change('DROP TABLE road_rules')
        with self.assertRaisesRegex(ValueError, 'lacks enriched restriction tables'):
            audit_database(self.path)

    def test_invalid_bounds_are_rejected(self):
        for sql in ('UPDATE roads SET south=NULL WHERE id=1', 'UPDATE roads SET west=181 WHERE id=1'):
            with self.subTest(sql=sql):
                self.change(sql)
                with self.assertRaisesRegex(ValueError, 'Invalid road bounds'):
                    audit_database(self.path)

    def test_invalid_tag_payload_is_not_silently_counted(self):
        self.change("UPDATE road_rules SET tags='[]' WHERE way=1")
        with self.assertRaisesRegex(ValueError, 'Invalid restriction tags'):
            audit_database(self.path)

    def test_batch_report_preserves_previous_output(self):
        out = Path(self.folder.name) / 'coverage.json'
        write_report([self.path, self.path], out)
        original = out.read_bytes()
        self.assertEqual(2, len(json.loads(original)['maps']))
        with self.assertRaises(FileExistsError):
            write_report([self.path], out)
        self.assertEqual(original, out.read_bytes())

    def test_real_compiler_and_enrichment_produce_auditable_data(self):
        output = Path(self.folder.name) / 'compiled.sqlite'
        source = Path(__file__).parent / 'fixtures/profiles.osm'
        compile_display([source], output, 'Synthetic profile audit')
        enrich([source], output)
        report = audit_database(output)
        self.assertGreater(report['totals']['numeric_limit_records'], 0)
        self.assertGreater(report['totals']['hgv_tag_records'], 0)
        with closing(sqlite3.connect(output)) as db:
            self.assertEqual(db.execute('SELECT count(*) FROM roads').fetchone()[0], report['totals']['roads'])
            self.assertEqual(db.execute('SELECT count(*) FROM road_rules').fetchone()[0], report['totals']['way_rule_records'])
        self.assertEqual('unknown', report['legal_completeness'])


if __name__ == '__main__':
    unittest.main()
