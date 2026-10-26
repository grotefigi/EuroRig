import math
import tempfile
import unittest
import sqlite3
from contextlib import closing
from pathlib import Path
from compile_display import encode,cells,normalize,compile_display
from enrich_display import enrich


def decode(shape):
    result=[];previous=[0,0];offset=0
    while offset<len(shape):
        for axis in (0,1):
            value=shift=0
            while True:
                digit=ord(shape[offset])-63;offset+=1;value|=(digit&31)<<shift;shift+=5
                if digit<32:break
            previous[axis]+=~(value>>1) if value&1 else value>>1
        result.append(tuple(v/1e6 for v in previous))
    return result


class DisplayTests(unittest.TestCase):
    def test_polyline_roundtrip_negative_large_and_small_deltas(self):
        points=[(44.426801,26.102501),(44.426802,26.102502),(-43.123456,-29.456789),(0,0)]
        self.assertEqual(points,decode(encode(points)))

    def test_small_grid_edges_and_large_roads(self):
        self.assertEqual(4,len(cells((44,26,44.021,26.021))))
        self.assertEqual([],cells((43,20,48,29)))

    def test_romanian_accents_normalize_for_search(self):
        self.assertEqual('bucuresti',normalize('București'))

    def test_streamed_index_has_named_roads_addresses_and_prefix_search(self):
        try:import osmium
        except ImportError:self.skipTest('optional osmium build dependency unavailable')
        with tempfile.TemporaryDirectory() as folder:
            source=Path(folder)/'small.osm';output=Path(folder)/'display.sqlite'
            source.write_text('''<osm version="0.6"><node id="1" lat="44.42" lon="26.10"><tag k="name" v="București"/><tag k="place" v="city"/></node><node id="2" lat="44.43" lon="26.11"><tag k="addr:street" v="Strada Test"/><tag k="addr:housenumber" v="7"/></node><way id="4"><nd ref="1"/><nd ref="2"/><tag k="highway" v="primary"/><tag k="name" v="Șoseaua Test"/></way></osm>''',encoding='utf-8')
            metadata=compile_display([source],output,'Test',{'start':[44.42,26.10],'end':[44.43,26.11]})
            self.assertEqual(1,metadata['roads']);self.assertEqual(3,metadata['places']);self.assertEqual(0,metadata['missing_geometry'])
            with closing(sqlite3.connect(output)) as db:
                self.assertEqual(1,db.execute('PRAGMA user_version').fetchone()[0])
                labels=db.execute('SELECT p.label FROM search s JOIN places p ON p.id=s.rowid WHERE s.text MATCH ?',('bucurest*',)).fetchall()
                self.assertEqual([('București',)],labels)
                self.assertEqual([(44.42,26.1),(44.43,26.11)],decode(db.execute('SELECT shape FROM roads').fetchone()[0]))
            enrich([source],output)
            with closing(sqlite3.connect(output)) as db:
                self.assertEqual('ok',db.execute('PRAGMA integrity_check').fetchone()[0])
                self.assertEqual(0,db.execute('SELECT count(*) FROM road_rules').fetchone()[0])
                self.assertFalse(any('rtree' in (row[0] or '').lower() for row in db.execute('SELECT sql FROM sqlite_master')))

    def test_profile_evidence_preserves_access_dimensions_and_adr_tags(self):
        try:import osmium
        except ImportError:self.skipTest('optional osmium build dependency unavailable')
        with tempfile.TemporaryDirectory() as folder:
            output=Path(folder)/'display.sqlite';source=Path(__file__).parent/'fixtures/profiles.osm'
            compile_display([source],output,'QA',{'start':[45,27.001],'end':[45,27.015]});enrich([source],output)
            with closing(sqlite3.connect(output)) as db:
                self.assertEqual((3.5,2.4,12,20,8),db.execute('SELECT height,width,length,weight,axle FROM road_rules WHERE way=20000003').fetchone())
                flags,tags=db.execute('SELECT flags,tags FROM road_rules WHERE way=20000007').fetchone()
                self.assertTrue(flags&1);self.assertIn('"hgv":"no"',tags)
                self.assertIn('"tunnel:category":"C"',db.execute('SELECT tags FROM road_rules WHERE way=20000013').fetchone()[0])

    def test_truck_access_on_minor_paths_survives_package_build(self):
        try:import osmium
        except ImportError:self.skipTest('optional osmium build dependency unavailable')
        with tempfile.TemporaryDirectory() as folder:
            output=Path(folder)/'display.sqlite';source=Path(folder)/'minor.osm'
            source.write_text('''<osm version="0.6"><node id="1" lat="45" lon="27"/><node id="2" lat="45.001" lon="27.001"/>
            <way id="1"><nd ref="1"/><nd ref="2"/><tag k="highway" v="track"/><tag k="hgv" v="yes"/></way>
            <way id="2"><nd ref="1"/><nd ref="2"/><tag k="highway" v="pedestrian"/><tag k="hgv" v="delivery"/></way>
            <way id="3"><nd ref="1"/><nd ref="2"/><tag k="highway" v="primary"/></way></osm>''',encoding='utf-8')
            compile_display([source],output,'QA');enrich([source],output)
            with closing(sqlite3.connect(output)) as db:
                self.assertEqual(3,db.execute('SELECT count(*) FROM roads').fetchone()[0])
                self.assertIn('"hgv":"yes"',db.execute('SELECT tags FROM road_rules WHERE way=1').fetchone()[0])
                self.assertIn('"hgv":"delivery"',db.execute('SELECT tags FROM road_rules WHERE way=2').fetchone()[0])


if __name__=='__main__':unittest.main()
