#!/usr/bin/env python3
"""Read-only storage audit for region packages and their display databases.

Answers what a map costs: transport (the compressed .eurorig), installed (the payloads as the app stores
them), the peak for a *new* map install (staging) and, separately, the peak for an in-place *upgrade*
where the old map is still on disk. Then it breaks the display database down by object, measures the
search text that appears twice, and reports savings candidates with the contract cost of each. Nothing
here rewrites a map.

    # measurements on a package and/or a display database, nothing written but the receipt
    python audit_map_storage.py --package dist/Romania-2026-10-04-v2.eurorig --out analysis/private/x.json
    python audit_map_storage.py --package new.eurorig --upgrade-from old.eurorig --display <display.sqlite>
    python audit_map_storage.py --display <display.sqlite> --quick

    # the search-index experiment, ON A COPY, in a workspace you name
    python audit_map_storage.py --experiment --display <display.sqlite> --workspace D:/scratch/fts

What is measured and what is estimated — the distinction is kept explicit everywhere, in the printout and
in the receipt:

  measured             file sizes, page counts, and dbstat page bytes: real physical storage
  measured_utf8_bytes  SUM(LENGTH(CAST(col AS BLOB))) on a TEXT column: real UTF-8 bytes of the text
  estimated_bytes      arithmetic from the declared type and the row count (a REAL is 8 bytes, an
                       INTEGER is a 1-9 byte varint, an INTEGER PRIMARY KEY is the rowid and costs
                       nothing in the row). NOT a measurement: SQLite's LENGTH() on a numeric returns
                       the characters of its text rendering, which is not storage at all. Confirming an
                       estimated saving needs a rebuilt copy compared against the original.
  candidate            a saving the audit has not applied, and in the search case not yet proven to be
                       the same text (see identity_check) - reported, never performed

Rules this tool keeps:
  * inputs are opened read-only; the only writes are the receipt and files inside --workspace
  * an experiment never touches its input: it copies the database first and reports the copy's path
  * dbstat and the identity check are the slow parts, both skippable or bounded from the command line
  * no geography is pruned and no row is dropped to reach a size target; every candidate is reported
    with what it would cost in behaviour, not applied
"""
import argparse
import json
import shutil
import sqlite3
import sys
import time
import unicodedata
import zipfile
from contextlib import closing
from pathlib import Path

FTS_TABLES = ('search_content', 'search_segments', 'search_docsize', 'search_segdir', 'search_stat')
BIG_TABLES = ('places', 'roads', 'road_rules', 'node_rules', 'cells', 'large_cells')
TEXT_KINDS = ('TEXT', 'CHAR', 'CLOB', 'VARCHAR')
DEFAULT_IDENTITY_SAMPLE = 200000

# What a new-map install peak deliberately does NOT count. Naming them is the point: an unqualified
# "temporary footprint" reads as a guarantee, and none of these is one.
NOT_COUNTED = [
    'the active map already installed (use --upgrade-from for an in-place upgrade peak)',
    'a previously downloaded archive that the app chooses to retain',
    'filesystem reserve and allocator slack (typically 5%): a device must keep free space to work',
    'SQLite scratch: journal/temp pages if the app ever VACUUMs, rebuilds or reindexes in place',
    'a second copy of the tiles if an install extracts into the tile directory rather than beside it',
    'OS page cache and other applications competing for the same free space',
]


def normalize(text):
    """The display builder's normalization: NFD, lower case, combining marks removed.

    Mirrored here so the audit can prove what the FTS private copy actually holds, and so the experiment
    can prove a rebuilt index answers the same queries. The app side is Graph.normalize; the two must
    agree or search silently changes behaviour.
    """
    return ''.join(c for c in unicodedata.normalize('NFD', text.lower()) if not unicodedata.combining(c))


def connect_read_only(path):
    uri = f'file:{Path(path).resolve().as_posix()}?mode=ro'
    return sqlite3.connect(uri, uri=True)


def human(value):
    for unit in ('B', 'KiB', 'MiB', 'GiB', 'TiB'):
        if abs(value) < 1024 or unit == 'TiB':
            return f'{value:,.0f} {unit}' if unit == 'B' else f'{value:,.1f} {unit}'
        value /= 1024
    return f'{value}'


# --------------------------------------------------------------------------------------------------
# transport: what the driver downloads


def audit_package(path, upgrade_from=None):
    path = Path(path)
    if not path.is_file():
        raise SystemExit(f'no such package: {path}')
    result = {'path': str(path), 'transport_bytes': path.stat().st_size, 'entries': [], 'manifest': None}
    with zipfile.ZipFile(path) as archive:
        for info in sorted(archive.infolist(), key=lambda i: -i.file_size):
            ratio = info.file_size / info.compress_size if info.compress_size else None
            result['entries'].append({'name': info.filename, 'stored': info.file_size,
                                      'compressed': info.compress_size, 'ratio': ratio})
        if 'manifest.json' in archive.namelist():
            result['manifest'] = json.loads(archive.read('manifest.json').decode('utf-8'))
    payloads = [e for e in result['entries'] if e['name'] != 'manifest.json']
    result['installed_bytes'] = sum(e['stored'] for e in payloads)
    result['compressed_payload_bytes'] = sum(e['compressed'] for e in payloads)
    result['expansion'] = (result['installed_bytes'] / result['transport_bytes']) if result['transport_bytes'] else None
    result['bytes'] = result['transport_bytes']  # convenience alias for the transport size
    # Scope, stated rather than implied: the archive and its extraction coexist while one map is being
    # unpacked into free space. This is NOT the peak of an upgrade and NOT the peak on a full device.
    result['new_map_staging_bytes'] = result['transport_bytes'] + result['installed_bytes']
    result['new_map_staging_scope'] = ('transport still present while the payloads are extracted, into '
                                       'free space, with no other map installed by this operation')
    result['not_counted'] = NOT_COUNTED
    result['measured'] = {'transport_bytes': 'file size', 'installed_bytes': 'zip entry sizes',
                          'new_map_staging_bytes': 'sum of the two above'}
    if upgrade_from:
        previous = Path(upgrade_from)
        if not previous.is_file():
            raise SystemExit(f'no such package to upgrade from: {previous}')
        with zipfile.ZipFile(previous) as archive:
            old_installed = sum(i.file_size for i in archive.infolist() if i.filename != 'manifest.json')
        result['upgrade_from'] = {'path': str(previous), 'installed_bytes': old_installed,
                                  'transport_bytes': previous.stat().st_size}
        # An in-place upgrade holds the old map until the swap, plus the new transport and its payloads.
        result['upgrade_peak_bytes'] = old_installed + result['transport_bytes'] + result['installed_bytes']
        result['upgrade_peak_scope'] = ('old map still installed + new transport + new payloads; a device '
                                        'that deletes the old map before unpacking needs only '
                                        'new_map_staging_bytes, one that keeps both needs this')
    return result


# --------------------------------------------------------------------------------------------------
# installed: what the app keeps


def audit_tiles(path):
    path = Path(path)
    result = {'path': str(path)}
    if path.is_file() and path.suffix == '.tar':
        import tarfile
        count = total = 0
        with tarfile.open(path, 'r:') as archive:
            for entry in archive:
                if entry.isfile():
                    count += 1
                    total += entry.size
        result.update({'shape': 'tar', 'bytes': path.stat().st_size, 'tiles': count,
                       'payload_bytes': total})
    elif path.is_dir():
        files = [p for p in path.rglob('*.gph')]
        result.update({'shape': 'directory', 'bytes': sum(p.stat().st_size for p in files),
                       'tiles': len(files), 'payload_bytes': sum(p.stat().st_size for p in files)})
    else:
        raise SystemExit(f'not a tile tar or directory: {path}')
    result['measured'] = 'tar header sizes / file sizes'
    return result


def dbstat_breakdown(connection):
    """Bytes per object. This walks every b-tree page, so it is the slow measurement."""
    started = time.time()
    rows = connection.execute(
        'SELECT name, SUM(pgsize) AS bytes, COUNT(*) AS pages FROM dbstat GROUP BY name ORDER BY bytes DESC'
    ).fetchall()
    total = connection.execute('SELECT SUM(pgsize) FROM dbstat').fetchone()[0] or 0
    return ({'total': total, 'seconds': round(time.time() - started, 1),
             'measure': 'dbstat page bytes per b-tree',
             'objects': [{'name': n, 'bytes': b, 'pages': p, 'percent': 100.0 * b / total if total else 0}
                         for n, b, p in rows]})


def value_profile(connection, table):
    """Size profile per column, kept honest about what is storage and what is not.

    TEXT columns are measured as real UTF-8 bytes (LENGTH(CAST(col AS BLOB))). Numeric columns get an
    arithmetic estimate from the declared type and the row count, because SQLite's LENGTH() on a number
    returns the characters of its text rendering - not the bytes it occupies. Nothing here is called a
    measured column size except the text.
    """
    columns = [(r[1], (r[2] or '').upper(), r[5]) for r in connection.execute(f'PRAGMA table_info({table})')]
    if not columns:
        return None
    rows = connection.execute(f'SELECT COUNT(*) FROM {table}').fetchone()[0]
    text_columns = []
    numeric_columns = []
    for name, kind, primary_key in columns:
        if any(token in kind for token in TEXT_KINDS):
            query = f'SELECT SUM(LENGTH(CAST("{name}" AS BLOB))) FROM {table}'
            text_columns.append({'name': name, 'type': kind or 'TEXT', 'bytes': connection.execute(query).fetchone()[0] or 0})
        else:
            rowid_alias = bool(primary_key) and kind == 'INTEGER'
            per_cell = 0 if rowid_alias else (8 if 'REAL' in kind or 'FLOA' in kind or 'DOUB' in kind else 5)
            numeric_columns.append({'name': name, 'type': kind or 'NUMERIC',
                                    'rowid_alias': rowid_alias,
                                    'estimated_bytes': rows * per_cell,
                                    'estimate_basis': ('stored in the b-tree key as the rowid' if rowid_alias
                                                       else f'{per_cell} B/cell from the declared type x {rows:,} rows')})
    text_total = sum(c['bytes'] for c in text_columns)
    for column in text_columns:
        column['percent_of_text'] = 100.0 * column['bytes'] / text_total if text_total else 0
    return {'rows': rows, 'text_columns': text_columns, 'numeric_columns': numeric_columns,
            'text_bytes_total': text_total,
            'note': ('text bytes are measured UTF-8 (LENGTH of the BLOB); numeric sizes are estimates '
                     'from the declared type and row count, never a measurement of stored bytes')}


def identity_check(connection, sample=DEFAULT_IDENTITY_SAMPLE, full=False):
    """Is the FTS private copy actually the same text as places.label?

    Two totals that happen to be close prove nothing: the FTS holds NORMALIZED text while places.label
    holds the display label, so the sizes can agree and the strings still differ. This joins them on
    rowid and compares normalize(label) to the indexed text. Sampled by an explicit step (deterministic
    and spread) unless full is asked for, and the verdict says which it was.
    """
    total = connection.execute('SELECT COUNT(*) FROM search_content').fetchone()[0]
    step = 1 if (full or total <= sample) else max(1, total // sample)
    query = ('SELECT p.id, c.c0text, p.label FROM search_content c JOIN places p ON p.id = c.rowid '
             + ('' if step == 1 else f'WHERE p.id % {step} = 0 '))
    checked = matched = mismatched = 0
    examples = []
    for place_id, indexed, label in connection.execute(query):
        checked += 1
        if indexed == normalize(label):
            matched += 1
        else:
            mismatched += 1
            if len(examples) < 3:
                examples.append({'id': place_id, 'indexed': indexed, 'normalized_label': normalize(label)})
    if not checked:
        verdict = 'nothing_to_check'
    elif mismatched:
        verdict = 'texts_differ'
    elif step == 1:
        verdict = 'proven_same_text'
    else:
        verdict = 'partially_checked_same_text'
    return {'method': 'join search_content.rowid = places.id, compare indexed text to normalize(label)',
            'rows_total': total, 'rows_checked': checked, 'step': step, 'full': bool(full or step == 1),
            'matched': matched, 'mismatched': mismatched, 'examples': examples, 'verdict': verdict}


def audit_display(path, quick=False, identity_sample=DEFAULT_IDENTITY_SAMPLE, identity_full=False):
    path = Path(path)
    if not path.is_file():
        raise SystemExit(f'no such display database: {path}')
    result = {'path': str(path), 'bytes': path.stat().st_size}
    with closing(connect_read_only(path)) as db:
        pragmas = {}
        for pragma in ('user_version', 'page_size', 'page_count', 'freelist_count', 'encoding', 'auto_vacuum'):
            pragmas[pragma] = db.execute(f'PRAGMA {pragma}').fetchone()[0]
        result['pragmas'] = pragmas
        result['objects'] = [{'type': r[0], 'name': r[1], 'table': r[2]} for r in db.execute(
            'SELECT type, name, tbl_name FROM sqlite_master ORDER BY type, name')]
        counts = {}
        for (name,) in db.execute("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name"):
            try:
                counts[name] = db.execute(f'SELECT COUNT(*) FROM "{name}"').fetchone()[0]
            except sqlite3.Error:
                counts[name] = None
        result['row_counts'] = counts
        # The search text question this audit exists to answer, with its identity actually checked.
        search_text = {}
        if 'search_content' in counts and 'places' in counts:
            fts_bytes = db.execute('SELECT SUM(LENGTH(CAST(c0text AS BLOB))) FROM search_content').fetchone()[0] or 0
            label_bytes = db.execute('SELECT SUM(LENGTH(CAST(label AS BLOB))) FROM places').fetchone()[0] or 0
            search_text['fts_private_text'] = {'bytes': fts_bytes, 'rows': counts['search_content'],
                                               'measure': 'measured_utf8_bytes'}
            search_text['places_label_text'] = {'bytes': label_bytes, 'rows': counts['places'],
                                                'measure': 'measured_utf8_bytes'}
            search_text['candidate_duplicate_bytes'] = min(fts_bytes, label_bytes)
            search_text['candidate_duplicate_note'] = ('two totals of similar size are a candidate, not '
                                                       'proof: the FTS holds normalized text while '
                                                       'places.label holds display text - see identity_check')
            search_text['measure'] = 'measured_utf8_bytes, candidate for duplication until identity_check'
            search_text['identity_check'] = identity_check(db, identity_sample, identity_full)
        result['search_text'] = search_text
        if not quick:
            result['dbstat'] = dbstat_breakdown(db)
            result['values'] = {t: value_profile(db, t) for t in BIG_TABLES if counts.get(t)}
    result['page_bytes'] = result['pragmas']['page_size'] * result['pragmas']['page_count']
    result['freelist_bytes'] = result['pragmas']['page_size'] * result['pragmas']['freelist_count']
    if 'dbstat' in result:
        objects = {o['name']: o['bytes'] for o in result['dbstat']['objects']}
        fts = sum(objects.get(name, 0) for name in FTS_TABLES)
        result['fts_bytes'] = fts
        result['fts_percent'] = 100.0 * fts / result['dbstat']['total'] if result['dbstat']['total'] else 0
    return result


# --------------------------------------------------------------------------------------------------
# savings candidates: reported with their contract cost, never applied


def savings_candidates(display):
    """What could be reclaimed and what each one would cost in behaviour.

    'measured_physical_bytes' comes straight from dbstat. 'estimated_bytes' is arithmetic from the
    declared type and the row count and must be confirmed by rebuilding a copy and comparing file sizes -
    the search candidates already have that confirmation, see the experiment.
    """
    objects = {}
    if 'dbstat' in display:
        objects = {o['name']: o['bytes'] for o in display['dbstat']['objects']}
    candidates = []
    private_copy = objects.get('search_content', 0)
    docsize = objects.get('search_docsize', 0)
    search_text = display.get('search_text', {})
    check = search_text.get('identity_check', {})
    identity = check.get('verdict', 'not_checked')
    if private_copy:
        candidates.append({
            'candidate': 'FTS4 without a private text copy',
            'bytes': private_copy,
            'kind': 'measured_physical_bytes',
            'how': "contentless FTS4 (content='') or external content (content=places) instead of "
                   "fts4(text): the app queries s.text MATCH and joins on rowid = places.id, and nothing "
                   "calls snippet/offsets, so the private copy is never read",
            'identity_evidence': f'the copy holds the same text as places.label: {identity}',
            'contract_cost': 'search index must still be fed the same normalized text via triggers; '
                             'snippet/offsets would stop working (unused today); contentless forbids '
                             'UPDATE/DELETE on the index (a display map is written once)',
            'status': 'not_applied',
        })
    if docsize:
        candidates.append({
            'candidate': 'FTS4 without the docsize table',
            'bytes': docsize,
            'kind': 'measured_physical_bytes',
            'how': 'add matchinfo=fts3 to the FTS4 declaration',
            'identity_evidence': 'independent of the search text: it removes per-document size tracking',
            'contract_cost': 'matchinfo()/snippet() lose per-document sizes; grep shows neither is used, '
                             'and the app sorts by length(p.label) from places instead',
            'status': 'not_applied',
        })
    places = display.get('values', {}).get('places', {})
    real_columns = [c for c in places.get('numeric_columns', [])
                    if 'REAL' in c['type'] and not c['rowid_alias']]
    if len(real_columns) >= 2 and places.get('rows'):
        # 8 B stored as REAL, at most 4 B as a fixed-point INTEGER: an estimate, not a measurement.
        saving = int(sum(c['estimated_bytes'] / 8 * 4 for c in real_columns))
        candidates.append({
            'candidate': 'places lat/lon stored as fixed-point integers',
            'bytes': saving,
            'kind': 'estimated_bytes',
            'how': 'micro-degrees in INTEGER (<=4 B) instead of REAL (8 B), as the cells table already does',
            'estimate_basis': f"{places['rows']:,} rows x {len(real_columns)} REAL columns, 4 B saved per cell",
            'confirm_by': 'rebuild a copy with the new types, VACUUM, compare file sizes',
            'contract_cost': 'changes the display schema and every place query in the app; needs the '
                             'display compiler and the app changed in one generation',
            'status': 'not_applied',
        })
    roads = display.get('values', {}).get('roads', {})
    bounds = [c for c in roads.get('numeric_columns', [])
              if c['name'] in ('south', 'west', 'north', 'east') and not c['rowid_alias']]
    # south is required by road_large(large,level,south) and road_level(level,south); the other three are
    # derivable from shape.
    droppable = [c for c in bounds if c['name'] != 'south']
    if droppable and roads.get('rows'):
        saving = int(sum(c['estimated_bytes'] for c in droppable))
        candidates.append({
            'candidate': 'roads bounding box as a derived, not stored, value',
            'bytes': saving,
            'kind': 'estimated_bytes',
            'how': f"drop {', '.join(c['name'] for c in droppable)} and derive them from shape; south stays "
                   "because road_large/road_level index it",
            'estimate_basis': f"{roads['rows']:,} rows x {len(droppable)} REAL columns at 8 B",
            'confirm_by': 'rebuild a copy, VACUUM, compare file sizes and re-run the bbox query checks',
            'contract_cost': 'index definitions change and bbox queries become computations; only worth '
                             'it once the bigger search savings are taken',
            'status': 'not_applied',
        })
    if display.get('freelist_bytes'):
        candidates.append({
            'candidate': 'reclaim free pages',
            'bytes': display['freelist_bytes'],
            'kind': 'measured_physical_bytes',
            'how': 'VACUUM when the compiler writes the database',
            'contract_cost': 'none beyond the compiler rewriting the whole file',
            'status': 'not_applied',
        })
    return sorted(candidates, key=lambda c: -c['bytes'])


# --------------------------------------------------------------------------------------------------
# the search-index experiment, on copies only


def query_ids(connection, expression):
    """The app's own query shape: prefix tokens against s.text, joined to places on rowid."""
    sql = ('SELECT p.id FROM search s JOIN places p ON p.id = s.rowid WHERE s.text MATCH ? '
           'ORDER BY CASE p.kind WHEN \'city\' THEN 0 WHEN \'town\' THEN 1 WHEN \'village\' THEN 2 ELSE 3 END, '
           'length(p.label) LIMIT 30')
    return [row[0] for row in connection.execute(sql, (expression,))]


def search_expression(text):
    tokens = [t for t in ''.join(ch if ch.isalnum() else ' ' for ch in normalize(text)).split() if t]
    return ' '.join(f'{token}*' for token in tokens)


def build_variant(source, destination, declaration):
    """Copy the database and replace the FTS with `declaration`, refeeding the same normalized text."""
    shutil.copyfile(source, destination)
    with closing(sqlite3.connect(destination)) as db:
        db.executescript('DROP TABLE search;')
        db.execute(f'CREATE VIRTUAL TABLE search USING fts4({declaration});')
        batch = []
        for pid, label in db.execute('SELECT id, label FROM places'):
            batch.append((pid, normalize(label)))
            if len(batch) >= 20000:
                db.executemany('INSERT INTO search(docid, text) VALUES(?,?)', batch)
                batch.clear()
        if batch:
            db.executemany('INSERT INTO search(docid, text) VALUES(?,?)', batch)
        db.commit()
        db.execute("INSERT INTO search(search) VALUES('optimize')")
        db.commit()
        db.isolation_level = None
        db.execute('VACUUM')
    return destination


def experiment(display_path, workspace, out, queries=None):
    """Rebuild the index on a copy and compare: the rebuilt-copy confirmation a candidate needs."""
    source = Path(display_path)
    workspace = Path(workspace)
    workspace.mkdir(parents=True, exist_ok=True)
    queries = queries or ['Cluj', 'București', 'Bucuresti', 'Sibiu', 'Otopeni', 'Suceava', 'Clu',
                          'Bucure', 'Sib', 'Târgu Mureș', 'Targu Mures', 'Baia Mare', 'Piatra Neamț',
                          'Iasi', 'Galați', 'Timisoara', 'Constanța', 'Craiova', 'Brasov', 'Arad']
    started = time.time()
    # FTS4, not FTS5: external content maps docid to the content table's rowid implicitly, so there is
    # no content_rowid option (FTS5 only). A declaration SQLite rejects must not lose the other
    # variant's measurement, so each build records its own error.
    variants = {
        'contentless': "text,tokenize=unicode61,content='',matchinfo=fts3",
        'external': 'text,content=places,tokenize=unicode61,matchinfo=fts3',
    }
    results = {'source': str(source), 'source_bytes': source.stat().st_size,
               'workspace': str(workspace), 'queries': queries, 'variants': {}, 'notes': []}
    for name, declaration in variants.items():
        target = workspace / f'{source.stem}.{name}.sqlite'
        if target.exists():
            target.unlink()
        t0 = time.time()
        try:
            build_variant(source, target, declaration)
        except sqlite3.Error as error:
            results['variants'][name] = {'declaration': f'fts4({declaration})', 'path': str(target),
                                         'error': f'{type(error).__name__}: {error}', 'bytes': 0,
                                         'build_seconds': round(time.time() - t0, 1),
                                         'saving_bytes': 0, 'saving_percent': 0.0,
                                         'queries_matching': 0, 'mismatches': [], 'duplicate_tables_removed': []}
            continue
        results['variants'][name] = {
            'declaration': f'fts4({declaration})',
            'path': str(target),
            'bytes': target.stat().st_size,
            'measure': 'rebuilt copy, VACUUMed, compared against the source file size',
            'build_seconds': round(time.time() - t0, 1),
        }
    # Parity: same queries, same app query shape, on the original and on each variant.
    with closing(connect_read_only(source)) as db:
        baseline = {q: query_ids(db, search_expression(q)) for q in queries}
    results['parity'] = {}
    for name, info in results['variants'].items():
        if 'error' in info:
            continue
        with closing(connect_read_only(info['path'])) as db:
            mismatched = []
            for q in queries:
                if query_ids(db, search_expression(q)) != baseline[q]:
                    mismatched.append({'query': q, 'baseline': baseline[q][:8],
                                       'variant': query_ids(db, search_expression(q))[:8]})
            tables = {r[0] for r in db.execute("SELECT name FROM sqlite_master WHERE type='table'")}
        info['duplicate_tables_removed'] = sorted({'search_content', 'search_docsize'} & set(
            t for t in {'search_content', 'search_docsize'} if t not in tables))
        info['saving_bytes'] = results['source_bytes'] - info['bytes']
        info['saving_percent'] = 100.0 * info['saving_bytes'] / results['source_bytes']
        info['queries_matching'] = len(queries) - len(mismatched)
        info['mismatches'] = mismatched
    results['seconds'] = round(time.time() - started, 1)
    results['measure'] = ('variant sizes are measured file sizes after a rebuild and VACUUM; parity is an '
                          'exact comparison of the app query result ids, not a count')
    if out:
        Path(out).parent.mkdir(parents=True, exist_ok=True)
        Path(out).write_text(json.dumps(results, indent=1), encoding='utf-8')
    return results


# --------------------------------------------------------------------------------------------------


def write_receipt(receipt, out):
    if not out:
        return
    Path(out).parent.mkdir(parents=True, exist_ok=True)
    Path(out).write_text(json.dumps(receipt, indent=1), encoding='utf-8')


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--package', action='append', default=[], help='a .eurorig to measure (repeatable)')
    parser.add_argument('--display', action='append', default=[], help='a display.sqlite (repeatable)')
    parser.add_argument('--tiles', action='append', default=[], help='a routing tar or tile directory')
    parser.add_argument('--upgrade-from', default=None,
                        help='the package currently installed, for an in-place upgrade peak')
    parser.add_argument('--quick', action='store_true', help='skip dbstat, per-column profiles and the check')
    parser.add_argument('--identity-sample', type=int, default=DEFAULT_IDENTITY_SAMPLE,
                        help='rows to compare when checking whether the FTS copy mirrors places.label')
    parser.add_argument('--identity-full', action='store_true',
                        help='compare every row (slow) instead of a stepped sample')
    parser.add_argument('--experiment', action='store_true',
                        help='rebuild the search index on COPIES and check query parity')
    parser.add_argument('--workspace', default=None, help='writable directory for experiment copies')
    parser.add_argument('--out', default=None, help='write the JSON receipt here')
    args = parser.parse_args(argv)

    if args.experiment:
        if not args.workspace:
            parser.error('--experiment writes copies, so it requires --workspace')
        if not args.display:
            parser.error('--experiment needs --display (the input is never written to)')
        results = experiment(args.display[0], args.workspace, args.out)
        for name, info in results['variants'].items():
            if 'error' in info:
                print(f'{name:12s} FAILED: {info["error"]}', file=sys.stderr)
                continue
            print(f'{name:12s} {human(info["bytes"]):>12} MEASURED  saving {human(info["saving_bytes"]):>12} '
                  f'({info["saving_percent"]:.1f}%)  parity {info["queries_matching"]}/{len(results["queries"])}'
                  f'  build {info["build_seconds"]}s  removed={info["duplicate_tables_removed"]}')
        for name, info in results['variants'].items():
            for mismatch in info['mismatches']:
                print(f'  MISMATCH {name}: {mismatch}', file=sys.stderr)
        print(f'  receipt: {args.out}')
        failed = [name for name, info in results['variants'].items() if 'error' in info]
        broken = [name for name, info in results['variants'].items() if info['mismatches']]
        return 0 if not failed and not broken else 1

    receipt = {'packages': [], 'displays': [], 'tiles': []}
    for path in args.package:
        info = audit_package(path, args.upgrade_from)
        receipt['packages'].append(info)
        print(f'package {Path(path).name}: transport {human(info["transport_bytes"])} MEASURED, '
              f'installed {human(info["installed_bytes"])} MEASURED, expansion {info["expansion"]:.2f}x')
        print(f'   new-map staging peak {human(info["new_map_staging_bytes"])} MEASURED '
              f'({info["new_map_staging_scope"]})')
        if 'upgrade_peak_bytes' in info:
            print(f'   in-place upgrade peak {human(info["upgrade_peak_bytes"])} MEASURED '
                  f'(old map {human(info["upgrade_from"]["installed_bytes"])} + transport + payloads); '
                  f'not counted: {len(info["not_counted"])} items, see the receipt')
        for entry in info['entries']:
            print(f'   {entry["name"]:18s} stored {human(entry["stored"]):>12}  '
                  f'compressed {human(entry["compressed"]):>12}  {entry["ratio"]:.2f}x')
    for path in args.display:
        info = audit_display(path, quick=args.quick, identity_sample=args.identity_sample,
                             identity_full=args.identity_full)
        receipt['displays'].append(info)
        print(f'display {Path(path).name}: {human(info["bytes"])} MEASURED = '
              f'{info["pragmas"]["page_count"]:,} pages x {info["pragmas"]["page_size"]}')
        if 'dbstat' in info:
            print(f'   dbstat {human(info["dbstat"]["total"])} MEASURED in {info["dbstat"]["seconds"]}s; '
                  f'FTS share {human(info["fts_bytes"])} ({info["fts_percent"]:.1f}%)')
            for obj in info['dbstat']['objects'][:8]:
                print(f'     {obj["name"]:24s} {human(obj["bytes"]):>12}  {obj["percent"]:5.1f}%')
        search_text = info.get('search_text', {})
        if search_text:
            check = search_text['identity_check']
            print(f'   search text: FTS copy {human(search_text["fts_private_text"]["bytes"])} MEASURED vs '
                  f'places.label {human(search_text["places_label_text"]["bytes"])} MEASURED -> '
                  f'{human(search_text["candidate_duplicate_bytes"])} CANDIDATE duplicate')
            print(f'     identity check: {check["verdict"]} ({check["matched"]:,} matched / '
                  f'{check["mismatched"]:,} mismatched of {check["rows_checked"]:,} checked, '
                  f'step {check["step"]}, {check["rows_total"]:,} rows total)')
        for candidate in savings_candidates(info):
            print(f'   candidate [{candidate["kind"]}]: {candidate["candidate"]}: '
                  f'{human(candidate["bytes"])} ({candidate["status"]})')
    for path in args.tiles:
        info = audit_tiles(path)
        receipt['tiles'].append(info)
        print(f'tiles {Path(path).name}: {info["shape"]}, {info["tiles"]:,} tiles, {human(info["bytes"])} MEASURED')
    receipt['savings_candidates'] = [c for d in receipt['displays'] for c in savings_candidates(d)]
    receipt['legend'] = {'MEASURED': 'read from the file system, dbstat page bytes, or UTF-8 text length',
                         'ESTIMATE': 'arithmetic from the declared column type and row count; needs a '
                                     'rebuilt copy to confirm',
                         'CANDIDATE': 'a saving that has not been applied and, for the search text, has '
                                      'only been checked on a sample unless identity_check.full is true'}
    receipt['measured_at'] = time.strftime('%Y-%m-%dT%H:%M:%S')
    write_receipt(receipt, args.out)
    if args.out:
        print(f'receipt: {args.out}')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
