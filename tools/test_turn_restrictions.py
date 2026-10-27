"""Source-derived turn evidence tests; no commercial maps or legal oracle."""
import json
import sqlite3
import tempfile
import unittest
from contextlib import closing
from pathlib import Path
from compile_display import compile_display
from compile_map import compile_osm, UNCERTAIN
from enrich_display import enrich
from turn_restrictions import applicability, topology, SUPPORTED, UNKNOWN, EXEMPT


class TurnEvidenceTests(unittest.TestCase):
    def test_truck_scope_precedence_and_exceptions(self):
        base = {'type': 'restriction', 'restriction': 'no_left_turn'}
        self.assertEqual(('no_left_turn', SUPPORTED, ''), applicability(base))
        self.assertEqual('only_right_turn', applicability(dict(base, **{'restriction:hgv': 'only_right_turn'}))[0])
        self.assertEqual(EXEMPT, applicability(dict(base, **{'except': 'hgv;bus'}))[1])
        self.assertEqual(SUPPORTED, applicability(dict(base, **{'except': 'bus;bicycle'}))[1])
        self.assertEqual(UNKNOWN, applicability(dict(base, **{'restriction:hgv': 'no_left_turn', 'except': 'hgv'}))[1])

    def test_unsupported_applicability_never_becomes_clearance(self):
        for extra in ({'restriction:conditional': 'no_left_turn @ (Mo-Fr)'},
                      {'day_on': 'Mo'}, {'restriction:hgv:trailer': 'no_right_turn'},
                      {'except': 'hgv:articulated'}, {'restriction': 'maybe'},
                      {'type': 'restriction:hgv'}):
            with self.subTest(extra=extra):
                self.assertEqual(UNKNOWN, applicability(dict({'type': 'restriction', 'restriction': 'no_left_turn'}, **extra))[1])

    def test_ordered_multiple_vias_and_reverse_way_node_order(self):
        members = [('w', 11, 'from'), ('w', 12, 'via'), ('w', 13, 'via'), ('w', 14, 'to')]
        self.assertEqual(([11, 12, 13, 14], [2, 3, 4]), topology(members, {11: [1, 2], 12: [3, 2], 13: [3, 4], 14: [5, 4]}))
        with self.assertRaises(ValueError):
            topology([members[0], members[2], members[1], members[3]], {11: [1, 2], 12: [2, 3], 13: [3, 4], 14: [4, 5]})

    def test_ambiguous_missing_mixed_and_internal_junctions_are_unknown(self):
        good = [('w', 11, 'from'), ('w', 12, 'via'), ('w', 13, 'to')]
        for members, ways in ((good, {11: [1, 2], 12: [2, 3]}),
                              (good, {11: [1, 2], 12: [2, 4, 3], 13: [4, 5]}),
                              (good, {11: [1, 2], 12: [2, 3, 2], 13: [3, 5]}),
                              (good, {11: [1, 2], 12: [2, 3, 4, 5, 4], 13: [4, 6]}),
                              (good + [('n', 2, 'via')], {11: [1, 2], 12: [2, 3], 13: [3, 5]})):
            with self.subTest(members=members, ways=ways), self.assertRaises(ValueError):
                topology(members, ways)

    def test_enrichment_retains_relations_raw_evidence_and_members(self):
        with tempfile.TemporaryDirectory() as folder:
            source, destination = Path(folder)/'turns.osm', Path(folder)/'display.sqlite'
            source.write_text('''<osm version="0.6">
              <node id="1" lat="46" lon="26"/><node id="2" lat="46" lon="26.004"/>
              <node id="3" lat="46" lon="26.008"/><node id="4" lat="46.004" lon="26.008"/>
              <way id="11"><nd ref="1"/><nd ref="2"/><tag k="highway" v="residential"/></way>
              <way id="12"><nd ref="2"/><nd ref="3"/><tag k="highway" v="residential"/></way>
              <way id="13"><nd ref="3"/><nd ref="4"/><tag k="highway" v="residential"/></way>
              <relation id="101"><member type="way" ref="11" role="from"/><member type="way" ref="12" role="via"/><member type="way" ref="13" role="to"/><tag k="type" v="restriction"/><tag k="restriction:hgv" v="no_right_turn"/></relation>
              <relation id="102"><member type="way" ref="11" role="from"/><member type="node" ref="2" role="via"/><member type="way" ref="12" role="to"/><tag k="type" v="restriction"/><tag k="restriction:conditional" v="no_straight_on @ (Mo-Fr)"/></relation>
              <relation id="103"><member type="way" ref="11" role="from"/><member type="node" ref="2" role="via"/><member type="way" ref="12" role="to"/><tag k="type" v="restriction"/><tag k="restriction" v="no_straight_on"/><tag k="except" v="hgv"/></relation>
              <relation id="104"><member type="way" ref="11" role="from"/><member type="way" ref="999" role="via"/><member type="way" ref="13" role="to"/><tag k="type" v="restriction"/><tag k="restriction" v="no_straight_on"/></relation>
            </osm>''', encoding='utf-8')
            compile_display([source], destination, 'Synthetic turns')
            self.assertEqual({'road_rules': 0, 'node_rules': 0}, enrich([source], destination))
            with closing(sqlite3.connect(destination)) as db:
                rows = db.execute('SELECT relation,status,chain,junctions,raw FROM turn_rules ORDER BY relation').fetchall()
                self.assertEqual([SUPPORTED, UNKNOWN, EXEMPT, UNKNOWN], [r[1] for r in rows])
                self.assertEqual([11, 12, 13], json.loads(rows[0][2]))
                self.assertEqual([[46, 26.004], [46, 26.008]], json.loads(rows[0][3]))
                self.assertEqual('no_right_turn', json.loads(rows[0][4])['tags']['restriction:hgv'])
                self.assertEqual(999, json.loads(rows[3][4])['members'][1][1])
                self.assertEqual([(11, 104), (13, 104), (999, 104)], db.execute('SELECT * FROM turn_members WHERE relation=104 ORDER BY way').fetchall())
                self.assertEqual('1', db.execute("SELECT value FROM metadata WHERE key='turn_restriction_version'").fetchone()[0])
                self.assertEqual({'supported': 1, 'unknown': 2, 'exempt': 1}, json.loads(db.execute("SELECT value FROM metadata WHERE key='turn_restriction_counts'").fetchone()[0]))
            legacy = compile_osm(source)
            self.assertEqual([], legacy['turns'])
            self.assertTrue(all(edge[-2] & UNCERTAIN for edge in legacy['edges']))


if __name__ == '__main__':
    unittest.main()
