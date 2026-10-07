"""Package already-built Valhalla 0.6.3 tiles for manual, offline installation.

This packages maps; it does not build tiles or certify their truck restrictions.
Use one coherent tile extract to support cross-border routing.

Formats
-------
format 1  EuroPack v1 display graph + tar of tiles.       manifest.json, routing.tar, display.europack
format 2  SQLite display/search database + tar of tiles.  manifest.json, routing.tar, display.sqlite
format 3  as format 2, plus a per-tile hash index.        manifest.json, routing.tar, display.sqlite, tiles.sqlite

Format 3 is produced only when BOTH --generation (the coherent build's generation.json) and
--country (an ISO2 code) are given. It copies only the tile rows that this package's tar actually
contains, keeps the coherent build's generation_id unchanged, and streams every tile against its
claimed hash. The per-tile index lives in its own hashed file because the app manifest is capped at
65536 bytes; a whole-country package cannot carry per-tile evidence in the manifest itself.

Nothing here activates a package on a device: format 3 is described and gated, not yet consumed.
"""
import argparse
import hashlib
import json
import math
import os
import re
import sqlite3
import tarfile
import tempfile
import zipfile
from contextlib import closing
from pathlib import Path

MANIFEST_LIMIT = 65536
TILE_INDEX_VERSION = 1
MAX_TILE_PATH = 64
# Valhalla graph tile paths exactly as coherent builds record them: a level digit 0-2, one or more
# three-digit groups, then .gph. Level 2 is three groups (2/000/761/203.gph, the shape 941 of the 1024
# tiles in the QA snapshot use); levels 0 and 1 are two (0/003/019.gph). Pinning the level to 0-2 and
# every group to exactly three digits is what stops a package indexing a path no builder produces.
CANONICAL_TILE = re.compile(r'[012]/(?:[0-9]{3}/)*[0-9]{3}\.gph')
TILE_SHA256 = re.compile(r'[0-9a-f]{64}')
GENERATION_ID = re.compile(r'[0-9a-f]{16}')
TILE_INDEX_SCHEMA = (
    'CREATE TABLE metadata(key TEXT PRIMARY KEY, value TEXT);'
    'CREATE TABLE tiles(path TEXT PRIMARY KEY, sha256 TEXT, size INTEGER) WITHOUT ROWID;'
)


def sha256(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def coverage_box(value):
    """Validate a published coverage box, or refuse the package.

    The app treats the box as authoritative when deciding that a location is outside the installed
    map, so a reversed, out-of-range or non-finite box is worse than none: it would send a driver to
    download a map they already have. A box is not proof of coverage inside it.
    """
    if value is None:
        return None
    try:
        numbers = [float(component) for component in value]
    except (TypeError, ValueError):
        raise ValueError('Coverage bbox must be four numbers: minLon,minLat,maxLon,maxLat')
    if len(numbers) != 4:
        raise ValueError('Coverage bbox needs exactly four numbers: minLon,minLat,maxLon,maxLat')
    if not all(math.isfinite(component) for component in numbers):
        raise ValueError('Coverage bbox values must be finite (no NaN or Infinity)')
    min_lon, min_lat, max_lon, max_lat = numbers
    if not -180 <= min_lon < max_lon <= 180:
        raise ValueError('Coverage bbox longitudes must satisfy -180 <= minLon < maxLon <= 180')
    if not -90 <= min_lat < max_lat <= 90:
        raise ValueError('Coverage bbox latitudes must satisfy -90 <= minLat < maxLat <= 90')
    return numbers


def canonical_tile_name(name):
    """Return the one acceptable tile path, or refuse it.

    The index and the generation manifest are keyed by path, so a path that is not the canonical
    relative Valhalla graph path - absolute, backslashed, containing "..", of an unexpected level, of
    an unexpected group width, or over MAX_TILE_PATH characters - cannot be matched against a claim and
    must never be silently normalised into one.
    """
    if not name or name.startswith('/') or '\\' in name or ':' in name:
        raise ValueError(f'Unsafe tile name {name!r}')
    if name.startswith('./'):
        name = name[2:]
    if any(part in ('', '.', '..') for part in name.split('/')):
        raise ValueError(f'Unsafe tile name {name!r}')
    if len(name) > MAX_TILE_PATH:
        raise ValueError(f'Tile path is longer than {MAX_TILE_PATH} characters: {name!r}')
    if not CANONICAL_TILE.fullmatch(name):
        raise ValueError(f'Tile name is not a canonical Valhalla graph path: {name!r}')
    return name


def read_generation(path):
    """Read a coherent build's generation.json: an id plus a path -> sha256 claim for every tile.

    A digest must be 64 hexadecimal characters, and two spellings of the same canonical path are
    refused rather than merged: accepting both would let one claim silently overwrite the other, which
    is exactly the class of mistake the index exists to catch.
    """
    document = json.loads(Path(path).read_text(encoding='utf-8'))
    if not isinstance(document, dict):
        raise ValueError('Generation manifest is not a JSON object')
    generation_id, tiles = document.get('generation_id'), document.get('tiles')
    if not isinstance(generation_id, str) or not GENERATION_ID.fullmatch(generation_id):
        raise ValueError(f'Generation manifest has no 16-hex generation_id (got {generation_id!r})')
    if not isinstance(tiles, dict) or not tiles:
        raise ValueError('Generation manifest has no tile hashes')
    claims = {}
    for name, digest in tiles.items():
        if not isinstance(name, str):
            raise ValueError(f'Generation manifest has a non-string tile name {name!r}')
        canonical = canonical_tile_name(name)
        if not isinstance(digest, str) or not TILE_SHA256.fullmatch(digest):
            raise ValueError(f'Generation manifest has no 64-hex sha256 for {name!r}')
        if canonical in claims:
            raise ValueError(f'Generation manifest claims {canonical} more than once '
                             f'(as {name!r} and another spelling)')
        claims[canonical] = digest
    return generation_id, claims


def scan_tiles(archive, claims):
    """Stream every tile in the tar, verify it against its claim, and return the index rows.

    Rejection is total and specific: a tile the generation manifest does not claim, a tile whose bytes
    do not hash to the claim, a duplicate path, an unsafe or non-canonical name, a tile shorter than its
    own header, and a .gph entry that is not a regular file (symlink, hardlink or device) all refuse the
    package. Partial trust is not an option here - the index is what a later activation would rely on,
    and a wrong row is worse than no package.
    """
    rows, seen, count = [], set(), 0
    for entry in archive:
        if entry.name.endswith('.gph') and not entry.isfile():
            raise ValueError(f'Tile {entry.name!r} is not a regular file in the tar')
        if not entry.isfile() or not entry.name.endswith('.gph'):
            continue
        name = canonical_tile_name(entry.name)
        if name in seen:
            raise ValueError(f'Duplicate tile {name} in the routing extract')
        seen.add(name)
        if entry.size < 272:
            raise ValueError('Truncated graph tile')
        if name not in claims:
            raise ValueError(f'Tile {name} is not present in the generation manifest')
        count += 1
        digest, read = hashlib.sha256(), 0
        stream = archive.extractfile(entry)
        if stream is None:
            raise ValueError(f'Tile {name} cannot be read from the tar')
        while True:
            chunk = stream.read(1 << 20)
            if not chunk:
                break
            digest.update(chunk)
            read += len(chunk)
        if read != entry.size:
            raise ValueError(f'Tile {name} is shorter than its tar header claims')
        if digest.hexdigest() != claims[name]:
            raise ValueError(f'Tile {name} does not match the generation manifest hash')
        rows.append((name, digest.hexdigest(), read))
    if not count:
        raise ValueError('Routing extract contains no Valhalla graph tiles')
    return sorted(rows)


def write_tile_index(path, generation_id, country, rows):
    """Write the per-tile index: metadata plus one row per tile actually shipped in the tar."""
    with closing(sqlite3.connect(path)) as db:
        db.executescript(f'PRAGMA user_version={TILE_INDEX_VERSION};' + TILE_INDEX_SCHEMA)
        db.executemany('INSERT INTO metadata VALUES (?,?)',
                       (('generation_id', generation_id), ('country', country), ('tiles', str(len(rows)))))
        db.executemany('INSERT INTO tiles VALUES (?,?,?)', rows)
        db.commit()


def encode_manifest(manifest):
    """Serialise the manifest and hold it to the app's 65536-byte limit."""
    encoded = json.dumps(manifest, ensure_ascii=False, indent=2)
    if len(encoded.encode('utf-8')) > MANIFEST_LIMIT:
        raise ValueError(f'Manifest exceeds the {MANIFEST_LIMIT}-byte limit; per-tile evidence belongs '
                         f'in tiles.sqlite, not in the manifest')
    return encoded


def package(tiles, display, destination, name, source, date, bbox=None, generation=None, country=None):
    tiles, display, destination = map(Path, (tiles, display, destination))
    if (generation is None) != (country is None):
        raise ValueError('--generation and --country must be given together: format 3 carries both, and '
                         'neither alone means anything')
    if country is not None and not re.fullmatch(r'[A-Z]{2}', country):
        raise ValueError('Country must be a two-letter ISO2 code in upper case')
    claims = None
    if generation is not None:
        generation_id, claims = read_generation(generation)
    with tarfile.open(tiles, 'r:') as archive:
        if claims is None:
            count = 0
            for entry in archive:
                if entry.name.endswith('.gph') and not entry.isfile():
                    raise ValueError(f'Tile {entry.name!r} is not a regular file in the tar')
                if entry.isfile() and entry.name.endswith('.gph'):
                    if entry.size < 272:
                        raise ValueError('Truncated graph tile')
                    count += 1
            if not count:
                raise ValueError('Routing extract contains no Valhalla graph tiles')
            rows = None
        else:
            rows = scan_tiles(archive, claims)
            count = len(rows)
    with display.open('rb') as stream:
        magic = stream.read(16)
    indexed = magic == b'SQLite format 3\x00'
    if indexed:
        with closing(sqlite3.connect(f'file:{display.resolve().as_posix()}?mode=ro', uri=True)) as db:
            if db.execute('PRAGMA user_version').fetchone()[0] != 1:
                raise ValueError('Unknown display database version')
    else:
        if magic[:4] != b'ERG1':
            raise ValueError('Display map must be EuroPack v1')
        if claims is not None:
            raise ValueError('Format 3 requires an indexed SQLite display database')
    inputs = {tiles.resolve(), display.resolve()}
    if generation is not None:
        inputs.add(Path(generation).resolve())
    if destination.resolve() in inputs:
        raise ValueError('Output must not overwrite an input')
    display_name = 'display.sqlite' if indexed else 'display.europack'
    fmt = 3 if claims is not None else (2 if indexed else 1)
    manifest: dict = dict(format=fmt, engine='valhalla', native_version='0.6.3', name=name,
                         routing_source=source, routing_date=date,
                         attribution='© OpenStreetMap contributors', license='ODbL-1.0')
    if claims is not None:
        manifest['country'] = country
        manifest['generation_id'] = generation_id
    # Coverage is published so the app can tell "this location is outside your installed map" apart from
    # "your point is not on a mapped truck road". Without it both surface as engine code 171 and the
    # driver is told to check country coverage for what is really a missing download. Validated before
    # the archive is written, so a rejected box leaves no package behind.
    coverage = coverage_box(bbox)
    if coverage is not None:
        manifest['coverage'] = dict(tiles=count, bbox=coverage)
    index = output = None
    try:
        if claims is not None:
            # An unpredictable name inside the destination's own directory: a deterministic staging name
            # could overwrite or delete a file the caller happens to have there, since the staging file
            # is removed on every path including failure.
            descriptor, index_name = tempfile.mkstemp(prefix='.tiles-index-', suffix='.part',
                                                      dir=str(destination.parent))
            os.close(descriptor)
            index = Path(index_name)
            write_tile_index(index, generation_id, country, rows)
        manifest['sha256'] = {'routing.tar': sha256(tiles), display_name: sha256(display)}
        if claims is not None:
            manifest['sha256']['tiles.sqlite'] = sha256(index)
        encoded = encode_manifest(manifest)
        descriptor, output_name = tempfile.mkstemp(prefix='.region-package-', suffix='.part',
                                                   dir=str(destination.parent))
        os.close(descriptor)
        output = Path(output_name)
        # Import streams the decompressed payload to disk; routes memory-map that TAR.
        with zipfile.ZipFile(output, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=3,
                             allowZip64=True) as archive:
            archive.write(tiles, 'routing.tar')
            archive.write(display, display_name)
            if claims is not None:
                archive.write(index, 'tiles.sqlite')
            archive.writestr('manifest.json', encoded)
        os.replace(output, destination)
    finally:
        if output is not None:
            output.unlink(missing_ok=True)
        if index is not None and index.exists():
            index.unlink()
    return manifest


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('tiles', help='Uncompressed .tar from compatible Valhalla build tools')
    parser.add_argument('display', help='Indexed SQLite display/search database or small EuroPack graph')
    parser.add_argument('destination', help='Output .eurorig file')
    parser.add_argument('--name', required=True)
    parser.add_argument('--source', required=True, help='Public OSM extract/source URL')
    parser.add_argument('--date', required=True, help='OSM snapshot date, or unknown')
    parser.add_argument('--bbox', default=None,
                        help='minLon,minLat,maxLon,maxLat of the extract; published as coverage in the manifest')
    parser.add_argument('--generation', default=None,
                        help="The coherent build's generation.json; with --country produces format 3")
    parser.add_argument('--country', default=None,
                        help='ISO2 code of the country this package covers; with --generation produces format 3')
    args = parser.parse_args()
    try:
        manifest = package(args.tiles, args.display, args.destination, args.name, args.source, args.date,
                           args.bbox.split(',') if args.bbox else None,
                           generation=args.generation, country=args.country)
    except ValueError as error:
        parser.error(str(error))
    print(f'Created {args.destination}; format {manifest["format"]}; routes use local tiles, '
          f'display/search uses the separate graph')
