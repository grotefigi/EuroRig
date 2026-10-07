"""Host gate for a built region package: structure, hashes, and the per-tile index.

Refuses a package on any doubt. A package that passes here is internally consistent and consistent
with the coherent build whose generation it names; it is not thereby correct on a device, and nothing
in this script activates anything.

    python check_qa_package.py RO-HU-RS-QA.eurorig
    python check_qa_package.py RO-HU-RS-QA.eurorig --generation <coherent generation.json> --out gate.json

Checks, in order (a refusal names the check that failed):
 1. it is a zip; no entry is listed twice; no directory entries; the entry set is exactly what the
    declared format allows
 2. manifest.json parses as an object, its format is 1/2/3, engine and native_version are the expected
    values, and it is within the app's 65536-byte limit
 3. every declared payload is present, stream-hashed, and matches its recorded sha256 - no extra, no missing
 4. the coverage box, if published, validates
 5. format 3: country and generation_id syntax; tiles.sqlite carries user_version 1, exactly the
    documented schema, the metadata rows (generation_id, country, tiles), and one row per canonical tile
    in the tar with matching hash and size - no duplicate, absent or extra row, and no row for a tile the
    tar does not contain. The index metadata is ALWAYS compared with the manifest, with or without
    --generation: an index that disagrees with the manifest it ships beside is refused either way.
 6. with --generation: the package's generation_id matches the coherent manifest, and every tile in
    the tar hashes to the coherent claim - the same claims the packager enforced, re-checked
    independently by this gate

Anything that fails for a structural reason - malformed JSON, an unexpected JSON type, a sqlite error, a
bad tar - is reported as a FAIL receipt with exit 1, never as an uncaught traceback.
"""
import argparse
import hashlib
import json
import re
import sqlite3
import sys
import tarfile
import tempfile
import zipfile
from contextlib import closing
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from package_region import (GENERATION_ID, MANIFEST_LIMIT, TILE_INDEX_VERSION, TILE_SHA256,  # noqa: E402
                            canonical_tile_name, coverage_box, read_generation)

ALLOWED = {
    1: {'manifest.json', 'routing.tar', 'display.europack'},
    2: {'manifest.json', 'routing.tar', 'display.sqlite'},
    3: {'manifest.json', 'routing.tar', 'display.sqlite', 'tiles.sqlite'},
}
EXPECTED_SCHEMA = {
    'metadata': 'CREATE TABLE metadata(key TEXT PRIMARY KEY, value TEXT)',
    'tiles': 'CREATE TABLE tiles(path TEXT PRIMARY KEY, sha256 TEXT, size INTEGER) WITHOUT ROWID',
}
ENGINE = 'valhalla'
NATIVE_VERSION = '0.6.3'
COUNTRY = re.compile(r'[A-Z]{2}')


class Refused(Exception):
    pass


def stream_sha256(stream):
    digest = hashlib.sha256()
    while True:
        chunk = stream.read(1 << 20)
        if not chunk:
            break
        digest.update(chunk)
    return digest.hexdigest()


def check_entries(archive):
    """The zip's entry list must be exactly right: no repeats, no directory entries, no extras."""
    names = archive.namelist()
    duplicates = sorted({name for name in names if names.count(name) > 1})
    if duplicates:
        raise Refused(f'package lists the same entry more than once: {duplicates}')
    directories = sorted(name for name in names if name.endswith('/'))
    if directories:
        raise Refused(f'package contains directory entries: {directories}')
    return set(names)


def check_manifest(archive, names):
    try:
        if archive.getinfo('manifest.json').file_size > MANIFEST_LIMIT:
            raise Refused(f'manifest.json exceeds the {MANIFEST_LIMIT}-byte app limit')
        raw = archive.read('manifest.json')
    except KeyError:
        raise Refused('manifest.json is missing')
    if len(raw) > MANIFEST_LIMIT:
        raise Refused(f'manifest.json is {len(raw)} bytes, over the {MANIFEST_LIMIT}-byte app limit')
    manifest = json.loads(raw.decode('utf-8'))
    if not isinstance(manifest, dict):
        raise Refused(f'manifest.json is a JSON {type(manifest).__name__}, expected an object')
    fmt = manifest.get('format')
    if type(fmt) is not int or fmt not in ALLOWED:
        raise Refused(f'manifest format {fmt!r} is not one of 1, 2, 3')
    if manifest.get('engine') != ENGINE:
        raise Refused(f'manifest engine is {manifest.get("engine")!r}, expected {ENGINE!r}')
    if manifest.get('native_version') != NATIVE_VERSION:
        raise Refused(f'manifest native_version is {manifest.get("native_version")!r}, '
                      f'expected {NATIVE_VERSION!r}')
    if names != ALLOWED[fmt]:
        raise Refused(f'format {fmt} entries are {sorted(names)}, expected {sorted(ALLOWED[fmt])}')
    declared = manifest.get('sha256')
    if not isinstance(declared, dict):
        raise Refused('manifest has no sha256 map')
    payloads = names - {'manifest.json'}
    if set(declared) != payloads:
        raise Refused(f'manifest sha256 covers {sorted(declared)}, payloads are {sorted(payloads)}')
    if fmt == 3:
        country, generation_id = manifest.get('country'), manifest.get('generation_id')
        if not isinstance(country, str) or not COUNTRY.fullmatch(country):
            raise Refused(f'manifest country is {country!r}, expected an ISO2 code in upper case')
        if not isinstance(generation_id, str) or not GENERATION_ID.fullmatch(generation_id):
            raise Refused(f'manifest generation_id is {generation_id!r}, expected 16 hex characters')
    return manifest, fmt


def check_payload_hashes(archive, manifest):
    mismatched = []
    for name, digest in manifest['sha256'].items():
        if stream_sha256(archive.open(name)) != digest:
            mismatched.append(name)
    if mismatched:
        raise Refused(f'payload hash mismatch: {sorted(mismatched)}')


def check_coverage(manifest):
    if 'coverage' not in manifest:
        return None
    coverage = manifest['coverage']
    if not isinstance(coverage, dict) or 'bbox' not in coverage:
        raise Refused('coverage must be an object with a bbox')
    return coverage_box(coverage['bbox'])


def tar_tiles(stream):
    """Every canonical tile in the tar, with its sha256 and byte count. Refuses anything else.

    A .gph member that is not a regular file is refused rather than skipped: a symlink or hardlink named
    like a tile is not a tile, and silently ignoring it would let a package claim coverage it does not
    carry.
    """
    rows, seen = {}, set()
    with tarfile.open(fileobj=stream, mode='r|') as archive:
        for entry in archive:
            if entry.name.endswith('.gph') and not entry.isfile():
                raise Refused(f'tar member {entry.name!r} is not a regular file')
            if not entry.isfile() or not entry.name.endswith('.gph'):
                continue
            name = canonical_tile_name(entry.name)
            if name in seen:
                raise Refused(f'tar lists {name} more than once')
            seen.add(name)
            if entry.size < 272:
                raise Refused(f'{name} is too small to be a graph tile')
            digest, read = hashlib.sha256(), 0
            source = archive.extractfile(entry)
            if source is None:
                raise Refused(f'{name} cannot be read from the tar')
            while True:
                chunk = source.read(1 << 20)
                if not chunk:
                    break
                digest.update(chunk)
                read += len(chunk)
            rows[name] = (digest.hexdigest(), read)
    if not rows:
        raise Refused('tar contains no Valhalla graph tiles')
    return rows


def check_index_identity(manifest, index):
    """The index must agree with the manifest it ships beside - checked with or without --generation."""
    for field in ('country', 'generation_id'):
        if index['metadata'].get(field) != manifest.get(field):
            raise Refused(f'tiles.sqlite metadata {field} is {index["metadata"].get(field)!r}, '
                          f'the manifest says {manifest.get(field)!r}')


def check_tile_index(archive, tar_rows):
    raw = archive.read('tiles.sqlite')
    digest = hashlib.sha256(raw).hexdigest()
    with tempfile.TemporaryDirectory() as directory:
        path = Path(directory) / 'index.sqlite'
        path.write_bytes(raw)
        with closing(sqlite3.connect(f'file:{path.as_posix()}?mode=ro', uri=True)) as db:
            version = db.execute('PRAGMA user_version').fetchone()[0]
            if version != TILE_INDEX_VERSION:
                raise Refused(f'tiles.sqlite user_version is {version}, expected {TILE_INDEX_VERSION}')
            schema = dict(db.execute('SELECT name, sql FROM sqlite_master WHERE type="table"'))
            for table, expected in EXPECTED_SCHEMA.items():
                if table not in schema:
                    raise Refused(f'tiles.sqlite has no {table} table')
                if ' '.join(schema[table].split()) != expected:
                    raise Refused(f'{table} schema is {schema[table]!r}, expected {expected!r}')
            objects = set(db.execute('SELECT type, name FROM sqlite_master WHERE sql IS NOT NULL'))
            if objects != {('table', name) for name in EXPECTED_SCHEMA}:
                raise Refused('tiles.sqlite contains unexpected schema objects')
            meta = dict(db.execute('SELECT key, value FROM metadata'))
            for key in ('generation_id', 'country', 'tiles'):
                if key not in meta:
                    raise Refused(f'tiles.sqlite metadata has no {key}')
            for digest_value in [row[0] for row in db.execute('SELECT sha256 FROM tiles')]:
                if not isinstance(digest_value, str) or not TILE_SHA256.fullmatch(digest_value):
                    raise Refused(f'tiles.sqlite has a malformed sha256 value {digest_value!r}')
            rows = dict((row[0], (row[1], row[2])) for row in
                        db.execute('SELECT path, sha256, size FROM tiles'))
    if len(rows) != len(tar_rows):
        raise Refused(f'index has {len(rows)} tile rows, the tar has {len(tar_rows)}')
    if set(rows) != set(tar_rows):
        missing = sorted(set(tar_rows) - set(rows))[:3]
        extra = sorted(set(rows) - set(tar_rows))[:3]
        raise Refused(f'index rows do not match the tar (missing {missing}, extra {extra})')
    for name, (digest_claimed, size) in rows.items():
        if (digest_claimed, size) != tar_rows[name]:
            raise Refused(f'index row for {name} disagrees with the tar: {digest_claimed[:12]}…/{size} '
                          f'vs {tar_rows[name][0][:12]}…/{tar_rows[name][1]}')
    if str(len(rows)) != meta['tiles']:
        raise Refused(f'metadata says {meta["tiles"]} tiles, the index has {len(rows)}')
    return {'sha256': digest, 'rows': len(rows), 'metadata': meta}


def check_generation(manifest, index, tar_rows, generation):
    generation_id, claims = read_generation(generation)
    if manifest.get('generation_id') != generation_id:
        raise Refused(f'package generation_id {manifest.get("generation_id")!r} does not match the '
                      f'coherent manifest {generation_id!r}')
    missing = sorted(name for name in tar_rows if name not in claims)
    if missing:
        raise Refused(f'{len(missing)} tar tiles are not claimed by the coherent manifest, e.g. {missing[:3]}')
    wrong = sorted(name for name, (digest, _) in tar_rows.items() if digest != claims[name])
    if wrong:
        raise Refused(f'{len(wrong)} tar tiles do not match the coherent claim, e.g. {wrong[:3]}')
    return {'generation_id': generation_id, 'claims_checked': len(tar_rows)}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('package', help='The .eurorig package to gate')
    parser.add_argument('--generation', default=None,
                        help="The coherent build's generation.json, to re-check tile claims independently")
    parser.add_argument('--out', default=None, help='Write the verdict as JSON here')
    args = parser.parse_args(argv)
    receipt = {'package': args.package, 'checks': {}, 'verdict': 'FAIL'}
    try:
        with zipfile.ZipFile(args.package) as archive:
            names = check_entries(archive)
            manifest, fmt = check_manifest(archive, names)
            receipt['checks']['manifest'] = {'format': fmt, 'entries': sorted(names),
                                             'bytes': len(archive.read('manifest.json')),
                                             'engine': manifest.get('engine'),
                                             'native_version': manifest.get('native_version')}
            check_payload_hashes(archive, manifest)
            receipt['checks']['payload_hashes'] = sorted(manifest['sha256'])
            receipt['checks']['coverage'] = check_coverage(manifest)
            if fmt == 3:
                tar_rows = tar_tiles(archive.open('routing.tar'))
                index = check_tile_index(archive, tar_rows)
                check_index_identity(manifest, index)
                receipt['checks']['tile_index'] = index
                receipt['checks']['tar_tiles'] = len(tar_rows)
                if args.generation is not None:
                    receipt['checks']['generation'] = check_generation(manifest, index, tar_rows,
                                                                       args.generation)
            receipt['checks']['country'] = manifest.get('country')
            receipt['checks']['generation_id'] = manifest.get('generation_id')
    except Refused as refusal:
        receipt['refused'] = str(refusal)
        print(f'FAIL: {refusal}', file=sys.stderr)
    except (zipfile.BadZipFile, tarfile.TarError, sqlite3.Error, json.JSONDecodeError,
            UnicodeDecodeError, ValueError, KeyError, TypeError) as error:
        reason = f'{type(error).__name__}: {error}'
        receipt['refused'] = reason
        print(f'FAIL: {reason}', file=sys.stderr)
    except OSError as error:
        reason = f'not a readable package: {error}'
        receipt['refused'] = reason
        print(f'FAIL: {reason}', file=sys.stderr)
    else:
        receipt['verdict'] = 'PASS'
        print(f'PASS: format {receipt["checks"]["manifest"]["format"]} package is internally consistent'
              + ('' if args.generation is None else ' and matches the coherent generation'))
    if args.out:
        # A receipt that cannot be written must never be mistaken for a failed gate: the verdict is what
        # the checks found, and the exit code reports that, not the state of the output path.
        try:
            destination = Path(args.out)
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_text(json.dumps(receipt, indent=1), encoding='utf-8')
        except OSError as error:
            print(f'WARNING: could not write the receipt to {args.out}: {error}', file=sys.stderr)
    return 0 if receipt['verdict'] == 'PASS' else 1


if __name__ == '__main__':
    raise SystemExit(main())
