"""Stage reviewed CSV restriction evidence; never edit maps or grant road access.

Source approval and exact road matches are separate reviewer-owned inputs.
Output is a candidate bundle, not a routing/display overlay or a legal certificate.
"""
import argparse
import collections
import csv
import hashlib
import io
import json
import math
import re
import sqlite3
from contextlib import closing
from datetime import date
from pathlib import Path
from urllib.parse import urlsplit

from compile_map import limit
from package_region import sha256
from restriction_kind import classify

FIELDS = ('record_id', 'country', 'restriction', 'value', 'unit', 'direction',
          'mass_basis', 'valid_from', 'valid_until', 'condition', 'exception')
NUMERIC = {'height': 'maxheight', 'width': 'maxwidth', 'length': 'maxlength',
           'loaded_mass': 'maxweight', 'axle_load': 'maxaxleload'}
DIRECTIONS = {'both', 'forward', 'backward'}


def _object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError('Duplicate JSON key: ' + key)
        result[key] = value
    return result


def read_json(data):
    def invalid_constant(value):
        raise ValueError('Nonstandard JSON numeric constant: ' + value)
    return json.loads(data.decode('utf-8-sig'), object_pairs_hook=_object, parse_constant=invalid_constant)


def _text(value):
    return isinstance(value, str) and bool(value.strip()) and value.strip().lower() not in ('unknown', 'unverified', 'none', 'n/a')


def _date(value):
    if not isinstance(value, str) or not re.fullmatch(r'\d{4}-\d{2}-\d{2}', value):
        raise ValueError('Expected YYYY-MM-DD')
    return date.fromisoformat(value)


def review_errors(review, actual, as_of):
    errors = []
    if type(review.get('schema_version')) is not int or review['schema_version'] != 1:
        errors.append('unsupported_review_schema')
    if review.get('status') != 'approved':
        errors.append('source_not_approved')
    for key in ('source_id', 'publisher', 'license', 'license_review', 'currentness_review'):
        if not _text(review.get(key)):
            errors.append('missing_' + key)
    if review.get('reuse_allowed') is not True:
        errors.append('reuse_not_approved')
    if not isinstance(review.get('country'), str) or not re.fullmatch('[A-Z]{2}', review['country']):
        errors.append('invalid_review_country')
    try:
        url = urlsplit(review.get('source_url', '') if isinstance(review.get('source_url', ''), str) else '')
        public_url = url.scheme == 'https' and url.hostname and not url.username and not url.password
    except ValueError:
        public_url = False
    if not public_url:
        errors.append('invalid_public_source_url')
    for name, digest in actual.items():
        if review.get(name) != digest:
            errors.append(name + '_mismatch')
    try:
        checked, due = _date(review.get('reviewed_on')), _date(review.get('review_due'))
        if not checked <= as_of <= due:
            errors.append('review_not_current')
    except ValueError:
        errors.append('invalid_review_dates')
    return errors


def normalize(row, as_of):
    if set(row) != set(FIELDS) or any(not isinstance(v, str) for v in row.values()):
        raise ValueError('malformed_csv_record')
    r = {k: v.strip() for k, v in row.items()}
    if not r['record_id']:
        raise ValueError('missing_record_id')
    if r['condition'] or r['exception']:
        raise ValueError('unsupported_condition_or_exception')
    try:
        start = _date(r['valid_from'])
        end = _date(r['valid_until']) if r['valid_until'] else None
    except ValueError:
        raise ValueError('invalid_or_unknown_validity') from None
    if start > as_of or end is not None and (end < start or as_of > end):
        raise ValueError('record_not_current')
    direction = r['direction']
    if direction not in DIRECTIONS:
        raise ValueError('unknown_direction')
    kind = r['restriction']
    if kind in NUMERIC:
        weight = kind in ('loaded_mass', 'axle_load')
        if r['mass_basis'] != ('loaded' if kind == 'loaded_mass' else 'axle' if kind == 'axle_load' else ''):
            raise ValueError('unsupported_mass_basis')
        if r['unit'] not in ({'t', 'kg', 'lbs'} if weight else {'m', 'cm', 'ft'}):
            raise ValueError('unsupported_unit')
        value = limit(r['value'] + ' ' + r['unit'], weight)
        if value is None or not math.isfinite(value) or value <= 0:
            raise ValueError('invalid_numeric_limit')
        base, tag_value, unit = NUMERIC[kind], str(value), 't' if weight else 'm'
    elif kind == 'hgv_access':
        if r['unit'] or r['mass_basis'] or r['value'] not in ('no', 'private', 'delivery', 'destination'):
            raise ValueError('unsupported_access_value')
        base, tag_value, value, unit = 'hgv', r['value'], r['value'], None
    else:
        raise ValueError('unsupported_restriction')
    tags = {base + (':' + direction if direction != 'both' else ''): tag_value}
    classification, evidence = classify(tags)
    return {'record_id': r['record_id'], 'country': r['country'], 'restriction': kind,
            'direction': direction, 'value_si': value, 'unit_si': unit, 'tags': tags,
            'kind': classification, 'evidence': evidence, 'valid_from': r['valid_from'],
            'valid_until': r['valid_until'] or None}


def intake(source, review_file, matches_file, display, destination, as_of):
    """Validate one bounded source batch and emit deterministic staging records and a manifest."""
    source, review_file, matches_file, display, destination = map(Path, (source, review_file, matches_file, display, destination))
    as_of = _date(as_of)
    if destination.exists():
        raise FileExistsError('Choose a fresh intake output directory')
    if any(Path(str(display) + suffix).exists() for suffix in ('-wal', '-journal')):
        raise ValueError('Use a finalized display snapshot without WAL or journal files')
    source_bytes, review_bytes, match_bytes = source.read_bytes(), review_file.read_bytes(), matches_file.read_bytes()
    review, matches = read_json(review_bytes), read_json(match_bytes)
    if not isinstance(review, dict) or not isinstance(matches, dict):
        raise ValueError('Review and matches must be JSON objects')
    actual = {'source_sha256': hashlib.sha256(source_bytes).hexdigest(),
              'matches_sha256': hashlib.sha256(match_bytes).hexdigest(), 'display_sha256': sha256(display)}
    global_errors = review_errors(review, actual, as_of)
    reader = csv.DictReader(io.StringIO(source_bytes.decode('utf-8-sig'), newline=''), strict=True)
    if reader.fieldnames is None or len(reader.fieldnames) != len(set(reader.fieldnames)) or set(reader.fieldnames) != set(FIELDS):
        raise ValueError('CSV headers must contain each contract field exactly once')
    # shortcut: one source batch stays in memory; use disk staging when measured batches exceed RAM.
    records = []
    with closing(sqlite3.connect(display.resolve().as_uri() + '?mode=ro&immutable=1', uri=True)) as db:
        if db.execute('PRAGMA user_version').fetchone()[0] != 1:
            raise ValueError('Unsupported display snapshot version')
        for row in reader:
            reasons = list(global_errors)
            normalized = None
            try:
                normalized = normalize(row, as_of)
                if normalized['country'] != review.get('country'):
                    raise ValueError('country_not_in_reviewed_scope')
                match = matches.get(normalized['record_id'])
                if not isinstance(match, dict) or not _text(match.get('review_ref')):
                    raise ValueError('road_match_not_reviewed')
                ways = match.get('osm_way_ids')
                if not isinstance(ways, list) or not ways or any(type(w) is not int or not 0 < w < 2**63 for w in ways) or len(set(ways)) != len(ways):
                    raise ValueError('invalid_or_ambiguous_road_ids')
                if any(db.execute('SELECT 1 FROM roads WHERE id=?', (w,)).fetchone() is None for w in ways):
                    raise ValueError('matched_road_missing_from_snapshot')
                normalized.update(osm_way_ids=sorted(ways), match_review=match['review_ref'])
            except ValueError as error:
                reasons.append(str(error))
            raw = {k: v for k, v in row.items() if k is not None}
            if None in row:
                raw['_extra_columns'] = row[None]
            records.append({'record_id': row.get('record_id'), 'csv_end_line': reader.line_num,
                            'raw': raw, 'normalized': normalized, 'reasons': reasons})
    if not records:
        raise ValueError('Source contains no records')
    if sha256(display) != actual['display_sha256']:
        raise ValueError('Display snapshot changed during intake')
    ids = collections.Counter((r['record_id'] or '').strip() for r in records)
    by_way = collections.defaultdict(list)
    for i, record in enumerate(records):
        if ids[(record['record_id'] or '').strip()] > 1:
            record['reasons'].append('duplicate_record_id')
        if not record['reasons']:
            n = record['normalized']
            for way in n['osm_way_ids']:
                by_way[(way, n['restriction'])].append((i, n['direction'], n['value_si']))
    for candidates in by_way.values():
        values = {direction: set() for direction in DIRECTIONS}
        for _, direction, value in candidates:
            values[direction].add(value)
        overlapping = {direction: values[direction] | values['both'] for direction in DIRECTIONS}
        overlapping['both'] = set().union(*values.values())
        for index, direction, value in candidates:
            if len(overlapping[direction]) > 1:
                records[index]['reasons'].append('conflicting_overlapping_records')
    accepted, rejected = [], []
    for record in records:
        raw = {k: record[k] for k in ('record_id', 'csv_end_line', 'raw')}
        if record['reasons']:
            rejected.append({**raw, 'reasons': sorted(set(record['reasons']))})
        else:
            accepted.append({**raw, **record['normalized'], 'source_id': review['source_id']})
    destination.mkdir(parents=True)
    for name, rows in [('accepted.jsonl', accepted), ('rejected.jsonl', rejected)]:
        with (destination / name).open('x', encoding='utf-8', newline='\n') as stream:
            for row in rows:
                stream.write(json.dumps(row, ensure_ascii=False, sort_keys=True, allow_nan=False) + '\n')
    for name, data in [('source.csv', source_bytes), ('matches.json', match_bytes), ('review.json', review_bytes)]:
        with (destination / name).open('xb') as stream:
            stream.write(data)
    manifest = {'schema_version': 1, 'status': 'staged_only', 'as_of': as_of.isoformat(),
                'source_id': review.get('source_id'), 'input_sha256': {**actual, 'review_sha256': hashlib.sha256(review_bytes).hexdigest()},
                'records': len(records), 'accepted': len(accepted), 'rejected': len(rejected),
                'rejection_counts': dict(sorted(collections.Counter(reason for r in rejected for reason in r['reasons']).items())),
                'files': {name: sha256(destination / name) for name in ('accepted.jsonl', 'rejected.jsonl', 'source.csv', 'matches.json', 'review.json')},
                'legal_completeness': 'unknown', 'map_modified': False}
    with (destination / 'manifest.json').open('x', encoding='utf-8') as stream:
        json.dump(manifest, stream, ensure_ascii=False, indent=2, allow_nan=False)
        stream.write('\n')
    return manifest


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    for name in ('source', 'review', 'matches', 'display', 'out'):
        p.add_argument('--' + name, required=True, type=Path)
    p.add_argument('--as-of', required=True, help='Explicit YYYY-MM-DD review snapshot date')
    args = p.parse_args()
    result = intake(args.source, args.review, args.matches, args.display, args.out, args.as_of)
    print(json.dumps({key: result[key] for key in ('status', 'records', 'accepted', 'rejected', 'rejection_counts')}))
