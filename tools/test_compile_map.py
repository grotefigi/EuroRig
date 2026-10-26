import tempfile
import unittest
from pathlib import Path
from compile_map import compile_osm, restrictions, limit, BLOCKED, UNCERTAIN, HAZMAT, TOLL

class CompilerTests(unittest.TestCase):
    def test_units(self):
        self.assertAlmostEqual(limit('13\'1"'), 3.9878)
        self.assertEqual(limit('3500 kg', True), 3.5)
        self.assertIsNone(limit('below_default'))
        self.assertIsNone(limit('4 @ wet'))
    def test_conditional_fails_closed(self):
        self.assertTrue(restrictions({'hgv:conditional':'no @ (Mo-Fr)'}, 'forward')[1] & UNCERTAIN)
        self.assertTrue(restrictions({'maxheight:lanes':'3.5|4.5'}, 'forward')[1] & UNCERTAIN)
    def test_specific_access(self):
        self.assertFalse(restrictions({'access':'no','hgv':'yes'}, 'forward')[1] & BLOCKED)
        self.assertTrue(restrictions({'hgv':'destination'}, 'forward')[1] & BLOCKED)
    def test_directional_limit(self):
        self.assertEqual(restrictions({'maxheight:forward':'3.5','maxheight:backward':'4.5'},'forward')[0][0],3.5)
        self.assertEqual(restrictions({'maxheight:forward':'3.5','maxheight:backward':'4.5'},'backward')[0][0],4.5)
    def test_node_barrier(self):
        self.assertTrue(restrictions({'barrier':'bollard'}, 'forward')[1] & BLOCKED)
    def test_tunnel_hazmat_and_toll(self):
        flags=restrictions({'tunnel':'yes','toll:hgv':'yes'},'forward')[1]
        self.assertEqual(flags & (HAZMAT|TOLL),HAZMAT|TOLL)
    def test_xml_geometry_oneway_and_turns(self):
        xml='''<osm version="0.6">
        <node id="1" lat="44" lon="26"/><node id="2" lat="44" lon="26.01"><tag k="maxheight" v="3.5"/></node>
        <node id="3" lat="44.01" lon="26.01"/><node id="4" lat="44.01" lon="26"/>
        <way id="10"><nd ref="1"/><nd ref="2"/><tag k="highway" v="primary"/><tag k="oneway" v="yes"/></way>
        <way id="20"><nd ref="2"/><nd ref="3"/><tag k="highway" v="primary"/></way>
        <way id="30"><nd ref="3"/><nd ref="4"/><tag k="highway" v="primary"/></way>
        <relation id="100"><member type="way" ref="10" role="from"/><member type="node" ref="2" role="via"/><member type="way" ref="20" role="to"/><tag k="type" v="restriction"/><tag k="restriction" v="no_right_turn"/></relation>
        <relation id="101"><member type="way" ref="20" role="from"/><member type="way" ref="30" role="via"/><member type="way" ref="10" role="to"/><tag k="type" v="restriction"/><tag k="restriction" v="no_left_turn"/></relation>
        </osm>'''
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)/'test.osm';path.write_text(xml)
            g=compile_osm(path)
        self.assertEqual(len(g['edges']),5)
        self.assertEqual(g['edges'][0][5],3.5)
        self.assertEqual(g['turns'],[(1,10,20,False)])
        self.assertEqual(g['excluded_ways'],3)
        self.assertTrue(all(e[-2]&UNCERTAIN for e in g['edges']))

if __name__=='__main__':unittest.main()
