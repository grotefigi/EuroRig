import copy
import hashlib
import html
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

from cestrin_candidates import MAIN, ORDINALS, UNITS, extract

URL = 'https://www.cestrin.ro/restrictii/example-og43.htm'


def row(values):
    return '<tr>' + ''.join('<td>' + html.escape(str(value)) + '</td>' for value in values) + '</tr>'


def document(records, main=MAIN, units=UNITS, ordinals=ORDINALS):
    return ('<table>' + row(main) + row(tuple(units) + ('Bypass km', 'Category', 'Road', 'Bypass position'))
            + row(ordinals) + ''.join(row(r) for r in records) + '</table>').encode('utf-8')


def record():
    cells = [''] * 34
    cells[:14] = ['1', 'DN', '1C', '15', '+', '35', '-', '15', '+', '89', 'Apahida', 'Cluj', 'Bridge', 'mass']
    cells[18:25] = ['999', '+', '888', '-', '777', '+', '666']
    return cells


class CestrinCandidateTests(unittest.TestCase):
    def test_detour_numbers_never_become_limits(self):
        data = document([record()]); result = extract(data, URL); candidate = result['records'][0]
        self.assertEqual([], candidate['measurements'])
        self.assertIn('non_numeric_or_missing_limit', candidate['issues'])
        self.assertEqual(['999', '+', '888', '-', '777', '+', '666'], candidate['detour_raw'][:7])
        self.assertEqual(hashlib.sha256(data).hexdigest(), candidate['source_sha256'])
        self.assertEqual(4, candidate['source_row'])

    def test_only_four_bound_columns_produce_typed_values(self):
        cells = record(); cells[14:18] = ['16', '8,5', '3.00', '4.25']
        result = extract(document([cells]), URL)
        self.assertEqual([('gross_mass', 16, 't', 14), ('single_axle_mass', 8.5, 't', 15),
                          ('width', 3, 'm', 16), ('height', 4.25, 'm', 17)],
                         [(m['quantity'], m['value'], m['unit'], m['source_column'])
                          for m in result['records'][0]['measurements']])
        self.assertFalse(result['import_approved']); self.assertTrue(result['candidate_only'])
        self.assertEqual('unknown', result['reuse_rights'])
        self.assertEqual('unknown', result['records'][0]['measurements'][0]['mass_basis'])
        self.assertIn('rule_applicability', result['records'][0]['review_required'])

    def test_changed_units_grouping_and_numbering_fail(self):
        for data in (document([record()], units=('masa totala [kg]', *UNITS[1:])),
                     document([record()], main=tuple(reversed(MAIN))),
                     document([record()], ordinals=tuple(reversed(ORDINALS)))):
            with self.subTest(data=data), self.assertRaises(ValueError):
                extract(data, URL)

    def test_unknown_numbers_and_chainage_remain_quarantined(self):
        for bad in ('16/20', 'NaN', '1e309', '-3', '0'):
            cells = record(); cells[14] = bad; cells[7:10] = ['14', '+', '0']
            candidate = extract(document([cells]), URL)['records'][0]
            self.assertEqual([], candidate['measurements'])
            self.assertIn('reversed_chainage', candidate['issues'])
            self.assertEqual('quarantined', candidate['status'])
            self.assertEqual(bad, candidate['raw_cells'][14])

    def test_duplicate_ids_and_unknown_row_shapes_are_not_discarded(self):
        cells = record(); cells[14] = '16'
        result = extract(document([cells, copy.deepcopy(cells), ['3', 'DN']]), URL)
        self.assertEqual(3, result['summary']['source_rows'])
        self.assertEqual(3, len({r['record_id'] for r in result['records']}))
        self.assertIn('duplicate_publisher_record_id', result['records'][0]['issues'])
        self.assertIn('duplicate_publisher_record_id', result['records'][1]['issues'])
        self.assertIn('unknown_row_shape', result['records'][2]['issues'])

    def test_html_entities_and_malformed_input(self):
        cells = record(); cells[10] = 'Mihăileni'; data = document([cells]).replace('ă'.encode(), b'&#259;')
        self.assertEqual('Mihăileni', extract(data, URL)['records'][0]['localities_raw'])
        for data in (b'<table><tr><td>broken', document([])):
            with self.assertRaises(ValueError):
                extract(data, URL)
        with self.assertRaises(ValueError):
            extract(document([cells]), 'https://example.org/unverified')

    def test_actual_cli_is_deterministic_and_refuses_overwrite(self):
        with tempfile.TemporaryDirectory() as folder:
            source, output = Path(folder) / 'source.htm', Path(folder) / 'candidates.json'
            data = document([record()]); source.write_bytes(data)
            command = [sys.executable, str(Path(__file__).with_name('cestrin_candidates.py')),
                       str(source), '--source-url', URL, '--output', str(output)]
            run = subprocess.run(command, capture_output=True, text=True)
            self.assertEqual(0, run.returncode, run.stderr)
            self.assertIn('UNREVIEWED_CANDIDATES', run.stdout)
            first = output.read_bytes()
            self.assertEqual(extract(data, URL), json.loads(first))
            self.assertNotEqual(0, subprocess.run(command, capture_output=True).returncode)
            self.assertEqual(first, output.read_bytes())
            self.assertEqual(data, source.read_bytes())


if __name__ == '__main__':
    unittest.main()
