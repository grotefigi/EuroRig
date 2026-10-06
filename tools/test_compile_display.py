import math
import tempfile
import unittest
import sqlite3
from contextlib import closing
from pathlib import Path
from compile_display import encode,cells,normalize,compile_display


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


if __name__=='__main__':unittest.main()
