"""Extract unreviewed candidates from the observed CESTRIN OG43 Word-HTML layout.

Only the four header-bound limit columns supply measurements. No map is edited;
reuse rights, currentness and exact road matching always require separate review.
"""
import argparse
import collections
import hashlib
from html.parser import HTMLParser
import json
import math
from pathlib import Path
import re
import unicodedata
from urllib.parse import urlsplit


MAIN = ('Nr. Crt.', 'Categorie drum', 'Numar drum', 'Pozitii kilometrice pe A/DN',
        'Localitati apropiate', 'Judet', 'Cauza restrictiei',
        'Caracteristica restrictionata', 'Valoarea restrictiei', 'Varianta ocolitoare')
UNITS = ('masa totala [t]', 'masa pe axa simpla [t]', 'latime [m]', 'inaltime [m]')
ORDINALS = ('1', '2', '3', '4', '', '5', '', '6', '', '7', '8', '9', '10', '11',
            '12', '13', '14', '15', '16', '', '17', '', '18', '', '19', '20',
            '21', '22', '', '23', '', '24', '', '25')
LIMITS = ((14, 'gross_mass', 't'), (15, 'single_axle_mass', 't'),
          (16, 'width', 'm'), (17, 'height', 'm'))


def folded(text):
    return ' '.join(''.join(c for c in unicodedata.normalize('NFD', text)
                           if not unicodedata.combining(c)).casefold().split())


class Rows(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.rows, self.row, self.cell = [], None, None

    def handle_starttag(self, tag, attrs):
        if tag == 'tr':
            if self.row is not None:
                raise ValueError('Nested table rows are not supported by this layout')
            self.row = []
        elif tag in ('td', 'th') and self.row is not None:
            if self.cell is not None:
                raise ValueError('Overlapping table cells')
            self.cell = []
        elif tag in ('br', 'p') and self.cell is not None:
            self.cell.append(' ')

    def handle_data(self, data):
        if self.cell is not None:
            self.cell.append(data)

    def handle_endtag(self, tag):
        if tag in ('td', 'th') and self.cell is not None:
            self.row.append(' '.join(''.join(self.cell).split()))
            self.cell = None
        elif tag == 'tr' and self.row is not None:
            if self.cell is not None:
                raise ValueError('Unclosed table cell')
            self.rows.append(self.row)
            self.row = None


def chainage(cells, index):
    km, plus, metres = cells[index:index + 3]
    if not km.isascii() or not metres.isascii() or not km.isdigit() or plus != '+' or not metres.isdigit():
        return None
    if int(metres) > 999:
        return None
    return int(km) * 1000 + int(metres)


def extract(data, source_url):
    url = urlsplit(source_url)
    if url.scheme != 'https' or url.hostname not in ('cestrin.ro', 'www.cestrin.ro') or url.username or url.password:
        raise ValueError('Supply the observed HTTPS publisher URL; it still requires provenance review')
    text = data.decode('utf-8-sig')
    parser = Rows()
    parser.feed(text)
    parser.close()
    if parser.row is not None or parser.cell is not None:
        raise ValueError('Truncated table')
    indices = [i for i, row in enumerate(parser.rows) if tuple(row) == ORDINALS]
    if not indices:
        raise ValueError('Unknown OG43 numbered column layout')
    start = indices[0]
    headers = parser.rows[:start]
    if not any(tuple(map(folded, row)) == tuple(map(folded, MAIN)) for row in headers):
        raise ValueError('Unknown OG43 grouped column headers')
    if not any(len(row) == 8 and tuple(map(folded, row[:4])) == UNITS for row in headers):
        raise ValueError('Unknown OG43 measurement headers or units')
    digest = hashlib.sha256(data).hexdigest()
    records = []
    for index, cells in enumerate(parser.rows[start + 1:], start + 2):
        if not any(cells) or tuple(cells) == ORDINALS:
            continue
        issues, measurements = [], []
        record = {'record_id': digest + ':row:' + str(index), 'source_row': index,
                  'publisher_record_id': cells[0] if cells else '', 'raw_cells': cells,
                  'country': 'RO', 'source_sha256': digest, 'measurements': measurements,
                  'review_required': ['reuse_rights', 'effective_period', 'exact_road_match', 'rule_applicability'],
                  'issues': issues}
        if len(cells) != len(ORDINALS):
            issues.append('unknown_row_shape')
        else:
            record.update(road_category=cells[1], road_number=cells[2],
                          localities_raw=cells[10], county_or_locality_raw=cells[11],
                          cause_raw=cells[12], characteristic_raw=cells[13],
                          detour_raw=cells[18:])
            if not cells[0].isascii() or not cells[0].isdigit():
                issues.append('unknown_publisher_record_id')
            if not cells[1] or not cells[2]:
                issues.append('missing_road_identity')
            begin, end = chainage(cells, 3), chainage(cells, 7)
            record.update(chainage_start_metres=begin, chainage_end_metres=end)
            if begin is None or end is None:
                issues.append('ambiguous_chainage')
            elif end < begin:
                issues.append('reversed_chainage')
            for column, quantity, unit in LIMITS:
                raw = cells[column]
                if not raw:
                    continue
                if not re.fullmatch(r'[0-9]+(?:[.,][0-9]+)?', raw):
                    issues.append('unparsed_limit_column_' + str(column))
                    continue
                value = float(raw.replace(',', '.'))
                if not math.isfinite(value) or value <= 0:
                    issues.append('invalid_limit_column_' + str(column))
                    continue
                measurement = {'quantity': quantity, 'value': value, 'unit': unit,
                               'raw_value': raw, 'source_column': column}
                if quantity == 'gross_mass':
                    measurement['mass_basis'] = 'unknown'
                measurements.append(measurement)
            if not measurements:
                issues.append('non_numeric_or_missing_limit')
        records.append(record)
    if not records:
        raise ValueError('No source records follow the headers')
    identifiers = collections.Counter(record['publisher_record_id'] for record in records)
    for record in records:
        if identifiers[record['publisher_record_id']] > 1:
            record['issues'].append('duplicate_publisher_record_id')
        record['status'] = 'quarantined' if record['issues'] else 'unreviewed'
    return {'schema_version': 1, 'format': 'cestrin-og43-word-html-v1', 'candidate_only': True,
            'import_approved': False, 'source_url': source_url, 'source_sha256': digest,
            'reuse_rights': 'unknown', 'effective_period': 'unknown', 'exact_road_match': 'unknown',
            'capture_contains_replacement_characters': '\ufffd' in text,
            'summary': {'source_rows': len(records),
                        'measurement_candidates': sum(len(r['measurements']) for r in records),
                        'quarantined_rows': sum(bool(r['issues']) for r in records)},
            'records': records}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('html', type=Path)
    parser.add_argument('--source-url', required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    result = extract(args.html.read_bytes(), args.source_url)
    with args.output.open('x', encoding='utf-8') as stream:
        json.dump(result, stream, ensure_ascii=False, indent=2, allow_nan=False)
        stream.write('\n')
    print('UNREVIEWED_CANDIDATES ' + json.dumps(result['summary']))


if __name__ == '__main__':
    main()
