"""Candidate adapters for the three remaining observed CESTRIN table layouts.

Layouts: the temporary-restriction list, the permanent-restriction list and the admitted
maximum-mass list. All three are Word-HTML exports whose units, headers and numbered
columns are bound before any value is read; nothing is inferred from a city or road name.

Deliberate limits (candidates only):
- Bypass/chainage columns never become measurements.
- Vehicle-class and side/lane columns are preserved verbatim, not interpreted.
- Gross mass keeps mass_basis 'unknown' (plated versus loaded is not decided here).
- Nonnumeric, missing, zero/negative, ambiguous or reversed values stay visible.
- Unsupported headers fail the file; unsupported units quarantine the row.
"""
import argparse
import collections
import hashlib
import json
import math
from pathlib import Path
import re
import unicodedata
from urllib.parse import urlsplit

from cestrin_grid import GridRows, grid

ORDINAL = re.compile(r'^[0-9]{1,2}$')
NUMERIC = re.compile(r'^[0-9]+(?:[.,][0-9]+)?$')
CLASS = re.compile(r'^(?:0|E|I{1,3}|IV|V|[0-9]{1,2})$')

# quantity -> accepted units (folded)
UNITS = {'gross_mass': {'t'}, 'single_axle_mass': {'t'}, 'width': {'m'}, 'height': {'m'}}

# Each binding: column -> (leaf label, ancestry words that must all appear above it)
SIDE_HALF = ('stanga', 'dreapta')
LANE = ('parte carosabila', 'maxima intre elementele care')
HEIGHT_LEAVES = ('stg.1', 'stg.2', 'ax s', 'ax d', 'dr.2', 'dr.1')


def _side_bindings(first, mass_leaf='masa totala (t)', width_case='lower'):
    """Build the four-value 'Valoarea restrictiei' block starting at `first`."""
    bindings = {
        first: (mass_leaf, ('stanga',)),
        first + 1: ('masa pe axa simpla (t)', ('stanga',)),
        first + 2: (LANE[0], ('stanga', 'latime (m)')),
        first + 3: (LANE[1], ('stanga', 'latime (m)')),
        first + 4: (mass_leaf, ('dreapta',)),
        first + 5: ('masa pe axa simpla (t)', ('dreapta',)),
        first + 6: (LANE[0], ('dreapta', 'latime (m)')),
        first + 7: (LANE[1], ('dreapta', 'latime (m)')),
    }
    return bindings


QUANTITY = {
    'masa totala (t)': ('gross_mass', 't', 'load_mass_total'),
    'masa pe axa simpla (t)': ('single_axle_mass', 't', 'single_axle'),
    'latime (m)': ('width', 'm', 'width'),
    'parte carosabila': ('width', 'm', 'width'),
    'maxima intre elementele care': ('width', 'm', 'width'),
    'inaltimea reala (m)': ('height', 'm', 'height'),
}
LANE_LEAVES = ('parte carosabila', 'maxima intre elementele care')

LAYOUTS = {
    # width, ordinal header row signature, measurement bindings, class column
    'temporare': {
        'width': 28, 'class_column': None, 'chainage': (3, 7), 'bindings': _side_bindings(14),
        'heights': 22, 'name': 'cestrin-temporare-word-html-v1',
    },
    'permanente': {
        'width': 30, 'class_column': 14, 'chainage': (3, 7), 'bindings': _side_bindings(15),
        'heights': 24, 'name': 'cestrin-permanente-word-html-v1',
    },
}


def folded(text):
    return ' '.join(''.join(c for c in unicodedata.normalize('NFD', text)
                            if not unicodedata.combining(c)).casefold().split())


def read_grid(data):
    parser = GridRows()
    parser.feed(data.decode('utf-8-sig'))
    parser.close()
    if parser.table_count != 1:
        raise ValueError('Expected one complete source table')
    rows, width = grid(parser.rows)
    return rows, width


def header_columns(rows, ordinal_index, width):
    """Column -> list of folded header labels above the ordinal row (top to bottom)."""
    columns = collections.defaultdict(list)
    for row in rows[:ordinal_index]:
        for column, text in row.items():
            if text.strip():
                columns[column].append(folded(text))
    return {c: labels for c, labels in columns.items() if c < width}


BASE_LEAVES = (('masa totala', 'gross_mass', 't', 'load_mass_total'),
               ('masa pe axa simpla', 'single_axle_mass', 't', 'single_axle'),
               ('latime', 'width', 'm', 'width'),
               ('parte carosabila', 'width', 'm', 'width'),
               ('maxima intre elementele', 'width', 'm', 'width'))
UNIT_TEXT = re.compile(r'\(([^)]+)\)')


def expected_signature(layout):
    """Column signature a layout must expose: (quantity, side, lane leaf) -> count."""
    signature = collections.Counter()
    for half in ('left', 'right'):
        signature[('gross_mass', half, None)] = 1
        signature[('single_axle_mass', half, None)] = 1
        signature[('width', half, 'parte carosabila')] = 1
        signature[('width', half, 'maxima intre elementele care')] = 1
    for leaf in HEIGHT_LEAVES:
        signature[('height', None, leaf)] = 1
    return signature


def bind_measurements(rows, width, layout):
    """Locate ordinal row and measurement columns from the headers themselves."""
    ordinal = [i for i, row in enumerate(rows)
               if [v for v in row.values() if v] and all(ORDINAL.match(v) for v in row.values() if v)]
    if not ordinal:
        raise ValueError('Unknown numbered column layout')
    start = ordinal[0]
    headers = header_columns(rows, start, width)
    bindings = {}
    for column in range(width):
        labels = headers.get(column, [])
        if not labels:
            continue
        leaf = labels[-1]
        hit = None
        for base, quantity, unit, group in BASE_LEAVES:
            if leaf == base or leaf.startswith(base + ' '):
                hit = (base, quantity, unit, group)
                break
        if hit is None and leaf in HEIGHT_LEAVES and any('inaltimea reala' in label for label in labels):
            hit = (leaf, 'height', 'm', 'height')
        if hit is None:
            if any(base[1] in ('gross_mass',) for base in ()) and column in layout['bindings']:
                pass
            continue
        base, quantity, unit, group = hit
        observed = None
        for label in reversed(labels):
            observed = UNIT_TEXT.search(label)
            if observed:
                break
        side = 'left' if any(label.startswith('stanga') for label in labels) else (
            'right' if any(label.startswith('dreapta') for label in labels) else None)
        lane = None
        if quantity == 'width':
            if any(label.startswith('parte carosabila') for label in labels):
                lane = 'parte carosabila'
            elif any(label.startswith('maxima intre elementele') for label in labels):
                lane = 'maxima intre elementele care'
            else:
                raise ValueError('Width column %d lacks a lane header (%r)' % (column, labels))
        if quantity == 'height':
            lane = leaf
        bindings[column] = {'quantity': quantity, 'unit': (observed.group(1).strip().lower() if observed else unit),
                            'expected_unit': unit, 'group': group, 'side': side, 'lane': lane,
                            'leaf': base, 'observed_header': labels[-1]}
    signature = collections.Counter((b['quantity'], b['side'], b['lane']) for b in bindings.values())
    if signature != expected_signature(layout):
        missing = expected_signature(layout) - signature
        extra = signature - expected_signature(layout)
        raise ValueError('Measurement header signature mismatch for %s; missing=%r extra=%r'
                         % (layout['name'], dict(missing), dict(extra)))
    return start, bindings


def chainage(cells, first):
    km, plus, metres = cells[first], cells[first + 1], cells[first + 2]
    if not (km.isascii() and km.isdigit() and plus == '+' and metres.isascii() and metres.isdigit()):
        return None
    if int(metres) > 999:
        return None
    return int(km) * 1000 + int(metres)


def _measurement(column, binding, raw, issues):
    accepted = UNITS[binding['quantity']]
    if binding['unit'] not in accepted:
        issues.append('unsupported_unit_column_%d' % column)
    if not NUMERIC.match(raw):
        issues.append('unparsed_value_column_%d' % column)
        return None
    value = float(raw.replace(',', '.'))
    if not math.isfinite(value) or value <= 0:
        issues.append('invalid_value_column_%d' % column)
        return None
    measurement = {'quantity': binding['quantity'], 'value': value, 'unit': binding['unit'],
                   'raw_value': raw, 'source_column': column + 1}
    if binding['side']:
        measurement['side'] = binding['side']
    measurement['column_group'] = binding['group']
    if binding.get('lane'):
        measurement['lane_position'] = binding['lane']
    if binding['quantity'] == 'gross_mass':
        measurement['mass_basis'] = 'unknown'
    return measurement


def extract_table(data, source_url, layout_name):
    layout = LAYOUTS[layout_name]
    url = urlsplit(source_url)
    if url.scheme != 'https' or url.hostname not in ('cestrin.ro', 'www.cestrin.ro') or url.username or url.password:
        raise ValueError('Supply the observed HTTPS publisher URL; it still requires provenance review')
    rows, width = read_grid(data)
    if width != layout['width']:
        raise ValueError('Unexpected column count %d for %s (expected %d)' % (width, layout_name, layout['width']))
    start, bindings = bind_measurements(rows, width, layout)
    digest = hashlib.sha256(data).hexdigest()
    records = []
    for index in range(start + 1, len(rows)):
        cells = [rows[index].get(c, '') for c in range(width)]
        if not any(cells) or all(ORDINAL.match(v) for v in cells if v):
            continue
        issues, measurements = [], []
        record = {'record_id': digest + ':row:' + str(index + 1), 'source_row': index + 1,
                  'publisher_record_id': cells[0], 'raw_cells': {str(i + 1): v for i, v in enumerate(cells) if v},
                  'country': 'RO', 'source_sha256': digest, 'layout': layout_name,
                  'measurements': measurements,
                  'review_required': ['reuse_rights', 'effective_period', 'exact_road_match',
                                      'rule_applicability', 'mass_basis'],
                  'issues': issues}
        record.update(road_category=cells[1], road_number=cells[2], localities_raw=cells[10],
                      county_raw=cells[11], cause_raw=cells[12], characteristic_raw=cells[13])
        if layout['class_column'] is not None:
            record['vehicle_class_raw'] = cells[layout['class_column']]
            if cells[layout['class_column']] and not CLASS.match(cells[layout['class_column']]):
                issues.append('vehicle_class_unexpected_text')
        if not cells[0].isdigit():
            issues.append('unknown_publisher_record_id')
        if not cells[1] or not cells[2]:
            issues.append('missing_road_identity')
        begin, end = chainage(cells, layout['chainage'][0]), chainage(cells, layout['chainage'][1])
        record.update(chainage_start_metres=begin, chainage_end_metres=end)
        if begin is None or end is None:
            issues.append('ambiguous_chainage')
        elif end < begin:
            issues.append('reversed_chainage')
        for column, binding in sorted(bindings.items()):
            raw = cells[column]
            if not raw:
                continue
            measurement = _measurement(column, binding, raw, issues)
            if measurement:
                if binding['quantity'] == 'gross_mass':
                    measurement['mass_basis'] = 'unknown'
                measurements.append(measurement)
        if not measurements:
            issues.append('non_numeric_or_missing_limit')
        if not record['characteristic_raw']:
            issues.append('blank_characteristic')
        records.append(record)
    return _finish(records, digest, source_url, layout['name'])


def extract_mass_maximum(data, source_url):
    url = urlsplit(source_url)
    if url.scheme != 'https' or url.hostname not in ('cestrin.ro', 'www.cestrin.ro') or url.username or url.password:
        raise ValueError('Supply the observed HTTPS publisher URL; it still requires provenance review')
    rows, width = read_grid(data)
    if width != 8:
        raise ValueError('Unexpected column count %d for mass-maximum layout (expected 8)' % width)
    header = [folded(rows[0].get(c, '')) for c in range(8)]
    expected = ['categorie drum', 'numar drum', 'pozitie kilometrica inceput sector drum',
                'pozitie kilometrica sfarsit sector drum', 'localitate inceput sector drum',
                'localitate sfarsit sector drum', 'judet', 'limita masa totala [tone]']
    if header != expected:
        raise ValueError('Unknown mass-maximum header: %r' % (header,))
    digest = hashlib.sha256(data).hexdigest()
    records = []
    for index in range(1, len(rows)):
        cells = [rows[index].get(c, '') for c in range(8)]
        if not any(cells):
            continue
        issues, measurements = [], []
        record = {'record_id': digest + ':row:' + str(index + 1), 'source_row': index + 1,
                  'publisher_record_id': '', 'raw_cells': {str(i + 1): v for i, v in enumerate(cells) if v},
                  'country': 'RO', 'source_sha256': digest, 'layout': 'masa_maxima',
                  'measurements': measurements,
                  'review_required': ['reuse_rights', 'effective_period', 'exact_road_match',
                                      'rule_applicability', 'mass_basis'],
                  'issues': issues, 'road_category': cells[0], 'road_number': cells[1],
                  'localities_raw': cells[4] + ' -> ' + cells[5], 'county_raw': cells[6],
                  'chainage_raw': cells[2] + '-' + cells[3]}
        if not cells[0] or not cells[1]:
            issues.append('missing_road_identity')
        for column, key in ((2, 'chainage_start_dotted'), (3, 'chainage_end_dotted')):
            raw = cells[column]
            if not re.fullmatch(r'[0-9]{3}\.[0-9]{3}', raw):
                issues.append('ambiguous_chainage')
            else:
                record[key] = raw
        start = record.get('chainage_start_dotted')
        end = record.get('chainage_end_dotted')
        if start and end and end < start:
            issues.append('reversed_chainage')
        raw = cells[7]
        if raw:
            measurement = _measurement(7, {'quantity': 'gross_mass', 'unit': 't', 'group': 'load_mass_total',
                                           'side': None, 'leaf': 'limita masa totala'}, raw, issues)
            if measurement:
                measurement['observed_unit_header'] = 'tone'
                measurements.append(measurement)
        if not measurements:
            issues.append('non_numeric_or_missing_limit')
        records.append(record)
    return _finish(records, digest, source_url, 'cestrin-mass-maximum-word-html-v1')


def _finish(records, digest, source_url, name):
    if not records:
        raise ValueError('No source records follow the headers')
    identifiers = collections.Counter(r['publisher_record_id'] for r in records if r['publisher_record_id'])
    for record in records:
        if identifiers[record['publisher_record_id']] > 1:
            record['issues'].append('duplicate_publisher_record_id')
        record['status'] = 'quarantined' if record['issues'] else 'unreviewed'
    return {'schema_version': 1, 'format': name, 'candidate_only': True, 'import_approved': False,
            'source_url': source_url, 'source_sha256': digest, 'reuse_rights': 'unknown',
            'effective_period': 'unknown', 'exact_road_match': 'unknown', 'mass_basis': 'unknown',
            'summary': {'source_rows': len(records),
                        'measurement_candidates': sum(len(r['measurements']) for r in records),
                        'quarantined_rows': sum(bool(r['issues']) for r in records),
                        'rows_with_class_column': sum('vehicle_class_raw' in r for r in records),
                        'distinct_quarantine_issues': len({i for r in records for i in r['issues']})},
            'records': records}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('html', type=Path)
    parser.add_argument('--layout', required=True, choices=sorted(LAYOUTS) + ['masa_maxima'])
    parser.add_argument('--source-url', required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    data = args.html.read_bytes()
    result = (extract_mass_maximum(data, args.source_url) if args.layout == 'masa_maxima'
              else extract_table(data, args.source_url, args.layout))
    with args.output.open('x', encoding='utf-8') as stream:
        json.dump(result, stream, ensure_ascii=False, indent=2, allow_nan=False)
        stream.write('\n')
    print('UNREVIEWED_CANDIDATES ' + json.dumps(result['summary']))


if __name__ == '__main__':
    main()
