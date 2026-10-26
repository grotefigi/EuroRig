"""Tests for the three remaining CESTRIN layout adapters (candidate-only)."""
import hashlib
import html
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

from cestrin_tables import extract_mass_maximum, extract_table
from cestrin_grid import GridRows, grid

URL = 'https://www.cestrin.ro/restrictii/example/table.htm'
MASS_URL = 'https://www.cestrin.ro/restrictii/example/mass.htm'

FIXTURES = Path(__file__).with_name('fixtures')
MAX_LEAF = 'Maxima intre elementele care au cauzat restrictia'


def cell(text, span=1, attr='colspan'):
    extra = ' %s="%d"' % (attr, span) if span > 1 else ''
    return '<td%s>%s</td>' % (extra, html.escape(str(text)))


def row(cells):
    return '<tr>' + ''.join(cells) + '</tr>'


def document(name, records, width, edits=()):
    text = FIXTURES.joinpath(name).read_text(encoding='utf-8')
    for old, new in edits:
        if old not in text:
            raise AssertionError('fixture lacks %r' % old)
        text = text.replace(old, new, 1)
    body = ''.join(row([cell(record.get(i, '')) for i in range(width)]) for record in records)
    return text.replace('</table>', body + '</table>').encode('utf-8')


def temp_record(overrides=None):
    cells = {0: '1', 1: 'DN', 2: '1C', 3: '15', 4: '+', 5: '35', 6: '-', 7: '15', 8: '+', 9: '89',
             10: 'Apahida', 11: 'Cluj', 12: 'supratraversare', 13: 'latime'}
    cells.update(overrides or {})
    return cells


def perm_record(overrides=None):
    cells = {0: '7', 1: 'DN', 2: '1C', 3: '15', 4: '+', 5: '35', 6: '-', 7: '15', 8: '+', 9: '89',
             10: 'Apahida', 11: 'Cluj', 12: 'subtraversare', 13: 'inaltime', 14: 'E'}
    cells.update(overrides or {})
    return cells


def temporare_document(records, edits=()):
    return document('head-temporare.html', records, 28, edits)


def permanente_document(records, edits=()):
    return document('head-permanente.html', records, 30, edits)


def run(data, layout='temporare', url=URL):
    return extract_table(data, url, layout) if layout != 'masa_maxima' else extract_mass_maximum(data, url)


class SourceRowPreservation(unittest.TestCase):
    def test_numeric_chainage_never_becomes_a_measurement(self):
        result = run(temporare_document([temp_record({})]))
        record = result['records'][0]
        self.assertEqual([], record['measurements'])
        self.assertIn('non_numeric_or_missing_limit', record['issues'])
        self.assertEqual(15 * 1000 + 35, record['chainage_start_metres'])
        self.assertEqual(15 * 1000 + 89, record['chainage_end_metres'])
        self.assertEqual(8, record['source_row'])

    def test_row_and_cell_anchors_survive(self):
        result = run(temporare_document([temp_record({14: '16.00'})]))
        record = result['records'][0]
        self.assertEqual('16.00', record['raw_cells']['15'])
        self.assertEqual(result['source_sha256'], record['source_sha256'])
        self.assertFalse(result['import_approved'])
        self.assertTrue(result['candidate_only'])

    def test_duplicate_publisher_ids_stay_visible(self):
        data = temporare_document([temp_record({}), temp_record({14: '20'})])
        result = run(data)
        self.assertEqual(2, len(result['records']))
        self.assertTrue(all('duplicate_publisher_record_id' in r['issues'] for r in result['records']))

    def test_missing_road_identity_and_entity_decoding(self):
        data = temporare_document([temp_record({1: '', 2: ''})]).replace(b'Apahida', b'C&#226;mpia Turzii')
        record = run(data)['records'][0]
        self.assertIn('missing_road_identity', record['issues'])
        self.assertEqual('Câmpia Turzii', record['localities_raw'])


class MeasurementBinding(unittest.TestCase):
    def test_mass_binds_to_mass_column_and_keeps_unknown_basis(self):
        result = run(permanente_document([perm_record({15: '16.00'})]), 'permanente')
        measurement = result['records'][0]['measurements'][0]
        self.assertEqual('gross_mass', measurement['quantity'])
        self.assertEqual(16.0, measurement['value'])
        self.assertEqual('left', measurement['side'])
        self.assertEqual('unknown', measurement['mass_basis'])
        self.assertEqual(16, measurement['source_column'])

    def test_both_sides_and_lane_positions_are_distinguished(self):
        record = perm_record({17: '4,5', 18: '4.6', 21: '3.9', 22: '4.1'})
        result = run(permanente_document([record]), 'permanente')
        sides = [(m['side'], m['lane_position'], m['value']) for m in result['records'][0]['measurements']]
        self.assertIn(('left', 'parte carosabila', 4.5), sides)
        self.assertIn(('left', 'maxima intre elementele care', 4.6), sides)
        self.assertIn(('right', 'parte carosabila', 3.9), sides)
        self.assertIn(('right', 'maxima intre elementele care', 4.1), sides)

    def test_heights_keep_their_lane_position(self):
        record = temp_record({22: '4.5', 27: '5,2', 14: '16'})
        result = run(temporare_document([record]))
        heights = [(m['lane_position'], m['value']) for m in result['records'][0]['measurements']
                   if m['quantity'] == 'height']
        self.assertEqual([('stg.1', 4.5), ('dr.1', 5.2)], heights)

    def test_vehicle_class_is_kept_verbatim(self):
        result = run(permanente_document([perm_record({14: 'II'})]), 'permanente')
        self.assertEqual('II', result['records'][0]['vehicle_class_raw'])
        self.assertNotIn('vehicle_class_unexpected_text', result['records'][0]['issues'])

    def test_class_column_holding_a_characteristic_is_flagged(self):
        result = run(permanente_document([perm_record({14: 'latime'})]), 'permanente')
        self.assertIn('vehicle_class_unexpected_text', result['records'][0]['issues'])


class MalformedValues(unittest.TestCase):
    def test_invalid_numeric_text_is_quarantined_not_guessed(self):
        result = run(temporare_document([temp_record({16: 'n/a', 14: '16'})]))
        record = result['records'][0]
        self.assertIn('unparsed_value_column_16', record['issues'])
        self.assertEqual(1, len(record['measurements']))

    def test_zero_and_negative_are_invalid(self):
        result = run(temporare_document([temp_record({16: '0', 14: '16'})]))
        self.assertIn('invalid_value_column_16', result['records'][0]['issues'])

    def test_comma_decimal_is_accepted(self):
        result = run(temporare_document([temp_record({16: '4,5'})]))
        self.assertEqual(4.5, result['records'][0]['measurements'][0]['value'])

    def test_missing_and_reversed_chainage_are_visible(self):
        result = run(temporare_document([temp_record({3: ''}), temp_record({3: '20'})]))
        self.assertIn('ambiguous_chainage', result['records'][0]['issues'])
        self.assertIn('reversed_chainage', result['records'][1]['issues'])

    def test_blank_closure_survives_as_a_row(self):
        record = temp_record({13: 'drum inchis', 14: '', 16: '', 17: ''})
        result = run(temporare_document([record]))
        self.assertEqual('drum inchis', result['records'][0]['characteristic_raw'])
        self.assertEqual([], result['records'][0]['measurements'])
        self.assertEqual('quarantined', result['records'][0]['status'])


class HeaderStrictness(unittest.TestCase):
    def test_changed_unit_header_quarantines_the_row(self):
        result = run(temporare_document([temp_record({16: '450'})], edits=[('latime (m)', 'latime (cm)')]))
        measurement = result['records'][0]['measurements'][0]
        self.assertEqual('cm', measurement['unit'])
        self.assertIn('unsupported_unit_column_16', result['records'][0]['issues'])

    def test_reordered_lane_headers_still_bind(self):
        text = temporare_document([temp_record({16: '4.5', 17: '4.6'})]).decode()
        text = text.replace('Parte carosabila', '@@', 1)
        text = text.replace(MAX_LEAF, 'Parte carosabila', 1)
        text = text.replace('@@', MAX_LEAF, 1)
        result = run(text.encode())
        first = result['records'][0]['measurements'][0]
        self.assertEqual('maxima intre elementele care', first['lane_position'])
        self.assertEqual(4.5, first['value'])

    def test_unknown_measurement_header_fails(self):
        data = temporare_document([temp_record({14: '16'})], edits=[('Masa totala (t)', 'Greutate (t)')])
        with self.assertRaises(ValueError):
            run(data)

    def test_missing_header_column_fails(self):
        data = temporare_document([temp_record({14: '16'})])
        broken = data.replace(b'<td>Kms</td>', b'<td>Kms</td>').replace(b'Inaltimea reala (m)', b'Inaltime')
        with self.assertRaises(ValueError):
            run(broken)

    def test_wrong_column_count_fails(self):
        data = temporare_document([temp_record({14: '16'})]).replace(b'</tr>', b'<td>extra</td></tr>', 1)
        with self.assertRaises(ValueError):
            run(data)

    def test_non_publisher_url_is_refused(self):
        with self.assertRaises(ValueError):
            run(temporare_document([temp_record({})]), url='http://example.org/x.htm')
        with self.assertRaises(ValueError):
            run(temporare_document([temp_record({})]), url='https://:secret@cestrin.ro/x.htm')


class MassMaximumLayout(unittest.TestCase):
    def document(self, rows):
        return document('head-mass.html', [{i: v for i, v in enumerate(values)} for values in rows], 8)

    def test_dotted_chainage_and_tonnage(self):
        data = self.document([['DN', '1', '007.418', '053.650', 'A', 'B', 'PH', '80']])
        result = extract_mass_maximum(data, MASS_URL)
        record = result['records'][0]
        self.assertEqual('007.418', record['chainage_start_dotted'])
        self.assertEqual(80.0, record['measurements'][0]['value'])
        self.assertEqual('unknown', record['measurements'][0]['mass_basis'])
        self.assertEqual([], record['issues'])

    def test_invalid_dotted_chainage_and_reversal(self):
        data = self.document([['DN', '1', '7.418', '053.650', 'A', 'B', 'PH', '80'],
                              ['DN', '2', '060.000', '010.000', 'A', 'B', 'PH', '80']])
        result = extract_mass_maximum(data, MASS_URL)
        self.assertIn('ambiguous_chainage', result['records'][0]['issues'])
        self.assertIn('reversed_chainage', result['records'][1]['issues'])

    def test_changed_mass_header_fails(self):
        data = self.document([['DN', '1', '007.418', '053.650', 'A', 'B', 'PH', '80']])
        data = data.replace(b'[tone]', b'[to]')
        with self.assertRaises(ValueError):
            extract_mass_maximum(data, MASS_URL)


class Cli(unittest.TestCase):
    def test_cli_writes_result_and_refuses_overwrite(self):
        with tempfile.TemporaryDirectory() as folder:
            source = Path(folder) / 'in.htm'
            source.write_bytes(temporare_document([temp_record({14: '16'})]))
            output = Path(folder) / 'out.json'
            command = [sys.executable, str(Path(__file__).with_name('cestrin_tables.py')), str(source), '--layout', 'temporare',
                       '--source-url', URL, '--output', str(output)]
            done = subprocess.run(command, capture_output=True, text=True)
            self.assertEqual(0, done.returncode, done.stderr)
            self.assertIn('UNREVIEWED_CANDIDATES', done.stdout)
            self.assertEqual('cestrin-temporare-word-html-v1', json.loads(output.read_text(encoding='utf-8'))['format'])
            digest = hashlib.sha256(output.read_bytes()).hexdigest()
            again = subprocess.run(command, capture_output=True, text=True)
            self.assertNotEqual(0, again.returncode)
            self.assertEqual(digest, hashlib.sha256(output.read_bytes()).hexdigest())


if __name__ == '__main__':
    unittest.main()
