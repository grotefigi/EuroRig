"""Apply revalidated authority limits to a new display snapshot for the route audit.

The original map and native tiles stay intact. Only supported restrictions can
tighten the copied rules; access exceptions remain in the rejection queue.
"""
import argparse
import json
import shutil
import sqlite3
import tempfile
from contextlib import closing
from pathlib import Path

from authority_intake import intake, read_json
from audit_restriction_coverage import audit_database
from compile_map import UNCERTAIN as FLAG_UNCERTAIN, limit
from enrich_display import summary
from migrate_rules import migrate
from package_region import sha256
from restriction_kind import BACKWARD, FORWARD, HARD, ACCESS, UNCERTAIN, PREFERENCE, classify

FILES = ('accepted.jsonl', 'rejected.jsonl', 'source.csv', 'matches.json', 'review.json')
CLASS_BITS = HARD | ACCESS | UNCERTAIN | PREFERENCE


def validated_bundle(bundle, display, as_of):
    bundle = Path(bundle)
    manifest = read_json((bundle / 'manifest.json').read_bytes())
    if (not isinstance(manifest, dict) or manifest.get('schema_version') != 1
            or manifest.get('status') != 'staged_only'):
        raise ValueError('Unsupported authority bundle')
    if not isinstance(manifest.get('files'), dict) or set(manifest['files']) != set(FILES):
        raise ValueError('Authority bundle file manifest is incomplete')
    for name in FILES:
        if sha256(bundle / name) != manifest['files'][name]:
            raise ValueError('Authority bundle hash mismatch: ' + name)
    review = read_json((bundle / 'review.json').read_bytes())
    if not isinstance(review, dict) or review.get('map_derivative_approved') is not True:
        raise ValueError('Map derivative reuse is not approved')
    if not isinstance(review.get('map_attribution'), str) or not review['map_attribution'].strip():
        raise ValueError('Reviewed map attribution is required')
    # Reuse the intake's source, rights, date, exact-map and exact-way checks.
    # Never trust a worker-edited accepted.jsonl or an updated checksum alone.
    with tempfile.TemporaryDirectory(prefix='eurorig-authority-review-') as folder:
        checked = Path(folder) / 'checked'
        fresh = intake(bundle / 'source.csv', bundle / 'review.json', bundle / 'matches.json',
                       display, checked, as_of)
        if (fresh['input_sha256'] != manifest.get('input_sha256')
                or any(fresh[key] != manifest.get(key) for key in ('records', 'accepted', 'rejected'))
                or any((checked / name).read_bytes() != (bundle / name).read_bytes()
                       for name in ('accepted.jsonl', 'rejected.jsonl'))):
            raise ValueError('Authority bundle no longer matches current reviewed intake')
        if not fresh['accepted']:
            raise ValueError('Authority bundle has no accepted records')
        records = [read_json(line) for line in (checked / 'accepted.jsonl').read_bytes().splitlines() if line]
    provenance = {key: review[key] for key in ('source_id', 'country', 'publisher', 'source_url',
                  'license', 'license_review', 'map_attribution', 'currentness_review', 'reviewed_on', 'review_due')}
    provenance.update(bundle_sha256=sha256(bundle / 'manifest.json'),
                      source_sha256=sha256(bundle / 'source.csv'),
                      review_sha256=sha256(bundle / 'review.json'))
    return records, provenance, fresh['rejected']


def merge_record(db, record):
    if record['restriction'] == 'hgv_access' and record['value_si'] != 'no':
        raise ValueError('access_exception_requires_separate_review')
    updates = []
    for way in record['osm_way_ids']:
        road = db.execute('SELECT kind FROM roads WHERE id=?', (way,)).fetchone()
        if road is None:
            raise ValueError('matched_road_missing_from_snapshot')
        old = db.execute('SELECT height,width,length,weight,axle,flags,tags,kind FROM road_rules WHERE way=?', (way,)).fetchone()
        tags = read_json(old[6].encode('utf-8')) if old else {'highway': road[0]}
        if not isinstance(tags, dict) or any(not isinstance(k, str) or not isinstance(v, str) for k, v in tags.items()):
            raise ValueError('invalid_existing_rule_tags')
        if record['restriction'] == 'hgv_access' and old and (old[5] & FLAG_UNCERTAIN
                or ((old[7] or 0) | classify(tags, road[0])[0]) & UNCERTAIN):
            # The runtime reports uncertainty before access; never count a masked ban as applied.
            raise ValueError('existing_uncertainty_conflicts_with_access_ban')
        for key, value in record['tags'].items():
            if record['restriction'] == 'hgv_access':
                tags[key] = 'no'
                if key == 'hgv':
                    # A new ban in both directions must also close a pre-existing scoped grant.
                    for scoped in ('hgv:forward', 'hgv:backward', 'hgv:both_ways'):
                        if scoped in tags:
                            tags[scoped] = 'no'
            else:
                previous = limit(tags[key], record['restriction'] in ('loaded_mass', 'axle_load')) if key in tags else None
                if key in tags and previous is None:
                    raise ValueError('existing_limit_not_interpretable')
                tags[key] = str(min(previous, record['value_si']) if previous is not None else record['value_si'])
        values = list(summary(tags, road[0]))
        kind, evidence = classify(tags, road[0])
        if old:
            # Keep earlier limits and uncertainty even if an older pack lacks complete tag evidence.
            values[:5] = [min((x for x in pair if x and x > 0), default=0)
                          for pair in zip(values[:5], old[:5])]
            values[5] |= old[5]
            kind |= (old[7] or 0) & CLASS_BITS
            if not (old[7] or 0) & (FORWARD | BACKWARD) and (any(old[:5]) or old[5] or old[7]):
                kind &= ~(FORWARD | BACKWARD)
        updates.append((way, *values, kind, evidence))
    # Prepare all matched ways before writing: one rejected record cannot partially apply.
    db.executemany('INSERT OR REPLACE INTO road_rules VALUES(?,?,?,?,?,?,?,?,?,?)', updates)


def apply(display, bundles, destination, as_of):
    display, destination = Path(display), Path(destination)
    if destination.exists():
        raise FileExistsError('Choose a fresh authority output directory')
    if not bundles:
        raise ValueError('At least one reviewed authority bundle is required')
    input_hash = sha256(display)
    validated = [validated_bundle(bundle, display, as_of) for bundle in bundles]
    ids = [source['source_id'] for _, source, _ in validated]
    if len(set(ids)) != len(ids):
        raise ValueError('Duplicate source IDs in authority bundles')
    with closing(sqlite3.connect(display.resolve().as_uri() + '?mode=ro&immutable=1', uri=True)) as db:
        for table in ('road_rules', 'node_rules'):
            if not db.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", (table,)).fetchone():
                raise ValueError('Authority application requires an enriched display snapshot')
        previous = db.execute("SELECT value FROM metadata WHERE key='authority_sources'").fetchone()
        sources = read_json(previous[0].encode('utf-8')) if previous else []
        if not isinstance(sources, list) or any(not isinstance(x, dict) or 'source_id' not in x for x in sources):
            raise ValueError('Invalid existing authority provenance')
        if set(ids) & {x['source_id'] for x in sources}:
            raise ValueError('Source already applied; rebuild from the original OSM snapshot')
    destination.mkdir()
    created = []
    try:
        output = destination / 'display.sqlite'
        with display.open('rb') as original, output.open('xb') as target:
            created.append(output)
            shutil.copyfileobj(original, target, 4 * 1024 * 1024)
        if sha256(output) != input_hash or sha256(display) != input_hash:
            raise ValueError('Display snapshot changed during application')
        migrate(output)
        applied, rejected = [], []
        with closing(sqlite3.connect(output)) as db, db:
            db.execute('''CREATE TABLE IF NOT EXISTS authority_rules(
                source_id TEXT,record_id TEXT,way INTEGER,record TEXT NOT NULL,
                PRIMARY KEY(source_id,record_id,way))''')
            for records, source, _ in validated:
                count = 0
                for record in records:
                    try:
                        merge_record(db, record)
                    except ValueError as error:
                        rejected.append({**record, 'application_rejection': str(error)})
                        continue
                    count += 1
                    applied.append(record)
                    db.executemany('INSERT INTO authority_rules VALUES(?,?,?,?)',
                        ((source['source_id'], record['record_id'], way, json.dumps(record, sort_keys=True, allow_nan=False))
                         for way in record['osm_way_ids']))
                if count:
                    sources.append({**source, 'applied_records': count, 'as_of': as_of})
            counts = {table: db.execute('SELECT count(*) FROM ' + table).fetchone()[0] for table in ('road_rules', 'node_rules')}
            for key, value in (('authority_sources', sources), ('restriction_counts', counts)):
                db.execute('INSERT OR REPLACE INTO metadata VALUES(?,?)', (key, json.dumps(value, sort_keys=True)))
            if db.execute('PRAGMA integrity_check').fetchone()[0] != 'ok':
                raise ValueError('Applied display integrity check failed')
        for name, records in (('applied.jsonl', applied), ('application-rejected.jsonl', rejected)):
            path = destination / name
            with path.open('x', encoding='utf-8') as stream:
                created.append(path)
                for record in records:
                    stream.write(json.dumps(record, sort_keys=True, allow_nan=False) + '\n')
        coverage = destination / 'restriction-coverage.json'
        report = {'schema_version': 1, 'maps': [audit_database(output)]}
        with coverage.open('x', encoding='utf-8') as stream:
            created.append(coverage)
            json.dump(report, stream, indent=2, allow_nan=False)
            stream.write('\n')
        manifest = {'schema_version': 1, 'status': 'applied' if applied else 'quarantined_only',
                    'as_of': as_of, 'input_display_sha256': input_hash,
                    'applied_records': len(applied), 'application_rejected': len(rejected),
                    'intake_rejected': sum(n for _, _, n in validated), 'sources': sources,
                    'reviewed_sources': [source for _, source, _ in validated],
                    'legal_completeness': 'unknown', 'native_tiles_modified': False,
                    'files': {p.name: sha256(p) for p in created}}
        receipt = destination / 'manifest.json'
        with receipt.open('x', encoding='utf-8') as stream:
            created.append(receipt)
            json.dump(manifest, stream, indent=2, sort_keys=True, allow_nan=False)
            stream.write('\n')
        return manifest
    except BaseException:
        # Delete only files created by this attempt, never the input map or caller-owned output.
        for path in reversed(created):
            path.unlink(missing_ok=True)
        try:
            destination.rmdir()
        except OSError:
            pass
        raise


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--display', required=True, type=Path)
    p.add_argument('--bundle', required=True, action='append', type=Path)
    p.add_argument('--out', required=True, type=Path)
    p.add_argument('--as-of', required=True)
    args = p.parse_args()
    result = apply(args.display, args.bundle, args.out, args.as_of)
    print(json.dumps({k: result[k] for k in ('status', 'applied_records', 'application_rejected', 'intake_rejected')}))
