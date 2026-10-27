"""ADR evidence survives enrichment and existing maps receive current classification."""
import json
import sqlite3
import tempfile
import unittest
from contextlib import closing
from pathlib import Path

from audit_restriction_coverage import audit_database
from compile_display import compile_display
from compile_map import HAZMAT
from enrich_display import enrich
from migrate_rules import VERSION, migrate, stored_version
from restriction_kind import HARD, classify


class AdrEvidenceTests(unittest.TestCase):
    def test_adr_only_ways_and_nodes_retain_flags_and_tags(self):
        with tempfile.TemporaryDirectory() as folder:
            source = Path(folder) / 'adr.osm'
            database = Path(folder) / 'display.sqlite'
            source.write_text('''<osm version="0.6" generator="EuroRig synthetic ADR test">
              <node id="1" lat="45" lon="27" version="1"><tag k="hazmat:water" v="no"/></node>
              <node id="2" lat="45" lon="27.01" version="1"/>
              <node id="3" lat="45" lon="27.02" version="1"/>
              <node id="4" lat="45" lon="27.03" version="1"/>
              <node id="5" lat="45" lon="27.04" version="1"/>
              <way id="11" version="1"><nd ref="1"/><nd ref="2"/><tag k="highway" v="primary"/><tag k="hazmat" v="no"/></way>
              <way id="12" version="1"><nd ref="2"/><nd ref="3"/><tag k="highway" v="primary"/><tag k="hazmat:explosives" v="no"/></way>
              <way id="13" version="1"><nd ref="3"/><nd ref="4"/><tag k="highway" v="primary"/><tag k="tunnel" v="yes"/><tag k="tunnel:category" v="C"/></way>
              <way id="14" version="1"><nd ref="4"/><nd ref="5"/><tag k="highway" v="primary"/><tag k="tunnel" v="yes"/></way>
            </osm>''', encoding='utf-8')
            self.assertEqual((HARD, 'hazmat=no'), classify({'hazmat': 'no'}, 'primary'))
            compile_display([source], database, 'Synthetic ADR evidence')
            counts = enrich([source], database)
            self.assertEqual({'road_rules': 4, 'node_rules': 1}, counts)
            expected = {11: {'hazmat': 'no'}, 12: {'hazmat:explosives': 'no'},
                        13: {'tunnel': 'yes', 'tunnel:category': 'C'}, 14: {'tunnel': 'yes'}}
            with closing(sqlite3.connect(database)) as db:
                self.assertEqual(VERSION, stored_version(db))
                rows = db.execute('SELECT way, flags, tags, kind FROM road_rules').fetchall()
                self.assertEqual(set(expected), {row[0] for row in rows})
                for way, flags, raw, kind in rows:
                    self.assertEqual(HAZMAT, flags & HAZMAT)
                    self.assertEqual(HARD if way in (11, 12) else 0, kind)
                    for key, value in expected[way].items():
                        self.assertEqual(value, json.loads(raw)[key])
                node = db.execute('SELECT flags,tags,kind FROM node_rules WHERE node=1').fetchone()
                self.assertEqual(HAZMAT, node[0] & HAZMAT)
                self.assertEqual('no', json.loads(node[1])['hazmat:water'])
                self.assertEqual(HARD, node[2])
            report = audit_database(database)
            # A bare tunnel is retained for the runtime unknown-category check, but is not an ADR tag.
            self.assertEqual(3, report['totals']['adr_tag_records'])
            self.assertEqual(1, report['totals']['adr_records_without_classification'])
            self.assertEqual('unknown', report['legal_completeness'])
            # Version 4 records may carry a non-null zero kind; migration must revisit them.
            with closing(sqlite3.connect(database)) as db:
                db.execute("UPDATE metadata SET value='4' WHERE key='restriction_version'")
                db.execute('UPDATE road_rules SET kind=0,evidence=\'\'')
                db.execute('UPDATE node_rules SET kind=0,evidence=\'\'')
                db.commit()
            self.assertEqual({'road_rules': 4, 'node_rules': 1}, migrate(database))
            with closing(sqlite3.connect(database)) as db:
                self.assertEqual(VERSION, stored_version(db))
                self.assertEqual([(11, HARD), (12, HARD)], db.execute(
                    'SELECT way,kind FROM road_rules WHERE way IN (11,12) ORDER BY way').fetchall())
                self.assertEqual(HARD, db.execute('SELECT kind FROM node_rules WHERE node=1').fetchone()[0])
            self.assertEqual({'road_rules': 0, 'node_rules': 0}, migrate(database))


if __name__ == '__main__':
    unittest.main()
