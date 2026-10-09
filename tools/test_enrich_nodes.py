"""Handler-level proof that enrich stores node restrictions it used to prefilter away.

summary()-only checks cannot show whether the handler inserted a row. This builds a real display.sqlite
from a minimal OSM XML with compile_display and then runs the actual enrich handler over it, so the
node_rules rows, their tags and the road_rules semantics are the real persisted evidence.
"""
import json
import sqlite3
import tempfile
import unittest
from contextlib import closing
from pathlib import Path

from compile_map import BLOCKED, HAZMAT, UNCERTAIN, EVIDENCE_PREFIXES, restrictions
from compile_display import compile_display
from enrich_display import enrich, summary

# The node prefilter as it was before the fix: it decided for itself which nodes were worth summarising.
OLD_NODE_PREFIXES = ('max', 'access', 'hgv', 'barrier', 'hazmat')

SOURCE = '''<osm version="0.6">
<node id="1" lat="45.0000" lon="27.0000"><tag k="minspeed" v="30"/></node>
<node id="2" lat="45.0005" lon="27.0005"><tag k="trailer" v="no"/></node>
<node id="3" lat="45.0007" lon="27.0007"><tag k="vehicle" v="no"/></node>
<node id="4" lat="45.0009" lon="27.0009"><tag k="name" v="Unrelated"/></node>
<node id="5" lat="45.0010" lon="27.0010"><tag k="maxheight" v="3.5"/><tag k="minspeed" v="30"/></node>
<node id="6" lat="45.0020" lon="27.0020"><tag k="maxheight" v="3.5"/></node>
<node id="7" lat="45.0020" lon="27.0020"><tag k="maxheight" v="3.5"/></node>
<node id="8" lat="45.0030" lon="27.0030"><tag k="oneway" v="yes"/></node>
<node id="9" lat="45.0031" lon="27.0031"><tag k="bridge" v="yes"/></node>
<way id="100"><nd ref="1"/><nd ref="5"/><nd ref="6"/>
  <tag k="highway" v="residential"/><tag k="maxheight" v="3.5"/></way>
</osm>'''

EXPECTED_NODES = {1, 2, 3, 5, 6, 7}


class NodeEvidenceHandlerTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        try:
            import osmium  # noqa: F401
        except ImportError:  # pragma: no cover
            raise unittest.SkipTest('optional osmium build dependency unavailable')
        cls._dir = tempfile.TemporaryDirectory(ignore_cleanup_errors=True)
        root = Path(cls._dir.name)
        cls.source = root / 'nodes.osm'
        cls.source.write_text(SOURCE, encoding='utf-8')
        cls.output = root / 'display.sqlite'
        compile_display([cls.source], cls.output, 'Node evidence',
                        {'start': [45.0, 27.0], 'end': [45.002, 27.002]})
        cls.counts = enrich([cls.source], cls.output)

    @classmethod
    def tearDownClass(cls):
        cls._dir.cleanup()

    def rules(self):
        with closing(sqlite3.connect(self.output)) as db:
            rows = {row[0]: row for row in db.execute(
                'SELECT node,lat,lon,height,width,length,weight,axle,flags,tags FROM node_rules')}
            road = db.execute('SELECT way,height,width,length,weight,axle,flags,tags FROM road_rules').fetchone()
            metadata = dict(db.execute('SELECT key,value FROM metadata'))
            integrity = db.execute('PRAGMA integrity_check').fetchone()[0]
        return rows, road, metadata, integrity

    def tags_of(self, rows, node):
        return json.loads(rows[node][9])

    def test_the_handler_persists_every_node_with_real_evidence(self):
        rows, _, _, integrity = self.rules()
        self.assertEqual(integrity, 'ok')
        self.assertEqual(set(rows), EXPECTED_NODES)
        self.assertNotIn(4, rows, 'a node with no restriction evidence must not be stored')

    def test_the_reason_is_persisted_with_the_node(self):
        rows, _, _, _ = self.rules()
        self.assertIn('minspeed', self.tags_of(rows, 1))
        self.assertTrue(rows[1][8] & UNCERTAIN)
        self.assertIn('trailer', self.tags_of(rows, 2))
        self.assertTrue(rows[2][8] & UNCERTAIN)

    def test_a_physical_and_an_uncertain_restriction_are_both_kept(self):
        rows, _, _, _ = self.rules()
        node = rows[5]
        self.assertEqual(node[3], 3.5)  # height
        self.assertTrue(node[8] & UNCERTAIN)
        self.assertEqual({'maxheight', 'minspeed'}, set(self.tags_of(rows, 5)))

    def test_the_old_prefilter_would_have_dropped_the_uncertain_nodes(self):
        """Before-fail at the handler: these nodes never reached summary under the old filter.

        The old filter kept only the three maxheight nodes (5, 6, 7), so node_rules held 3 rows; it now
        holds 6. vehicle/motor_vehicle/motorcar were dropped as well, not just minspeed/trailer.
        """
        rows, _, _, _ = self.rules()
        for node, key in ((1, 'minspeed'), (2, 'trailer'), (3, 'vehicle')):
            with self.subTest(node):
                self.assertFalse(key.startswith(OLD_NODE_PREFIXES),
                                 'control must show the old prefilter rejecting this node')
                self.assertIn(node, rows, 'after-pass: the handler persists it now')
        self.assertEqual(len(rows), 6)
        self.assertTrue(rows[3][8] & BLOCKED)

    def test_same_coordinate_overpass_nodes_are_both_kept(self):
        rows, _, _, _ = self.rules()
        self.assertEqual((rows[6][1], rows[6][2]), (rows[7][1], rows[7][2]))
        self.assertEqual((rows[6][3], rows[7][3]), (3.5, 3.5))

    def test_road_rules_and_physical_semantics_are_unchanged(self):
        _, road, metadata, _ = self.rules()
        self.assertEqual(road[0], 100)
        self.assertEqual(road[1], 3.5)   # height
        self.assertEqual(road[6], 0)     # no uncertain/blocked flag on the way itself
        self.assertIn('maxheight', json.loads(road[7]))
        self.assertEqual(json.loads(metadata['restriction_counts']),
                         {'road_rules': 1, 'node_rules': len(EXPECTED_NODES)})
        self.assertEqual(metadata['restriction_version'], '1')

    def test_a_node_with_only_context_tags_is_not_stored(self):
        """Not a tag dump: oneway=yes and bridge=yes pass the prefilter but carry no restriction."""
        rows, _, _, _ = self.rules()
        self.assertNotIn(8, rows)
        self.assertNotIn(9, rows)
        for key in ('oneway', 'bridge'):
            with self.subTest(key):
                self.assertTrue(key.startswith(EVIDENCE_PREFIXES), 'the prefilter does accept it')
                values = summary({key: 'yes'})
                self.assertEqual((any(values[:5]), values[5]), (False, 0),
                                 'but summary finds no limit and no flag')

    def test_the_prefilter_accepts_every_evidence_key(self):
        """Drift guard: the prefilter must be a superset of what can produce limits or flags."""
        probes = {'maxheight': '3.5', 'maxwidth': '2.5', 'maxlength': '12', 'maxweight': '7.5',
                  'maxaxleload': '8', 'minspeed': '30', 'trailer': 'no', 'hgv:trailer': 'yes',
                  'maxweightrating': '7.5', 'maxaxles': '3', 'maxbogieweight': '10', 'access': 'no',
                  'vehicle': 'no', 'motor_vehicle': 'no', 'motorcar': 'no', 'hgv': 'no', 'hazmat': 'yes',
                  'tunnel': 'yes', 'barrier': 'gate', 'oneway:conditional': 'no @ (22:00-06:00)',
                  'maxspeed:conditional': '30 @ (22:00-06:00)', 'toll:conditional': 'yes @ (22:00-06:00)'}
        for key, value in probes.items():
            with self.subTest(key):
                values = summary({key: value})
                produces = any(values[:5]) or values[5] & (BLOCKED | UNCERTAIN | HAZMAT)
                self.assertTrue(produces, f'{key} no longer produces a limit or flag')
                self.assertTrue(key.startswith(EVIDENCE_PREFIXES),
                                f'{key} produces evidence but the prefilter would drop the node')
                self.assertEqual(list(restrictions({key: value}, 'forward')[0]), list(values[:5]))


if __name__ == '__main__':
    unittest.main()
