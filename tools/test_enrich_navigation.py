import json
import sqlite3
import tempfile
import unittest
from contextlib import closing
from pathlib import Path
from enrich_navigation import enrich,keep_navigation_tags


class NavigationTagTests(unittest.TestCase):
    def test_lane_access_families_keep_direction_and_conditional_evidence(self):
        tags={
            'hgv:lanes':'yes|no',
            'hgv:lanes:forward':'yes|no',
            'motor_vehicle:lanes:backward':'yes|private',
            'access:lanes:conditional':'yes|no @ (Mo-Fr)',
            'turn:lanes:forward':'left|through',
            'surface':'asphalt',
        }
        self.assertEqual({key:value for key,value in tags.items() if key!='surface'},keep_navigation_tags(tags))

    def test_enrichment_package_retains_lane_access_and_marks_coverage_version(self):
        try:import osmium
        except ImportError:self.skipTest('optional osmium build dependency unavailable')
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);source=root/'lane.osm';base=root/'base.sqlite';destination=root/'navigation.sqlite'
            source.write_text('''<osm version="0.6"><node id="1" lat="44" lon="26"/><node id="2" lat="44.001" lon="26.001"/><way id="10"><nd ref="1"/><nd ref="2"/><tag k="highway" v="primary"/><tag k="name" v="Lane test"/><tag k="hgv:lanes:forward" v="yes|no"/><tag k="motor_vehicle:lanes:backward" v="yes|private"/><tag k="access:lanes:conditional" v="yes|no @ (Mo-Fr)"/></way></osm>''',encoding='utf-8')
            with closing(sqlite3.connect(base)) as db:
                db.executescript('CREATE TABLE metadata(key TEXT PRIMARY KEY,value TEXT);CREATE TABLE roads(id INTEGER PRIMARY KEY);CREATE TABLE node_rules(node INTEGER PRIMARY KEY);')
                with osmium.io.Reader(str(source)) as reader:stamp=reader.header().get('osmosis_replication_timestamp')
                db.execute('INSERT INTO metadata VALUES(?,?)',('sources',json.dumps([{'file':source.name,'osm_timestamp':stamp}])))
                db.execute('INSERT INTO roads VALUES(10)')
                db.commit()
            enrich(base,destination,[source])
            with closing(sqlite3.connect(destination)) as db:
                tags=json.loads(db.execute('SELECT tags FROM navigation_roads WHERE way=10').fetchone()[0])
                self.assertEqual({'hgv:lanes:forward':'yes|no','motor_vehicle:lanes:backward':'yes|private','access:lanes:conditional':'yes|no @ (Mo-Fr)'},tags)
                self.assertEqual('2',db.execute("SELECT value FROM metadata WHERE key='navigation_version'").fetchone()[0])


if __name__=='__main__':unittest.main()
